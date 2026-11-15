package knightlink.core.runtime

import knightlink.core.adapter.AdapterDisconnectedException
import knightlink.core.adapter.Elm327Driver
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.scheduler.DiagnosticRequester
import knightlink.core.scheduler.PollResult
import knightlink.core.scheduler.PollScheduler
import knightlink.core.scheduler.PollingLoop
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
import knightlink.core.vehicle.ProbeException
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
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
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
 */
public class VehicleSession(
    private val transport: Transport,
    private val initScript: List<String>,
    private val catalog: SignalCatalog,
    scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val config: Config = Config(),
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
    ) {
        init {
            require(initialBackoff > Duration.ZERO) { "initialBackoff must be positive" }
            require(maxBackoff >= initialBackoff) { "maxBackoff must not be shorter than initialBackoff" }
            require(backoffFactor >= 1.0) { "backoffFactor must be at least 1" }
            require(maxConnectAttempts == null || maxConnectAttempts >= 1) { "maxConnectAttempts must be at least 1" }
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
        withContext(NonCancellable) { main.cancelAndJoin() }
        _state.value = SessionState.Stopped
    }

    private suspend fun run() {
        sessionStart = timeSource.markNow()
        try {
            connectWithBackoff()
        } catch (e: CancellationException) {
            _state.value = SessionState.Stopped
            throw e
        } catch (e: Exception) {
            // A bug, not a connection problem (those are handled per connection). Show it in the state, then let it reach the scope.
            _state.value = SessionState.Failed("Unexpected error: ${describe(e)}")
            throw e
        }
    }

    private suspend fun connectWithBackoff() {
        var attempt = 1
        var wait = config.initialBackoff
        while (true) {
            _state.value = SessionState.Connecting(attempt)
            val ended = runConnection()
            if (ended.reachedPolling) {
                // The link worked for a while, so this is a new outage, not a continuation of the failed attempts.
                attempt = 0
                wait = config.initialBackoff
            }
            val limit = config.maxConnectAttempts
            if (limit != null && attempt >= limit) {
                _state.value = SessionState.Failed("Gave up after $attempt failed connection attempts; last error: ${ended.cause}")
                return
            }
            attempt++
            _state.value = SessionState.Reconnecting(attempt, wait, ended.cause)
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
        _state.value = SessionState.Initialising
        val driver = Elm327Driver(stream, driverScope)
        driver.initialize(initScript)

        val known = knowledgeOf(driver)
        val items = confirmedPollItems(catalog, known.coverage, primaryEcu, config.pollPeriod)
        val requester = DiagnosticRequester { id, payload, expected -> driver.request(id, payload, expected) }
        val loop = PollingLoop(requester, PollScheduler(items), timeSource)
        val decoder = TelemetryDecoder(catalog)

        onPolling()
        _state.value = SessionState.Polling(known.report, known.coverage)
        loop.results().collect { publish(it, decoder) }
    }

    private suspend fun knowledgeOf(driver: Elm327Driver): Knowledge {
        knowledge?.takeIf { !config.reprobeOnReconnect }?.let { return it }
        val report = CapabilityProbe(driver, timeSource).run { stage -> _state.value = SessionState.Probing(stage) }
        // An empty report is what a car with the ignition off gives. Polling nothing and caching that report would
        // make the session useless until restarted, so it counts as a failed connection and is retried.
        if (report.ecus.isEmpty()) throw ProbeException("No ECU answered the probe; is the ignition on?")
        // A report with warnings may be incomplete (a request timed out on a flaky link). It is good enough for this
        // connection, but it is not kept: the next connection probes again instead of living with the gaps.
        return Knowledge(report, DefinitionCoverage.compare(report, catalog)).also { if (report.warnings.isEmpty()) knowledge = it }
    }

    private fun publish(result: PollResult, decoder: TelemetryDecoder) {
        _pollResults.tryEmit(result)
        val error = result.error
        // The link is gone: reconnect now instead of letting the loop count to its failure limit first.
        if (error is AdapterDisconnectedException || error is TransportException) throw error
        val readings = result.response?.messages.orEmpty()
            .filterIsInstance<DiagnosticResponse.Positive>()
            .flatMap(decoder::decode)
        if (readings.isNotEmpty()) _telemetry.update { it.with(readings, sessionStart.elapsedNow()) }
    }

    private fun describe(e: Exception): String = e.message ?: e::class.simpleName ?: "unknown error"

    private fun describeLoss(cause: Throwable?): String =
        if (cause == null) "the adapter closed the connection" else "the connection failed: ${cause.message ?: cause::class.simpleName}"

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
    }
}
