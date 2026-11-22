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
import knightlink.core.scan.CandidateChecks
import knightlink.core.scan.DidSweepPlan
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
        /**
         * The data identifiers an earlier full scan of this kind of vehicle found, so that they are polled raw from the first drive on without
         * a parked scan (about 9 minutes with the engine running). Used only while no scan of this phone is known, and only when every control
         * unit in it answered the probe; a real scan replaces it. Needs [scanPlan] for its limits.
         */
        val seedScan: ScanResult? = null,
        /**
         * Identifiers no scan has asked yet, asked once per app session while connected, for the control units that answered the probe (see
         * [candidateCheckItems]). Their answers show in the recording; nothing else is done with them.
         */
        val candidateChecks: CandidateChecks? = null,
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

    /** Set by [stop]: a scan cut short then was stopped, not cut off by the link. */
    @Volatile
    private var stopping = false

    /** The data identifiers the last scan found, polled raw from then on; kept across reconnects, like [knowledge]. */
    private var discoveryItems: List<PollItem> = emptyList()

    // The one-time candidate check. The queue lives as long as the app session: an identifier that was asked leaves it for good, so a
    // reconnect carries on where the last connection stopped. Only one candidate at a time is given to the scheduler, and the next one only
    // after CANDIDATE_SPACING: a never-asked item is always due first, so 256 of them at once would push the vehicle's own signals aside.
    private var candidatesQueued = false
    private val candidateQueue = ArrayDeque<PollItem>()
    private var candidateActive: PollItem? = null
    private var candidateNextAt: Duration = Duration.ZERO

    /** What the scheduler polls beyond the vehicle's own signals: the discovery identifiers, and the candidate being asked now. */
    private fun extras(): List<PollItem> = discoveryItems + listOfNotNull(candidateActive)

    /**
     * Called with every result: the active candidate leaves when it has been asked (answered or refused), and the next one is given to the
     * scheduler when CANDIDATE_SPACING has passed. Nothing happens when there is no check.
     */
    private fun feedCandidates(result: PollResult, now: Duration) {
        val active = candidateActive
        if (active != null && result.item.id == active.id) {
            candidateActive = null
            candidateNextAt = now + CANDIDATE_SPACING
            activeScheduler?.replaceExtras(extras())
        }
        if (candidateActive == null && candidateQueue.isNotEmpty() && now >= candidateNextAt) {
            candidateActive = candidateQueue.removeFirst()
            activeScheduler?.replaceExtras(extras())
        }
    }
    private var discoveryIds: Set<String> = emptySet()

    // What is remembered about the vehicle between sessions (see [Config.memory]); only the session coroutine and the poll collector touch it.
    private var vehicleKey: String? = null
    private var memoryProbe: CapabilityReport? = null
    private var lastScan: knightlink.core.scan.ScanResult? = null
    private val history = LinkedHashMap<String, DidHistory>()
    private var gear = GearEstimator()
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
        if (current is ScanState.Finished || current is ScanState.Failed) _scan.value = if (config.scanPlan == null) ScanState.Unavailable else ScanState.Idle
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

        val known = cached ?: restoreStored(driver) ?: probeUntilVehicleAnswers(driver).also {
            // The probe's timing is done with; polling runs with the definition's own, and a re-init must restore that, not the probe's.
            if (config.probeScript.isNotEmpty() || config.pollScript.isNotEmpty()) driver.applyScript(pollingScript, send = config.pollScript)
        }
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
        val items = confirmedPollItems(catalog, known.coverage, primaryEcu, config.pollPeriod)
        val requester = DiagnosticRequester { id, payload, expected -> driver.request(id, payload, expected) }
        val scheduler = PollScheduler(items, maxBatch = config.maxBatch).also { it.replaceExtras(extras()) }
        activeScheduler = scheduler
        val loop = PollingLoop(requester, scheduler, timeSource)
        val decoder = TelemetryDecoder(catalog)

        onPolling()
        setState(SessionState.Polling(known.report, known.coverage))
        // With nothing to ask for, silence means nothing: initialising the adapter again would only disturb a link that works.
        // Items that are asked once leave nothing to watch after the first round.
        pollWithRecovery(driver, loop, scheduler, decoder, known, watchSilence = items.any { it.period.isFinite() })
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
        adopt(stored)
        val reused = Knowledge(stored.probe, DefinitionCoverage.compare(stored.probe, catalog))
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
        history.clear()
        history.putAll(stored.discovery)
        gear = GearEstimator(stored.gear)
        gearRevisionSaved = gear.revision
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
                history.clear()
                gear = GearEstimator()
                gearRevisionSaved = 0
                rebuildDiscovery()
            }
        }
        memoryProbe = report.redacted()
        memoryDirty = true
        saveMemory()
    }

    /**
     * Polls the identifiers of [Config.seedScan] when no scan of this phone is known (no stored scan, no scan run in this session). The seed
     * is for one kind of vehicle: it is used only when every control unit it names answered the probe, so a different car gets nothing from it.
     */
    private suspend fun applySeed(report: CapabilityReport) {
        val seed = config.seedScan ?: return
        val plan = config.scanPlan ?: return
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

    /** Adds the one-time candidate check (once per app session) for the control units that answered the probe. */
    private suspend fun applyCandidates(report: CapabilityReport) {
        val checks = config.candidateChecks ?: return
        if (candidatesQueued) {
            // A reconnect: the one that was in flight when the link went is asked again, then the queue carries on.
            candidateActive?.let { candidateQueue.addFirst(it) }
            candidateActive = null
            candidateNextAt = Duration.ZERO
            return
        }
        val answering = report.ecus.mapNotNull { it.requestId.toIntOrNull(16) }.toSet()
        val items = candidateCheckItems(checks, answering)
        candidatesQueued = true
        if (items.isEmpty()) return
        candidateQueue.addAll(items)
        note(
            SessionEvents.MEMORY,
            "asking ${items.size} untested data identifiers once, one about every ${CANDIDATE_SPACING.inWholeSeconds} s, to see whether any answers (read-only)",
        )
    }

    /**
     * Identifiers to ask only once per session. Always none: on 2026-11-22 two drives of 67 and 105 minutes asked every raw identifier once,
     * because an earlier parked run had left them marked as constant (unchanged over ten answers while the engine idled), and a constant one
     * is never asked again. Whether a value changes can only be told from a drive, which is what the identifiers are polled for.
     */
    private fun constantIds(): Set<String> = emptySet()

    private fun rebuildDiscovery() {
        val plan = config.scanPlan
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
            store.save(VehicleMemory(key = key, probe = probe, scan = lastScan, discovery = history.toMap(), gear = gear.model))
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
        if (old != null && old.constant && updated.changed) {
            rebuildDiscovery()
            activeScheduler?.replaceExtras(extras())
        }
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
                    return Knowledge(chosen, DefinitionCoverage.compare(chosen, catalog)).also {
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
            if (end is PollEnd.Asleep) {
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
                "polling ${discoveryItems.size} identifiers that the scan found, raw, about every ${discoveryPeriod(discoveryItems.size)} " +
                    "(${constantIds().count { it in discoveryIds }} of them only once: their value never changed)",
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
        feedCandidates(result, sessionStart.elapsedNow())
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
            if (config.gearCalc && readings.any { it.signal.id == SIGNAL_RPM || it.signal.id == SIGNAL_SPEED }) publishGear(at)
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
        const val SERVICE_LIVE_DATA: Byte = 0x01
        const val SERVICE_READ_DATA = 0x22
        const val SIGNAL_RPM = "engine.rpm"
        const val SIGNAL_SPEED = "vehicle.speed"
        const val MAX_SAMPLES = 1_000_000

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

/** The pause between two identifiers of the one-time check: it must not take more than a small share of the bus. */
private val CANDIDATE_SPACING: Duration = 1.seconds
