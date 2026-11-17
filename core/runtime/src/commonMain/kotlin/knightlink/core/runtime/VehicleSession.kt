package knightlink.core.runtime

import knightlink.core.adapter.AdapterDisconnectedException
import knightlink.core.adapter.AdapterException
import knightlink.core.adapter.Elm327Driver
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmStatus
import knightlink.core.scheduler.DiagnosticRequester
import knightlink.core.scheduler.PollResult
import knightlink.core.scheduler.PollScheduler
import knightlink.core.scheduler.PollingLoop
import knightlink.core.scheduler.PollingStalledException
import knightlink.core.signals.CompiledParameter
import knightlink.core.signals.SignalCatalog
import knightlink.core.telemetry.TelemetryDecoder
import knightlink.core.telemetry.TelemetrySnapshot
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.transport.TransportException
import knightlink.core.vehicle.CapabilityProbe
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One vehicle session: connect, initialise the adapter, probe the vehicle, poll it, and when the link is
 * lost go round again. This is the object the Android foreground service hosts; the UI and the rules engine
 * only observe [state], [telemetry] and [pollResults].
 *
 * The session is read-only towards the vehicle: it sends what the adapter definition's init script says,
 * what [CapabilityProbe] sends, and the service 01 requests of the confirmed poll list, nothing else.
 *
 * Recovery is in two levels. Trouble that the adapter itself can fix (it reset itself, or nothing has answered for
 * [Config.silenceWindow]) is met by initialising it again on the same link ([SessionState.Recovering]); only when that does
 * not help [Config.maxReinits] times, or the link is lost, is the connection closed and opened again with a growing wait
 * ([SessionState.Reconnecting]). A car that does not answer the probe at all (ignition off) keeps the link and is probed again
 * every [Config.ignitionProbeInterval] ([SessionState.WaitingForIgnition]).
 *
 * Threading: [start] and [stop] may be called from any thread. The session's own work runs in one coroutine
 * (plus a reader coroutine per connection) on [scope]'s dispatcher, so give it a dispatcher that is not
 * blocked by other work.
 *
 * @param transport how to reach the adapter; opened again on every reconnect
 * @param initScript the adapter's initialisation commands, from its definition file
 * @param catalog the vehicle's signal definitions; decides what is polled and how replies are decoded
 * @param scope owns every coroutine of the session. Cancelling it stops the session like [stop] does, though
 *   without waiting for the cleanup to finish.
 * @param timeSource measures the time stamps of readings and the round trips of the probe and the poll loop
 * @param events receives what happened, in order: connection attempts, init steps, state changes, resets, re-inits,
 *   reconnects, why the session ended. Never a full VIN or a device address. A sink that throws is ignored.
 */
