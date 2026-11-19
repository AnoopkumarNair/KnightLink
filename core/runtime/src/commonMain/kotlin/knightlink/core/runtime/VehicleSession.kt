package knightlink.core.runtime

import knightlink.core.adapter.AdapterDisconnectedException
import knightlink.core.adapter.AdapterException
import knightlink.core.adapter.Elm327Driver
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmStatus
import knightlink.core.scan.DidSweepPlan
import knightlink.core.scan.FullScan
import knightlink.core.scan.ScanRefusedException
import knightlink.core.scheduler.DiagnosticRequester
import knightlink.core.scheduler.PollItem
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.isActive
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
        /**
         * Wanted interval per parameter; return null to leave a parameter out. The default is the period of the parameter's
         * [knightlink.core.signals.PollTier], which its definition names. The adapter's speed may make the achieved rate lower.
         */
        val pollPeriod: (CompiledParameter) -> Duration? = { it.tier.period },
        /** Most PIDs asked in one service 01 request; 1 asks every PID alone. See [PollScheduler]. */
        val maxBatch: Int = PollScheduler.MAX_BATCH,
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
        /**
         * A probe that found some ECU but not the primary one (or came with warnings) is repeated up to this many more times, keeping the
         * fullest report, before the session settles for what it has: an engine ECU that has not woken yet would otherwise leave a whole
         * drive without engine data (2026-11-19: no 7E0, 13 parameters instead of 42). Such a report is never kept for a reconnect.
         */
        val incompleteProbeRetries: Int = 4,
        /** Wait between those repeated probes. */
        val incompleteProbeInterval: Duration = 2.seconds,
        /** What a full scan sweeps (see [startScan]); null means this session cannot scan. */
        val scanPlan: DidSweepPlan? = null,
        /** The scan's own settings: pace, how often the speed is read, and so on. */
        val scan: FullScan.Config = FullScan.Config(),
        /** While a scan waits for a car that moves, the session polls normally for this long between two readings of the speed. */
        val scanPauseSlice: Duration = 5.seconds,
    ) {
        init {
            require(maxBatch in 1..PollScheduler.MAX_BATCH) { "maxBatch must be 1..${PollScheduler.MAX_BATCH}" }
            require(initialBackoff > Duration.ZERO) { "initialBackoff must be positive" }
            require(maxBackoff >= initialBackoff) { "maxBackoff must not be shorter than initialBackoff" }
            require(backoffFactor >= 1.0) { "backoffFactor must be at least 1" }
            require(maxConnectAttempts == null || maxConnectAttempts >= 1) { "maxConnectAttempts must be at least 1" }
            require(silenceWindow > Duration.ZERO) { "silenceWindow must be positive" }
            require(maxReinits >= 1) { "maxReinits must be at least 1" }
            require(ignitionProbeInterval > Duration.ZERO) { "ignitionProbeInterval must be positive" }
            require(incompleteProbeRetries >= 0) { "incompleteProbeRetries must not be negative" }
            require(incompleteProbeInterval > Duration.ZERO) { "incompleteProbeInterval must be positive" }
            require(scanPauseSlice > Duration.ZERO) { "scanPauseSlice must be positive" }
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

    private val _rates = MutableStateFlow(PollRates.EMPTY)
    private val _scan = MutableStateFlow<ScanState>(if (config.scanPlan == null) ScanState.Unavailable else ScanState.Idle)

    // A request for a scan reaches the polling loop through this channel; CONFLATED, so asking twice is asking once.
    private val scanRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var scanJob: Deferred<*>? = null

    /** The data identifiers the last scan found, polled raw from then on; kept across reconnects, like [knowledge]. */
    private var discoveryItems: List<PollItem> = emptyList()

    /** Where the session is in its life. */
    public val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * The latest value of every signal seen. Keeps the last values while reconnecting; each reading's
     * [knightlink.core.telemetry.TimedReading.at] shows how old it is.
     */
    public val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    /** Where the full scan is. See [startScan]. */
    public val scan: StateFlow<ScanState> = _scan.asStateFlow()

    /** What polling achieved over the last few seconds: requests per second and each signal's update rate. Refreshed about once a second. */
    public val rates: StateFlow<PollRates> = _rates.asStateFlow()

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
     * Asks for a full scan: a one-shot, parked, read-only sweep of everything the car answers (see [FullScan]). Polling stops while it runs, and
     * restarts afterwards with the data identifiers the scan found added as raw items (see [discoveryPollItems]). Progress is in [scan].
     *
     * @return false, and does nothing, when the session has no sweep plan, is not polling the car, or a scan is under way already
     */
    public fun startScan(): Boolean {
        if (config.scanPlan == null) return false
        if (_state.value !is SessionState.Polling) return false
        val current = _scan.value
        if (current is ScanState.Starting || current is ScanState.Running) return false
        _scan.value = ScanState.Starting
        return scanRequests.trySend(Unit).isSuccess
    }

    /** Stops a running scan (or withdraws a request that has not started); polling goes on. The scan's partial result is kept in [scan]. Does nothing otherwise. */
    public fun cancelScan() {
        if (_scan.value is ScanState.Starting) {
            scanRequests.tryReceive()
            _scan.value = ScanState.Idle
        }
        scanJob?.cancel()
    }

    /** Clears a finished or failed scan from [scan]. Does nothing while one is under way. */
    public fun dismissScan() {
        val current = _scan.value
        if (current is ScanState.Finished || current is ScanState.Failed) _scan.value = if (config.scanPlan == null) ScanState.Unavailable else ScanState.Idle
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
        // A scan asked for on an earlier connection is not started on this one, possibly much later and with the car on the road.
        scanRequests.tryReceive()
        if (_scan.value is ScanState.Starting) _scan.value = ScanState.Failed("The connection was lost before the scan could start")
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
        val scheduler = PollScheduler(items, maxBatch = config.maxBatch).also { it.replaceExtras(discoveryItems) }
        val loop = PollingLoop(requester, scheduler, timeSource)
        val decoder = TelemetryDecoder(catalog)

        onPolling()
        setState(SessionState.Polling(known.report, known.coverage))
        // With nothing to ask for, silence means nothing: initialising the adapter again would only disturb a link that works.
        // Items that are asked once leave nothing to watch after the first round.
        pollWithRecovery(driver, loop, scheduler, decoder, known, watchSilence = items.any { it.period.isFinite() })
    }

    /**
     * Runs the capability probe until some ECU answers it. A car that is asleep (ignition off) answers nothing; instead of
     * giving up the link, this waits and asks again, so the session goes live as soon as the ignition is switched on.
     */
    private suspend fun probeUntilVehicleAnswers(driver: Elm327Driver): Knowledge {
        var probes = 0
        var incomplete = 0
        var best: CapabilityReport? = null
        val primaryRequestId = catalog.ecus.firstOrNull { it.id == primaryEcu }?.requestCanId
        while (true) {
            val first = probes == 0
            val report = CapabilityProbe(driver, timeSource).run { stage ->
                // Later probes run quietly: a status that flickers between "waiting" and "probing" every few seconds helps nobody.
                if (first) setState(SessionState.Probing(stage))
            }.let { it.copy(adapter = it.adapter.copy(unsupportedCommands = driver.unsupportedCommands)) }
            probes++
            note(SessionEvents.PROBE_RESULT, summarise(report))
            if (report.ecus.isNotEmpty()) {
                // A report without the primary ECU, or with warnings, may be incomplete (that ECU asleep, a request timed out on a flaky
                // link). Ask again a few times and keep the fullest; only a complete report is kept for the next connection.
                val complete = report.warnings.isEmpty() && (primaryRequestId == null || report.ecus.any { it.requestId.toIntOrNull(16) == primaryRequestId })
                if (best == null || report.pidCount() >= best.pidCount()) best = report
                if (complete || incomplete >= config.incompleteProbeRetries) {
                    val chosen = best
                    return Knowledge(chosen, DefinitionCoverage.compare(chosen, catalog)).also { if (complete && chosen === report) knowledge = it }
                }
                incomplete++
                note(SessionEvents.PROBE_INCOMPLETE, "probe $probes: asking again ($incomplete of ${config.incompleteProbeRetries})")
                delay(config.incompleteProbeInterval)
                continue
            }
            setState(SessionState.WaitingForIgnition(probes, report.busFault))
            delay(config.ignitionProbeInterval)
        }
    }

    private fun CapabilityReport.pidCount(): Int = ecus.sumOf { it.supportedPids.size }

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
            val end = pollUntilTrouble(loop, scheduler, decoder, watchSilence, listenForScan = true)
            if (end is PollEnd.ScanRequested) {
                runScan(driver, loop, scheduler, decoder, watchSilence)
                lastAnswer = timeSource.markNow()
                continue
            }
            val trouble = (end as? PollEnd.Trouble)?.reason ?: continue
            // Something answered since the last re-initialisation: that outage is over, this is a new one.
            if (answers > answersBefore) failedReinits = 0
            reinitialiseOrGiveUp(driver, trouble, failedReinits).also { failedReinits = it }
            lastAnswer = timeSource.markNow()
            setState(SessionState.Polling(known.report, known.coverage))
        }
    }

    // The full scan -------------------------------------------------------------------------------------------

    /**
     * Runs the scan that [startScan] asked for. The adapter belongs to the scan meanwhile: the polling loop is not running. When the car
     * moves, the scan puts the adapter back and calls the hook below, which polls normally for a while before the scan looks at the
     * speed again. Afterwards the identifiers the scan found are added to the poll list.
     */
    private suspend fun runScan(driver: Elm327Driver, loop: PollingLoop, scheduler: PollScheduler, decoder: TelemetryDecoder, watchSilence: Boolean) {
        val plan = config.scanPlan
        if (plan == null) {
            _scan.value = ScanState.Failed("This session has no sweep plan")
            return
        }
        val scan = FullScan(
            link = driver,
            plan = plan,
            events = events,
            timeSource = timeSource,
            config = config.scan,
            whileMoving = { _ ->
                // Polling is what the car is for while it moves; a stretch of it, then the scan looks at the speed again. Trouble found here is
                // met by the ordinary recovery when the scan has ended, so it is not handled in this hook.
                pollUntilTrouble(loop, scheduler, decoder, watchSilence, limit = config.scanPauseSlice, listenForScan = false)
            },
        )
        try {
            coroutineScope {
                val watcher = launch {
                    scan.progress.collect { if (it.running) _scan.value = ScanState.Running(it) }
                }
                val job = async { scan.run() }
                scanJob = job
                try {
                    job.await()
                } catch (e: CancellationException) {
                    // Only the scan was cancelled (by the user): the session itself is still alive and goes on polling.
                    if (!currentCoroutineContext().isActive) throw e
                } finally {
                    scanJob = null
                    watcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScanRefusedException) {
            _scan.value = ScanState.Failed(e.message ?: "The scan was refused")
            return
        } catch (e: AdapterDisconnectedException) {
            _scan.value = ScanState.Failed("The link was lost during the scan")
            throw e
        } catch (e: TransportException) {
            _scan.value = ScanState.Failed("The link was lost during the scan")
            throw e
        } catch (e: Exception) {
            // The adapter stopped answering, say: what the scan found so far is in its result; polling goes on and the silence watchdog judges the link.
            note(SessionEvents.SCAN, "the scan failed: ${describe(e)}")
        }
        val result = scan.result.value
        if (result == null) {
            _scan.value = ScanState.Failed("The scan did not finish")
            return
        }
        _scan.value = ScanState.Finished(result)
        if (result.sweeps.isNotEmpty()) {
            discoveryItems = discoveryPollItems(result, plan)
            scheduler.replaceExtras(discoveryItems)
            note(SessionEvents.SCAN, "polling ${discoveryItems.size} identifiers that the scan found, raw, about every ${discoveryPeriod(discoveryItems.size)}")
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

    /** Why [pollUntilTrouble] returned. */
    private sealed interface PollEnd {
        /** The silence window passed, or the adapter kept failing. */
        class Trouble(val reason: String) : PollEnd

        /** A scan was asked for. */
        data object ScanRequested : PollEnd

        /** The time limit that the caller gave passed. */
        data object TimeUp : PollEnd
    }

    /**
     * Polls until the silence window passes or the adapter fails repeatedly (a [PollEnd.Trouble]), a scan is asked for (when [listenForScan]),
     * or [limit] has passed. A lost link throws instead.
     */
    private suspend fun pollUntilTrouble(
        loop: PollingLoop,
        scheduler: PollScheduler,
        decoder: TelemetryDecoder,
        watchSilence: Boolean,
        limit: Duration? = null,
        listenForScan: Boolean = false,
    ): PollEnd = coroutineScope {
        // A silence backs every item off and a new collection of the loop starts its clock again: begin with a clean slate.
        scheduler.restart()
        val meter = RateMeter(start = sessionStart.elapsedNow())
        var ratesShown = sessionStart.elapsedNow()
        var ratesLogged = ratesShown
        lastAnswer = timeSource.markNow()
        busFaultSinceAnswer = null
        val trouble = CompletableDeferred<PollEnd>()
        val collector = launch {
            try {
                loop.results().collect { result ->
                    publish(result, decoder, meter)
                    val now = sessionStart.elapsedNow()
                    if (now - ratesShown >= RATES_REFRESH) {
                        ratesShown = now
                        _rates.value = meter.rates(now, RATES_WINDOW)
                        if (now - ratesLogged >= RATES_LOG_INTERVAL) {
                            ratesLogged = now
                            note(
                                SessionEvents.RATES,
                                meter.rates(now, RATES_LOG_INTERVAL).summary(
                                    scheduler.singleRequestOnly.map {
                                        it.toString(16).uppercase()
                                    },
                                ),
                            )
                        }
                    }
                }
            } catch (e: PollingStalledException) {
                trouble.complete(PollEnd.Trouble(unreachableOr("the adapter failed ${e.failures} requests in a row")))
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
                        trouble.complete(PollEnd.Trouble(unreachableOr("nothing answered for ${config.silenceWindow}")))
                        return@launch
                    }
                }
            }
        }
        val scanWatcher = if (!listenForScan) {
            null
        } else {
            launch {
                scanRequests.receive()
                trouble.complete(PollEnd.ScanRequested)
            }
        }
        val timer = if (limit == null) {
            null
        } else {
            launch {
                delay(limit)
                trouble.complete(PollEnd.TimeUp)
            }
        }
        val reason = trouble.await()
        collector.cancelAndJoin()
        watchdog?.cancelAndJoin()
        scanWatcher?.cancelAndJoin()
        timer?.cancelAndJoin()
        reason
    }

    /** The reason, led by the bus fault the adapter reported meanwhile (if any), which says more than silence does. */
    private fun unreachableOr(reason: String): String {
        val fault = busFaultSinceAnswer ?: return reason
        return "vehicle bus not reachable (${fault.name.replace('_', ' ')}); $reason"
    }

    private suspend fun publish(result: PollResult, decoder: TelemetryDecoder, meter: RateMeter) {
        _pollResults.tryEmit(result)
        // The meter counts every request sent, answered or not: it measures the load on the bus.
        meter.request(sessionStart.elapsedNow(), result.items.size)
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
        if (readings.isNotEmpty()) {
            val at = sessionStart.elapsedNow()
            readings.forEach { meter.reading(it.signal.id, at) }
            _telemetry.update { it.with(readings, at) }
        }
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

        /** How often [rates] is refreshed, how many seconds it averages over, and how often the rates are written to the log. */
        val RATES_REFRESH: Duration = 1.seconds
        val RATES_WINDOW: Duration = 10.seconds
        val RATES_LOG_INTERVAL: Duration = 30.seconds
    }
}
