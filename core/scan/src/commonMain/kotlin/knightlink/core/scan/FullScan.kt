package knightlink.core.scan

import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticLink
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import knightlink.core.vehicle.CapabilityProbe
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.EcuReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The stages of a full scan, in the order they run. */
public enum class ScanStage {
    /** The standard capability probe: adapter, control units that speak OBD, supported data, VIN, calibration ids, names. */
    PROBE,

    /** Readiness monitors and the on-board test results of service 06. */
    READINESS,

    /** Which request identifiers on the bus are answered by a control unit. */
    DISCOVERY,

    /** The identification data identifiers (F180–F1FF) of every control unit. */
    IDENTIFICATION,

    /** Fault codes: OBD services 03, 07, 0A and UDS `19 02`. */
    FAULTS,

    /** The manufacturer data identifiers of the engine, gearbox and exhaust treatment control units. */
    SWEEP,
}

/**
 * Where a scan is, for a screen.
 *
 * @property stage the stage running, null before the first and after the last
 * @property subject what the stage is working on (a control unit's label or request id), when that is a thing
 * @property done progress within the stage in units of [total], which depend on the stage (request identifiers, data identifiers, control units)
 * @property paused the car is moving and the scan waits; [speedKmh] is what it saw
 */
public data class ScanProgress(
    val stage: ScanStage? = null,
    val subject: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val paused: Boolean = false,
    val speedKmh: Int? = null,
    val running: Boolean = false,
)

/** The scan will not start: the car's speed cannot be read, so nothing says that it is parked. Nothing was sent but the speed requests. */
public class ScanRefusedException(message: String) : Exception(message)

/** The scan stops itself, with a result: something that must not be pressed on past happened. */
private class ScanStopped(message: String) : Exception(message)

/**
 * A one-shot, parked, read-only scan of everything the car answers: the standard probe, readiness and on-board tests, a discovery of the
 * control units on the bus, their identification data, their fault codes, and a sweep of the manufacturer data identifiers (service 22) of
 * the control units a plan names. It finds out what is there, not what it means: whatever answers is recorded with provenance `inferred`.
 *
 * Read-only: every request is one the adapter driver's read-only policy allows (service 01, 03, 06, 07, 09, 0A, 22 and 19 with a reporting
 * sub-function), sent to one control unit by its physical identifier, never to the functional address. Besides requests it sends adapter
 * settings for receiving from control units with non-OBD identifiers (`ATCF`, `ATCM`, `ATFCSH`, `ATFCSD`, `ATFCSM`).
 *
 * Safety, in the code below:
 * - It runs only while the car's speed (service 01 PID 0D on [Config.speedRequestId]) reads 0. The speed is read before it starts, and at
 *   least every [Config.speedCheckEvery] requests and [Config.speedCheckInterval]. When the car moves, the adapter is put back to its
 *   ordinary settings, [whileMoving] is called (the session polls normally meanwhile), and the scan goes on when the speed reads 0 again.
 *   A speed that cannot be read stops the scan.
 * - At most [DidSweepPlan.MAX_REQUESTS_PER_SECOND] requests a second.
 * - A control unit that answers anything unexpected to a data identifier (conditions not correct, a response that is still pending, any
 *   other refusal than "request out of range" or "security access denied") is not asked further. One that stops answering after it was found
 *   stops the whole scan.
 * - The battery voltage is read every [Config.voltageInterval] and logged.
 *
 * Not thread-safe; [run] once.
 *
 * @param link the adapter. Its requests must already be gated by a read-only policy; this class adds nothing to that.
 * @param plan what to sweep
 * @param events where what the scan does is written, and its result when it ends
 * @param whileMoving called while the car moves, with its speed in km/h (null when unknown). It must return when it has waited a while (it
 *   is called again as long as the car moves). The default only waits a second.
 */
