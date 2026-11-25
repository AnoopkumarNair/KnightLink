package knightlink.core.runtime

import knightlink.core.adapter.AdapterDisconnectedException
import knightlink.core.adapter.AdapterException
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import knightlink.core.protocol.BusAddressing
import knightlink.core.protocol.DiagnosticLink
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import knightlink.core.scan.DidAnswer
import knightlink.core.scan.FaultReader
import knightlink.core.scan.FaultReport
import knightlink.core.scan.ScanEcu
import knightlink.core.scan.ScanOutcome
import knightlink.core.scan.ScanResult
import knightlink.core.scan.SweepResult
import knightlink.core.transport.TransportException
import knightlink.core.vehicle.CapabilityProbe
import knightlink.core.vehicle.EcuReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How much a session may ask of a car, from the standard services up. Every level only reads; none sends a service that changes anything.
 *
 * - L0: the standard probe, VIN, readiness and fault codes (services 01, 03, 07, 09, 0A).
 * - L1: the identification data identifiers (a subset of F180-F1A0) of the units that answered, and `19 02 FF` of them.
 * - L2: discovery of the units on request identifiers 700-7FF (`22 F190`, ten requests a second at most).
 * - L3: paced sweeps of data identifiers on the units that answered (the deep scan).
 * - L4: the one-time checks of a candidate file that a definition selects by the VIN's manufacturer code.
 * - L5: passive capture of the bus broadcast, and `10 01` to body units: only for a car the app has a definition for, or on the user's opt-in.
 */
public enum class LadderLevel { L0, L1, L2, L3, L4, L5 }

/**
 * The learning ladder's settings.
 *
 * @property deepScan whether L3 and L4 run (the user's switch); changeable while the session runs ([VehicleSession.setDeepScan])
 * @property passiveOptIn whether an unknown car may be captured passively (L5) and asked `10 01`; off unless the user asks
 * @property requestsPerSecond the pace of everything the ladder sends, never above 10
 * @property l1Box time box of level 1
 * @property l2Box time box of level 2
 * @property l3PerUnit time box of level 3 for one control unit
 * @property totalBox the most a ladder takes in all (the car stays parked meanwhile)
 * @property ranges the data identifiers swept at L3 when no plan names the unit
 * @property rangesFor the ranges a sweep plan names for a request identifier, or null for the default
 * @property silentRunLimit unanswered requests in a row in one range after which the rest of the range is skipped
 */
public data class LadderConfig(
    val deepScan: Boolean = true,
    val passiveOptIn: Boolean = false,
    val requestsPerSecond: Double = MAX_REQUESTS_PER_SECOND,
    val l1Box: Duration = 60.seconds,
    val l2Box: Duration = 90.seconds,
    val l3PerUnit: Duration = 3.minutes,
    val totalBox: Duration = 10.minutes,
    val ranges: List<IntRange> = DEFAULT_RANGES,
    val rangesFor: (Int) -> List<IntRange>? = { null },
    val silentRunLimit: Int = 20,
) {
    init {
        require(requestsPerSecond > 0.0 && requestsPerSecond <= MAX_REQUESTS_PER_SECOND) {
            "the ladder asks at most $MAX_REQUESTS_PER_SECOND requests a second"
        }
        require(silentRunLimit >= 1) { "silentRunLimit must be at least 1" }
    }

    public companion object {
        public const val MAX_REQUESTS_PER_SECOND: Double = 10.0

        /** The identification identifiers L1 asks: spare part number, supplier, serial number, VIN, hardware number, software version. */
        public val IDENTIFICATION_DIDS: List<Int> = listOf(0xF187, 0xF18A, 0xF18C, 0xF190, 0xF191, 0xF195)

        /** What L3 sweeps when nothing names a unit's ranges. */
        public val DEFAULT_RANGES: List<IntRange> = listOf(0xF100..0xF1FF, 0x0100..0x01FF, 0x2000..0x20FF, 0xDD00..0xDDFF)
    }
}

/** Where the learning ladder is, next to [SessionState] (see [VehicleSession.ladder]). The banner of the app shows it. */
public sealed interface LadderState {
    /** Nothing running, nothing to report. */
    public data object Idle : LadderState

    /** A level runs: [label] says what is asked, [done] of [total] requests, about [secondsLeft] seconds left of the level's time box. */
    public data class Running(val level: LadderLevel, val label: String, val done: Int, val total: Int, val secondsLeft: Int) : LadderState

