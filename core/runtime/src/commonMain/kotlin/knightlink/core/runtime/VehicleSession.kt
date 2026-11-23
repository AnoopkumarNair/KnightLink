package knightlink.core.runtime

import knightlink.core.adapter.AdapterDisconnectedException
import knightlink.core.adapter.AdapterException
import knightlink.core.adapter.Elm327Driver
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmStatus
import knightlink.core.scan.CandidateCheck
import knightlink.core.scan.CandidateChecks
import knightlink.core.scan.DidSweepPlan
import knightlink.core.scan.FaultCodeDictionary
import knightlink.core.scan.FaultReader
import knightlink.core.scan.FullScan
import knightlink.core.scan.ScanRefusedException
import knightlink.core.scan.ScanResult
import knightlink.core.scheduler.DiagnosticRequester
import knightlink.core.scheduler.PollItem
import knightlink.core.scheduler.PollResult
import knightlink.core.scheduler.PollScheduler
import knightlink.core.scheduler.PollingLoop
import knightlink.core.scheduler.PollingStalledException
import knightlink.core.signals.CompiledParameter
import knightlink.core.signals.SignalCatalog
import knightlink.core.signals.SignalValue
import knightlink.core.telemetry.Reading
import knightlink.core.telemetry.TelemetryDecoder
import knightlink.core.telemetry.TelemetrySnapshot
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.transport.TransportException
import knightlink.core.vehicle.CapabilityProbe
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.ObdProtocol
import knightlink.core.vehicle.stillMatches
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
import kotlinx.coroutines.flow.first
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
    catalog: SignalCatalog,
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
        /**
         * A connection that reached polling counts as a new outage, not a continuation of the failed attempts, only if it lasted this long.
         * On 2026-11-22 the car was switched off with the adapter still plugged in: the adapter's link came up, polled for a few seconds and
         * dropped again, fifteen times in ten minutes, because every such short connection reset the wait to one second.
         */
        val stableLinkAfter: Duration = 60.seconds,
        /** ... and only if it also got this many answers from the car: an adapter that links up but hears a sleeping car has not recovered. */
        val stableAnswers: Int = 50,
        /** The phone's clock in milliseconds since 1970, to date the refuels found; 0 when there is none. */
        val wallClockMs: () -> Long = { 0L },
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
        /**
         * The data identifiers an earlier full scan of this kind of vehicle found, so that they are polled raw from the first drive on without
         * a parked scan (about 9 minutes with the engine running). Used only while no scan of this phone is known, and only when every control
         * unit in it answered the probe; a real scan replaces it. Needs [scanPlan] for its limits.
         */
        val seedScan: ScanResult? = null,
        /**
         * Identifiers no scan has asked yet, asked once per vehicle for the control units that answered the probe or are marked unprobed (the body
         * units): in a parked stage before polling starts when the car stands still, otherwise a few at a time between stretches of polling (see
         * [BodyUnitReader]). Their answers show in the recording; nothing else is done with them.
         */
        val candidateChecks: CandidateChecks? = null,
        /** How the body-unit check asks: pace, how often the speed is read, when a unit counts as absent. */
        val body: BodyUnitReader.Config = BodyUnitReader.Config(),
        /** The longest the parked body-unit stage takes before polling starts; what is left is asked in the background. */
        val bodyStageCap: Duration = 120.seconds,
        /** While driving (or when the speed is unknown), the body-unit check interrupts polling this often... */
        val bodyPacedEvery: Duration = 15.seconds,
        /** ... for at most this many requests (a pause of about a second, and under one request a second on average). */
        val bodyPacedRequests: Int = 10,
        /** How passive capture listens: slice length, identifier groups, limits (see [setCaptureMode]). */
        val capture: PassiveCapture.Config = PassiveCapture.Config(),
        /**
         * Called at the exact moment passive capture starts (true, before the adapter is told to listen) and when polling is back (false, after
         * the adapter is restored), so the app can write it into the recording. Not called when the link is lost (a new link starts active).
         * Errors it throws are ignored.
         */
        val captureStamp: suspend (passive: Boolean) -> Unit = {},
        /** The scan's own settings: pace, how often the speed is read, and so on. */
        val scan: FullScan.Config = FullScan.Config(),
        /** While a scan waits for a car that moves, the session polls normally for this long between two readings of the speed. */
        val scanPauseSlice: Duration = 5.seconds,
        /** Where what is learned about a vehicle is kept between sessions (probe, scan, discovery identifiers, gear); null remembers nothing. */
        val memory: VehicleMemoryStore? = null,
        /** Every ECU answering NO DATA for this long means the ignition is off: polling stops ([SessionState.VehicleAsleep]). */
        val asleepAfter: Duration = 3.seconds,
        /** While the vehicle sleeps, one `01 00` is sent this often to find out when it wakes. */
        val asleepProbeInterval: Duration = 10.seconds,
        /** Whether the calculated gear (`transmission.gear_calc`) is worked out and published with the telemetry. */
        val gearCalc: Boolean = true,
        /** The adapter definition's id: what protocol records are kept under. */
        val adapterId: String = "",
        /**
         * Finds the car's protocol before the probe (see [ProtocolNegotiator]); null leaves the protocol to the init script and does not negotiate,
         * as the session always did.
         */
        val negotiation: ProtocolNegotiator.Config? = null,
        /** Where the protocol that was found is remembered (per adapter and VIN prefix); null remembers nothing. */
        val protocolMemory: ProtocolMemoryStore? = null,
        /** How long a car that does not answer is waited for; null waits for ever (see [ReadLimits]). */
        val readLimits: ReadLimits? = null,
        /**
         * Chooses the vehicle definition once the probe has found the VIN; null means [catalog] is the vehicle's, and every feature is on. With a
         * resolver, a car it calls unknown gets the standard signals only and none of the sweeps, the body-unit check, `10 01` or passive capture.
         */
        val resolver: VehicleResolver? = null,
        /** Reads the fault codes and the readiness once after the probe, and again on request ([refreshFaults]). */
        val readFaults: Boolean = false,
        /** How the fault codes are read. */
        val faultReader: FaultReader.Config = FaultReader.Config(),
        /** The generic descriptions of the standard fault codes. */
        val faultDictionary: FaultCodeDictionary = FaultCodeDictionary.EMPTY,
        /** The learning ladder for a car without a definition (see [LearningLadder]); null does not run it. */
        val ladder: LadderConfig? = null,
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
            require(asleepAfter > Duration.ZERO) { "asleepAfter must be positive" }
            require(asleepProbeInterval > Duration.ZERO) { "asleepProbeInterval must be positive" }
            require(bodyStageCap > Duration.ZERO && bodyPacedEvery > Duration.ZERO && bodyPacedRequests >= 1) { "the body check's limits must be positive" }
        }
    }

    /** What a connection learned about the vehicle; kept so a reconnect does not have to repeat the probe. */
    private class Knowledge(val report: CapabilityReport, val coverage: DefinitionCoverage)

    /** How one connection ended. */
    private class Ended(val cause: String, val reachedPolling: Boolean)

    // What the session works with: the catalog it was given, until a resolver (see [Config.resolver]) has identified the car.
    private var activeCatalog: SignalCatalog = catalog
    private var primaryEcu: String? = config.primaryEcu ?: catalog.ecus.firstOrNull()?.id
    private var vehicleKnown = config.resolver == null
    private var allowedLevel = if (config.resolver == null) LadderLevel.L5 else LadderLevel.L1
    private var deepScanOn = config.ladder?.deepScan ?: true
    private var ladderMemory: LadderMemory? = null

    @Volatile
    private var ladderSkip = false
    private val ladderRequests = Channel<Unit>(Channel.CONFLATED)
    private var scanPlan: DidSweepPlan? = config.scanPlan
    private var candidateChecks: CandidateChecks? = config.candidateChecks
    private var seedScan: ScanResult? = config.seedScan
    private var resolvedModel: String? = null
    private var userModel: String? = null

    // The protocol: the init script with the locked protocol in it, once one is locked; what a reconnect and a re-initialisation send.
    private var lockedInitScript: List<String> = initScript
    private var connectionProtocol: ObdProtocol? = null
    private var negotiated: Negotiation.Found? = null
    private val _facts = MutableStateFlow(SessionFacts())
    private val _faults = MutableStateFlow<FaultsState>(FaultsState.Idle)
    private val faultRequests = Channel<Unit>(Channel.CONFLATED)
    private val retryRequests = Channel<Unit>(Channel.CONFLATED)

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
    private val _captureMode = MutableStateFlow(CaptureMode.ACTIVE)
    private val _scan = MutableStateFlow<ScanState>(if (config.scanPlan == null) ScanState.Unavailable else ScanState.Idle)

    // A request for a scan reaches the polling loop through this channel; CONFLATED, so asking twice is asking once.
    private val scanRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var scanJob: Deferred<*>? = null

    /** Set by [stop]: a scan cut short then was stopped, not cut off by the link. */
    @Volatile
    private var stopping = false

    /** The data identifiers the last scan found, polled raw from then on; kept across reconnects, like [knowledge]. */
    private var discoveryItems: List<PollItem> = emptyList()

    // The one-time body-unit check (see [BodyUnitReader]). The units and their progress live as long as the app session (and, with a memory
    // store, across installations of the session), so a reconnect carries on where the last connection stopped.
    private var bodyPrepared = false
    private var bodyUnits: List<CandidateCheck> = emptyList()
    private var bodyCheckId: String = config.candidateChecks?.id.orEmpty()
    private val bodyProgress = LinkedHashMap<String, BodyUnitProgress>()

    /** Set per connection: the parked stage did not finish, so the rest is asked a few at a time between stretches of polling. */
    private var bodyPaced = false
    private var bodyReader: BodyUnitReader? = null
    private var bodyFailures = 0
    private var bodyGaveUp = false
    private val _bodyScan = MutableStateFlow<BodyScanState>(BodyScanState.Idle)
    private val _ladder = MutableStateFlow<LadderState>(LadderState.Idle)

    /** What polling asks beyond the vehicle's own signals: the discovery identifiers. */
    private fun extras(): List<PollItem> = discoveryItems
    private var discoveryIds: Set<String> = emptySet()

    // What is remembered about the vehicle between sessions (see [Config.memory]); only the session coroutine and the poll collector touch it.
    private var vehicleKey: String? = null
    private var memoryProbe: CapabilityReport? = null
    private var lastScan: knightlink.core.scan.ScanResult? = null
    private val history = LinkedHashMap<String, DidHistory>()
    private var gear = GearEstimator()
    private var fuel = FuelTracker()
    private val _fuelFills = MutableStateFlow<List<FuelFill>>(emptyList())

    /** The refuels found so far, oldest first: those of earlier sessions (remembered) and of this one. */
    public val fuelFills: StateFlow<List<FuelFill>> = _fuelFills.asStateFlow()
    private var gearRevisionSaved = 0

    @Volatile
    private var memoryDirty = false

    @Volatile
    private var activeScheduler: PollScheduler? = null

    /** The part-way result a resumed scan carries on from; set by [resumeScan]. */
    @Volatile
    private var resumeFrom: knightlink.core.scan.ScanResult? = null

    /** Since when every answer was a clean NO DATA (the car is asleep), null while anything else was seen. */
    @Volatile
    private var noDataSince: TimeMark? = null

    /** Where the session is in its life. */
    public val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * The latest value of every signal seen. Keeps the last values while reconnecting; each reading's
     * [knightlink.core.telemetry.TimedReading.at] shows how old it is.
     */
    public val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    /**
     * Whether the session asks the car (ACTIVE, polling) or only listens to its broadcast (PASSIVE, see [setCaptureMode]). Always ACTIVE
     * after a reconnect or a recovery: the mode lives as long as the link does.
     */
    public val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

    /**
     * Where the body-unit check is: running in its parked stage (with a progress and an estimate of the seconds left), done, partly done
     * (the time cap or movement ended it; the rest is asked in the background), or skipped (the car moves or its speed is unknown).
     */
    public val bodyScan: StateFlow<BodyScanState> = _bodyScan.asStateFlow()

    /** The learning ladder of a car without a definition: which level runs and how far it is, and how it ended (see [LadderState]). */
    public val ladder: StateFlow<LadderState> = _ladder.asStateFlow()

    /** Where the full scan is. See [startScan]. */
    public val scan: StateFlow<ScanState> = _scan.asStateFlow()

    /** What the session knows about the car beyond the probe: the protocol, whether a definition applies, the model. */
    public val facts: StateFlow<SessionFacts> = _facts.asStateFlow()

    /** The fault codes and the readiness: read once after the probe (see [Config.readFaults]) and again on request ([refreshFaults]). */
    public val faults: StateFlow<FaultsState> = _faults.asStateFlow()

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
        require(config.primaryEcu == null || activeCatalog.ecus.any { it.id == config.primaryEcu }) {
            "primaryEcu '${config.primaryEcu}' is not an ECU of the catalog"
        }
    }

    /** Starts connecting. Does nothing if the session was already started, has failed or has been stopped. */
    public fun start() {
        main.start()
    }

    /**
     * Switches between polling the car (ACTIVE) and passive capture (PASSIVE): polling pauses, the adapter only listens to the bus
     * broadcast and sends nothing to the car, and each slice of what it heard is noted ([PassiveCapture]). Going back resumes polling on the
     * same link, with what the session has learned, and without a new probe. The switch happens within about a second.
     *
     * @return false, and does nothing, when PASSIVE is asked for while the session is not polling the car or a scan is under way. ACTIVE is always accepted.
     */
    public fun setCaptureMode(mode: CaptureMode): Boolean {
        if (mode == CaptureMode.PASSIVE) {
            // Passive capture of the broadcast (L5) is for a car the app knows, or one the user opted in for.
            if (!allows(LadderLevel.L5)) return false
            if (_state.value !is SessionState.Polling) return false
            val current = _scan.value
            if (current is ScanState.Starting || current is ScanState.Running) return false
        }
        _captureMode.value = mode
        return true
    }

    /**
     * Asks for a full scan: a one-shot, parked, read-only sweep of everything the car answers (see [FullScan]). Polling stops while it runs, and
     * restarts afterwards with the data identifiers the scan found added as raw items (see [discoveryPollItems]). Progress is in [scan].
     *
     * @return false, and does nothing, when the session has no sweep plan, is not polling the car, or a scan is under way already
     */
    public fun startScan(): Boolean {
        if (scanPlan == null || !allows(LadderLevel.L3)) return false
        if (_state.value !is SessionState.Polling) return false
        if (_captureMode.value == CaptureMode.PASSIVE) return false
        val current = _scan.value
        if (current is ScanState.Starting || current is ScanState.Running) return false
        _scan.value = ScanState.Starting
        return scanRequests.trySend(Unit).isSuccess
    }

    /**
     * Carries on a scan that ended part-way (the link was lost, or it was cancelled) from its last completed stage, keeping what it found. Needs the
     * part-way result in [scan] (it is restored from the vehicle's memory after a restart too).
     *
     * @return false, and does nothing, when there is no such result or a scan cannot start now (see [startScan])
     */
    public fun resumeScan(): Boolean {
        val earlier = (_scan.value as? ScanState.Finished)?.result?.takeIf { it.resumable } ?: return false
        resumeFrom = earlier
        return startScan().also { if (!it) resumeFrom = null }
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
        if (current is ScanState.Finished || current is ScanState.Failed) _scan.value = if (scanPlan == null) ScanState.Unavailable else ScanState.Idle
    }

    /**
     * Stops the session and waits for it to finish: no coroutine of the session runs afterwards, the open
     * stream has been closed (once), and [state] is [SessionState.Stopped]. Safe to call at any time, also
     * before [start] and more than once. Completes even if the caller's own coroutine is cancelled meanwhile.
     */
    public suspend fun stop() {
        stopping = true
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
            withContext(NonCancellable) {
                saveMemory()
                note(SessionEvents.END, "the session was stopped")
            }
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
            val connectedAt = timeSource.markNow()
            val answersBefore = answers
            val ended = runConnection()
            if (ended.reachedPolling && connectedAt.elapsedNow() >= config.stableLinkAfter && answers - answersBefore >= config.stableAnswers) {
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
        faultRequests.tryReceive()
        ladderRequests.tryReceive()
        retryRequests.tryReceive()
        connectionProtocol = null
        if (_captureMode.value == CaptureMode.PASSIVE) {
            _captureMode.value = CaptureMode.ACTIVE
            note(SessionEvents.CAPTURE_MODE, "back to active polling: a new connection always starts active")
        }
        if (_scan.value is ScanState.Starting) _scan.value = ScanState.Failed("The connection was lost before the scan could start")
        setState(SessionState.Initialising)
        // The rate ceiling runs on the session's clock, so it measures the same time as everything else here.
        val driver = Elm327Driver(stream, driverScope, config = config.driver.copy(timeSource = timeSource), events = events)
        val cached = knowledge?.takeIf { !config.reprobeOnReconnect }
        driver.initialize(if (cached == null) lockedInitScript + config.probeScript else lockedInitScript + config.pollScript)

        restoreFuelEarly()
        var probedNow = false
        val known = cached ?: restoreStored(driver) ?: probeUntilVehicleAnswers(driver).also {
            probedNow = true
            // The probe's timing is done with; polling runs with the definition's own, and a re-init must restore that, not the probe's.
            if (config.probeScript.isNotEmpty() || config.pollScript.isNotEmpty() || lockedInitScript != initScript) {
                driver.applyScript(lockedInitScript + config.pollScript, send = config.pollScript)
            }
        }
        // The probe has read the voltage already (ATRV); without a probe on this connection it is asked once now.
        val probedVoltage = known.report.adapter.voltageText
        if (probedNow && probedVoltage != null) note(SessionEvents.BATTERY, probedVoltage) else readBattery(driver)
        // A convenience, never a reason to fail the connection: a bad seed or candidate file only costs itself.
        try {
            applySeed(known.report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note(SessionEvents.MEMORY, "the seeded identifiers were not used: ${e.message ?: e::class.simpleName}")
        }
        try {
            applyCandidates(known.report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note(SessionEvents.MEMORY, "the one-time identifier check was not used: ${e.message ?: e::class.simpleName}")
        }
        if (config.readFaults) readFaults(driver, known.report)
        _ladder.value = LadderState.Idle
        runLadder(driver, known.report)
        runBodyStage(driver)
        val items = confirmedPollItems(activeCatalog, known.coverage, primaryEcu, config.pollPeriod)
        val requester = DiagnosticRequester { id, payload, expected -> driver.request(id, payload, expected) }
        val scheduler = PollScheduler(items, maxBatch = config.maxBatch).also { it.replaceExtras(extras()) }
        activeScheduler = scheduler
        // One clock for the whole connection: polling is interrupted now and then (the body check, a scan), and what the scheduler learned about
        // the items' timing must survive that.
        val loop = PollingLoop(requester, scheduler, timeSource, origin = timeSource.markNow())
        val decoder = TelemetryDecoder(activeCatalog)

        onPolling()
        setState(SessionState.Polling(known.report, known.coverage))
        // With nothing to ask for, silence means nothing: initialising the adapter again would only disturb a link that works.
        // Items that are asked once leave nothing to watch after the first round.
        pollWithRecovery(driver, loop, scheduler, decoder, known, watchSilence = items.any { it.period.isFinite() })
    }

    /** The battery voltage as the adapter reads it (`ATRV`), once per connection, as an event: it explains a session that went wrong at cranking. */
    private suspend fun readBattery(driver: Elm327Driver) {
        try {
            val text = driver.command("ATRV").firstOrNull { it.isNotBlank() && it.trim() != "?" }?.trim() ?: return
            note(SessionEvents.BATTERY, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdapterDisconnectedException) {
            throw e
        } catch (_: Exception) {
            // A convenience, never a reason to fail the connection.
        }
    }

    // What is remembered between sessions -------------------------------------------------------------------

    /**
     * The probe of the vehicle remembered last, when every ECU in it still answers `01 00` with the same bitmap (a cheap check that it is the same
     * vehicle in the same state); null when there is none or it does not match, and the full probe runs. Restores that vehicle's scan, discovery
     * identifiers and learned gear too.
     */
    private suspend fun restoreStored(driver: Elm327Driver): Knowledge? {
        val store = config.memory ?: return null
        if (config.reprobeOnReconnect) return null
        val stored = store.lastKey()?.let { store.load(it) } ?: return null
        if (!stored.probe.stillMatches(driver)) {
            note(SessionEvents.MEMORY, "the remembered probe was not reused: the vehicle did not answer 01 00 as remembered")
            return null
        }
        resolveVehicle(stored.probe)
        adopt(stored)
        val reused = Knowledge(stored.probe, DefinitionCoverage.compare(stored.probe, activeCatalog))
        knowledge = reused
        note(SessionEvents.PROBE_RESULT, "reused the remembered probe (01 00 matched on every ECU): ${summarise(stored.probe)}")
        note(SessionEvents.MEMORY, "restored ${discoveryItems.size} discovery identifiers, ${stored.gear.clusters.size} gear ratios")
        return reused
    }

    /** Takes over what [stored] remembers about its vehicle. */
    private fun adopt(stored: VehicleMemory) {
        vehicleKey = stored.key
        memoryProbe = stored.probe
        lastScan = stored.scan
        userModel = stored.model
        ladderMemory = stored.ladder
        publishFacts()
        history.clear()
        history.putAll(stored.discovery)
        gear = GearEstimator(stored.gear)
        gearRevisionSaved = gear.revision
        fuel = FuelTracker(stored.fuel)
        _fuelFills.value = stored.fuel.fills
        loadBodyProgress(stored.body)
        rebuildDiscovery()
        val partial = stored.scan?.takeIf { it.resumable }
        if (partial != null && _scan.value == ScanState.Idle) _scan.value = ScanState.Finished(partial, restored = true)
    }

    /** After a full probe: this vehicle becomes the remembered one (its earlier memory is loaded when there is one), and the probe is saved. */
    private fun rememberFresh(report: CapabilityReport) {
        val store = config.memory ?: return
        val key = VehicleKey.of(report, store.salt) ?: return
        if (key != vehicleKey) {
            val earlier = store.load(key)
            if (earlier != null) {
                adopt(earlier)
            } else {
                vehicleKey = key
                lastScan = null
                userModel = null
                ladderMemory = null
                history.clear()
                gear = GearEstimator()
                fuel = FuelTracker()
                _fuelFills.value = emptyList()
                gearRevisionSaved = 0
                bodyProgress.clear()
                rebuildDiscovery()
            }
        }
        memoryProbe = report.redacted()
        memoryDirty = true
        publishFacts()
        saveMemory()
    }

    /**
     * Polls the identifiers of [Config.seedScan] when no scan of this phone is known (no stored scan, no scan run in this session). The seed
     * is for one kind of vehicle: it is used only when every control unit it names answered the probe, so a different car gets nothing from it.
     */
    private suspend fun applySeed(report: CapabilityReport) {
        if (!allows(LadderLevel.L4)) return
        val seed = seedScan ?: return
        val plan = scanPlan ?: return
        if (discoveryItems.isNotEmpty() || lastScan?.sweeps?.isNotEmpty() == true) return
        // Per control unit: a unit that did not answer the probe (asleep, slow to wake) costs only its own identifiers.
        val answering = report.ecus.map { it.requestId.uppercase() }.toSet()
        val usable = seed.copy(sweeps = seed.sweeps.filter { it.positive.isNotEmpty() && it.requestId.uppercase() in answering })
        val items = if (usable.sweeps.isEmpty()) emptyList() else discoveryPollItems(usable, plan, constantIds())
        if (items.isEmpty()) {
            note(SessionEvents.MEMORY, "the seeded identifiers were not used: no control unit they name answered the probe, or none is worth polling")
            return
        }
        discoveryItems = items
        discoveryIds = discoveryItems.map { it.id }.toSet()
        note(SessionEvents.MEMORY, "polling ${discoveryItems.size} seeded data identifiers (from an earlier scan of this kind of vehicle), no scan needed")
    }

    /** Decides, once per app session, which units the one-time body check covers: those that answered the probe and those marked unprobed. */
    private suspend fun applyCandidates(report: CapabilityReport) {
        val checks = candidateChecks ?: return
        if (bodyPrepared) return
        if (!allows(LadderLevel.L4)) {
            bodyPrepared = true
            bodyUnits = emptyList()
            note(SessionEvents.BODY_SCAN, "skipped: the car is not one the app has a definition for")
            return
        }
        bodyPrepared = true
        bodyCheckId = checks.id
        val vin = report.ecus.firstNotNullOfOrNull { it.vin }
        if (!checks.appliesTo(vin)) {
            // The app's config names one vehicle; another car on the same app must not be asked the first one's identifiers.
            bodyUnits = emptyList()
            note(SessionEvents.BODY_SCAN, "skipped: not a known vehicle")
            return
        }
        val answering = report.ecus.mapNotNull { it.requestId.toIntOrNull(16) }.toSet()
        bodyUnits = checks.checks.filter { it.requestCanId in answering || it.unprobed }
        bodyUnits.forEach { bodyProgress.getOrPut(it.requestId) { BodyUnitProgress() } }
    }

    /** Takes over the stored progress of the body check when it is for the same candidate file. */
    private fun loadBodyProgress(stored: BodyCheckMemory) {
        bodyProgress.clear()
        if (stored.checkId.isNotEmpty() && stored.checkId == bodyCheckId) bodyProgress.putAll(stored.units)
    }

    private fun pendingBodyUnits(): List<CandidateCheck> = bodyUnits.filter { bodyProgress[it.requestId]?.done != true }

    private fun bodyTotals(): Pair<Int, Int> {
        val done = bodyUnits.sumOf { unit -> bodyProgress[unit.requestId]?.let { if (it.done) it.total.coerceAtLeast(it.asked) else it.asked } ?: 0 }
        val total = bodyUnits.sumOf { unit -> unit.identifiers().size }
        return done to total
    }

    private fun bodyAnswered(): Int = bodyProgress.values.sumOf { it.positive }

    private fun bodyReaderFor(driver: Elm327Driver): BodyUnitReader = BodyUnitReader(driver, events, timeSource, config.body)

    /**
     * The parked stage: with the car standing still (speed 0, read once), every unit that is not done is asked at scan pace before polling starts,
     * for at most [Config.bodyStageCap]. The speed is read again while it runs; when the car moves, the speed cannot be read, the cap passes or
     * the link is lost, the stage ends and the rest is asked in the background (see [runBodySlice]), or on the next connection. Polling is simply
     * not running meanwhile, and nothing the units answer (or do not) reaches the silence, asleep or bus-fault logic.
     */
    private suspend fun runBodyStage(driver: Elm327Driver) {
        bodyPaced = false
        bodyReader = null
        if (_bodyScan.value is BodyScanState.Running) _bodyScan.value = BodyScanState.Idle
        if (candidateChecks == null || !allows(LadderLevel.L3) || bodyGaveUp) return
        val pending = pendingBodyUnits()
        if (pending.isEmpty()) {
            note(SessionEvents.BODY_SCAN, "the body units were all checked on an earlier connection or session; nothing to ask")
            return
        }
        val reader = bodyReaderFor(driver)
        bodyReader = reader
        val speed = try {
            reader.readSpeed()
        } catch (e: AdapterDisconnectedException) {
            throw e
        } catch (e: TransportException) {
            throw e
        } catch (e: AdapterException) {
            null
        }
        if (speed != 0) {
            val reason = if (speed == null) "the speed cannot be read" else "the car moves ($speed km/h)"
            note(SessionEvents.BODY_SCAN, "no parked stage: $reason; the ${pending.size} body units are asked in the background, about one identifier a second")
            bodyPaced = true
            _bodyScan.value = BodyScanState.Skipped(moving = speed != null, reason = reason)
            return
        }
        note(SessionEvents.BODY_SCAN, "parked stage: ${pending.size} body units, up to ${config.bodyStageCap}")
        val stageStart = timeSource.markNow()
        publishBodyRunning(pending.first().label, stageStart)
        val outcome = try {
            reader.run(pending, bodyProgress, BodyUnitReader.Limits(maxTime = config.bodyStageCap, watchSpeed = true)) { label, _, _ ->
                memoryDirty = true
                publishBodyRunning(label, stageStart)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdapterDisconnectedException) {
            throw e
        } catch (e: TransportException) {
            throw e
        } catch (e: AdapterException) {
            note(SessionEvents.BODY_SCAN, "the parked stage failed: ${describe(e)}; the rest is asked in the background")
            bodyFailures++
            BodyOutcome.SPEED_UNKNOWN
        } finally {
            memoryDirty = true
            saveMemory()
            // However the stage ended (a lost link included), the banner must not stay on "keep the car parked".
            if (_bodyScan.value is BodyScanState.Running) {
                _bodyScan.value = if (bodyAnswered() > 0 || bodyTotals().first > 0) BodyScanState.Partial(bodyAnswered()) else BodyScanState.Idle
            }
        }
        val remaining = pendingBodyUnits()
        if (outcome == BodyOutcome.COMPLETED || remaining.isEmpty()) {
            _bodyScan.value = BodyScanState.Complete(bodyAnswered())
            note(SessionEvents.BODY_SCAN, "parked stage complete: ${bodyAnswered()} identifiers answered")
        } else {
            bodyPaced = true
            _bodyScan.value = BodyScanState.Partial(bodyAnswered())
            note(SessionEvents.BODY_SCAN, "parked stage ended (${outcome.name.lowercase()}); ${remaining.size} units are asked in the background")
        }
    }

    private fun publishBodyRunning(label: String, stageStart: TimeMark) {
        val (done, total) = bodyTotals()
        val capLeft = (config.bodyStageCap - stageStart.elapsedNow()).coerceAtLeast(Duration.ZERO).inWholeSeconds
        val needed = kotlin.math.ceil((total - done).coerceAtLeast(0) / config.body.requestsPerSecond * BODY_ESTIMATE_MARGIN).toLong()
        _bodyScan.value = BodyScanState.Running(label, done, total, minOf(needed, capLeft).toInt())
    }

    /** A few body-unit requests between two stretches of polling (the car moves or its speed is unknown). Errors cost only the check. */
    private suspend fun runBodySlice(driver: Elm327Driver) {
        val pending = pendingBodyUnits()
        if (pending.isEmpty() || bodyGaveUp) return
        val reader = bodyReader ?: bodyReaderFor(driver).also { bodyReader = it }
        try {
            reader.run(pending, bodyProgress, BodyUnitReader.Limits(maxRequests = config.bodyPacedRequests)) { _, _, _ -> memoryDirty = true }
            bodyFailures = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdapterDisconnectedException) {
            throw e
        } catch (e: TransportException) {
            throw e
        } catch (e: AdapterException) {
            note(SessionEvents.BODY_SCAN, "a background slice of the body check failed: ${describe(e)}")
            if (++bodyFailures >= MAX_BODY_FAILURES) {
                bodyGaveUp = true
                note(SessionEvents.BODY_SCAN, "the body check is given up for this session after $bodyFailures failed slices")
            }
        }
        saveMemory()
        if (pendingBodyUnits().isEmpty()) {
            note(SessionEvents.BODY_SCAN, "every body unit is checked: ${bodyAnswered()} identifiers answered")
        }
    }

    /**
     * Identifiers to ask only once per session. Always none: on 2026-11-22 two drives of 67 and 105 minutes asked every raw identifier once,
     * because an earlier parked run had left them marked as constant (unchanged over ten answers while the engine idled), and a constant one
     * is never asked again. Whether a value changes can only be told from a drive, which is what the identifiers are polled for.
     */
    private fun constantIds(): Set<String> = emptySet()

    private fun rebuildDiscovery() {
        val plan = scanPlan
        val scan = lastScan
        discoveryItems = if (plan != null && scan != null && scan.sweeps.isNotEmpty()) discoveryPollItems(scan, plan, constantIds()) else emptyList()
        discoveryIds = discoveryItems.map { it.id }.toSet()
    }

    /** Writes the memory when there is a vehicle to remember and something changed. Never throws: a full disk costs the memory, not the session. */
    private fun saveMemory() {
        val store = config.memory ?: return
        val key = vehicleKey ?: return
        val probe = memoryProbe ?: return
        if (!memoryDirty) return
        try {
            store.save(
                VehicleMemory(
                    key = key,
                    probe = probe,
                    scan = lastScan,
                    discovery = history.toMap(),
                    gear = gear.model,
                    fuel = fuel.model,
                    body = BodyCheckMemory(bodyCheckId, bodyProgress.toMap()),
                    model = userModel,
                    ladder = ladderMemory,
                ),
            )
            memoryDirty = false
        } catch (_: Exception) {
            // Remembering is a convenience.
        }
    }

    /**
     * Notes the value of a discovery item that answered. A value that differs from the one before marks the item as changing for good; an item
     * that was asked once because it never changed then goes back to the slow period.
     */
    private fun observeDiscovery(result: PollResult) {
        val id = result.item.id
        if (id !in discoveryIds) return
        val positive = result.response?.messages?.filterIsInstance<DiagnosticResponse.Positive>()
            ?.firstOrNull { it.service == SERVICE_READ_DATA && it.data.size >= 2 } ?: return
        val hex = positive.data.copyOfRange(2, positive.data.size).toHex()
        val old = history[id]
        val updated = when {
            old == null -> DidHistory(1, hex, false)
            else -> DidHistory((old.samples + 1).coerceAtMost(MAX_SAMPLES), hex, old.changed || old.lastHex != hex)
        }
        history[id] = updated
        memoryDirty = true
    }

    /**
     * Finds the car's protocol (when [Config.negotiation] is set) and runs the capability probe until some ECU answers it. A car that is asleep
     * (ignition off) answers nothing; instead of giving up the link, this waits and asks again, so the session goes live as soon as the ignition
     * is switched on. With [Config.readLimits] the waiting has a limit, after which the session says it cannot read the car
     * ([SessionState.CannotReadVehicle]) and waits for [retry]; a car that this session has read and that has a definition is waited for
     * for as long as it takes, as before.
     */
    private suspend fun probeUntilVehicleAnswers(driver: Elm327Driver): Knowledge {
        var probes = 0
        var incomplete = 0
        var emptyRounds = 0
        var best: CapabilityReport? = null
        var waitStart = timeSource.markNow()
        val observer = ProbeObserver(driver)
        while (true) {
            val first = probes == 0
            if (config.negotiation != null && connectionProtocol == null) {
                setState(SessionState.Negotiating(emptyRounds + 1))
                when (val result = negotiateProtocol(driver, quick = emptyRounds > 0)) {
                    is Negotiation.Found -> {
                        if (!result.readable) {
                            val name = result.protocol?.title ?: "an unidentified protocol (${result.dpn ?: "ATDPN unanswered"})"
                            giveUp(CannotReadReason.UnsupportedProtocol(name), name, "the car answers over $name, which the app cannot read yet")
                            emptyRounds = 0
                            waitStart = timeSource.markNow()
                            continue
                        }
                    }
                    is Negotiation.NotFound -> {
                        emptyRounds++
                        probes++
                        if (limitReached(emptyRounds, waitStart)) {
                            val name = ObdProtocol.fromDpn(result.dpn)?.title
                            giveUp(CannotReadReason.NoResponse, name, "no protocol answered 0100 (ATDPN ${result.dpn ?: "unanswered"})")
                            emptyRounds = 0
                            waitStart = timeSource.markNow()
                            continue
                        }
                        setState(SessionState.WaitingForIgnition(emptyRounds, if (result.busFault) "UNABLE_TO_CONNECT" else null))
                        delay(config.ignitionProbeInterval)
                        continue
                    }
                }
            }
            observer.reset()
            val probeStarted = timeSource.markNow()
            val report = CapabilityProbe(observer, timeSource).run { stage ->
                // Later probes run quietly: a status that flickers between "waiting" and "probing" every few seconds helps nobody.
                if (first) setState(SessionState.Probing(stage))
            }.let { it.copy(adapter = it.adapter.copy(unsupportedCommands = driver.unsupportedCommands)) }
            probes++
            note(SessionEvents.PROBE_RESULT, summarise(report) + ", took ${probeStarted.elapsedNow().inWholeMilliseconds} ms")
            if (report.ecus.isNotEmpty()) {
                noteBitmaps(report)
                // A report without the primary ECU, or with warnings, may be incomplete (that ECU asleep, a request timed out on a flaky
                // link). Ask again a few times and keep the fullest; only a complete report is kept for the next connection.
                val primary = primaryRequestId()
                val complete = report.warnings.isEmpty() && (primary == null || report.ecus.any { it.requestId.toIntOrNull(16) == primary })
                if (best == null || report.pidCount() >= best.pidCount()) best = report
                if (complete || incomplete >= config.incompleteProbeRetries) {
                    val chosen = best
                    resolveVehicle(chosen)
                    saveProtocolRecord(chosen)
                    return Knowledge(chosen, DefinitionCoverage.compare(chosen, activeCatalog)).also {
                        if (complete && chosen === report) {
                            knowledge = it
                            rememberFresh(report)
                        }
                    }
                }
                incomplete++
                note(SessionEvents.PROBE_INCOMPLETE, "probe $probes: asking again ($incomplete of ${config.incompleteProbeRetries})")
                delay(config.incompleteProbeInterval)
                continue
            }
            emptyRounds++
            if (observer.refusals > 0) note(SessionEvents.CANNOT_READ, "the probe was refused: ${observer.summary()}")
            if (limitReached(emptyRounds, waitStart)) {
                // A gateway is a bus that answers with refusals; a bus that merely carried the negotiation is silence.
                val refused = observer.refused > 0 || negotiated?.refused == true
                val reason = if (refused) CannotReadReason.GatewayBlocked else CannotReadReason.NoResponse
                giveUp(reason, negotiated?.protocol?.title, "no control unit answered the probe" + if (refused) " (a unit refuses)" else "")
                emptyRounds = 0
                waitStart = timeSource.markNow()
                continue
            }
            setState(SessionState.WaitingForIgnition(probes, report.busFault))
            delay(config.ignitionProbeInterval)
        }
    }

    /** The request id of the engine control unit the probe expects to answer: the catalog's primary, or the usual 7E0 when a resolver picks the catalog. */
    private fun primaryRequestId(): Int? =
        activeCatalog.ecus.firstOrNull { it.id == primaryEcu }?.requestCanId ?: if (config.resolver != null) DEFAULT_PRIMARY_REQUEST_ID else null

    /** Whether the time to wait for a silent car is up. A car this session has read, with a definition, is waited for without limit. */
    private fun limitReached(emptyRounds: Int, waitStart: TimeMark): Boolean {
        val limits = config.readLimits ?: return false
        // Only the car in front of the adapter counts: one that was read in this session and has a definition may be switched off for as long as it likes.
        if (knowledge != null && vehicleKnown) return false
        return emptyRounds >= limits.maxProbes || waitStart.elapsedNow() >= limits.maxWait
    }

    /**
     * Ends the waiting: the state says why, the log says it, and the session waits for [retry] (the link stays open; the adapter is idle).
     * A lost link ends the connection as it always does.
     */
    private suspend fun giveUp(reason: CannotReadReason, protocolName: String?, detail: String) {
        setState(SessionState.CannotReadVehicle(reason, protocolName))
        note(SessionEvents.CANNOT_READ, "$detail; reason ${reason::class.simpleName}; ATDPN ${lastDpn ?: "unknown"}")
        retryRequests.tryReceive()
        retryRequests.receive()
        note(SessionEvents.CANNOT_READ, "retry requested: negotiating and probing again")
        connectionProtocol = null
        negotiated = null
    }

    /**
     * Runs the negotiation and, when a readable protocol answers, locks it: the init script (and what a re-initialisation sends) names it from
     * now on. Records what happened in the log either way.
     */
    private suspend fun negotiateProtocol(driver: Elm327Driver, quick: Boolean): Negotiation {
        val negotiationConfig = checkNotNull(config.negotiation)
        val remembered = config.protocolMemory?.last(config.adapterId)?.protocol?.firstOrNull()?.let { ObdProtocol.fromCode(it) }
        val scripted = lockedInitScript.lastOrNull { it.filterNot(Char::isWhitespace).uppercase().startsWith("ATSP") }
            ?.filterNot(Char::isWhitespace)?.drop(SP_PREFIX_LENGTH)?.firstOrNull()?.let { ObdProtocol.fromCode(it) }
        val preferred = (remembered ?: scripted)?.takeIf { it != ObdProtocol.J1939 }
        val result = ProtocolNegotiator(driver, events, timeSource, negotiationConfig, timerRestore = config.probeScript).negotiate(preferred, quick)
        lastDpn = (result as? Negotiation.Found)?.dpn ?: (result as? Negotiation.NotFound)?.dpn
        if (result is Negotiation.Found) {
            negotiated = result
            val protocol = result.protocol
            if (protocol != null && protocol.isReadable) {
                connectionProtocol = protocol
                lockProtocol(driver, protocol)
            }
            _facts.value = _facts.value.copy(protocol = protocol, dpn = result.dpn, negotiationMs = result.elapsedMs)
        }
        return result
    }

    private var lastDpn: String? = null

    /** Names [protocol] in the init script, so that a re-initialisation, the next connection and the poll script all use it. */
    private suspend fun lockProtocol(driver: Elm327Driver, protocol: ObdProtocol) {
        val locked = initScript.map { if (it.filterNot(Char::isWhitespace).uppercase().startsWith("ATSP")) protocol.selectCommand else it }
        if (locked == lockedInitScript) return
        lockedInitScript = locked
        // Nothing is sent: the negotiation has selected the protocol already. Only what a re-initialisation runs changes.
        driver.applyScript(locked + config.probeScript, send = emptyList())
    }

    /** Remembers the protocol that worked for this adapter and this make (the VIN's manufacturer code), and what finding it took. */
    private fun saveProtocolRecord(report: CapabilityReport) {
        val store = config.protocolMemory ?: return
        val found = negotiated ?: return
        val protocol = found.protocol ?: return
        if (!protocol.isReadable) return
        try {
            store.save(
                ProtocolRecord(
                    adapterId = config.adapterId,
                    vinPrefix = report.ecus.firstNotNullOfOrNull { it.vin }?.take(VIN_PREFIX_LENGTH),
                    protocol = protocol.code.toString(),
                    dpn = found.dpn.orEmpty(),
                    viaAuto = found.viaAuto,
                    negotiationMs = found.elapsedMs,
                    failedAttempts = found.attempts.filterNot { it.outcome == "answered" }.map { "${it.step}: ${it.outcome}" },
                    seenAtMs = config.wallClockMs(),
                ),
            )
        } catch (_: Exception) {
            // Remembering is a convenience.
        }
    }

    /** The first support bitmap of every control unit, as hex (`7E0 0100 BE3FA813`): what the probe saw, in the recording. */
    private suspend fun noteBitmaps(report: CapabilityReport) {
        for (ecu in report.ecus) {
            val pids = ecu.supportedPids.map { it.toInt(16) }
            val ranges = (0..LAST_RANGE_START step RANGE_SIZE).filter { base -> base == 0 || pids.any { it in base + 1..base + RANGE_SIZE } }
            val text = ranges.joinToString(" ") { base ->
                var bits = 0L
                for (offset in 1..RANGE_SIZE) {
                    val pid = base + offset
                    val supported = if (offset == RANGE_SIZE) pids.any { it > pid } else pid in pids
                    if (supported) bits = bits or (1L shl (RANGE_SIZE - offset))
                }
                "01" + base.toString(16).uppercase().padStart(2, '0') + "=" + bits.toString(16).uppercase().padStart(8, '0')
            }
            note(SessionEvents.BITMAPS, "${ecu.requestId} -> ${ecu.responseId}: $text")
        }
    }

    /** Identifies the vehicle with [Config.resolver] (when there is one) and switches to its catalog and features. */
    private suspend fun resolveVehicle(probe: CapabilityReport) {
        val resolver = config.resolver
        val report = withRememberedVin(probe)
        val resolved = try {
            resolver?.resolve(report)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (resolved == null) {
            // Fail closed: a resolver that could not decide leaves an unknown car, never the features of a known one.
            if (resolver != null) {
                vehicleKnown = false
                allowedLevel = LadderLevel.L1
                scanPlan = null
                candidateChecks = null
                seedScan = null
                note(SessionEvents.CANNOT_READ, "the vehicle could not be identified: treated as an unknown car")
                _facts.value = _facts.value.copy(known = false, definitionId = null)
            }
            publishFacts()
            return
        }
        activeCatalog = resolved.catalog
        primaryEcu = config.primaryEcu?.takeIf { id -> resolved.catalog.ecus.any { it.id == id } } ?: resolved.catalog.ecus.firstOrNull()?.id
        vehicleKnown = resolved.known
        allowedLevel = resolved.allowedLevel
        scanPlan = if (resolved.known) resolved.scanPlan else null
        candidateChecks = if (resolved.known) resolved.candidateChecks else null
        seedScan = if (resolved.known) resolved.seedScan else null
        resolvedModel = resolved.model
        _facts.value = _facts.value.copy(known = resolved.known, definitionId = resolved.definitionId)
        val current = _scan.value
        if (current == ScanState.Unavailable || current == ScanState.Idle) _scan.value = if (scanPlan == null) ScanState.Unavailable else ScanState.Idle
        publishFacts()
    }

    /**
     * How far up the learning ladder this car may go now: a car with a definition all the way; any other as far as the resolver allows, but
     * without the user's deep-scan switch only to L1, and to L5 (passive capture, `10 01`) only when the user opted in.
     */
    private fun effectiveLevel(): LadderLevel {
        if (vehicleKnown) return LadderLevel.L5
        var level = allowedLevel
        if (config.ladder?.passiveOptIn == true && level >= LadderLevel.L4) level = LadderLevel.L5
        return if (!deepScanOn && level > LadderLevel.L1) LadderLevel.L1 else level
    }

    private fun allows(level: LadderLevel): Boolean = effectiveLevel() >= level

    /** Switches the deep scan (levels L3 and L4 of the learning ladder) on or off. It applies from the next level the ladder starts, and to the next connection. */
    public fun setDeepScan(enabled: Boolean) {
        deepScanOn = enabled
    }

    /** Ends the learning ladder that runs now, cleanly: the adapter is put back and polling goes on. Does nothing when none runs. */
    public fun skipLadder() {
        ladderSkip = true
    }

    /**
     * Runs the learning ladder again ("scan again"), parked, in place of polling for a while. It also runs on every level, whatever was remembered.
     *
     * @return false, and does nothing, when this car has a definition, the ladder is not configured, or the session is not polling the car
     */
    public fun rerunLadder(): Boolean {
        if (vehicleKnown || config.ladder == null || _state.value !is SessionState.Polling) return false
        if (_captureMode.value == CaptureMode.PASSIVE) return false
        val current = _scan.value
        if (current is ScanState.Starting || current is ScanState.Running) return false
        return ladderRequests.trySend(Unit).isSuccess
    }

    /**
     * A probe whose VIN request (09 02) went unanswered, from a car that looks exactly like the one remembered last (the same units with the
     * same support bitmaps): the remembered VIN (masked after the manufacturer code) stands in for it. Any other probe is returned as it is.
     */
    private suspend fun withRememberedVin(report: CapabilityReport): CapabilityReport {
        if (report.ecus.isEmpty() || report.ecus.any { it.vin != null }) return report
        val store = config.memory ?: return report
        val stored = try {
            store.lastKey()?.let { store.load(it) }?.probe
        } catch (_: Exception) {
            null
        } ?: return report
        val remembered = stored.ecus.firstNotNullOfOrNull { it.vin } ?: return report
        val same = stored.ecus.size == report.ecus.size &&
            report.ecus.all { live -> stored.ecus.any { it.requestId == live.requestId && it.supportedPids == live.supportedPids } }
        if (!same) return report
        note(SessionEvents.MEMORY, "the VIN was not answered; the remembered one (masked) stands in for it, the units and their support bitmaps are the same")
        val first = report.ecus.first()
        return report.copy(ecus = listOf(first.copy(vin = remembered)) + report.ecus.drop(1))
    }

    private fun publishFacts() {
        _facts.value = _facts.value.copy(model = userModel ?: resolvedModel)
    }

    /** Sets the model the user says this car is (kept in the vehicle's memory, when the car is remembered). Null or blank clears it. */
    public fun setVehicleModel(model: String?) {
        userModel = model?.trim()?.takeIf { it.isNotEmpty() }
        publishFacts()
        memoryDirty = true
        saveMemory()
    }

    // The fault codes and the readiness ---------------------------------------------------------------------

    /**
     * Asks for the fault codes and the readiness to be read again: read-only, nothing is cleared. Polling stops for the few seconds it takes.
     *
     * @return false, and does nothing, when the session is not polling the car, or a scan or passive capture is under way
     */
    public fun refreshFaults(): Boolean {
        if (!config.readFaults || _state.value !is SessionState.Polling) return false
        if (_captureMode.value == CaptureMode.PASSIVE) return false
        val current = _scan.value
        if (current is ScanState.Starting || current is ScanState.Running) return false
        return faultRequests.trySend(Unit).isSuccess
    }

    /**
     * After [SessionState.CannotReadVehicle]: negotiates and probes again on the same link (switch the ignition on first, when that was the
     * reason). Does nothing in any other state.
     *
     * @return whether the request was accepted
     */
    public fun retry(): Boolean {
        if (_state.value !is SessionState.CannotReadVehicle) return false
        return retryRequests.trySend(Unit).isSuccess
    }

    /** Reads the fault codes and the readiness of the units that answered the probe, once. Errors cost only the reading, except a lost link. */
    private suspend fun readFaults(driver: Elm327Driver, report: CapabilityReport) {
        _faults.value = FaultsState.Reading
        note(SessionEvents.FAULTS, "reading the fault codes and the readiness of ${report.ecus.size} units (read-only)")
        try {
            val reader = FaultReader(
                driver,
                events,
                timeSource,
                config.faultReader.copy(isLinkLoss = { it is AdapterDisconnectedException || it is TransportException }),
            )
            val result = reader.read(report.ecus).described(config.faultDictionary)
            _faults.value = FaultsState.Ready(result)
            result.readiness?.compressionIgnition?.let { _facts.value = _facts.value.copy(compressionIgnition = it) }
        } catch (e: CancellationException) {
            _faults.value = FaultsState.Idle
            throw e
        } catch (e: AdapterDisconnectedException) {
            _faults.value = FaultsState.Failed("the link was lost")
            throw e
        } catch (e: TransportException) {
            _faults.value = FaultsState.Failed("the link was lost")
            throw e
        } catch (e: Exception) {
            _faults.value = FaultsState.Failed(describe(e))
            note(SessionEvents.FAULTS, "the reading failed: ${describe(e)}")
        }
    }

    /**
     * The learning ladder: only for a car without a definition (a known car keeps its own features), only when [Config.ladder] is set, and
     * only as far as [effectiveLevel] says. Parked, read-only; polling has not started yet. Errors cost the ladder, not the connection, except a
     * lost link.
     */
    private suspend fun runLadder(driver: Elm327Driver, report: CapabilityReport, again: Boolean = false) {
        val settings = config.ladder ?: return
        if (vehicleKnown || report.ecus.isEmpty()) return
        val top = minOf(effectiveLevel(), LadderLevel.L4)
        if (top < LadderLevel.L1) return
        // A ladder that ran to its end on this car is not run on every connection: only when the units are not the same, or on request.
        val units = report.ecus.map { it.requestId }.sorted()
        val done = ladderMemory
        if (!again && done != null && done.units == units && LadderLevel.valueOf(done.highest) >= top) {
            note(SessionEvents.LADDER, "not run: it was done on this car (${done.highest}, ${done.answers} answers) and the units are the same")
            return
        }
        ladderSkip = false
        val ladder = LearningLadder(
            link = driver,
            units = report.ecus,
            baseline = (_faults.value as? FaultsState.Ready)?.report,
            maxLevel = top,
            config = settings,
            events = events,
            timeSource = timeSource,
            deepScan = { deepScanOn },
            skip = { ladderSkip },
            publish = { _ladder.value = it },
            faultConfig = config.faultReader.copy(isLinkLoss = { it is AdapterDisconnectedException || it is TransportException }),
        )
        val result = try {
            ladder.run()
        } catch (e: CancellationException) {
            _ladder.value = LadderState.Idle
            throw e
        } catch (e: AdapterDisconnectedException) {
            _ladder.value = LadderState.Idle
            throw e
        } catch (e: TransportException) {
            _ladder.value = LadderState.Idle
            throw e
        } catch (e: Exception) {
            note(SessionEvents.LADDER, "the ladder failed: ${describe(e)}")
            _ladder.value = LadderState.Idle
            return
        }
        when (result) {
            is LadderResult.Complete -> {
                _ladder.value = LadderState.Complete(result.highest, result.answers)
                ladderMemory = LadderMemory(units, result.highest.name, result.answers)
                memoryDirty = true
                saveMemory()
            }
            is LadderResult.Stopped -> _ladder.value = LadderState.Stopped(result.level, result.reason, result.answers)
            is LadderResult.Flagged -> {
                _ladder.value = LadderState.Flagged(result.reason)
                // What the ladder saw is shown with the other faults.
                if (config.readFaults) readFaults(driver, report)
            }
            is LadderResult.Skipped -> _ladder.value = LadderState.Idle
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
        var afterSlice = false
        while (true) {
            val answersBefore = answers
            // Slices only while the car is being watched and answers: not with nothing to watch, and not while it looks asleep or has gone quiet (more than half the silence window).
            val slice = bodyPaced && !bodyGaveUp && watchSilence && pendingBodyUnits().isNotEmpty()
            val end = pollUntilTrouble(
                loop,
                scheduler,
                decoder,
                watchSilence,
                limit = if (slice) config.bodyPacedEvery else null,
                listenForScan = true,
                restartScheduler = !afterSlice,
            )
            afterSlice = false
            if (end is PollEnd.TimeUp) {
                if (noDataSince == null && (lastAnswer?.elapsedNow() ?: Duration.ZERO) < config.silenceWindow / 2) {
                    runBodySlice(driver)
                    afterSlice = true
                }
                lastAnswer = timeSource.markNow()
                continue
            }
            if (end is PollEnd.ScanRequested) {
                runScan(driver, loop, scheduler, decoder, watchSilence)
                lastAnswer = timeSource.markNow()
                continue
            }
            if (end is PollEnd.PassiveRequested) {
                runPassive(driver, known)
                lastAnswer = timeSource.markNow()
                continue
            }
            if (end is PollEnd.FaultsRequested) {
                readFaults(driver, known.report)
                lastAnswer = timeSource.markNow()
                continue
            }
            if (end is PollEnd.LadderRequested) {
                runLadder(driver, known.report, again = true)
                lastAnswer = timeSource.markNow()
                continue
            }
            if (end is PollEnd.Asleep) {
                // Waking up asks the car (01 00): not while the mode says the adapter only listens.
                if (_captureMode.value == CaptureMode.PASSIVE) {
                    _captureMode.value = CaptureMode.ACTIVE
                    note(SessionEvents.CAPTURE_MODE, "back to active polling: the car went to sleep before passive capture could start")
                }
                waitForWake(driver, known)
                lastAnswer = timeSource.markNow()
                setState(SessionState.Polling(known.report, known.coverage))
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

    // Passive capture ----------------------------------------------------------------------------------------

    /**
     * Passive capture, as [setCaptureMode] asked for it: the adapter belongs to [PassiveCapture] until the mode goes back to ACTIVE, the bus has
     * been silent for too long, or the adapter cannot monitor; then it is put back and polling resumes (the caller's loop), with the scheduler and
     * everything learned kept and no probe. A lost link ends the connection as it always does, and the mode is ACTIVE again by then.
     */
    private suspend fun runPassive(driver: Elm327Driver, known: Knowledge) {
        note(SessionEvents.CAPTURE_MODE, "passive capture on: polling paused, the adapter only listens and sends nothing to the car")
        stamp(passive = true)
        _rates.value = PollRates.EMPTY
        val capture = PassiveCapture(driver, config.capture, events, timeSource)
        val ended = CompletableDeferred<Unit>()
        val reason = try {
            coroutineScope {
                val watcher = launch {
                    _captureMode.first { it == CaptureMode.ACTIVE }
                    ended.complete(Unit)
                }
                try {
                    capture.run(ended)
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            _captureMode.value = CaptureMode.ACTIVE
            withContext(NonCancellable) {
                // A lost link cancels the connection's coroutines; so does stop().
                val why = if (stopping) "the session was stopped" else "the link was lost; the next connection starts with active polling"
                note(SessionEvents.CAPTURE_MODE, "passive capture ended: $why")
            }
            throw e
        } catch (e: AdapterDisconnectedException) {
            _captureMode.value = CaptureMode.ACTIVE
            note(SessionEvents.CAPTURE_MODE, "passive capture ended: the link was lost; the next connection starts with active polling")
            throw e
        } catch (e: TransportException) {
            _captureMode.value = CaptureMode.ACTIVE
            note(SessionEvents.CAPTURE_MODE, "passive capture ended: the link was lost; the next connection starts with active polling")
            throw e
        } catch (e: AdapterException) {
            // The adapter stopped answering while listening (it did not come back to its prompt, say): initialise it again, and poll.
            _captureMode.value = CaptureMode.ACTIVE
            note(SessionEvents.CAPTURE_MODE, "passive capture ended: ${describe(e)}; initialising the adapter again and polling")
            recoverAfterPassive(driver, known, "after a failed passive capture: ${describe(e)}")
            stamp(passive = false)
            return
        }
        _captureMode.value = CaptureMode.ACTIVE
        note(
            SessionEvents.CAPTURE_MODE,
            when (reason) {
                PassiveCapture.Ended.REQUESTED -> "back to active polling"
                PassiveCapture.Ended.BUS_SILENT -> "back to active polling: the bus was silent for ${config.capture.silenceLimit}"
                PassiveCapture.Ended.UNSUPPORTED -> "back to active polling: the adapter cannot monitor the bus"
            },
        )
        try {
            capture.restore()
        } catch (e: AdapterDisconnectedException) {
            throw e
        } catch (e: AdapterException) {
            // The restore itself failed (and so did the fall-back initialisation it makes): the ordinary recovery decides whether the link is kept.
            recoverAfterPassive(driver, known, "restoring the adapter after passive capture failed: ${describe(e)}")
        }
        stamp(passive = false)
    }

    /** The adapter would not come back from passive capture: initialise it again, as often as [Config.maxReinits] allows, then polling resumes. */
    private suspend fun recoverAfterPassive(driver: Elm327Driver, known: Knowledge, trouble: String) {
        reinitialiseOrGiveUp(driver, trouble, 0)
        setState(SessionState.Polling(known.report, known.coverage))
    }

    private suspend fun stamp(passive: Boolean) {
        try {
            config.captureStamp(passive)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The recording is a convenience for the reader; losing a line must not end the session.
        }
    }

    // The full scan -------------------------------------------------------------------------------------------

    /**
     * Runs the scan that [startScan] asked for. The adapter belongs to the scan meanwhile: the polling loop is not running. When the car
     * moves, the scan puts the adapter back and calls the hook below, which polls normally for a while before the scan looks at the
     * speed again. Afterwards the identifiers the scan found are added to the poll list.
     */
    private suspend fun runScan(driver: Elm327Driver, loop: PollingLoop, scheduler: PollScheduler, decoder: TelemetryDecoder, watchSilence: Boolean) {
        val plan = scanPlan.takeIf { allows(LadderLevel.L3) }
        if (plan == null) {
            _scan.value = ScanState.Failed("This session has no sweep plan")
            return
        }
        val scan = FullScan(
            link = driver,
            plan = plan,
            events = events,
            timeSource = timeSource,
            config = config.scan.copy(isLinkLoss = { it is AdapterDisconnectedException || it is TransportException }),
            resume = resumeFrom.also { resumeFrom = null },
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
            // The whole connection ended under the scan: the adapter's link was lost (or the session is being stopped). The scan has published what it
            // found; it stays on the screen, and after a lost link it says so.
            withContext(NonCancellable) { keepPartialScan(scan, linkLost = !stopping) }
            throw e
        } catch (e: ScanRefusedException) {
            _scan.value = ScanState.Failed(e.message ?: "The scan was refused")
            return
        } catch (e: AdapterDisconnectedException) {
            keepPartialScan(scan)
            throw e
        } catch (e: TransportException) {
            keepPartialScan(scan)
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
        lastScan = result
        memoryDirty = true
        if (result.sweeps.isNotEmpty()) {
            rebuildDiscovery()
            scheduler.replaceExtras(extras())
            note(
                SessionEvents.SCAN,
                "polling ${discoveryItems.size} identifiers that the scan found, raw, about every ${discoveryPeriod(discoveryItems.size)}",
            )
        }
        saveMemory()
    }

    /** The link went during the scan: what it found so far stays on the screen, marked as lost, and can be resumed after the reconnect. */
    private fun keepPartialScan(scan: FullScan, linkLost: Boolean = true) {
        var partial = scan.result.value
        if (partial == null) {
            _scan.value = ScanState.Failed("The link was lost during the scan")
            return
        }
        if (linkLost && partial.outcome == knightlink.core.scan.ScanOutcome.CANCELLED) {
            partial = partial.copy(outcome = knightlink.core.scan.ScanOutcome.LINK_LOST, detail = "link lost")
        }
        _scan.value = ScanState.Finished(partial)
        lastScan = partial
        memoryDirty = true
        saveMemory()
    }

    /**
     * The ignition is off: nothing is polled. One `01 00` goes to the ECUs every [Config.asleepProbeInterval], and this returns when one answers
     * (the car woke up). A request that fails is no answer; a lost link ends the session's connection as it always does.
     */
    private suspend fun waitForWake(driver: Elm327Driver, known: Knowledge) {
        var checks = 0
        setState(SessionState.VehicleAsleep(checks))
        note(
            SessionEvents.ASLEEP,
            "every ECU answered NO DATA for ${config.asleepAfter}: the ignition is off; polling paused, asking 01 00 every ${config.asleepProbeInterval}",
        )
        while (true) {
            delay(config.asleepProbeInterval)
            val awake = known.report.ecus.any { ecu ->
                try {
                    driver.request(ecu.requestCanId, byteArrayOf(SERVICE_LIVE_DATA, 0x00), 1).messages
                        .any { it is DiagnosticResponse.Positive && it.service == SERVICE_LIVE_DATA.toInt() && it.sourceId == ecu.responseCanId }
                } catch (e: AdapterDisconnectedException) {
                    throw e
                } catch (e: AdapterException) {
                    false
                }
            }
            if (awake) break
            checks++
            setState(SessionState.VehicleAsleep(checks))
        }
        note(SessionEvents.ASLEEP, "the vehicle answers again after $checks unanswered checks; polling resumes")
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

        /** Passive capture was asked for ([setCaptureMode]). */
        data object PassiveRequested : PollEnd

        /** The fault codes were asked for again ([refreshFaults]). */
        data object FaultsRequested : PollEnd

        data object LadderRequested : PollEnd

        /** The time limit that the caller gave passed. */
        data object TimeUp : PollEnd

        /** Every answer was NO DATA for [Config.asleepAfter]: the ignition is off. */
        data object Asleep : PollEnd
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
        restartScheduler: Boolean = true,
    ): PollEnd = coroutineScope {
        // A silence backs every item off: begin with a clean slate. Not after a pause that only interrupted healthy polling (the body check), which
        // must not make every slow item due again at once.
        if (restartScheduler) scheduler.restart()
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
                            saveMemory()
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
        noDataSince = null
        val watchdog = launch {
            while (true) {
                delay(WATCHDOG_TICK)
                val quiet = lastAnswer?.elapsedNow() ?: Duration.ZERO
                // A car that answers NO DATA to everything is asleep, not broken: wait for it instead of initialising the adapter again.
                val allNoData = noDataSince?.elapsedNow() ?: Duration.ZERO
                if (allNoData >= config.asleepAfter && quiet >= config.asleepAfter && watchSilence) {
                    trouble.complete(PollEnd.Asleep)
                    return@launch
                }
                if (watchSilence && quiet >= config.silenceWindow) {
                    trouble.complete(PollEnd.Trouble(unreachableOr("nothing answered for ${config.silenceWindow}")))
                    return@launch
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
        val captureWatcher = if (!listenForScan) {
            null
        } else {
            launch {
                _captureMode.first { it == CaptureMode.PASSIVE }
                trouble.complete(PollEnd.PassiveRequested)
            }
        }
        val ladderWatcher = if (!listenForScan) {
            null
        } else {
            launch {
                ladderRequests.receive()
                trouble.complete(PollEnd.LadderRequested)
            }
        }
        val faultWatcher = if (!listenForScan) {
            null
        } else {
            launch {
                faultRequests.receive()
                trouble.complete(PollEnd.FaultsRequested)
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
        watchdog.cancelAndJoin()
        scanWatcher?.cancelAndJoin()
        captureWatcher?.cancelAndJoin()
        faultWatcher?.cancelAndJoin()
        ladderWatcher?.cancelAndJoin()
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
        when {
            result.answered -> noDataSince = null
            isCleanNoData(result) -> if (noDataSince == null) noDataSince = timeSource.markNow()
            else -> noDataSince = null
        }
        observeDiscovery(result)
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
            if (config.gearCalc && vehicleKnown && readings.any { it.signal.id == SIGNAL_RPM || it.signal.id == SIGNAL_SPEED }) publishGear(at)
            readings.firstOrNull { it.signal.id == SIGNAL_FUEL_LEVEL }?.let { watchFuel(it.signal.value) }
        }
    }

    /** NO DATA from the adapter and nothing else: no frame, no bus fault, no failure. The sign of an ECU that is asleep. */
    private fun isCleanNoData(result: PollResult): Boolean {
        val response = result.response ?: return false
        return result.error == null &&
            response.messages.isEmpty() &&
            response.respondingIds.isEmpty() &&
            response.busFault == null &&
            ElmStatus.NO_DATA in response.statuses
    }

    /**
     * Takes the last fuel level (and the refuels) of the vehicle remembered last before the probe, so that a refuel is found even when the car
     * is slow to wake and the probe is incomplete (no vehicle key yet). The memory of the vehicle the probe identifies replaces it later.
     */
    private fun restoreFuelEarly() {
        if (vehicleKey != null) return
        try {
            val store = config.memory ?: return
            val stored = store.lastKey()?.let { store.load(it) } ?: return
            fuel = FuelTracker(stored.fuel)
            _fuelFills.value = stored.fuel.fills
        } catch (e: Exception) {
            // Remembering is a convenience.
        }
    }

    /** Feeds a fuel level reading to the refuel finder; a refuel is noted in the log and kept in the memory. */
    private suspend fun watchFuel(percent: Double) {
        val fill = fuel.update(percent, config.wallClockMs())
        if (fill != null) {
            _fuelFills.value = fuel.model.fills
            note(SessionEvents.FUEL_FILL, "fuel level rose from ${fill.fromPercent.roundTenths()} % to ${fill.toPercent.roundTenths()} %")
        }
        if (fill != null || fuel.takeDirty()) {
            memoryDirty = true
            saveMemory()
        }
    }

    private fun Double.roundTenths(): Double = kotlin.math.round(this * 10.0) / 10.0

    /** The calculated gear from the latest engine speed and vehicle speed, when both are fresh; otherwise unknown. */
    private fun publishGear(at: Duration) {
        val snapshot = _telemetry.value
        val rpm = snapshot.reading(SIGNAL_RPM)
        val speed = snapshot.reading(SIGNAL_SPEED)
        if (rpm == null || speed == null) return
        val fresh = at - rpm.at <= GEAR_MAX_AGE && at - speed.at <= GEAR_MAX_AGE
        val value = if (fresh) gear.update(at.inWholeMilliseconds, rpm.reading.signal.value, speed.reading.signal.value) else GearEstimator.UNKNOWN
        _telemetry.update { it.with(listOf(Reading(null, SignalValue(GearEstimator.SIGNAL_ID, value.toDouble(), GearEstimator.UNIT))), at) }
        if (gear.revision != gearRevisionSaved) {
            gearRevisionSaved = gear.revision
            memoryDirty = true
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
        is SessionState.Negotiating -> "Negotiating the protocol, round ${state.round}"
        is SessionState.CannotReadVehicle -> "CannotReadVehicle, ${state.reason::class.simpleName}" + (state.detectedProtocol?.let { " ($it)" } ?: "")
        is SessionState.Probing -> "Probing, ${state.stage.name}"
        is SessionState.WaitingForIgnition ->
            "WaitingForIgnition, probe ${state.probes} found no ECU" + (state.busFault?.let { ", the adapter said $it" } ?: "")
        is SessionState.VehicleAsleep -> "VehicleAsleep, ignition off, ${state.checks} wake-up checks unanswered"
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
        val protocol = report.adapter.protocolNumber?.let { ", protocol $it" }.orEmpty()
        return "${report.ecus.size} ECUs: $ecus$rate$protocol" + warningsSuffix(report)
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
        const val SERVICE_LIVE_DATA: Byte = 0x01
        const val SERVICE_READ_DATA = 0x22
        const val SIGNAL_RPM = "engine.rpm"
        const val SIGNAL_FUEL_LEVEL = "fuel.level_pct"
        const val SIGNAL_SPEED = "vehicle.speed"
        const val MAX_SAMPLES = 1_000_000
        const val MAX_BODY_FAILURES = 3
        const val DEFAULT_PRIMARY_REQUEST_ID = 0x7E0
        const val VIN_PREFIX_LENGTH = 3
        const val SP_PREFIX_LENGTH = 4
        const val RANGE_SIZE = 0x20
        const val LAST_RANGE_START = 0xE0

        /** The time left of the parked stage is shown a little longer than the pace alone says: refusals and the speed checks cost time too. */
        const val BODY_ESTIMATE_MARGIN = 1.15

        /** Engine speed and vehicle speed are read a few hundred milliseconds apart; older than this and the pair says nothing about the gear. */
        val GEAR_MAX_AGE: Duration = 1.seconds

        /** How often the silence watchdog looks at the clock. */
        val WATCHDOG_TICK: Duration = 250.milliseconds

        /** How often [rates] is refreshed, how many seconds it averages over, and how often the rates are written to the log. */
        val RATES_REFRESH: Duration = 1.seconds
        val RATES_WINDOW: Duration = 10.seconds
        val RATES_LOG_INTERVAL: Duration = 30.seconds
    }
}
