package knightlink.app.session

import knightlink.app.data.AppConfig
import knightlink.app.data.DefinitionStore
import knightlink.app.data.DefinitionStoreException
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import knightlink.core.common.plus
import knightlink.core.recorder.RecordingHeader
import knightlink.core.recorder.RecordingTransport
import knightlink.core.runtime.ScanState
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleSession
import knightlink.core.signals.SignalCatalog
import knightlink.core.telemetry.TelemetrySnapshot
import knightlink.core.vehicle.CapabilityReport
import knightlink.voice.intents.AnswerContext
import knightlink.voice.intents.LinkStatus
import knightlink.voice.intents.ReadingAges
import knightlink.voice.intents.SignalProvenance
import knightlink.voice.intents.signalProvenance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Hosts at most one [VehicleSession] and turns it into [uiState]. Plain Kotlin, no Android types: the foreground
 * service owns one of these, and the activity only observes it.
 *
 * What happens on [connect]: the vehicle and adapter definitions named by the config are loaded, the transport for the
 * target is wrapped in a [RecordingTransport] (every link opened is recorded), and a [VehicleSession] is started. The
 * session, the screen-state publisher and the work of loading all run in one child scope of [scope], so
 * [disconnect] ends all of it at once.
 *
 * Threading: [connect] and [disconnect] may be called from any thread. [uiState] is a StateFlow and may be read from any
 * thread.
 *
 * @param scope owns everything started here. Its dispatcher must not be a single thread that is blocked by other work.
 * @param config the config to use, read at each connect so a broken config is a [ConnectionStatus.Failed], not a crash
 * @param transports how to reach a target
 * @param recordings where the recording of each link goes
 * @param timeSource clock of the session and of the ages of the readings
 * @param uiRefresh how often the screen state is rebuilt while nothing else changed: this is what ages values into "stale",
 *   and it caps how often the list is rebuilt (readings arrive many times per second)
 * @param staleAfter a value older than this is shown as stale
 * @param connectionLog the rolling text log that the session's events are also written to; null for none
 * @param sessionConfig the session's settings; the adapter definition's scripts and timing are put on top of them at each connect
 * @param samples the phone's own sensors, recorded next to the link's traffic while a session runs; null for none
 */