    /** Every level that may run has run; [answers] data identifiers were answered. The car may be driven. */
    public data class Complete(val highest: LadderLevel, val answers: Int) : LadderState

    /** A rule stopped the ladder at [level] ([reason] in words): the car moves, the battery is low, the bus is in trouble ... The car may be driven. */
    public data class Stopped(val level: LadderLevel, val reason: String, val answers: Int) : LadderState

    /** A new fault code or the warning lamp appeared while the ladder ran: everything stopped, nothing more is asked ([reason] in words). */
    public data class Flagged(val reason: String) : LadderState
}

/** What a run of the ladder ended with. */
internal sealed interface LadderResult {
    class Complete(val highest: LadderLevel, val answers: Int, val scanResult: ScanResult? = null) : LadderResult

    class Stopped(val level: LadderLevel, val reason: String, val answers: Int, val scanResult: ScanResult? = null) : LadderResult

    class Flagged(val reason: String) : LadderResult

    class Skipped(val reason: String) : LadderResult
}

/**
 * Learns what an unknown car answers, level by level, strictly read-only (see [LadderLevel]). Runs once per connection, parked, before polling
 * starts. Every request goes through a [LadderGuard], which holds the hard lines and the abort rules; after every level the fault codes and the
 * warning lamp are read again, and a new code or a lit lamp ends everything.
 *
 * What the units answer is recorded in the log as identifiers and lengths, never values.
 *
 * @param link the adapter (raw: the guard wraps it)
 * @param units the units that answered the probe
 * @param baseline the fault codes read before the ladder started, or null to read them first
 * @param maxLevel the highest level this car may reach (see [LadderLevel])
 * @param deepScan asked before levels L3 and up, so the switch of the user works while the ladder runs
 * @param publish where progress goes
 */