public class VehicleSession(
    private val transport: Transport,
    private val initScript: List<String>,
    private val catalog: SignalCatalog,
    scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val config: Config = Config(),
    private val events: SessionEventSink = SessionEventSink.None,
) {
    public data class Config(
        /** Wanted interval per parameter; return null to leave a parameter out. The adapter's speed may make the achieved rate lower. */
        val pollPeriod: (CompiledParameter) -> Duration? = { 1.seconds },
        /**
         * Catalog id of the ECU that is asked for parameters the definitions do not tie to one ECU, when it answers them.
         * Null means the first ECU of the catalog.
         */
        val primaryEcu: String? = null,
        /** Wait before the first retry. */
        val initialBackoff: Duration = 1.seconds,
        /** The wait grows by [backoffFactor] per failed attempt, up to this. */
        val maxBackoff: Duration = 30.seconds,
        val backoffFactor: Double = 2.0,
        /** Give up (state [SessionState.Failed]) after this many failed attempts in a row. Null retries forever. */
        val maxConnectAttempts: Int? = null,
        /** Probe again after every reconnect instead of reusing the report of the first connection. */
        val reprobeOnReconnect: Boolean = false,
        /** Sent after the init script while the capability probe runs (from the adapter definition). */
        val probeScript: List<String> = emptyList(),
        /** Sent after the probe, and again after the init script on every re-initialisation while polling (from the adapter definition). */
        val pollScript: List<String> = emptyList(),
        /** How the adapter driver treats the adapter: timeouts, pauses, which commands are essential. Usually from the adapter definition. */
        val driver: Elm327Driver.Config = Elm327Driver.Config(),
        /** While polling, this long without a positive answer to any request means the adapter is initialised again. */
        val silenceWindow: Duration = 5.seconds,
        /** Re-initialisations of one outage that may fail to bring an answer back before the connection is closed and opened again. */
        val maxReinits: Int = 2,
        /** Wait between probes while no ECU answers. */
        val ignitionProbeInterval: Duration = 4.seconds,
    ) {
        init {
            require(initialBackoff > Duration.ZERO) { "initialBackoff must be positive" }
            require(maxBackoff >= initialBackoff) { "maxBackoff must not be shorter than initialBackoff" }
            require(backoffFactor >= 1.0) { "backoffFactor must be at least 1" }
            require(maxConnectAttempts == null || maxConnectAttempts >= 1) { "maxConnectAttempts must be at least 1" }
            require(silenceWindow > Duration.ZERO) { "silenceWindow must be positive" }
            require(maxReinits >= 1) { "maxReinits must be at least 1" }
            require(ignitionProbeInterval > Duration.ZERO) { "ignitionProbeInterval must be positive" }
        }
    }

    /** What a connection learned about the vehicle; kept so a reconnect does not have to repeat the probe. */
    private class Knowledge(val report: CapabilityReport, val coverage: DefinitionCoverage)

    /** How one connection ended. */
    private class Ended(val cause: String, val reachedPolling: Boolean)

    private val primaryEcu: String? = config.primaryEcu ?: catalog.ecus.firstOrNull()?.id

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    private val _telemetry = MutableStateFlow(TelemetrySnapshot.EMPTY)

    // DROP_OLDEST: a slow recorder loses old results; it must never stall the poll loop, which the vehicle's data depends on.
    private val _pollResults = MutableSharedFlow<PollResult>(extraBufferCapacity = POLL_RESULT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Written only by the session coroutine, and read by it after the previous connection has ended. */
    private var knowledge: Knowledge? = null
    private lateinit var sessionStart: TimeMark

    // Written by the poll loop and read by the watchdog next to it, possibly on another thread.
    @Volatile
    private var lastAnswer: TimeMark? = null

    @Volatile
    private var answers = 0L

    @Volatile
    private var busFaultSinceAnswer: ElmStatus? = null

    /** Where the session is in its life. */
    public val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * The latest value of every signal seen. Keeps the last values while reconnecting; each reading's
     * [knightlink.core.telemetry.TimedReading.at] shows how old it is.
     */
    public val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    /**
     * Every poll result, for diagnostics and recording, including failed requests. No replay: a collector sees
     * results from the moment it subscribes. A collector that cannot keep up loses the oldest results.
     */
    public val pollResults: SharedFlow<PollResult> = _pollResults.asSharedFlow()

    // Lazy so that start() can be called any number of times: Job.start() is atomic and does nothing after the first call.
    private val main: Job = scope.launch(start = CoroutineStart.LAZY) { run() }

    init {
        require(config.primaryEcu == null || catalog.ecus.any { it.id == config.primaryEcu }) {
            "primaryEcu '${config.primaryEcu}' is not an ECU of the catalog"
        }
    }

    /** Starts connecting. Does nothing if the session was already started, has failed or has been stopped. */
    public fun start() {
        main.start()
    }

    /**
     * Stops the session and waits for it to finish: no coroutine of the session runs afterwards, the open
     * stream has been closed (once), and [state] is [SessionState.Stopped]. Safe to call at any time, also
     * before [start] and more than once. Completes even if the caller's own coroutine is cancelled meanwhile.
     */
    public suspend fun stop() {
        withContext(NonCancellable) {
            main.cancelAndJoin()
            _state.value = SessionState.Stopped
        }
    }

    private suspend fun run() {
        sessionStart = timeSource.markNow()
        try {
            connectWithBackoff()
        } catch (e: CancellationException) {
            _state.value = SessionState.Stopped
            // The scope is being cancelled, so this must not be cancellable: the reason a session ended is what a log is for.
            withContext(NonCancellable) { note(SessionEvents.END, "the session was stopped") }
            throw e
        } catch (e: Exception) {
            // A bug, not a connection problem (those are handled per connection). Show it in the state, then let it reach the scope.
            setState(SessionState.Failed("Unexpected error: ${describe(e)}"))
            note(SessionEvents.END, "unexpected error: ${describe(e)}")
            throw e
        }
    }

    private suspend fun connectWithBackoff() {
        var attempt = 1
        var wait = config.initialBackoff
        while (true) {
            setState(SessionState.Connecting(attempt))
            note(SessionEvents.CONNECT_ATTEMPT, "attempt $attempt")
            val ended = runConnection()
            if (ended.reachedPolling) {
                // The link worked for a while, so this is a new outage, not a continuation of the failed attempts.
                attempt = 0
                wait = config.initialBackoff
            }
            val limit = config.maxConnectAttempts
            if (limit != null && attempt >= limit) {
                val reason = "Gave up after $attempt failed connection attempts; last error: ${ended.cause}"
                setState(SessionState.Failed(reason))
                note(SessionEvents.END, reason)
                return
            }
            attempt++
            note(SessionEvents.RECONNECT, "attempt $attempt in $wait because: ${ended.cause}")
            setState(SessionState.Reconnecting(attempt, wait, ended.cause))
            delay(wait)
            wait = minOf(wait * config.backoffFactor, config.maxBackoff)
        }
    }

    /**
     * One connection from open to its end. Returns only when the connection has ended and been torn down;
     * the stream is closed here and nowhere else, so it is closed exactly once on every path.
     */
    private suspend fun runConnection(): Ended {
        val stream = try {
            transport.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Ended("could not open the link: ${describe(e)}", reachedPolling = false)
        }
        note(SessionEvents.LINK_UP, "the link is open")

        // A Deferred, not a plain variable: it is completed by the connection coroutine and read by this one, which may
        // run on different threads.
        val polling = CompletableDeferred<Unit>()
        val linkLost = CompletableDeferred<Throwable?>()
        // A supervisor, so a failing stage is reported through its Deferred below instead of cancelling the reader as a sibling.
        val connection = SupervisorJob(coroutineContext[Job])
        val connectionScope = CoroutineScope(coroutineContext + connection)
        try {
            val watched = LossReportingStream(stream, linkLost)
            val work = connectionScope.async { drive(watched, connectionScope) { polling.complete(Unit) } }
            return try {
                select {
                    // Normally the stream ending is seen first, before any command can fail because of it.
                    linkLost.onAwait { cause -> Ended(describeLoss(cause), polling.isCompleted) }
                    work.onAwait { Ended("the session ended unexpectedly", polling.isCompleted) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Ended(describe(e), polling.isCompleted)
            }
        } finally {
            withContext(NonCancellable) {
                // The reader goes first: closing the stream first would make it report our own close as a link loss.
                connection.cancelAndJoin()
                try {
                    stream.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The link may be gone already; there is nothing left to do for it.
                }
            }
        }
    }

    /** Initialise, probe (or reuse), then poll until something throws. Never returns normally. */
    private suspend fun drive(stream: ByteStream, driverScope: CoroutineScope, onPolling: () -> Unit) {
        setState(SessionState.Initialising)
        // The rate ceiling runs on the session's clock, so it measures the same time as everything else here.
        val driver = Elm327Driver(stream, driverScope, config = config.driver.copy(timeSource = timeSource), events = events)
        val cached = knowledge?.takeIf { !config.reprobeOnReconnect }
        val pollingScript = initScript + config.pollScript
        driver.initialize(if (cached == null) initScript + config.probeScript else pollingScript)

        val known = cached ?: probeUntilVehicleAnswers(driver).also {
            // The probe's timing is done with; polling runs with the definition's own, and a re-init must restore that, not the probe's.
            if (config.probeScript.isNotEmpty() || config.pollScript.isNotEmpty()) driver.applyScript(pollingScript, send = config.pollScript)
        }
        val items = confirmedPollItems(catalog, known.coverage, primaryEcu, config.pollPeriod)
        val requester = DiagnosticRequester { id, payload, expected -> driver.request(id, payload, expected) }
        val scheduler = PollScheduler(items)
        val loop = PollingLoop(requester, scheduler, timeSource)
        val decoder = TelemetryDecoder(catalog)

        onPolling()
        setState(SessionState.Polling(known.report, known.coverage))
        // With nothing to ask for, silence means nothing: initialising the adapter again would only disturb a link that works.
        pollWithRecovery(driver, loop, scheduler, decoder, known, watchSilence = items.isNotEmpty())
    }

    /**
     * Runs the capability probe until some ECU answers it. A car that is asleep (ignition off) answers nothing; instead of
     * giving up the link, this waits and asks again, so the session goes live as soon as the ignition is switched on.
     */
    private suspend fun probeUntilVehicleAnswers(driver: Elm327Driver): Knowledge {
        var probes = 0
        while (true) {
            val first = probes == 0
            val report = CapabilityProbe(driver, timeSource).run { stage ->
                // Later probes run quietly: a status that flickers between "waiting" and "probing" every few seconds helps nobody.
                if (first) setState(SessionState.Probing(stage))
            }.let { it.copy(adapter = it.adapter.copy(unsupportedCommands = driver.unsupportedCommands)) }
            probes++
            note(SessionEvents.PROBE_RESULT, summarise(report))
            if (report.ecus.isNotEmpty()) {
                // A report with warnings may be incomplete (a request timed out on a flaky link). It is good enough for this
                // connection, but it is not kept: the next connection probes again instead of living with the gaps.
                return Knowledge(report, DefinitionCoverage.compare(report, catalog)).also { if (report.warnings.isEmpty()) knowledge = it }
            }
            setState(SessionState.WaitingForIgnition(probes, report.busFault))
            delay(config.ignitionProbeInterval)
        }
    }

    /**
     * Polls until the connection ends. When nothing has answered for [Config.silenceWindow] (or the adapter keeps failing)
     * the adapter is initialised again on the same link; if that fails [Config.maxReinits] times in a row, the connection is
     * given up on so that the session closes and reopens the link.
     */
    private suspend fun pollWithRecovery(
        driver: Elm327Driver,
        loop: PollingLoop,
        scheduler: PollScheduler,
        decoder: TelemetryDecoder,
        known: Knowledge,
        watchSilence: Boolean,
    ) {
        var failedReinits = 0
        while (true) {
            val answersBefore = answers
            val trouble = pollUntilTrouble(loop, scheduler, decoder, watchSilence)
            // Something answered since the last re-initialisation: that outage is over, this is a new one.
            if (answers > answersBefore) failedReinits = 0
            reinitialiseOrGiveUp(driver, trouble, failedReinits).also { failedReinits = it }
            lastAnswer = timeSource.markNow()
            setState(SessionState.Polling(known.report, known.coverage))
        }
    }

    /**
     * Initialises the adapter again, as often as [Config.maxReinits] allows counting the tries that already failed.
     * A re-initialisation that itself fails (the adapter does not answer) counts as failed too.
     *
     * @return the number of failed tries of this outage so far, this one included
     * @throws SilentVehicleException when the allowance is used up
     */
    private suspend fun reinitialiseOrGiveUp(driver: Elm327Driver, trouble: String, failedSoFar: Int): Int {
        var failed = failedSoFar
        note(SessionEvents.SILENCE, trouble)
        while (true) {
            if (failed >= config.maxReinits) {
                // The next connection should find out afresh whether the car is awake, instead of polling a silent one.
                knowledge = null
                throw SilentVehicleException("no answer after $failed re-initialisations of the adapter ($trouble)")
            }
            failed++
            setState(SessionState.Recovering(failed, trouble))
            try {
                driver.reinitialize("$trouble (attempt $failed of ${config.maxReinits})")
                return failed
            } catch (e: AdapterDisconnectedException) {
                throw e
            } catch (e: AdapterException) {
                note(SessionEvents.REINIT, "failed: ${describe(e)}")
            }
        }
    }

    /** Polls until the silence window passes or the adapter fails repeatedly; returns what was noticed. A lost link throws instead. */
    private suspend fun pollUntilTrouble(
        loop: PollingLoop,
        scheduler: PollScheduler,
        decoder: TelemetryDecoder,
        watchSilence: Boolean,
    ): String = coroutineScope {
        // A silence backs every item off and a new collection of the loop starts its clock again: begin with a clean slate.
        scheduler.restart()
        lastAnswer = timeSource.markNow()
        busFaultSinceAnswer = null
        val trouble = CompletableDeferred<String>()
        val collector = launch {
            try {
                loop.results().collect { publish(it, decoder) }
            } catch (e: PollingStalledException) {
                trouble.complete(unreachableOr("the adapter failed ${e.failures} requests in a row"))
            }
        }
        val watchdog = if (!watchSilence) {
            null
        } else {
            launch {
                while (true) {
                    delay(WATCHDOG_TICK)
                    val quiet = lastAnswer?.elapsedNow() ?: Duration.ZERO
                    if (quiet >= config.silenceWindow) {
                        trouble.complete(unreachableOr("nothing answered for ${config.silenceWindow}"))
                        return@launch
                    }
                }
            }
        }
        val reason = trouble.await()
        collector.cancelAndJoin()
        watchdog?.cancelAndJoin()
        reason
    }

    /** The reason, led by the bus fault the adapter reported meanwhile (if any), which says more than silence does. */
    private fun unreachableOr(reason: String): String {
        val fault = busFaultSinceAnswer ?: return reason
        return "vehicle bus not reachable (${fault.name.replace('_', ' ')}); $reason"
    }

    private suspend fun publish(result: PollResult, decoder: TelemetryDecoder) {
        _pollResults.tryEmit(result)
        val error = result.error
        // The link is gone: reconnect now instead of letting the loop count to its failure limit first.
        if (error is AdapterDisconnectedException || error is TransportException) throw error
        result.busFault?.let { busFaultSinceAnswer = it }
        if (result.answered) {
            lastAnswer = timeSource.markNow()
            answers++
            busFaultSinceAnswer = null
        }
        val readings = result.response?.messages.orEmpty()
            .filterIsInstance<DiagnosticResponse.Positive>()
            .flatMap(decoder::decode)
        if (readings.isNotEmpty()) _telemetry.update { it.with(readings, sessionStart.elapsedNow()) }
    }

    private suspend fun setState(state: SessionState) {
        _state.value = state
        note(SessionEvents.STATE, describe(state))
    }

    /** The state in words for the log. Never the report: it holds the full VIN. */
    private fun describe(state: SessionState): String = when (state) {
        SessionState.Idle -> "Idle"
        is SessionState.Connecting -> "Connecting, attempt ${state.attempt}"
        SessionState.Initialising -> "Initialising"
        is SessionState.Probing -> "Probing, ${state.stage.name}"
        is SessionState.WaitingForIgnition ->
            "WaitingForIgnition, probe ${state.probes} found no ECU" + (state.busFault?.let { ", the adapter said $it" } ?: "")
        is SessionState.Polling -> "Polling, ${state.report.ecus.size} ECUs, ${state.coverage.confirmed.size} confirmed parameters"
        is SessionState.Recovering -> "Recovering, attempt ${state.attempt}: ${state.reason}"
        is SessionState.Reconnecting -> "Reconnecting, attempt ${state.attempt} in ${state.retryIn}: ${state.cause}"
        is SessionState.Failed -> "Failed: ${state.reason}"
        SessionState.Stopped -> "Stopped"
    }

    /** What the probe found, without the VIN: which ECUs answered, how many PIDs each supports, and how fast. */
    private fun summarise(report: CapabilityReport): String {
        if (report.ecus.isEmpty()) {
            return "no ECU answered" + (report.busFault?.let { " (the adapter said $it)" } ?: "") + warningsSuffix(report)
        }
        val ecus = report.ecus.joinToString(", ") { "${it.requestId} (${it.supportedPids.size} PIDs)" }
        val rate = report.timing.estimatedRequestsPerSecond?.let { ", about ${it.toInt()} requests/s" }.orEmpty()
        return "${report.ecus.size} ECUs: $ecus$rate" + warningsSuffix(report)
    }

    private fun warningsSuffix(report: CapabilityReport): String {
        if (report.warnings.isEmpty()) return ""
        return "; ${report.warnings.size} warnings: " + report.warnings.joinToString(" | ")
    }

    private suspend fun note(name: String, detail: String) {
        try {
            events.emit(name, detail)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A broken log must not end a session that is reading a car.
        }
    }

    private fun describe(e: Exception): String = e.message ?: e::class.simpleName ?: "unknown error"

    private fun describeLoss(cause: Throwable?): String =
        if (cause == null) "the adapter closed the connection" else "the connection failed: ${cause.message ?: cause::class.simpleName}"

    /** The adapter works but the vehicle does not answer, and initialising the adapter again did not change that. */
    private class SilentVehicleException(message: String) : Exception(message)

    /**
     * Passes the stream through and reports when [ByteStream.incoming] ends, which is how the session learns of
     * a lost link even while a stage is busy swallowing errors (the probe records failed requests as warnings).
     */
    private class LossReportingStream(private val delegate: ByteStream, private val lost: CompletableDeferred<Throwable?>) : ByteStream {
        override val incoming: Flow<ByteArray> = delegate.incoming.onCompletion { cause ->
            // A CancellationException is us tearing the reader down, not the link ending.
            if (cause !is CancellationException) lost.complete(cause)
        }

        override suspend fun write(bytes: ByteArray) {
            delegate.write(bytes)
        }

        override suspend fun close() {
            delegate.close()
        }
    }

    private companion object {
        const val POLL_RESULT_BUFFER = 64

        /** How often the silence watchdog looks at the clock. */
        val WATCHDOG_TICK: Duration = 250.milliseconds
    }
}