class SessionController(
    private val scope: CoroutineScope,
    private val config: () -> AppConfig,
    private val store: DefinitionStore,
    private val transports: TransportFactory,
    private val recordings: RecordSinkFactory,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val uiRefresh: Duration = DEFAULT_UI_REFRESH,
    private val staleAfter: Duration = DEFAULT_STALE_AFTER,
    private val connectionLog: ConnectionLog? = null,
    private val sessionConfig: VehicleSession.Config = VehicleSession.Config(),
    private val samples: SampleSource? = null,
) {
    /** One connection request, from [connect] to the end of [disconnect]. */
    private class Run(val scope: CoroutineScope) {
        @Volatile
        var session: VehicleSession? = null

        /** Stops the phone's sensors; set while they run. */
        @Volatile
        var stopSamples: (() -> Unit)? = null

        /** What a spoken answer needs besides the live data; set together with [session], before it is. */
        @Volatile
        var voice: VoiceFacts? = null
    }

    /**
     * The per-run facts of [answerContext]: [started] is the zero of every reading's `at` (the mark taken right before the session
     * starts, the same one [publish] ages the readings against), the provenance and maximum ages come from the run's catalog.
     */
    private class VoiceFacts(val started: TimeMark, val provenance: SignalProvenance, val maxAge: (String) -> Duration)

    private val lock = Any()
    private var current: Run? = null
    private val _uiState = MutableStateFlow(UiState.IDLE)

    /** What to show. Idle until [connect], and again after [disconnect] has finished. */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Starts connecting to [target] and returns at once; progress is in [uiState]. Failures (unknown scenario, missing
     * definition, a transport this build lacks) show up there as [ConnectionStatus.Failed].
     *
     * @return false, and does nothing, when a connection exists already (also one that has failed: [disconnect] first)
     */
    fun connect(target: ConnectionTarget): Boolean {
        val run = synchronized(lock) {
            if (current != null) {
                null
            } else {
                Run(CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))).also { current = it }
            }
        } ?: return false
        connectionLog?.app("connect requested: ${target.describe()}")
        _uiState.value = UiState(ConnectionStatus.Connecting(1), vehicle = null, liveValues = emptyList())
        run.scope.launch { start(run, target) }
        return true
    }

    /**
     * Ends the connection: no coroutine of it runs afterwards, the stream is closed, the recording is finished and
     * [uiState] is idle. Does nothing when there is no connection. Completes even if the caller is cancelled meanwhile.
     */
    suspend fun disconnect() {
        val run = synchronized(lock) { current } ?: return
        withContext(NonCancellable) {
            run.stopSamples?.invoke()
            // The session first: it waits for its own teardown, so the stream is closed when this returns.
            run.session?.stop()
            run.scope.coroutineContext.job.cancelAndJoin()
            recordings.finishCurrent()
            connectionLog?.app("disconnected")
            connectionLog?.endAttempt()
            // Only now may another connection start: two sessions must never share an adapter. Idle is published before the
            // slot is freed, so it cannot overwrite the "Connecting" of a connect that slips in right after.
            synchronized(lock) {
                if (current === run) {
                    _uiState.value = UiState.IDLE
                    current = null
                }
            }
        }
    }

    /**
     * Everything a spoken answer may use, captured now: the latest readings, the time in the readings' own frame (time since the
     * session started, from the same mark [uiState] ages values with), the link, how far each signal can be trusted, and how old a
     * reading may be. Without a connection it is the empty, not-connected context; while one is still loading its definitions, the empty, connecting one.
     * Safe to call from any thread.
     */
    fun answerContext(): AnswerContext {
        val run = synchronized(lock) { current }
        val session = run?.session
        val voice = run?.voice
        if (run == null) return NOT_CONNECTED_CONTEXT
        // Asked for, but the session has not started yet (definitions still loading).
        if (session == null || voice == null) return NOT_CONNECTED_CONTEXT.copy(link = LinkStatus.CONNECTING)
        return AnswerContext(
            snapshot = session.telemetry.value,
            now = voice.started.elapsedNow(),
            link = session.state.value.toLinkStatus(),
            provenance = voice.provenance,
            maxAge = voice.maxAge,
        )
    }

    private suspend fun start(run: Run, target: ConnectionTarget) {
        // The session's constructor validates its inputs too (the catalog against the config), so it belongs inside the try:
        // a throw there must show as Failed, not leave the screen on "Connecting".
        var catalog: SignalCatalog? = null
        var recorded: RecordingTransport? = null
        val session = try {
            val setup = prepare(target)
            catalog = setup.catalog
            recorded = setup.transport
            VehicleSession(setup.transport, setup.initScript, setup.catalog, run.scope, timeSource, setup.config, setup.events)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connectionLog?.app("could not start the session: ${e.message ?: e::class.simpleName}")
            _uiState.value = UiState(ConnectionStatus.Failed(e.message ?: e::class.simpleName ?: "unknown error"), vehicle = null, liveValues = emptyList())
            return
        }
        // A disconnect that arrived while the definitions were loading must not start a session.
        run.scope.coroutineContext.ensureActive()
        val started = timeSource.markNow()
        val periods = signalPeriods(checkNotNull(catalog))
        run.voice = VoiceFacts(started, catalog.signalProvenance(), ReadingAges.fromPeriods(periods))
        run.session = session
        run.scope.launch { publish(session, started, periods) }
        run.scope.launch { keepScanResults(session) }
        startSamples(run, checkNotNull(recorded))
        session.start()
    }

    /** The scan's result goes beside the recording of the link it ran on (VIN masked), where it can be pulled from the phone. */
    private suspend fun keepScanResults(session: VehicleSession) {
        session.scan.filterIsInstance<ScanState.Finished>().collect { finished ->
            recordings.writeSidecar(SCAN_RESULT_SUFFIX, finished.result.toJson(pretty = true))
            connectionLog?.app("full scan ended: ${finished.result.outcome.name.lowercase()}")
        }
    }

    /** Samples of the phone's sensors go into the recording while the session runs; a full buffer drops the oldest, never the session's time. */
    private fun startSamples(run: Run, transport: RecordingTransport) {
        val source = samples ?: return
        val lines = Channel<String>(SAMPLE_BUFFER, BufferOverflow.DROP_OLDEST)
        run.scope.launch { for (line in lines) transport.external(line) }
        source.start { line -> lines.trySend(line) }
        run.stopSamples = {
            source.stop()
            lines.close()
        }
    }

    /** Asks the live session for a full scan; false when there is none, or it cannot scan now (see [VehicleSession.startScan]). */
    fun startFullScan(): Boolean {
        val session = synchronized(lock) { current }?.session ?: return false
        val accepted = session.startScan()
        connectionLog?.app("full scan requested: ${if (accepted) "started" else "not possible now"}")
        return accepted
    }

    /** Stops a running full scan; polling goes on. */
    fun cancelFullScan() {
        synchronized(lock) { current }?.session?.cancelScan()
        connectionLog?.app("full scan cancelled")
    }

    /** Removes the result of a finished scan from the screen. */
    fun dismissFullScan() {
        synchronized(lock) { current }?.session?.dismissScan()
    }

    private class Setup(
        val transport: RecordingTransport,
        val initScript: List<String>,
        val catalog: SignalCatalog,
        val config: VehicleSession.Config,
        val events: SessionEventSink,
    )

    private fun prepare(target: ConnectionTarget): Setup {
        val config = config()
        val catalog = store.vehicleCatalog(config.vehicleDefinition)
        val adapter = store.adapter(config.adapterDefinition)
        // The note ends up in recordings that users share: it names the kind of link, never an address.
        val note = when (target) {
            is ConnectionTarget.Demo -> "app, demo: ${target.scenarioId}"
            is ConnectionTarget.Ble -> "app, bluetooth"
        }
        val transport = RecordingTransport(
            transports.create(target),
            { recordings.newSink() },
            timeSource,
            RecordingHeader(adapter.id, note),
            externalSamples = samples != null,
        )
        // The adapter definition decides the scripts and the adapter-specific timing; everything else is the app's. A missing sweep plan only
        // means no full scan: it is not a reason to refuse the connection.
        val plan = try {
            store.sweepPlan()
        } catch (e: DefinitionStoreException) {
            connectionLog?.app("no full scan in this session: ${e.message}")
            null
        }
        val sessionSettings = sessionConfig.copy(
            probeScript = adapter.probeScript,
            pollScript = adapter.pollScript,
            driver = adapter.driverConfig(),
            scanPlan = plan,
        )
        // The recording gets every event (it is the replayable record); the text log gets the same ones, except the scan's result, which is
        // a long line of JSON that belongs in the recording and its sidecar, not in a log that is meant to be read on a phone.
        val events: SessionEventSink = connectionLog?.let { transport + it.withoutScanResult() } ?: transport
        return Setup(transport, adapter.initScript, catalog, sessionSettings, events)
    }

    /**
     * Rebuilds [uiState] whenever the session's state changes and every [uiRefresh] otherwise. Readings are pulled at that
     * pace instead of being collected, which bounds the work however fast they arrive.
     */
    private suspend fun publish(session: VehicleSession, started: TimeMark, periods: Map<String, Duration>) {
        var vehicle: VehicleSummary? = null
        var summarised: CapabilityReport? = null
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(uiRefresh)
            }
        }
        combine(session.state, ticks) { state, _ -> state }.collect { state ->
            if (state is SessionState.Polling && state.report !== summarised) {
                summarised = state.report
                vehicle = vehicleSummary(state.report, state.coverage)
            }
            // The rate is only meaningful while polling; elsewhere (reconnecting, recovering) the old number would mislead.
            val rate = session.rates.value.takeIf { state is SessionState.Polling && it.window.isPositive() }?.requestsPerSecond
            _uiState.value = UiState(
                state.toStatus(),
                vehicle,
                liveValues(session.telemetry.value, started.elapsedNow(), staleAfter, periods),
                rate,
                session.scan.value.toStatus(),
            )
        }
    }

    /** How often each signal is asked for, from its definition's poll tier, so a value that is refreshed every few seconds is not shown as stale. */
    private fun signalPeriods(catalog: SignalCatalog): Map<String, Duration> =
        catalog.parameters.flatMap { parameter -> parameter.signals.map { it.definition.id to parameter.tier.period } }.toMap()

    companion object {
        /** What an answer sees when there is no running session. */
        val NOT_CONNECTED_CONTEXT = AnswerContext(TelemetrySnapshot.EMPTY, Duration.ZERO, LinkStatus.NOT_CONNECTED, { _, _ -> null })

        /** Sensor lines held for the recording at most; at 2 motion lines a second this is minutes of a recorder that is stuck. */
        private const val SAMPLE_BUFFER = 512
        const val SCAN_RESULT_SUFFIX = ".scan.json"
        val DEFAULT_UI_REFRESH: Duration = 250.milliseconds
        val DEFAULT_STALE_AFTER: Duration = 5.seconds
    }
}

/** What kind of link, for the log. Never an address. */
private fun ConnectionTarget.describe(): String = when (this) {
    is ConnectionTarget.Demo -> "demo ($scenarioId)"
    is ConnectionTarget.Ble -> "bluetooth"
}

/** The log's sink for the session's events, leaving out the scan's result line (see [SessionController]); a short note stands in for it. */
private fun ConnectionLog.withoutScanResult(): SessionEventSink = SessionEventSink { event ->
    if (event.name == SessionEvents.SCAN_RESULT) {
        sink.emit(SessionEvents.SCAN_RESULT, "(written to the recording)")
    } else {
        sink.emit(event)
    }
}