internal class LearningLadder(
    private val link: DiagnosticLink,
    private val units: List<EcuReport>,
    private val baseline: FaultReport?,
    private val maxLevel: LadderLevel,
    private val config: LadderConfig,
    private val events: SessionEventSink,
    private val timeSource: TimeSource,
    private val deepScan: () -> Boolean,
    private val publish: (LadderState) -> Unit,
    private val faultConfig: FaultReader.Config = FaultReader.Config(),
    private val guardConfig: LadderGuard.Config = LadderGuard.Config(),
    private val canAsk: (LadderLevel) -> Boolean = { true },
    /** Asked before every request: true when the user skipped the ladder. */
    private val skip: () -> Boolean = { false },
    private val addressing: BusAddressing = BusAddressing.Can11Bit,
) {
    private class Target(val requestId: Int, val responseId: Int, val label: String, val fromProbe: Boolean = false) {
        var stopped = false
    }

    private val didAnswers = LinkedHashMap<Int, LinkedHashMap<String, DidAnswer>>()

    private val guard = LadderGuard(
        link,
        timeSource,
        guardConfig.copy(minInterval = (MILLIS / config.requestsPerSecond).toLong().milliseconds),
        stopWhen = {
            if (skip()) {
                "skipped by the user"
            } else if (total.elapsedNow() >= config.totalBox) {
                "the parked time box of ${config.totalBox} is used"
            } else {
                null
            }
        },
    )
    private val known = LinkedHashMap<Int, Target>()
    private var answers = 0
    private var level = LadderLevel.L0
    private var wideReceive = false
    private var customFlowControl: Int? = null
    private var total: TimeMark = timeSource.markNow()

    /** How many requests the ladder sent (the guard's count, the safety reads included). */
    val requestsSent: Int get() = guard.sent

    suspend fun run(): LadderResult {
        for (ecu in units) known[ecu.requestCanId] = Target(ecu.requestCanId, ecu.responseCanId, ecu.requestId, fromProbe = true)
        total = timeSource.markNow()
        var baselineFaults = baseline
        try {
            guard.begin()?.let { reason ->
                note("not run: $reason")
                return LadderResult.Skipped(reason)
            }
            if (!guard.voltageGuarded) note("the battery voltage could not be read at the start: the ladder runs without the voltage guard")
            if (baselineFaults == null) baselineFaults = readFaults()
            var levelAborts = 0
            for (next in listOf(LadderLevel.L1, LadderLevel.L2, LadderLevel.L3, LadderLevel.L4)) {
                if (next > maxLevel) break
                if (next >= LadderLevel.L2 && !deepScan()) {
                    note("${next.name} and above not run: the deep scan is off")
                    break
                }
                if (!canAsk(next)) {
                    note("${next.name} not run: not allowed for this car")
                    continue
                }
                if (total.elapsedNow() >= config.totalBox) {
                    note("stopped before ${next.name}: the parked time box of ${config.totalBox} is used")
                    return stopped(next, "the time box of ${config.totalBox} is used")
                }
                level = next
                guard.beginLevel()
                note("${next.name} starts")
                try {
                    runLevel(next)
                    note("${next.name} done: $answers answers so far")
                } catch (e: LevelAborted) {
                    note("${next.name} aborted (${e.scope.name.lowercase()}): ${e.reason}")
                    if (e.scope == AbortScope.LADDER) return stopped(next, e.reason)
                    levelAborts++
                    if (e.scope == AbortScope.LEVEL) {
                        if (levelAborts >= 2) return stopped(next, "${e.reason}; stopped twice")
                        if (e.reprobe && !probeAgain()) return stopped(next, "the bus does not answer after: ${e.reason}")
                    }
                }
                // The receive filter and flow control the level changed are put back before the fault codes are read: they read the engine-bay units.
                restoreAdapter()
                flagged(baselineFaults)?.let { reason ->
                    note("FLAGGED after ${next.name}: $reason; nothing more is asked")
                    return LadderResult.Flagged(reason)
                }
            }
            note("complete: ${level.name}, $answers answers, ${guard.sent} requests, ${total.elapsedNow().inWholeSeconds} s")
            return LadderResult.Complete(level, answers, buildScanResult(ScanOutcome.COMPLETED))
        } catch (e: LevelAborted) {
            // From outside a level (the first check, the fault reading): a ladder-wide rule.
            note("aborted at ${level.name}: ${e.reason}")
            return stopped(level, e.reason)
        } finally {
            withContext(NonCancellable) { restoreAdapter() }
        }
    }

    private suspend fun stopped(at: LadderLevel, reason: String): LadderResult {
        note("stopped at ${at.name}: $reason")
        return LadderResult.Stopped(at, reason, answers, buildScanResult(ScanOutcome.STOPPED, reason))
    }

    private fun buildScanResult(outcome: ScanOutcome, detail: String? = null): ScanResult {
        val scanEcus = known.values.map { target ->
            val probeEcu = units.firstOrNull { it.requestCanId == target.requestId }
            ScanEcu(
                requestId = target.label,
                responseId = addressing.formatId(target.responseId),
                foundBy = if (target.fromProbe) "probe" else "discovery",
                name = probeEcu?.name,
            )
        }
        val sweeps = didAnswers.entries.mapNotNull { (reqId, didMap) ->
            if (didMap.isEmpty()) return@mapNotNull null
            val label = known[reqId]?.label ?: addressing.formatId(reqId)
            SweepResult(
                requestId = label,
                label = label,
                planned = didMap.size,
                asked = didMap.size,
                positive = didMap.values.toList(),
            )
        }
        return ScanResult(
            outcome = outcome,
            detail = detail,
            durationMs = total.elapsedNow().inWholeMilliseconds,
            stagesDone = listOfNotNull(
                if (level >= LadderLevel.L1) "L1" else null,
                if (level >= LadderLevel.L2) "L2" else null,
                if (level >= LadderLevel.L3) "L3" else null,
            ),
            ecus = scanEcus,
            sweeps = sweeps,
            probeEcus = units.map { ecu ->
                ecu.copy(vin = ecu.vin?.let { v -> v.take(3) + "*".repeat((v.length - 3).coerceAtLeast(0)) })
            },
        )
    }

    private suspend fun runLevel(level: LadderLevel) {
        when (level) {
            LadderLevel.L1 -> identification()
            LadderLevel.L2 -> discovery()
            LadderLevel.L3 -> sweeps()
            LadderLevel.L4 -> note("L4: no candidate file is selected by this car's manufacturer code, nothing to check")
            else -> Unit
        }
    }

    // L1 -----------------------------------------------------------------------------------------------------

    private suspend fun identification() {
        val all = known.values.toList()
        val ids = LadderConfig.IDENTIFICATION_DIDS
        val box = timeSource.markNow()
        var done = 0
        for (unit in all) {
            for (did in ids) {
                if (box.elapsedNow() >= config.l1Box) {
                    note("L1: the time box of ${config.l1Box} is used")
                    return
                }
                progress(LadderLevel.L1, unit.label, done++, all.size * ids.size, config.l1Box - box.elapsedNow())
                try {
                    if (ask(unit, did) == Answer.Unsupported) {
                        note("L1 ${unit.label}: does not support service 22 here")
                        break
                    }
                } catch (e: LevelAborted) {
                    if (e.scope == AbortScope.UNIT) {
                        stopUnit(unit, e)
                        break
                    }
                    throw e
                }
            }
        }
    }

    // L2 -----------------------------------------------------------------------------------------------------

    private suspend fun discovery() {
        setupReceive()
        val ids = if (addressing.extendedIds) {
            (0x00..0xFF).map { BusAddressing.Can29Bit.physicalRequestId(it) }
        } else {
            (REQUEST_FIRST..REQUEST_LAST).filter { it != FUNCTIONAL && it !in OBD_RESPONSES }
        }
        val box = timeSource.markNow()
        var found = 0
        for ((index, id) in ids.withIndex()) {
            val label = addressing.formatId(id)
            if (box.elapsedNow() >= config.l2Box) {
                note("L2: the time box of ${config.l2Box} is used at $label")
                break
            }
            progress(LadderLevel.L2, label, index, ids.size, config.l2Box - box.elapsedNow())
            // A known unit is not asked again, and nothing is sent to an identifier a known unit answers from: nothing listens there.
            if (id in known || known.values.any { it.responseId == id }) continue
            // One frame is enough to know something answers, so flow control is not set up for an id that may not exist.
            val first = send(id, readDid(DID_VIN), 1) ?: continue
            val responder = first.respondingIds.firstOrNull() ?: first.messages.firstOrNull()?.sourceId ?: continue
            if (known.values.any { it.responseId == responder }) continue
            // The answer must be this request's: ask once more, whole, and take the unit only when its reply names F190 (or refuses service 22).
            selectFlowControl(id, responder)
            delay(MULTI_FRAME_SETTLE)
            val whole = send(id, readDid(DID_VIN), null) ?: continue
            val own = whole.messages.filter { it.sourceId == responder }
            val positive = own.filterIsInstance<DiagnosticResponse.Positive>().firstOrNull { it.service == SERVICE_READ_DATA && it.data.matches(DID_VIN) }
            val refused = own.filterIsInstance<DiagnosticResponse.Negative>().firstOrNull { it.service == SERVICE_READ_DATA }
            if (positive == null && refused == null) {
                note("L2: $label: a reply from ${addressing.formatId(responder)} was not the answer to F190 and is dropped")
                continue
            }
            known[id] = Target(id, responder, label)
            found++
            note(
                "L2: a unit answers $label from ${addressing.formatId(responder)}" +
                    (positive?.let { " (F190, ${it.data.size - 2} bytes)" } ?: " (refuses F190)"),
            )
            answers++
        }
        note("L2: $found more units found; ${known.size} in all: ${known.keys.sorted().joinToString(" ") { addressing.formatId(it) }}")
    }

    private fun ByteArray.matches(did: Int): Boolean =
        size >= 2 && (((this[0].toInt() and 0xFF) shl 8) or (this[1].toInt() and 0xFF)) == did

    // L3 -----------------------------------------------------------------------------------------------------

    private suspend fun sweeps() {
        setupReceive()
        val targets = known.values.filter { !it.stopped }
        val planned = targets.sumOf { unit -> rangesOf(unit).sumOf { it.last - it.first + 1 } }
        var done = 0
        for (unit in targets) {
            if (total.elapsedNow() >= config.totalBox) {
                note("L3: the parked time box of ${config.totalBox} is used")
                return
            }
            val box = timeSource.markNow()
            var positives = 0
            var asked = 0
            try {
                for (range in rangesOf(unit)) {
                    var silentRun = 0
                    var skipped = false
                    for (did in range) {
                        if (box.elapsedNow() >= config.l3PerUnit) {
                            note("L3 ${unit.label}: the time box of ${config.l3PerUnit} is used")
                            throw UnitDone
                        }
                        progress(LadderLevel.L3, unit.label, done++, planned, config.l3PerUnit - box.elapsedNow())
                        if (skipped) continue
                        asked++
                        when (ask(unit, did)) {
                            Answer.Positive -> {
                                positives++
                                silentRun = 0
                            }
                            Answer.Silent -> {
                                if (++silentRun >= config.silentRunLimit) {
                                    note("L3 ${unit.label}: no answer to $silentRun in a row, the rest of ${hex4(range.first)}-${hex4(range.last)} is skipped")
                                    skipped = true
                                }
                            }
                            Answer.Refused, Answer.Other -> silentRun = 0
                            Answer.Unsupported -> {
                                note("L3 ${unit.label}: does not support service 22")
                                throw UnitDone
                            }
                        }
                    }
                }
            } catch (_: UnitDoneException) {
                // This unit is finished (time box or unsupported); the next one goes on.
            } catch (e: LevelAborted) {
                if (e.scope == AbortScope.UNIT) stopUnit(unit, e) else throw e
            }
            note("L3 ${unit.label}: $positives answered of $asked asked")
        }
    }

    private fun rangesOf(unit: Target): List<IntRange> = config.rangesFor(unit.requestId) ?: config.ranges

    // Asking -------------------------------------------------------------------------------------------------

    private enum class Answer { Positive, Refused, Silent, Unsupported, Other }

    /** One `22 <did>` to [unit]; a positive answer is recorded (identifier and length, never the value). */
    private suspend fun ask(unit: Target, did: Int): Answer {
        selectFlowControl(unit.requestId, unit.responseId)
        var response = send(unit.requestId, readDid(did), 1)
        var own = response?.messages?.filter { it.sourceId == unit.responseId }.orEmpty()
        val cut = response != null && own.isEmpty() && unit.responseId in response.respondingIds
        if (cut) {
            // The reply is longer than one frame and was cut after the first: wait for the unit to give up on it, then ask for it whole.
            delay(MULTI_FRAME_SETTLE)
            response = send(unit.requestId, readDid(did), null)
            own = response?.messages?.filter { it.sourceId == unit.responseId }.orEmpty()
        }
        // Only the answer to this identifier counts: a late reply to an earlier request names another one and is dropped.
        val positive = own.filterIsInstance<DiagnosticResponse.Positive>().firstOrNull { it.service == SERVICE_READ_DATA && it.data.matches(did) }
        if (positive != null) {
            answers++
            val didHex = hex4(did)
            val dataHex = if (did == DID_VIN) {
                ""
            } else {
                positive.data.drop(2).joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
            }
            didAnswers.getOrPut(unit.requestId) { LinkedHashMap() }[didHex] = DidAnswer(did = didHex, hex = dataHex)
            note("${level.name} ${unit.label} $didHex -> ${positive.data.size - 2} bytes")
            return Answer.Positive
        }
        val negative = own.filterIsInstance<DiagnosticResponse.Negative>().firstOrNull { it.service == SERVICE_READ_DATA }
        return when {
            negative != null && (negative.code == NRC_SERVICE_NOT_SUPPORTED || negative.code == NRC_SERVICE_NOT_IN_SESSION) -> Answer.Unsupported
            negative != null -> Answer.Refused
            response == null || (own.isEmpty() && response.respondingIds.isEmpty() && response.messages.isEmpty()) -> Answer.Silent
            else -> Answer.Other
        }
    }

    private suspend fun send(requestId: Int, payload: ByteArray, count: Int?): ElmResponse? = try {
        guard.request(requestId, payload, count)
    } catch (e: LevelAborted) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdapterDisconnectedException) {
        throw e
    } catch (e: TransportException) {
        throw e
    } catch (e: AdapterException) {
        // A request that failed is no answer; the adapter's own errors are counted by the guard when they arrive as statuses.
        null
    }

    private fun readDid(did: Int): ByteArray = byteArrayOf(SERVICE_READ_DATA.toByte(), (did shr 8).toByte(), did.toByte())

    private suspend fun stopUnit(unit: Target, reason: LevelAborted) {
        unit.stopped = true
        guard.forgetUnit(unit.requestId)
        note("${level.name} ${unit.label}: not asked any more at this level: ${reason.reason}")
    }

    // After a level ------------------------------------------------------------------------------------------

    private suspend fun readFaults(): FaultReport {
        // A stop of the guard (the car moves, Skip, the time box) must end the reading, not count as a unit that stayed silent.
        val config = faultConfig.copy(isLinkLoss = { it is LevelAborted || faultConfig.isLinkLoss(it) })
        return FaultReader(guard, events, timeSource, config).read(units)
    }

    /**
     * A reason when the fault codes or the lamp are not as before the ladder; null when they are the same (or fewer). Only what both readings
     * could see is compared: a unit or a service that did not answer in either reading says nothing, and the lamp is compared only when the first
     * reading found it. A reading the guard stops ends the ladder ([LevelAborted] goes up).
     */
    private suspend fun flagged(before: FaultReport?): String? {
        val after = readFaults()
        if (before == null) return null
        val comparable = before.answered intersect after.answered
        val old = before.faults.map { Triple(it.code, it.kind, it.ecu) }.toSet()
        val fresh = after.faults.filter { after.answerKey(it) in comparable && Triple(it.code, it.kind, it.ecu) !in old }
        if (fresh.isNotEmpty()) return "a new fault code appeared: ${fresh.joinToString { it.code }}"
        if (before.readiness?.milOn == false && after.readiness?.milOn == true) return "the warning lamp is on"
        return null
    }

    /** After bus trouble: the standard probe again; true when a unit answers. */
    private suspend fun probeAgain(): Boolean {
        note("probing the bus again after the errors")
        restoreAdapter()
        return try {
            CapabilityProbe(guard, timeSource, CapabilityProbe.Config(addressing = addressing)).run { }.ecus.isNotEmpty()
        } catch (e: LevelAborted) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdapterDisconnectedException) {
            throw e
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            // The probe says in words that nothing answers (every address failed): that is the answer.
            false
        }.also { note("the probe after the errors: ${if (it) "a unit answers" else "nothing answers"}") }
    }

    // The adapter ---------------------------------------------------------------------------------------------

    /** Listen to every diagnostic identifier, so a unit that answers outside standard OBD range is heard. */
    private suspend fun setupReceive() {
        if (wideReceive) return
        if (addressing.wideFilterCommands.all { adapterOk(it) }) {
            wideReceive = true
        } else {
            note("the adapter does not take ATCF/ATCM: only standard OBD units are heard")
        }
    }

    private suspend fun selectFlowControl(requestId: Int, responseId: Int) {
        if (!addressing.extendedIds && requestId in OBD_REQUESTS) {
            if (customFlowControl != null && adapterOk("ATFCSM0")) customFlowControl = null
        } else if (customFlowControl != requestId) {
            val cmds = addressing.flowControlCommands(requestId, responseId).filterNot { it.startsWith("ATCRA") }
            if (cmds.all { adapterOk(it) }) customFlowControl = requestId
        }
    }

    /** The ordinary OBD receive filter and automatic flow control, as an init script leaves them. */
    private suspend fun restoreAdapter() {
        if (wideReceive) {
            for (cmd in addressing.defaultFilterCommands) adapterOk(cmd)
            wideReceive = false
        }
        if (customFlowControl != null) {
            adapterOk("ATFCSM0")
            customFlowControl = null
        }
    }

    private suspend fun adapterOk(text: String): Boolean = try {
        guard.command(text).any { it.trim().equals("OK", ignoreCase = true) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdapterDisconnectedException) {
        throw e
    } catch (e: TransportException) {
        throw e
    } catch (_: Exception) {
        false
    }

    // Progress and the log -------------------------------------------------------------------------------------

    private fun progress(level: LadderLevel, label: String, done: Int, total: Int, left: Duration) {
        val totalLeft = config.totalBox - this.total.elapsedNow()
        publish(LadderState.Running(level, label, done, total, minOf(left, totalLeft).inWholeSeconds.coerceAtLeast(0).toInt()))
    }

    private suspend fun note(detail: String) {
        try {
            events.emit(SessionEvents.LADDER, detail)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A broken log must not stop the ladder.
        }
    }

    private fun hex3(id: Int) = id.toString(16).uppercase().padStart(3, '0')

    private fun hex4(id: Int) = id.toString(16).uppercase().padStart(4, '0')

    private object UnitDone : UnitDoneException()

    private open class UnitDoneException : RuntimeException()

    private companion object {
        const val MILLIS = 1000.0
        const val REQUEST_FIRST = 0x700
        const val REQUEST_LAST = 0x7FF
        const val FUNCTIONAL = 0x7DF
        val OBD_RESPONSES = 0x7E8..0x7EF
        val OBD_REQUESTS = 0x7E0..0x7E7
        const val SERVICE_READ_DATA = 0x22
        const val DID_VIN = 0xF190
        const val NRC_SERVICE_NOT_SUPPORTED = 0x11
        const val NRC_SERVICE_NOT_IN_SESSION = 0x7F
        val MULTI_FRAME_SETTLE: Duration = 500.milliseconds
        const val WIDE_MASK = "700"
        const val STANDARD_MASK = "7F8"
        const val STANDARD_FILTER = "7E8"
        const val CLEAR_TO_SEND = "300000"
    }
}
