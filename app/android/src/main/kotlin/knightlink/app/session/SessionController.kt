package knightlink.app.session

import knightlink.app.ble.StringStore
import knightlink.app.data.AppConfig
import knightlink.app.data.DashboardReadings
import knightlink.app.data.DashboardStore
import knightlink.app.data.DefinitionStore
import knightlink.app.data.DefinitionStoreException
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import knightlink.core.common.plus
import knightlink.core.recorder.ExternalSamples
import knightlink.core.recorder.RecordingHeader
import knightlink.core.recorder.RecordingTransport
import knightlink.core.runtime.CaptureMode
import knightlink.core.runtime.LadderConfig
import knightlink.core.runtime.ProtocolMemoryStore
import knightlink.core.runtime.ProtocolNegotiator
import knightlink.core.runtime.ReadLimits
import knightlink.core.runtime.ResolvedVehicle
import knightlink.core.runtime.ScanState
import knightlink.core.runtime.SessionFacts
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleResolver
import knightlink.core.runtime.VehicleSession
import knightlink.core.scan.DidSweepPlan
import knightlink.core.scan.FaultCodeDictionary
import knightlink.core.signals.SignalCatalog
import knightlink.core.telemetry.TelemetrySnapshot
import knightlink.core.tpms.TpmsDecoder
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.ModelList
import knightlink.core.vehicle.ModelYear
import knightlink.core.vehicle.SupportTier
import knightlink.core.vehicle.UNKNOWN_MAKE
import knightlink.core.vehicle.VehicleIdentity
import knightlink.core.vehicle.WmiTable
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
    /** Where the odometer and range the driver typed are kept; null for none (they are then only kept for this run). */
    private val dashboardStore: DashboardStore? = null,
    /** Where the protocol found for an adapter is remembered; null remembers nothing. */
    private val protocolMemory: ProtocolMemoryStore? = null,
    /** The calendar year, to read the model year out of a VIN. */
    private val currentYear: () -> Int = { java.time.Year.now().value },
    /** Where the deep scan switch is kept between runs; null keeps it for this run only. */
    private val settings: StringStore? = null,
) {
    /** The deep scan switch (levels L3 and L4 of the learning ladder on an unknown car): on unless the user turned it off. */
    private val deepScan = MutableStateFlow(settings?.get(DEEP_SCAN_KEY) != "off")

    /** One connection request, from [connect] to the end of [disconnect]. */
    private class Run(val scope: CoroutineScope) {
        @Volatile
        var session: VehicleSession? = null

        /** Stops the phone's sensors; set while they run. */
        @Volatile
        var stopSamples: (() -> Unit)? = null

        /** Writes a line into the recording of this run; set when the recording exists. */
        @Volatile
        var mark: (suspend (String) -> Unit)? = null

        /** Whether the drive mode has been written into the recording of the link that is polling now (every link opens a new recording). */
        var modeStamped = false

        /** What the tyre sensor capture shows now; set while it runs. */
        @Volatile
        var tyres: (() -> TyreSensors)? = null

        /** Where the session's events go (the recording and the log); set when the session is created. */
        @Volatile
        var events: SessionEventSink? = null

        /** Whether the dashboard start readings have been written into the recording of the link that is polling now. */
        var dashboardStamped = false

        /** When the scan of the other units last ended in an outcome the screen shows for a while; null while it has none. */
        var bodyOutcomeSince: TimeMark? = null

        /** When the ladder's outcome first showed; as [bodyOutcomeSince]. */
        var ladderOutcomeSince: TimeMark? = null

        /** What a spoken answer needs besides the live data; set together with [session], before it is. */
        @Volatile
        var voice: VoiceFacts? = null

        /** The data files that turn the probe into who the car is; set when the run is prepared. */
        @Volatile
        var identityData: IdentityData? = null
    }

    /** The manufacturer codes, the model picker lists of a run. */
    private class IdentityData(val wmi: WmiTable?, val models: ModelList?)

    /**
     * The per-run facts of [answerContext]: [started] is the zero of every reading's `at` (the mark taken right before the session
     * starts, the same one [publish] ages the readings against), the provenance and maximum ages come from the run's catalog.
     */
    private class VoiceFacts(val started: TimeMark, val provenance: SignalProvenance, val maxAge: (String) -> Duration)

    private val lock = Any()
    private var current: Run? = null
    private val _uiState = MutableStateFlow(UiState.IDLE)
    private val dashboard = MutableStateFlow(dashboardStore?.load() ?: DashboardReadings())

    /** The drive mode the driver last said in this run; forgotten when a run starts. */
    @Volatile
    private var driveMode: DriveMode? = null

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
        driveMode = null
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
        var tyres: List<TpmsDecoder> = emptyList()
        val session = try {
            val setup = prepare(target)
            catalog = setup.catalog
            recorded = setup.transport
            run.mark = { line -> setup.transport.external(line) }
            run.events = setup.events
            run.identityData = IdentityData(setup.wmi, setup.models)
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
        val periods = signalPeriods(checkNotNull(catalog))
        run.voice = VoiceFacts(started, catalog.signalProvenance(), ReadingAges.fromPeriods(periods))
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
            val listening = radio.start(capture.matches) { serviceData, key, data, rssi ->
                capture.accept(started.elapsedNow().inWholeMilliseconds, serviceData, key, data, rssi)?.let { lines.trySend(it) }
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

    /**
     * The driver says the car is now in [mode]: it goes into the recording at this moment (and the connection log), so the data can be read
     * with the mode it was driven in. False when no run is recording.
     */
    fun markDriveMode(mode: DriveMode): Boolean {
        val run = synchronized(lock) { current } ?: return false
        val mark = run.mark ?: return false
        driveMode = mode
        connectionLog?.app("drive mode: ${mode.tag}")
        run.scope.launch { mark(ExternalSamples.modeLine(mode.tag)) }
        return true
    }

    /**
     * The driver typed what the dashboard shows: the odometer ([odometer]) and the distance to empty ([range]) in kilometres, at the start or, with
     * [end], at the end of the drive. They are kept (and shown again next time), and written into the recording (`odo` and `dte` lines) and the
     * events when a run is recording; an empty or unreadable field is not written, and the field is kept as it was. False when no run is recording
     * (the values are kept all the same).
     */
    fun markDashboard(odometer: String, range: String, end: Boolean): Boolean {
        val odo = DashboardReadings.parseKm(odometer)
        val dte = DashboardReadings.parseKm(range)
        val before = dashboard.value
        val updated = if (end) {
            before.copy(odometerEndKm = odo ?: before.odometerEndKm, rangeEndKm = dte ?: before.rangeEndKm)
        } else {
            before.copy(odometerStartKm = odo ?: before.odometerStartKm, rangeStartKm = dte ?: before.rangeStartKm)
        }
        dashboard.value = updated
        dashboardStore?.save(updated)
        val run = synchronized(lock) { current } ?: return false
        val mark = run.mark ?: return false
        run.scope.launch { stampDashboard(run, mark, odo, dte, end) }
        return true
    }

    private suspend fun stampDashboard(run: Run, mark: suspend (String) -> Unit, odo: Long?, dte: Long?, end: Boolean) {
        if (odo == null && dte == null) return
        odo?.let { mark(ExternalSamples.odometerLine(it, end)) }
        dte?.let { mark(ExternalSamples.rangeLine(it, end)) }
        val which = if (end) "end" else "start"
        val text = "dashboard $which: odometer ${odo ?: "-"} km, range ${dte ?: "-"} km"
        connectionLog?.app(text)
        try {
            run.events?.emit(SessionEvents.APP, text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A broken log must not stop the screen.
        }
    }

    /**
     * Switches the live session to passive capture (the adapter only listens to the car's broadcast, live values pause) or back to active
     * polling. The change goes into the recording as a `capture` line (see [publish]). False when there is no session or it cannot do it now.
     */
    fun setCaptureMode(passive: Boolean): Boolean {
        val session = synchronized(lock) { current }?.session ?: return false
        val accepted = session.setCaptureMode(if (passive) CaptureMode.PASSIVE else CaptureMode.ACTIVE)
        connectionLog?.app("passive capture ${if (passive) "on" else "off"} requested: ${if (accepted) "accepted" else "not possible now"}")
        return accepted
    }

    /** Asks the live session for a full scan; false when there is none, or it cannot scan now (see [VehicleSession.startScan]). */
    fun startFullScan(): Boolean {
        val session = synchronized(lock) { current }?.session ?: return false
        val accepted = session.startScan()
        connectionLog?.app("full scan requested: ${if (accepted) "started" else "not possible now"}")
        return accepted
    }

    /** After "can't read this car": negotiates and probes again on the same link. False when the session is not in that state. */
    fun retry(): Boolean {
        val session = synchronized(lock) { current }?.session ?: return false
        val accepted = session.retry()
        connectionLog?.app("retry requested: ${if (accepted) "accepted" else "not possible now"}")
        return accepted
    }

    /** Switches the deep scan on or off (it applies from the next level of the ladder, and from the next connection on); kept on this phone. */
    fun setDeepScan(enabled: Boolean) {
        deepScan.value = enabled
        settings?.put(DEEP_SCAN_KEY, if (enabled) "on" else "off")
        synchronized(lock) { current }?.session?.setDeepScan(enabled)
        connectionLog?.app("deep scan ${if (enabled) "on" else "off"}")
    }

    /** Reads the fault codes and the readiness again (read-only, nothing is cleared). False when the session is not polling. */
    fun refreshFaults(): Boolean {
        val session = synchronized(lock) { current }?.session ?: return false
        val accepted = session.refreshFaults()
        connectionLog?.app("fault codes requested: ${if (accepted) "started" else "not possible now"}")
        return accepted
    }

    /** The user says which model the car is (kept for this vehicle on this phone). Null clears it. False when there is no session. */
    fun setVehicleModel(model: String?): Boolean {
        val session = synchronized(lock) { current }?.session ?: return false
        session.setVehicleModel(model)
        connectionLog?.app("vehicle model set by the user")
        return true
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
        val wmi: WmiTable?,
        val models: ModelList?,
    )

    private fun prepare(target: ConnectionTarget): Setup {
        val config = config()
        // A real car is identified by its VIN after the probe, and gets the standard signals until then. Only the demo names its definition up
        // front: its virtual car has no real VIN.
        val demo = target is ConnectionTarget.Demo
        val catalog = store.vehicleCatalog(if (demo) config.vehicleDefinition else STANDARD_DEFINITION)
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
        // Missing or broken data files cost only what they carry: a car without a make name, without descriptions, without a picker.
        val wmi = optional("manufacturer codes") { store.wmiTable() }
        val models = optional("model list") { store.modelList() }
        val dictionary = optional("fault code descriptions") { store.faultCodes() } ?: FaultCodeDictionary.EMPTY
        val resolver: VehicleResolver = if (demo) {
            // The demo's virtual car is a known one: its definition, the identifiers an earlier scan found, and no check of units it does not have.
            val seed = optional("seeded data identifiers") { store.discoverySeed(config.vehicleDefinition) }
            VehicleResolver { ResolvedVehicle(catalog, known = true, definitionId = config.vehicleDefinition, scanPlan = plan, seedScan = seed) }
        } else {
            DefinitionResolver(store, catalog, plan, currentYear() + 1) { connectionLog?.app(it) }
        }
        val sessionSettings = sessionConfig.copy(
            probeScript = adapter.probeScript,
            pollScript = adapter.pollScript,
            driver = adapter.driverConfig(),
            scanPlan = plan,
            resolver = resolver,
            adapterId = adapter.id,
            // Only a real link needs the protocol found; the demo's virtual adapter has one.
            negotiation = if (demo) null else ProtocolNegotiator.Config(),
            protocolMemory = protocolMemory,
            // A silent car is given up on after the limit; only a car this session has already read, with a definition, is waited for.
            readLimits = if (demo) null else ReadLimits(),
            readFaults = true,
            faultDictionary = dictionary,
            // The learning ladder is for a real link and a car without a definition; the demo's car is a known one.
            ladder = if (demo) null else LadderConfig(deepScan = deepScan.value, rangesFor = { id -> ladderRanges(plan, id) }),
            wallClockMs = { System.currentTimeMillis() },
            // Written by the session at the moment the mode changes, so it sits exactly between the requests it separates. A new link starts
            // active and a recording without a line reads as active, so nothing is written for that.
            captureStamp = { passive -> transport.external(ExternalSamples.captureLine(passive)) },
        )
        // The recording gets every event (it is the replayable record); the text log gets the same ones, except the scan's result, which is
        // a long line of JSON that belongs in the recording and its sidecar, not in a log that is meant to be read on a phone.
        val events: SessionEventSink = connectionLog?.let { transport + it.withoutScanResult() } ?: transport
        return Setup(transport, adapter.initScript, catalog, sessionSettings, events, tyres, wmi, models)
    }

    private fun <T> optional(what: String, load: () -> T?): T? =
        try {
            load()
        } catch (e: DefinitionStoreException) {
            connectionLog?.app("$what left out: ${e.message}")
            null
        }

    /**
     * Who the car is: the make from the VIN's manufacturer code, the model year from its tenth character, the model the definition or the user
     * says, the last four VIN characters, and the protocol. Never more of the VIN than that.
     */
    internal fun identity(report: CapabilityReport?, facts: SessionFacts, wmi: WmiTable?): VehicleIdentity {
        val vin = report?.ecus?.firstNotNullOfOrNull { it.vin }
        val entry = wmi?.lookup(vin)
        return VehicleIdentity(
            make = entry?.make ?: UNKNOWN_MAKE,
            makeVerified = entry?.verified == true,
            model = facts.model,
            year = ModelYear.fromVin(vin, currentYear() + 1),
            vinLast4 = vin?.takeLast(VIN_TAIL)?.takeIf { vin.length >= VIN_MIN_FOR_TAIL },
            protocol = facts.protocol?.title,
            tier = if (facts.known) SupportTier.FULL else SupportTier.BASIC,
        )
    }

    /**
     * Rebuilds [uiState] whenever the session's state changes and every [uiRefresh] otherwise. Readings are pulled at that
     * pace instead of being collected, which bounds the work however fast they arrive.
     */
    private suspend fun publish(session: VehicleSession, started: TimeMark, periods: Map<String, Duration>, run: Run) {
        var vehicle: VehicleSummary? = null
        var summarised: CapabilityReport? = null
        var summarisedDiesel: Boolean? = null
        var lastReport: CapabilityReport? = null
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(uiRefresh)
            }
        }
        combine(session.state, ticks) { state, _ -> state }.collect { state ->
            // A reconnect starts a new recording: the mode the driver said before it is written into that one too.
            if (state is SessionState.Polling) {
                if (!run.modeStamped) {
                    run.modeStamped = true
                    driveMode?.let { run.mark?.invoke(ExternalSamples.modeLine(it.tag)) }
                }
                // Every link has its own recording: what the driver typed for the start of the drive is written into each.
                if (!run.dashboardStamped) {
                    run.dashboardStamped = true
                    val saved = dashboard.value
                    run.mark?.let { mark -> stampDashboard(run, mark, saved.odometerStartKm, saved.rangeStartKm, end = false) }
                }
            } else {
                run.modeStamped = false
                run.dashboardStamped = false
            }
            val facts = session.facts.value
            if (state is SessionState.Polling) lastReport = state.report
            if (state is SessionState.Polling && (state.report !== summarised || facts.compressionIgnition != summarisedDiesel)) {
                summarised = state.report
                summarisedDiesel = facts.compressionIgnition
                vehicle = vehicleSummary(state.report, state.coverage, facts.compressionIgnition)
            }
            val data = run.identityData
            val identity = lastReport?.let { identity(it, facts, data?.wmi) }
            val make = identity?.make
            // The rate is only meaningful while polling; elsewhere (reconnecting, recovering) the old number would mislead.
            val rate = session.rates.value.takeIf { state is SessionState.Polling && it.window.isPositive() }?.requestsPerSecond
            _uiState.value = UiState(
                state.toStatus(),
                vehicle,
                liveValues(session.telemetry.value, started.elapsedNow(), staleAfter, periods),
                rate,
                session.scan.value.toStatus(),
                run.tyres?.invoke(),
                driveSummary(session.fuelFills.value, driveMode, if (facts.known) TANK_LITRES else 0.0),
                session.captureMode.value,
                bodyScanProgress(run, session.bodyScan.value.toProgress()),
                dashboard.value,
                identity,
                facts.known,
                session.faults.value.toSummary(),
                make?.let { data?.models?.modelsFor(it) }.orEmpty(),
                ladderProgress(run, session.ladder.value.toProgress()),
                deepScan.value,
            )
        }
    }

    /** The ladder as the screen shows it: an outcome stays for [BODY_OUTCOME_HOLD] and then the banner goes; a flag stays. */
    private fun ladderProgress(run: Run, progress: LadderProgress): LadderProgress {
        if (!progress.isOutcome) {
            run.ladderOutcomeSince = null
            return progress
        }
        val since = run.ladderOutcomeSince ?: timeSource.markNow().also { run.ladderOutcomeSince = it }
        return if (since.elapsedNow() > BODY_OUTCOME_HOLD) LadderProgress.Idle else progress
    }

    /** The data identifier ranges a sweep plan names for the unit at [requestId], or null for the ladder's default. */
    private fun ladderRanges(plan: DidSweepPlan?, requestId: Int): List<IntRange>? =
        plan?.targets?.firstOrNull { it.requestCanId == requestId }?.ranges?.map { it.first..it.last }

    /** The scan of the other units as the screen shows it: the outcome stays for [BODY_OUTCOME_HOLD] and then the banner goes. */
    private fun bodyScanProgress(run: Run, progress: BodyScanProgress): BodyScanProgress {
        if (!progress.isOutcome) {
            run.bodyOutcomeSince = null
            return progress
        }
        val since = run.bodyOutcomeSince ?: timeSource.markNow().also { run.bodyOutcomeSince = it }
        return if (since.elapsedNow() > BODY_OUTCOME_HOLD) BodyScanProgress.Idle else progress
    }

    /** How often each signal is asked for, from its definition's poll tier, so a value that is refreshed every few seconds is not shown as stale. */
    private fun signalPeriods(catalog: SignalCatalog): Map<String, Duration> =
        catalog.parameters.flatMap { parameter -> parameter.signals.map { it.definition.id to parameter.tier.period } }.toMap()

    companion object {
        /** What an answer sees when there is no running session. */
        val NOT_CONNECTED_CONTEXT = AnswerContext(TelemetrySnapshot.EMPTY, Duration.ZERO, LinkStatus.NOT_CONNECTED, { _, _ -> null })

        /** Sensor lines held for the recording at most; at 2 motion lines a second this is minutes of a recorder that is stuck. */
        private const val SAMPLE_BUFFER = 512
        private const val STANDARD_DEFINITION = "standard/sae-j1979-mode01"
        private const val VIN_TAIL = 4
        private const val VIN_MIN_FOR_TAIL = 17
        const val SCAN_RESULT_SUFFIX = ".scan.json"
        private const val DEEP_SCAN_KEY = "deep_scan"
        val BODY_OUTCOME_HOLD: Duration = 90.seconds
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