public class FullScan(
    private val link: DiagnosticLink,
    private val plan: DidSweepPlan,
    private val events: SessionEventSink = SessionEventSink.None,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val config: Config = Config(),
    private val whileMoving: suspend (speedKmh: Int?) -> Unit = { delay(DEFAULT_MOVING_WAIT) },
    /** What an earlier, unfinished scan of this car found: its finished stages are not run again and its results are carried on. */
    private val resume: ScanResult? = null,
) {
    /**
     * @property requestsPerSecond the pace of every request the scan sends, the probe's included; null takes the plan's. Never above
     *   [DidSweepPlan.MAX_REQUESTS_PER_SECOND].
     * @property speedRequestId the control unit asked for the vehicle speed (the engine control unit's OBD address)
     * @property speedCheckEvery most requests between two readings of the speed
     * @property speedCheckInterval longest time between two readings of the speed
     * @property voltageInterval time between two readings of the battery voltage
     * @property multiFrameSettle how long to wait before asking again for a reply that was cut to its first frame: the control unit is still
     *   waiting for a flow-control frame that never came, and a new request in that moment may be ignored
     * @property silenceBeforePing consecutive unanswered requests to a control unit after which it is asked whether it is still there
     * @property silentRunLimit consecutive unanswered requests after which the rest of a range is skipped: a control unit that ignores
     *   identifiers it does not have would otherwise cost a timeout for each of them
     * @property conditionsRunLimit consecutive identifiers answered "conditions not correct" after which a control unit is not asked any more
     *   (one such answer only skips that identifier)
     * @property isLinkLoss tells an error that means the link to the adapter is gone (the scan then ends as [ScanOutcome.LINK_LOST] and can be resumed)
     */
    public data class Config(
        val requestsPerSecond: Double? = null,
        val speedRequestId: Int = 0x7E0,
        val speedCheckEvery: Int = 40,
        val speedCheckInterval: Duration = 2.seconds,
        val voltageInterval: Duration = 30.seconds,
        val multiFrameSettle: Duration = 500.milliseconds,
        val silenceBeforePing: Int = 8,
        val silentRunLimit: Int = 48,
        val conditionsRunLimit: Int = 5,
        val isLinkLoss: (Throwable) -> Boolean = { false },
    ) {
        init {
            require(requestsPerSecond == null || requestsPerSecond > 0.0) { "requestsPerSecond must be positive" }
            require(speedCheckEvery >= 1) { "speedCheckEvery must be at least 1" }
            require(conditionsRunLimit >= 1) { "conditionsRunLimit must be at least 1" }
            require(silenceBeforePing >= 1 && silentRunLimit >= silenceBeforePing) { "silenceBeforePing must be at least 1 and no more than silentRunLimit" }
        }
    }

    private val minInterval: Duration =
        (MILLIS_PER_SECOND / minOf(config.requestsPerSecond ?: plan.requestsPerSecond, DidSweepPlan.MAX_REQUESTS_PER_SECOND)).milliseconds

    private val _progress = MutableStateFlow(ScanProgress())

    /** What the scan is doing now. */
    public val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    private val _result = MutableStateFlow<ScanResult?>(null)

    /** The result: null until the scan has ended, whichever way. */
    public val result: StateFlow<ScanResult?> = _result.asStateFlow()

    // What was found ----------------------------------------------------------------------------------------

    private class Found(val requestId: Int, val responseId: Int, val foundBy: String) {
        var name: String? = null
        val identity = ArrayList<Identifier>()
        var stored: List<String>? = null
        var pending: List<String>? = null
        var permanent: List<String>? = null
        var uds: UdsFaults? = null
        var note: String? = null
        var silentRun = 0

        /**
         * What to ask to find out whether the unit is still there: something it has answered before, because a unit may stay silent on a
         * request it does not know. The probe's own request for a control unit that speaks OBD, the identification request that found any
         * other; the first data identifier it answers replaces either.
         */
        var ping: ByteArray = byteArrayOf(SERVICE_LIVE_DATA.toByte(), 0x00)
        var pingFromData = false
        val label: String get() = requestId.toString(16).uppercase().padStart(3, '0')
    }

    private val units = LinkedHashMap<Int, Found>()
    private val readiness = ArrayList<EcuReadiness>()
    private val sweeps = ArrayList<SweepResult>()
    private val battery = ArrayList<BatteryReading>()
    private val warnings = ArrayList<String>()
    private val stagesDone = ArrayList<String>()
    private var probeEcus: List<EcuReport> = emptyList()

    // The adapter's state, as far as the scan has changed it ----------------------------------------------------
    private var wideReceive = false
    private var receiveUnavailable = false
    private var customFlowControlFor: Int? = null
    private var flowControlUnavailable = false

    // Pacing, speed and voltage -----------------------------------------------------------------------------
    private var started = false
    private lateinit var begin: TimeMark
    private var lastRequest: TimeMark? = null
    private var sinceSpeedCheck = 0
    private lateinit var speedMark: TimeMark
    private lateinit var voltageMark: TimeMark
    private var pausedMs = 0L
    private var failures = 0
    private var priorDurationMs = 0L

    /**
     * Runs the scan to its end and returns its result (also in [result]). A scan that is stopped by a safety rule ends with
     * [ScanOutcome.STOPPED]; one that is cancelled or whose link fails publishes its partial result and then throws.
     *
     * @throws ScanRefusedException when the car's speed cannot be read
     */
    public suspend fun run(): ScanResult {
        check(!started) { "A scan runs once" }
        started = true
        restore()
        begin = timeSource.markNow()
        speedMark = begin
        voltageMark = begin
        _progress.value = ScanProgress(running = true)
        var outcome = ScanOutcome.COMPLETED
        var detail: String? = null
        var failure: Throwable? = null
        try {
            note("starts, parked read-only scan")
            requireSpeedReadable()
            ensureParked()
            readVoltage()
            setupReceive()
            stage(ScanStage.PROBE) { probe() }
            stage(ScanStage.READINESS) { readReadiness() }
            stage(ScanStage.DISCOVERY) { discover() }
            stage(ScanStage.IDENTIFICATION) { identify() }
            stage(ScanStage.FAULTS) { readFaults() }
            stage(ScanStage.SWEEP) { sweep() }
        } catch (e: ScanStopped) {
            outcome = ScanOutcome.STOPPED
            detail = e.message
        } catch (e: ScanRefusedException) {
            _progress.value = ScanProgress()
            withContext(NonCancellable) { restoreAdapter() }
            throw e
        } catch (e: Throwable) {
            val lost = e !is CancellationException && config.isLinkLoss(e)
            outcome = if (lost) ScanOutcome.LINK_LOST else ScanOutcome.CANCELLED
            detail = when {
                lost -> "link lost"
                e is CancellationException -> "cancelled"
                else -> "stopped by an error: ${e.message ?: e::class.simpleName}"
            }
            failure = e
        }
        withContext(NonCancellable) {
            restoreAdapter()
            val finished = snapshot(outcome, detail)
            _result.value = finished
            _progress.value = ScanProgress()
            note("ended: ${outcome.name.lowercase()}${detail?.let { " ($it)" }.orEmpty()}")
            try {
                events.emit(SessionEvents.SCAN_RESULT, finished.toJson())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A broken log must not hide the result from the caller.
            }
        }
        failure?.let { throw it }
        return checkNotNull(_result.value)
    }

    private suspend fun stage(stage: ScanStage, body: suspend () -> Unit) {
        if (stage.name.lowercase() in stagesDone) {
            note("stage ${stage.ordinal + 1} of ${ScanStage.entries.size}: ${stage.name.lowercase()} was done before, not run again")
            return
        }
        _progress.value = ScanProgress(stage = stage, running = true)
        note("stage ${stage.ordinal + 1} of ${ScanStage.entries.size}: ${stage.name.lowercase()}")
        body()
        stagesDone += stage.name.lowercase()
    }

    private fun snapshot(outcome: ScanOutcome, detail: String?): ScanResult = ScanResult(
        outcome = outcome,
        detail = detail,
        durationMs = priorDurationMs + begin.elapsedNow().inWholeMilliseconds,
        pausedMs = pausedMs,
        stagesDone = stagesDone.toList(),
        ecus = units.values.sortedBy { it.requestId }.map { unit ->
            ScanEcu(
                requestId = unit.label,
                responseId = unit.responseId.toString(16).uppercase().padStart(3, '0'),
                foundBy = unit.foundBy,
                name = unit.name,
                identity = unit.identity.toList(),
                storedCodes = unit.stored,
                pendingCodes = unit.pending,
                permanentCodes = unit.permanent,
                udsFaults = unit.uds,
                note = unit.note,
            )
        },
        readiness = readiness.toList(),
        sweeps = sweeps.toList(),
        battery = battery.toList(),
        warnings = warnings.toList(),
        probeEcus = probeEcus.map { it.copy(vin = it.vin?.let(::maskVin)) },
    )

    /** Carries on from [resume]: what an earlier scan found is taken over, and its finished stages are skipped. */
    private suspend fun restore() {
        val earlier = resume ?: return
        stagesDone += earlier.stagesDone
        probeEcus = earlier.probeEcus
        for (ecu in earlier.ecus) {
            val unit = Found(ecu.requestId.toInt(16), ecu.responseId.toInt(16), ecu.foundBy)
            unit.name = ecu.name
            unit.identity += ecu.identity
            unit.stored = ecu.storedCodes
            unit.pending = ecu.pendingCodes
            unit.permanent = ecu.permanentCodes
            unit.uds = ecu.udsFaults
            unit.note = ecu.note
            if (ecu.foundBy == "discovery") unit.ping = identification(DID_VIN)
            units[unit.responseId] = unit
        }
        readiness += earlier.readiness
        sweeps += earlier.sweeps
        battery += earlier.battery
        warnings += earlier.warnings
        pausedMs = earlier.pausedMs
        priorDurationMs = earlier.durationMs
        note("resumes an earlier scan (${earlier.outcome.name.lowercase()}); stages done: ${earlier.stagesDone.joinToString(", ").ifEmpty { "none" }}")
    }

    // Stage 1: the standard probe ---------------------------------------------------------------------------

    private suspend fun probe() {
        val report: CapabilityReport = CapabilityProbe(PacedLink(), timeSource).run { step ->
            _progress.value = ScanProgress(stage = ScanStage.PROBE, subject = step.name.lowercase().replace('_', ' '), running = true)
        }
        if (report.ecus.isEmpty()) throw ScanStopped("no control unit answered the standard probe")
        probeEcus = report.ecus
        for (ecu in report.ecus) {
            val unit = Found(ecu.requestCanId, ecu.responseCanId, "probe")
            unit.name = ecu.name
            ecu.vin?.let { unit.identity += Identifier("0902", "VIN (OBD)", null, maskVin(it)) }
            if (ecu.calibrationIds.isNotEmpty()) unit.identity += Identifier("0904", "calibration ids (OBD)", null, ecu.calibrationIds.joinToString(", "))
            units[ecu.responseCanId] = unit
        }
        warnings += report.warnings.map { "probe: $it" }
        note("probe found ${report.ecus.size} control units: ${report.ecus.joinToString(" ") { it.requestId }}")
    }

    /** The probe's requests go through the scan's pace. */
    private inner class PacedLink : DiagnosticLink {
        override suspend fun command(text: String): List<String> = link.command(text)

        override suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int?): ElmResponse {
            pace()
            return link.request(requestId, payload, expectedResponses)
        }
    }

    // Stage 2: readiness and on-board tests -----------------------------------------------------------------

    private suspend fun readReadiness() {
        for ((index, ecu) in probeEcus.withIndex()) {
            val unit = units.getValue(ecu.responseCanId)
            _progress.value = ScanProgress(ScanStage.READINESS, unit.label, index, probeEcus.size, running = true)
            val since = if (ecu.supports(PID_MONITOR_STATUS)) monitorStatus(unit, PID_MONITOR_STATUS, withLamp = true) else null
            val cycle = if (ecu.supports(PID_MONITOR_STATUS_CYCLE)) monitorStatus(unit, PID_MONITOR_STATUS_CYCLE, withLamp = false) else null
            val (mids, results) = onBoardTests(unit)
            readiness += EcuReadiness(unit.label, since, cycle, mids.map { it.toString(16).uppercase().padStart(2, '0') }, results)
        }
    }

    private suspend fun monitorStatus(unit: Found, pid: Int, withLamp: Boolean): MonitorStatus? {
        val answer = askService(unit, byteArrayOf(SERVICE_LIVE_DATA.toByte(), pid.toByte()), SERVICE_LIVE_DATA, count = 1) as? Answer.Positive
        val data = answer?.data?.takeIf { it.size >= 5 && (it[0].toInt() and 0xFF) == pid } ?: return null
        return MonitorStatus.decode(data.copyOfRange(1, data.size), withLamp)
    }

    /** The monitor ids a unit supports (service 06) and the test results of each; empty when it does not do service 06. */
    private suspend fun onBoardTests(unit: Found): Pair<List<Int>, List<MidResult>> {
        val mids = ArrayList<Int>()
        var base = 0
        while (true) {
            val answer = askService(unit, byteArrayOf(SERVICE_TEST_RESULTS.toByte(), base.toByte()), SERVICE_TEST_RESULTS, count = 1) as? Answer.Positive
            val data = answer?.data?.takeIf { it.size >= 5 && (it[0].toInt() and 0xFF) == base } ?: break
            val (found, next) = Mode06Decoder.supported(base, data.copyOfRange(1, 5))
            mids += found
            if (!next || base >= LAST_MID_RANGE) break
            base += MIDS_PER_RANGE
        }
        val results = ArrayList<MidResult>()
        for (mid in mids) {
            // The reply holds nine bytes per test, so it is long: no response count.
            val answer =
                askService(unit, byteArrayOf(SERVICE_TEST_RESULTS.toByte(), mid.toByte()), SERVICE_TEST_RESULTS, count = null) as? Answer.Positive ?: continue
            results += Mode06Decoder.results(answer.data)
        }
        return mids to results
    }

    // Stage 3: which request identifiers are answered -------------------------------------------------------

    private suspend fun discover() {
        // 7E8-7EF are the engine-bay units' answers: nothing listens there, and the adapter's read-only policy does not let a request go to them.
        val ids = plan.controlUnitIds.ids().filter { it !in OBD_RESPONSE_IDS }
        val unitsBefore = units.size
        var alias = 0
        for ((index, id) in ids.withIndex()) {
            _progress.value = ScanProgress(ScanStage.DISCOVERY, null, index, ids.size, running = true)
            // Known units are not asked again, and neither are the identifiers they answer from: nothing listens there.
            if (units.values.any { it.requestId == id || it.responseId == id }) continue
            gate()
            val first = send(id, identification(DID_VIN), count = 1) ?: continue
            if (!answered(first)) continue
            if (first.messages.none { it is DiagnosticResponse.Positive }) {
                // Refused, or cut to its first frame: the control unit is there. A unit that refuses the VIN may give its part number.
                send(id, identification(DID_SPARE_PART_NUMBER), count = 1)
            }
            val responder = first.respondingIds.firstOrNull() ?: first.messages.firstOrNull()?.sourceId ?: continue
            if (units.containsKey(responder)) {
                alias++
                continue
            }
            units[responder] = Found(id, responder, "discovery").also { it.ping = identification(DID_VIN) }
            note("a control unit answers requests to ${id.toString(16).uppercase()} from ${responder.toString(16).uppercase()}")
        }
        if (alias > 0) warnings += "$alias request identifiers were answered by a control unit that was already listed; they were not listed again"
        note(
            "discovery found ${units.size - unitsBefore} more control units; ${units.size} in all: ${units.values.sortedBy {
                it.requestId
            }.joinToString(" ") { it.label }}",
        )
    }

    // Stage 4: identification -------------------------------------------------------------------------------

    private suspend fun identify() {
        val sorted = units.values.sortedBy { it.requestId }
        for ((index, unit) in sorted.withIndex()) {
            val dids = (IDENTIFICATION_FIRST..IDENTIFICATION_LAST).toList()
            // A resumed scan may have read some of them already.
            unit.identity.removeAll { it.did.toInt(16) in IDENTIFICATION_FIRST..IDENTIFICATION_LAST }
            val answers = ArrayList<DidAnswer>()
            val stats = Stats()
            val ended = readDids(unit, dids, ScanStage.IDENTIFICATION, unit.label, index * dids.size, sorted.size * dids.size, stats) { did, bytes ->
                answers += DidAnswer(did.hex(), bytes.toHex())
            }
            for (answer in answers) unit.identity += identifier(answer)
            unit.name = unit.name ?: answers.firstOrNull { it.did == "F197" }?.let { printable(it.hex) }
            if (ended is RangeEnd.Stop) unit.note = ended.reason
            note("identification of ${unit.label}: ${answers.size} identifiers" + (ended as? RangeEnd.Stop)?.let { " (${it.reason})" }.orEmpty())
        }
    }

    private fun identifier(answer: DidAnswer): Identifier {
        val did = answer.did.toInt(16)
        val label = IDENTIFIER_NAMES[did]
        if (did == DID_VIN) {
            // The VIN never leaves this function in the clear.
            val vin = printable(answer.hex)
            return Identifier(answer.did, label, null, vin?.let(::maskVin))
        }
        return Identifier(answer.did, label, answer.hex, printable(answer.hex))
    }

    private fun printable(hex: String): String? {
        val bytes = hex.chunked(2).map { it.toInt(16) }
        val trimmed = bytes.dropLastWhile { it == 0 || it == 0x20 }
        if (trimmed.isEmpty() || trimmed.any { it !in PRINTABLE }) return null
        return trimmed.map { it.toChar() }.joinToString("")
    }

    // Stage 5: fault codes ----------------------------------------------------------------------------------

    private suspend fun readFaults() {
        val sorted = units.values.sortedBy { it.requestId }
        val obdIds = probeEcus.map { it.responseCanId }.toSet()
        for ((index, unit) in sorted.withIndex()) {
            _progress.value = ScanProgress(ScanStage.FAULTS, unit.label, index, sorted.size, running = true)
            if (unit.responseId in obdIds) {
                unit.stored = obdCodes(unit, SERVICE_STORED_CODES)
                unit.pending = obdCodes(unit, SERVICE_PENDING_CODES)
                unit.permanent = obdCodes(unit, SERVICE_PERMANENT_CODES)
            }
            when (
                val answer =
                    askService(
                        unit,
                        byteArrayOf(SERVICE_READ_DTC.toByte(), SUB_BY_STATUS_MASK.toByte(), STATUS_MASK_ALL.toByte()),
                        SERVICE_READ_DTC,
                        count = null,
                    )
            ) {
                is Answer.Positive -> {
                    // data: 02 <availability mask> then four bytes per code.
                    val codes = DtcDecoder.uds(answer.data.copyOfRange(1.coerceAtMost(answer.data.size), answer.data.size))
                    unit.uds = DtcDecoder.classify(codes)
                }
                is Answer.Negative -> if (answer.code != NRC_SERVICE_NOT_SUPPORTED &&
                    answer.code != NRC_SUB_FUNCTION_NOT_SUPPORTED &&
                    answer.code != NRC_OUT_OF_RANGE
                ) {
                    unit.note = (unit.note?.plus("; ").orEmpty()) + "fault codes: answered ${nrcText(answer.code)}"
                }
                else -> Unit
            }
            val faults = unit.uds
            note(
                "faults of ${unit.label}: " + if (faults == null) {
                    "none read"
                } else {
                    "${faults.confirmed.size} confirmed, ${faults.pending.size} pending, ${faults.warningLamp.size} lamp, ${faults.notCompletedOnly} test not completed (not faults)"
                },
            )
        }
    }

    private suspend fun obdCodes(unit: Found, service: Int): List<String>? {
        val answer = askService(unit, byteArrayOf(service.toByte()), service, count = null) as? Answer.Positive ?: return null
        return DtcDecoder.obd(answer.data)
    }

    // Stage 6: the manufacturer data identifiers -------------------------------------------------------------

    private suspend fun sweep() {
        for (target in plan.targets) {
            if (sweeps.any { it.requestId.equals(target.requestId, ignoreCase = true) }) continue
            val unit = units.values.firstOrNull { it.requestId == target.requestCanId }
            val chunks = plan.chunksFor(target)
            val planned = chunks.sumOf { it.size }
            if (unit == null) {
                warnings += "${target.label} (${target.requestId}) did not answer, so its identifiers were not swept"
                sweeps += SweepResult(target.requestId, target.label, planned, 0, stoppedBecause = "the control unit was not found")
                continue
            }
            val stats = Stats()
            val positives = ArrayList<DidAnswer>()
            var stoppedBecause: String? = null
            var done = 0
            for (chunk in chunks) {
                val ended = readDids(unit, chunk, ScanStage.SWEEP, target.label, done, planned, stats) { did, bytes ->
                    positives += DidAnswer(did.hex(), bytes.toHex(), label = mirrorLabel(did))
                }
                done += chunk.size
                if (ended is RangeEnd.Stop) {
                    stoppedBecause = ended.reason
                    break
                }
                if (ended is RangeEnd.SkippedSilent) warnings += "${target.label}: ${ended.reason}"
            }
            sweeps += SweepResult(
                requestId = target.requestId,
                label = target.label,
                planned = planned,
                asked = stats.asked,
                positive = positives.toList(),
                refused = stats.refused,
                protectedDids = stats.protectedDids.toList(),
                silent = stats.silent,
                stoppedBecause = stoppedBecause,
            )
            note("sweep of ${target.label}: ${positives.size} identifiers answered of ${stats.asked} asked" + stoppedBecause?.let { " ($it)" }.orEmpty())
        }
    }

    // Reading data identifiers: one range of one control unit ---------------------------------------------

    private class Stats {
        var asked = 0
        var refused = 0
        var silent = 0
        val protectedDids = ArrayList<String>()
    }

    private sealed interface RangeEnd {
        data object Completed : RangeEnd

        /** The control unit ignores what it does not have; the rest of the range was left out. */
        class SkippedSilent(val reason: String) : RangeEnd

        /** The control unit answered something unexpected; it is not asked any more. */
        class Stop(val reason: String) : RangeEnd
    }

    /**
     * Asks [unit] for each of [dids], one at a time, and passes the positive answers to [onPositive]. A refusal of "request out of range" is the
     * ordinary "not here". Ends early, without an error, when the control unit answers anything unexpected or goes silent for [Config.silentRunLimit]
     * requests while still alive; throws [ScanStopped] when it is no longer there.
     */
    private suspend fun readDids(
        unit: Found,
        dids: List<Int>,
        stage: ScanStage,
        subject: String,
        doneBefore: Int,
        total: Int,
        stats: Stats,
        onPositive: (Int, ByteArray) -> Unit,
    ): RangeEnd {
        var silentRun = 0
        var conditionsRun = 0
        for ((index, did) in dids.withIndex()) {
            _progress.value = ScanProgress(stage, subject, doneBefore + index, total, running = true)
            // Only a quarter-second between progress updates would be enough, but the flow conflates anyway.
            stats.asked++
            when (val answer = askDid(unit, did)) {
                is Answer.Positive -> {
                    silentRun = 0
                    conditionsRun = 0
                    unit.silentRun = 0
                    rememberPing(unit, did)
                    onPositive(did, answer.data)
                }
                is Answer.Partial -> {
                    silentRun = 0
                    conditionsRun = 0
                    unit.silentRun = 0
                    // The reply is longer than one frame and was cut after the first: wait for the control unit to give up on it, then ask for it whole.
                    delay(config.multiFrameSettle)
                    val whole = askDid(unit, did, count = null)
                    if (whole is Answer.Positive) rememberPing(unit, did)
                    onPositive(did, (whole as? Answer.Positive)?.data ?: ByteArray(0))
                }
                is Answer.Negative -> {
                    silentRun = 0
                    unit.silentRun = 0
                    if (answer.code != NRC_CONDITIONS_NOT_CORRECT) conditionsRun = 0
                    when (answer.code) {
                        NRC_OUT_OF_RANGE -> stats.refused++
                        NRC_CONDITIONS_NOT_CORRECT -> {
                            // One refusal for the unit's present state is no reason to give up on it: only this identifier is skipped.
                            stats.refused++
                            if (++conditionsRun >= config.conditionsRunLimit) {
                                return RangeEnd.Stop(
                                    "answered ${nrcText(answer.code)} to $conditionsRun identifiers in a row, last ${did.hex()}; not asked any further",
                                )
                            }
                        }
                        NRC_SECURITY_ACCESS_DENIED -> stats.protectedDids += did.hex()
                        NRC_SERVICE_NOT_SUPPORTED -> return RangeEnd.Stop("does not support service 22")
                        else -> return RangeEnd.Stop("answered ${nrcText(answer.code)} to ${did.hex()}; not asked any further")
                    }
                }
                Answer.Silent -> {
                    stats.silent++
                    silentRun++
                    unit.silentRun++
                    if (unit.silentRun % config.silenceBeforePing == 0) ensureAlive(unit)
                    if (silentRun >= config.silentRunLimit) {
                        return RangeEnd.SkippedSilent("no answer to $silentRun identifiers in a row, from ${did.hex()} the rest of the range was skipped")
                    }
                }
            }
        }
        return RangeEnd.Completed
    }

    private fun rememberPing(unit: Found, did: Int) {
        if (unit.pingFromData) return
        unit.ping = identification(did)
        unit.pingFromData = true
    }

    /** Asks whether [unit] still answers; ends the scan when it does not. */
    private suspend fun ensureAlive(unit: Found) {
        val response = send(unit.requestId, unit.ping, count = 1)
        if (response != null && answeredBy(response, unit.responseId)) {
            unit.silentRun = 0
            return
        }
        throw ScanStopped("the control unit ${unit.label} stopped answering")
    }

    // Requests ----------------------------------------------------------------------------------------------

    private sealed interface Answer {
        class Positive(val data: ByteArray) : Answer

        class Negative(val code: Int) : Answer

        /** Frames arrived but no whole reply: the first frame of a long reply that the adapter stopped listening to. */
        data object Partial : Answer

        data object Silent : Answer
    }

    private fun identification(did: Int): ByteArray = byteArrayOf(SERVICE_READ_DATA.toByte(), (did shr 8).toByte(), did.toByte())

    /** A data identifier F4xx repeats Mode 01 PID xx (the same data under UDS); the label says so, and it is not polled again as a discovery item. */
    private fun mirrorLabel(did: Int): String? =
        if (did in MODE01_MIRROR_FIRST..MODE01_MIRROR_LAST) "mirrors Mode 01 PID ${(did and 0xFF).toString(16).uppercase().padStart(2, '0')}" else null

    private fun Int.hex(): String = toString(16).uppercase().padStart(4, '0')

    /** Service 22 for one identifier of [unit]; [count] 1 is the adapter's "one frame is enough", which a short answer or a refusal needs. */
    private suspend fun askDid(unit: Found, did: Int, count: Int? = 1): Answer {
        gate()
        selectFlowControl(unit.requestId)
        val response = send(unit.requestId, identification(did), count)
        return didAnswer(response, unit.responseId, did)
    }

    /** A request of any other service to [unit]: [service] is the one the reply must be for. */
    private suspend fun askService(unit: Found, payload: ByteArray, service: Int, count: Int?): Answer {
        gate()
        selectFlowControl(unit.requestId)
        val answer = serviceAnswer(send(unit.requestId, payload, count), unit.responseId, service)
        if (answer is Answer.Silent) {
            unit.silentRun++
            if (unit.silentRun % config.silenceBeforePing == 0) ensureAlive(unit)
        } else {
            unit.silentRun = 0
        }
        return answer
    }

    private fun didAnswer(response: ElmResponse?, responseId: Int, did: Int): Answer {
        if (response == null) return Answer.Silent
        val own = response.messages.filter { it.sourceId == responseId }
        own.filterIsInstance<DiagnosticResponse.Positive>().firstOrNull {
            it.service == SERVICE_READ_DATA && it.data.size >= 2 && ((it.data[0].toInt() and 0xFF) shl 8 or (it.data[1].toInt() and 0xFF)) == did
        }?.let { return Answer.Positive(it.data.copyOfRange(2, it.data.size)) }
        own.filterIsInstance<DiagnosticResponse.Negative>().firstOrNull { it.service == SERVICE_READ_DATA }?.let { return Answer.Negative(it.code) }
        return if (responseId in response.respondingIds) Answer.Partial else Answer.Silent
    }

    private fun serviceAnswer(response: ElmResponse?, responseId: Int, service: Int): Answer {
        if (response == null) return Answer.Silent
        val own = response.messages.filter { it.sourceId == responseId }
        own.filterIsInstance<DiagnosticResponse.Positive>().firstOrNull { it.service == service }?.let { return Answer.Positive(it.data) }
        own.filterIsInstance<DiagnosticResponse.Negative>().firstOrNull { it.service == service }?.let { return Answer.Negative(it.code) }
        return if (responseId in response.respondingIds) Answer.Partial else Answer.Silent
    }

    private fun answered(response: ElmResponse): Boolean = response.messages.isNotEmpty() || response.respondingIds.isNotEmpty()

    private fun answeredBy(response: ElmResponse, responseId: Int): Boolean =
        response.messages.any { it.sourceId == responseId } || responseId in response.respondingIds

    /**
     * One request, at the scan's pace. A request that fails (a timeout the adapter reports as an error) is no answer; three failures in a row
     * are not an adapter that is slow but one that is gone, and end the scan with the error.
     */
    private suspend fun send(requestId: Int, payload: ByteArray, count: Int?): ElmResponse? {
        pace()
        return try {
            link.request(requestId, payload, count).also { failures = 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A lost link is not a slow adapter: waiting for two more failures would only delay the ending.
            if (config.isLinkLoss(e) || ++failures >= MAX_FAILURES_IN_A_ROW) throw e
            null
        }
    }

    private suspend fun pace() {
        lastRequest?.let { last ->
            val wait = minInterval - last.elapsedNow()
            if (wait.isPositive()) delay(wait)
        }
        lastRequest = timeSource.markNow()
    }

    /** Before every request: the pause for a moving car, and the battery voltage when it is time. */
    private suspend fun gate() {
        if (++sinceSpeedCheck >= config.speedCheckEvery || speedMark.elapsedNow() >= config.speedCheckInterval) ensureParked()
        if (voltageMark.elapsedNow() >= config.voltageInterval) readVoltage()
    }

    // Speed -------------------------------------------------------------------------------------------------

    private suspend fun requireSpeedReadable() {
        if (readSpeed() == null) throw ScanRefusedException("The car's speed cannot be read, so the scan cannot tell that the car is parked")
    }

    /** The speed in km/h, asked up to [SPEED_TRIES] times; null when no answer came. */
    private suspend fun readSpeed(): Int? {
        // The reply is one frame, so flow control does not matter here and the settings made for another unit are left as they are.
        repeat(SPEED_TRIES) {
            val response = send(config.speedRequestId, byteArrayOf(SERVICE_LIVE_DATA.toByte(), PID_SPEED.toByte()), count = 1)
            val positive = response?.messages?.filterIsInstance<DiagnosticResponse.Positive>()
                ?.firstOrNull { it.service == SERVICE_LIVE_DATA && it.data.size >= 2 && (it.data[0].toInt() and 0xFF) == PID_SPEED }
            if (positive != null) return positive.data[1].toInt() and 0xFF
        }
        return null
    }

    /** Returns when the car is parked; while it moves the adapter is restored and [whileMoving] is called. */
    private suspend fun ensureParked() {
        var pausedAt: TimeMark? = null
        while (true) {
            val speed = readSpeed() ?: throw ScanStopped("the car's speed can no longer be read, so the scan cannot tell that it is parked")
            if (speed == 0) break
            if (pausedAt == null) {
                pausedAt = timeSource.markNow()
                note("paused: the car is moving ($speed km/h)")
                // Polling takes the adapter over: it must get it back as the init script left it.
                restoreAdapter()
            }
            _progress.value = _progress.value.copy(paused = true, speedKmh = speed)
            whileMoving(speed)
        }
        if (pausedAt != null) {
            pausedMs += pausedAt.elapsedNow().inWholeMilliseconds
            note("goes on: the car is parked again")
            _progress.value = _progress.value.copy(paused = false, speedKmh = null)
            setupReceive()
        }
        sinceSpeedCheck = 0
        speedMark = timeSource.markNow()
    }

    private suspend fun readVoltage() {
        voltageMark = timeSource.markNow()
        val text = try {
            link.command("ATRV").firstOrNull { it.isNotBlank() && it.trim() != "?" }?.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        battery += BatteryReading(begin.elapsedNow().inWholeMilliseconds, text)
        note("battery $text")
    }

    // The adapter's receive and flow-control settings -----------------------------------------------------

    /** Listen to every 7xx identifier, so a control unit that answers from an identifier that is not 7E8..7EF is heard. */
    private suspend fun setupReceive() {
        if (wideReceive || receiveUnavailable) return
        if (adapterCommand("ATCM$WIDE_MASK") && adapterCommand("ATCF$WIDE_MASK")) {
            wideReceive = true
        } else {
            receiveUnavailable = true
            warnings += "the adapter does not take ATCF/ATCM, so only control units that answer from 7E8..7EF can be scanned"
            adapterCommand("ATCM$STANDARD_MASK")
            adapterCommand("ATCF$STANDARD_FILTER")
        }
    }

    /** The adapter's flow control follows the request for the OBD units; for the others it is told where to send and what (clear to send). */
    private suspend fun selectFlowControl(requestId: Int) {
        if (flowControlUnavailable) return
        if (requestId in OBD_REQUEST_IDS) {
            if (customFlowControlFor != null && adapterCommand("ATFCSM0")) customFlowControlFor = null
        } else if (customFlowControlFor != requestId) {
            val set = adapterCommand("ATFCSH${requestId.toString(16).uppercase().padStart(3, '0')}") &&
                adapterCommand("ATFCSD$CLEAR_TO_SEND") &&
                adapterCommand("ATFCSM1")
            if (set) {
                customFlowControlFor = requestId
            } else {
                // Automatic flow control may still work: give up on setting it, and back to it.
                flowControlUnavailable = true
                warnings += "the adapter does not take ATFCSH/ATFCSD/ATFCSM; flow control is left to the adapter"
                adapterCommand("ATFCSM0")
                customFlowControlFor = null
            }
        }
    }

    /** Puts the adapter back to what an init script leaves: the ordinary OBD receive filter and automatic flow control. Errors are not fatal. */
    private suspend fun restoreAdapter() {
        if (wideReceive) {
            adapterCommand("ATCM$STANDARD_MASK")
            adapterCommand("ATCF$STANDARD_FILTER")
            wideReceive = false
        }
        if (customFlowControlFor != null) {
            adapterCommand("ATFCSM0")
            customFlowControlFor = null
        }
    }

    /** True when the adapter said OK. */
    private suspend fun adapterCommand(text: String): Boolean = try {
        link.command(text).any { it.trim().equals("OK", ignoreCase = true) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun note(detail: String) {
        try {
            events.emit(SessionEvents.SCAN, detail)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A broken log must not end a scan.
        }
    }

    private fun nrcText(code: Int): String = "negative response ${code.toString(16).uppercase().padStart(2, '0')}"

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        val DEFAULT_MOVING_WAIT: Duration = 1.seconds
        const val SPEED_TRIES = 3
        const val MAX_FAILURES_IN_A_ROW = 3

        const val SERVICE_LIVE_DATA = 0x01
        const val SERVICE_STORED_CODES = 0x03
        const val SERVICE_TEST_RESULTS = 0x06
        const val SERVICE_PENDING_CODES = 0x07
        const val SERVICE_PERMANENT_CODES = 0x0A
        const val SERVICE_READ_DATA = 0x22
        const val SERVICE_READ_DTC = 0x19
        const val SUB_BY_STATUS_MASK = 0x02
        const val STATUS_MASK_ALL = 0xFF
        const val PID_SPEED = 0x0D
        const val PID_MONITOR_STATUS = 0x01
        const val PID_MONITOR_STATUS_CYCLE = 0x41
        const val MIDS_PER_RANGE = 0x20
        const val LAST_MID_RANGE = 0xE0

        const val MODE01_MIRROR_FIRST = 0xF400
        const val MODE01_MIRROR_LAST = 0xF4FF

        const val DID_VIN = 0xF190
        const val DID_SPARE_PART_NUMBER = 0xF187
        const val IDENTIFICATION_FIRST = 0xF180
        const val IDENTIFICATION_LAST = 0xF1FF

        const val NRC_SERVICE_NOT_SUPPORTED = 0x11
        const val NRC_SUB_FUNCTION_NOT_SUPPORTED = 0x12
        const val NRC_CONDITIONS_NOT_CORRECT = 0x22
        const val NRC_OUT_OF_RANGE = 0x31
        const val NRC_SECURITY_ACCESS_DENIED = 0x33

        // The scan listens to every 7xx identifier while it runs (mask and filter 700), and the adapter's OBD default afterwards.
        const val WIDE_MASK = "700"
        const val STANDARD_MASK = "7F8"
        const val STANDARD_FILTER = "7E8"
        const val CLEAR_TO_SEND = "300000"

        val OBD_REQUEST_IDS = 0x7E0..0x7E7
        val OBD_RESPONSE_IDS = 0x7E8..0x7EF
        val PRINTABLE = 0x20..0x7E

        /** ISO 14229-1 annex C names of the identification identifiers. */
        val IDENTIFIER_NAMES: Map<Int, String> = mapOf(
            0xF180 to "boot software identification",
            0xF181 to "application software identification",
            0xF182 to "application data identification",
            0xF183 to "boot software fingerprint",
            0xF184 to "application software fingerprint",
            0xF185 to "application data fingerprint",
            0xF186 to "active diagnostic session",
            0xF187 to "spare part number",
            0xF188 to "ECU software number",
            0xF189 to "ECU software version",
            0xF18A to "system supplier",
            0xF18B to "ECU manufacturing date",
            0xF18C to "ECU serial number",
            0xF18D to "supported functional units",
            0xF18E to "kit assembly part number",
            0xF190 to "VIN",
            0xF191 to "ECU hardware number",
            0xF192 to "supplier ECU hardware number",
            0xF193 to "supplier ECU hardware version",
            0xF194 to "supplier ECU software number",
            0xF195 to "supplier ECU software version",
            0xF196 to "exhaust regulation or type approval number",
            0xF197 to "system name or engine type",
            0xF198 to "repair shop code or tester serial number",
            0xF199 to "programming date",
            0xF19D to "ECU installation date",
            0xF19E to "ODX file",
        )
    }
}
