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
import knightlink.core.tpms.TpmsDecoder
import knightlink.core.vehicle.CapabilityReport
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
 * @param tyreSensors tyre sensor advertisements, captured (rate-limited) into the recording and shown decoded, unconfirmed, while a session runs,
 *   when the config names a tyre sensor definition; null for none
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
    private val tyreSensors: AdvertisementSource? = null,
) {
    /** One connection request, from [connect] to the end of [disconnect]. */
    private class Run(val scope: CoroutineScope) {
        @Volatile
        var session: VehicleSession? = null

        /** Stops the phone's sensors; set while they run. */
        @Volatile
        var stopSamples: (() -> Unit)? = null

        /** What the tyre sensor capture shows now; set while it runs. */
        @Volatile
        var tyres: (() -> TyreSensors)? = null
    }

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

    private suspend fun start(run: Run, target: ConnectionTarget) {
        // The session's constructor validates its inputs too (the catalog against the config), so it belongs inside the try:
        // a throw there must show as Failed, not leave the screen on "Connecting".
        var catalog: SignalCatalog? = null
        var recorded: RecordingTransport? = null
        var tyres: List<TpmsDecoder> = emptyList()
        val session = try {
            val setup = prepare(target)
            catalog = setup.catalog
            recorded = setup.transport
            tyres = setup.tyres
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
        run.session = session
        run.scope.launch { publish(session, started, signalPeriods(checkNotNull(catalog)), run) }
        run.scope.launch { keepScanResults(session) }
        startSamples(run, checkNotNull(recorded), started, tyres)
        session.start()
    }

    /** The scan's result goes beside the recording of the link it ran on (VIN masked), where it can be pulled from the phone. */
    private suspend fun keepScanResults(session: VehicleSession) {
        session.scan.filterIsInstance<ScanState.Finished>().collect { finished ->
            recordings.writeSidecar(SCAN_RESULT_SUFFIX, finished.result.toJson(pretty = true))
            connectionLog?.app("full scan ended: ${finished.result.outcome.name.lowercase()}")
        }
    }

    /**
     * Samples of the phone's sensors, and tyre sensor advertisements when there are [tyres] layouts, go into the recording while the session runs; a full
     * buffer drops the oldest, never the session's time. Tyre advertisements are timed from [started], the session's clock.
     */
    private fun startSamples(run: Run, transport: RecordingTransport, started: TimeMark, tyres: List<TpmsDecoder>) {
        val phone = samples
        val radio = tyreSensors.takeIf { tyres.isNotEmpty() }
        if (phone == null && radio == null) return
        val lines = Channel<String>(SAMPLE_BUFFER, BufferOverflow.DROP_OLDEST)
        run.scope.launch { for (line in lines) transport.external(line) }
        phone?.start { line -> lines.trySend(line) }
        if (radio != null) {
            val capture = TyreSensorCapture(tyres)
            val companyIds = tyres.map { it.definition.companyId.uppercase() }.toSet()
            val listening = radio.start(companyIds) { companyId, payload, rssi ->
                capture.accept(started.elapsedNow().inWholeMilliseconds, companyId, payload, rssi)?.let { lines.trySend(it) }
            }
            connectionLog?.app(
                if (listening) {
                    "tyre sensors: listening (${tyres.joinToString {
                        it.definition.id
                    }})"
                } else {
                    "tyre sensors: not listening (no permission or Bluetooth off)"
                },
            )
            // Anything short of a confirmed layout is shown as unconfirmed; the rules engine refuses such signals for alerts anyway.
            run.tyres = { TyreSensors(listening, capture.layouts, capture.unconfirmed, capture.readings(started.elapsedNow().inWholeMilliseconds)) }
        }
        run.stopSamples = {
            phone?.stop()
            radio?.stop()
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

    /** Resumes an interrupted scan from its last completed stage; false when there is none to resume. */
    fun resumeFullScan(): Boolean {
        val session = synchronized(lock) { current }?.session ?: return false
        val accepted = session.resumeScan()
        connectionLog?.app("full scan resume requested: ${if (accepted) "resumed" else "not possible now"}")
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
        val tyres: List<TpmsDecoder>,
    )

    private fun prepare(target: ConnectionTarget): Setup {
        val config = config()
        val catalog = store.vehicleCatalog(config.vehicleDefinition)
        val adapter = store.adapter(config.adapterDefinition)
        // Like the sweep plan, a broken tyre sensor definition costs only the capture, never the connection.
        val tyres = if (tyreSensors == null) {
            emptyList()
        } else {
            config.tpmsDefinitions.mapNotNull { id ->
                try {
                    store.tpms(id)
                } catch (e: DefinitionStoreException) {
                    connectionLog?.app("tyre sensor layout left out: ${e.message}")
                    null
                }
            }
        }
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
            externalSamples = samples != null || tyres.isNotEmpty(),
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
        return Setup(transport, adapter.initScript, catalog, sessionSettings, events, tyres)
    }

    /**
     * Rebuilds [uiState] whenever the session's state changes and every [uiRefresh] otherwise. Readings are pulled at that
     * pace instead of being collected, which bounds the work however fast they arrive.
     */
    private suspend fun publish(session: VehicleSession, started: TimeMark, periods: Map<String, Duration>, run: Run) {
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
                run.tyres?.invoke(),
            )
        }
    }

    /** How often each signal is asked for, from its definition's poll tier, so a value that is refreshed every few seconds is not shown as stale. */
    private fun signalPeriods(catalog: SignalCatalog): Map<String, Duration> =
        catalog.parameters.flatMap { parameter -> parameter.signals.map { it.definition.id to parameter.tier.period } }.toMap()

    companion object {
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
