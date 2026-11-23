package knightlink.core.runtime

import knightlink.core.adapter.AdapterTimeoutException
import knightlink.core.adapter.Elm327Driver
import knightlink.core.adapter.ReadOnlyPolicy
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import knightlink.core.scan.CandidateCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * What the body-unit check has done on one control unit, kept between sessions (see [BodyCheckMemory]).
 *
 * @property asked how many of the unit's identifiers (in the order of [CandidateCheck.identifiers]) are done, skipped ones included
 * @property total how many identifiers the unit has in the candidate file
 * @property replies definitive answers (a value, or a refusal about the request itself, not "not now") the unit has given, over all attempts
 * @property positive how many of the data identifiers it answered positively
 * @property attempts how often the unit was gone through to its end
 * @property faultsRead whether `19 02 FF` was asked already
 * @property done finished for good: the unit was gone through and gave a definitive answer, or was silent [BodyUnitReader.MAX_SILENT_ATTEMPTS] times,
 *   or only said "not now" [BodyUnitReader.MAX_TRANSIENT_ATTEMPTS] times
 */
@Serializable
public data class BodyUnitProgress(
    val asked: Int = 0,
    val total: Int = 0,
    val replies: Int = 0,
    val positive: Int = 0,
    val attempts: Int = 0,
    val faultsRead: Boolean = false,
    val done: Boolean = false,
)

/** The body-unit check's progress for the candidate file [checkId] (a different file starts afresh), by request id (`700`). */
@Serializable
public data class BodyCheckMemory(val checkId: String = "", val units: Map<String, BodyUnitProgress> = emptyMap())

/** Why [BodyUnitReader.run] returned. */
public enum class BodyOutcome {
    /** Every unit it was given is finished. */
    COMPLETED,

    /** The request budget of a paced slice was used. */
    BUDGET,

    /** The time cap of the parked stage passed. */
    TIME_CAP,

    /** The car moves. */
    MOVING,

    /** The speed could not be read, so nothing says the car is parked. */
    SPEED_UNKNOWN,
}

/**
 * Asks the body units of a candidate file (the instrument cluster, the body controller ...) the way a scan tool does, read-only: for each unit
 * the adapter is told to listen to that unit's response identifier and to send flow control to it (`ATCRA`, `ATFCSH`, `ATFCSD`, `ATFCSM1`; the
 * ordinary filter hears only 7E8-7EF, so without this every answer is lost as NO DATA), then `19 02 FF` (stored fault codes, which also shows that the setup works) and the unit's data identifiers (`22 xxxx`). `10 01` (the default
 * session, which makes the unit start it afresh and so would end a session another tool opened) is sent once per unit, and only after the first
 * plain `22` was refused; that identifier is then asked again. Afterwards
 * [restore] puts the adapter back so that the live polling of the engine-bay units works again. Every adapter command and its answer is a
 * [SessionEvents.BODY_SETUP] event.
 *
 * What a unit answers is in the recording as raw traffic; the events say how many identifiers answered and which. Nothing is decoded.
 * A silent unit is absent, not broken: nothing here feeds the session's silence or asleep logic.
 *
 * Not thread-safe; one coroutine uses it. [driver] must not be used for anything else while [run] runs.
 */
public class BodyUnitReader(
    private val driver: Elm327Driver,
    private val events: SessionEventSink = SessionEventSink.None,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val config: Config = Config(),
) {
    /**
     * @property requestsPerSecond the pace of the requests (the scan's)
     * @property speedRequestId the unit asked for the vehicle speed
     * @property speedCheckEvery most requests between two readings of the speed while watching it
     * @property speedCheckInterval longest time between two readings
     * @property multiFrameSettle wait before a reply that was cut to its first frame is asked for whole
     * @property absentAfter requests to a unit without any reply after which it is taken to be absent
     * @property silentRunLimit consecutive unanswered identifiers after which the rest of a group is skipped
     * @property conditionsRunLimit consecutive "not now" refusals after which a unit is not asked any more
     */
    public data class Config(
        val requestsPerSecond: Double = DEFAULT_REQUESTS_PER_SECOND,
        val speedRequestId: Int = 0x7E0,
        val speedCheckEvery: Int = 100,
        val speedCheckInterval: Duration = 5.seconds,
        val multiFrameSettle: Duration = 1000.milliseconds,
        val absentAfter: Int = 12,
        val silentRunLimit: Int = 48,
        val conditionsRunLimit: Int = 5,
    ) {
        init {
            require(requestsPerSecond > 0.0) { "requestsPerSecond must be positive" }
            require(speedCheckEvery >= 1 && absentAfter >= 1 && silentRunLimit >= 1 && conditionsRunLimit >= 1) { "limits must be at least 1" }
        }
    }

    /**
     * Limits of one [run].
     *
     * @property maxRequests most requests (data identifiers and the two service requests) this run sends; null for no limit
     * @property maxTime longest this run takes; null for no limit
     * @property watchSpeed read the speed first and while running, and stop when it is not 0
     */
    public class Limits(
        public val maxRequests: Int? = null,
        public val maxTime: Duration? = null,
        public val watchSpeed: Boolean = false,
    )

    private class Interrupted(val outcome: BodyOutcome) : Exception()

    private sealed interface Answer {
        class Positive(val data: ByteArray) : Answer

        class Negative(val code: Int) : Answer

        data object Partial : Answer

        data object Silent : Answer
    }

    private var lastRequest: TimeMark? = null
    private var lastSpeedCheck: TimeMark? = null
    private var sinceSpeedCheck = 0
    private var adapterChanged = false
    private var craUnavailable = false
    private var listeningTo: Int? = null
    private var flowFor: Int? = null
    private var reinitsAtSetup = 0
    private val sessionRequested = HashSet<Int>()
    private val plainTried = HashSet<Int>()
    private var failures = 0

    // Per run
    private var started: TimeMark? = null
    private var requests = 0
    private var limits = Limits()
    private var onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }

    /** The unit identifiers asked `10 01` on this reader (once each). For tests and logs. */
    public val sessionRequests: Set<Int> get() = sessionRequested.toSet()

    /**
     * The speed in km/h, asked up to three times; null when no answer came. The adapter listens to the ordinary OBD filter, or is told to
     * for a moment while a body unit's receive address is set.
     */
    public suspend fun readSpeed(): Int? {
        val wasListening = listeningTo
        if (wasListening != null) listen(OBD_RESPONSE_ID)
        try {
            repeat(SPEED_TRIES) {
                pace()
                val response = try {
                    driver.request(config.speedRequestId, byteArrayOf(0x01, PID_SPEED), 1)
                } catch (_: AdapterTimeoutException) {
                    null
                }
                val positive = response?.messages?.filterIsInstance<DiagnosticResponse.Positive>()
                    ?.firstOrNull { it.service == 0x01 && it.data.size >= 2 && it.data[0] == PID_SPEED }
                if (positive != null) return positive.data[1].toInt() and 0xFF
            }
            return null
        } finally {
            if (wasListening != null) listen(wasListening)
        }
    }

    /**
     * Goes through [units] in order, resuming each at its [BodyUnitProgress.asked]; [progress] (by request id) is updated as it goes and holds
     * the result. The adapter is restored when this returns or throws. A lost link throws.
     *
     * @param onProgress called with the unit's label and the done and total identifiers of that unit
     */
    public suspend fun run(
        units: List<CandidateCheck>,
        progress: MutableMap<String, BodyUnitProgress>,
        limits: Limits,
        onProgress: (label: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): BodyOutcome {
        this.limits = limits
        this.onProgress = onProgress
        started = timeSource.markNow()
        requests = 0
        lastSpeedCheck = null
        sinceSpeedCheck = 0
        var outcome = BodyOutcome.COMPLETED
        try {
            if (limits.watchSpeed) checkSpeed()
            for (check in units) {
                if (progress[check.requestId]?.done == true) continue
                try {
                    readUnit(check, progress)
                } catch (e: Interrupted) {
                    outcome = e.outcome
                    break
                }
            }
        } catch (e: Interrupted) {
            outcome = e.outcome
        } finally {
            withContext(NonCancellable) {
                try {
                    restore()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The link may be gone; the connection's own recovery deals with that.
                    note(SessionEvents.BODY_SETUP, "restoring the adapter failed: ${e.message ?: e::class.simpleName}")
                }
            }
        }
        note(SessionEvents.BODY_SCAN, "run ended: ${outcome.name.lowercase()}, $requests requests")
        return outcome
    }

    /** Puts the adapter back to what the init script leaves: the ordinary OBD receive filter and automatic flow control. Does nothing when nothing was changed. */
    public suspend fun restore() {
        if (!adapterChanged) return
        val all = listOf("ATAR", "ATCM$STANDARD_MASK", "ATCF$STANDARD_FILTER", "ATFCSM0").map { setup(it) }
        listeningTo = null
        flowFor = null
        adapterChanged = false
        // An adapter that does not take these is set up again with its script, as after passive capture.
        if (!all.all { it }) driver.reinitialize("restoring the adapter after the body-unit check")
    }

    // One unit ------------------------------------------------------------------------------------------------

    private suspend fun readUnit(check: CandidateCheck, progress: MutableMap<String, BodyUnitProgress>) {
        val key = check.requestId
        val groups = check.groups()
        val total = groups.sumOf { it.size }
        val base = (progress[key] ?: BodyUnitProgress()).let { if (it.asked > total) it.copy(asked = 0) else it }.copy(total = total)
        progress[key] = base
        val tag = "$key ${check.label}"
        if (!prepare(check)) {
            note(SessionEvents.BODY_SCAN, "$tag: the adapter did not take the receive setup; not asked")
            val attempts = base.attempts + 1
            progress[key] = base.copy(attempts = attempts, done = attempts >= MAX_SILENT_ATTEMPTS)
            return
        }
        // Replies of any kind show that the unit is there; only definitive ones (a value, or a refusal that is about the request itself) finish it.
        var heard = 0
        var definite = 0
        var asked = 0
        val positives = ArrayList<String>()
        var refused = 0
        var silent = 0
        var faultsRead = base.faultsRead

        fun save(position: Int) {
            progress[key] = base.copy(
                asked = position,
                replies = base.replies + definite,
                positive = base.positive + positives.size,
                faultsRead = faultsRead,
            )
        }

        suspend fun service(label: String, payload: ByteArray, count: Int?) {
            val answer = ask(check, payload, count, serviceOf(payload))
            asked++
            if (answer is Answer.Positive || answer is Answer.Negative) heard++
            if (isDefinite(answer)) definite++
            note(SessionEvents.BODY_SCAN, "$tag: $label -> ${describe(answer, whole = true)}")
        }
        if (!faultsRead) {
            service("19 02 FF", byteArrayOf(0x19, 0x02, 0xFF.toByte()), null)
            faultsRead = true
            save(base.asked)
        }

        var groupStart = 0
        var stopReason: String? = null
        var transient = false
        var conditionsRun = 0
        var position = base.asked
        groupLoop@ for (group in groups) {
            var silentRun = 0
            for ((offset, did) in group.withIndex()) {
                position = groupStart + offset
                if (position < base.asked) continue
                if (heard == 0 && base.replies == 0 && asked >= config.absentAfter) {
                    stopReason = "no answer to $asked requests: absent"
                    break@groupLoop
                }
                onProgress(check.label, position, total)
                var answer = askDid(check, did)
                asked++
                // The default session is requested only when a plain request has failed with a refusal, and then once per unit: it makes the unit
                // start its default session afresh, which a unit that answers without it never needs.
                if (plainTried.add(check.requestCanId) && answer is Answer.Negative && ReadOnlyPolicy.isBodyUnit(check.requestCanId)) {
                    service("10 01 (after ${hex4(did)} was refused with ${answer.code.toString(16).uppercase().padStart(2, '0')})", byteArrayOf(0x10, 0x01), 1)
                    sessionRequested.add(check.requestCanId)
                    answer = askDid(check, did)
                    asked++
                }
                when (answer) {
                    is Answer.Positive -> {
                        heard++
                        definite++
                        silentRun = 0
                        conditionsRun = 0
                        positives += "${hex4(did)}(${answer.data.size})"
                    }
                    is Answer.Negative -> {
                        heard++
                        silentRun = 0
                        refused++
                        if (answer.code == NRC_SERVICE_NOT_SUPPORTED) {
                            definite++
                            stopReason = "does not support service 22"
                            break@groupLoop
                        }
                        if (answer.code in REFUSALS) {
                            definite++
                            conditionsRun = 0
                        } else if (++conditionsRun >= config.conditionsRunLimit) {
                            // "Not now" (busy, conditions not correct, still pending ...) says nothing about the identifiers: another time may do.
                            transient = true
                            stopReason = "answered not now (${answer.code.toString(16)}) to $conditionsRun identifiers in a row, last ${hex4(did)}"
                            break@groupLoop
                        }
                    }
                    else -> {
                        silent++
                        if (++silentRun >= config.silentRunLimit) {
                            note(
                                SessionEvents.BODY_SCAN,
                                "$tag: no answer to $silentRun identifiers in a row, from ${hex4(did)} the rest of the group is skipped",
                            )
                            position = groupStart + group.size - 1
                            break
                        }
                    }
                }
                save(position + 1)
            }
            groupStart += group.size
            save(groupStart)
        }
        val finished = stopReason != null || groupStart >= total
        val current = progress.getValue(key)
        val listed = positives.take(MAX_LISTED).joinToString(" ") + if (positives.size > MAX_LISTED) " ..." else ""
        note(
            SessionEvents.BODY_SCAN,
            "$tag: ${if (finished) "finished" else "paused at ${current.asked} of $total"}; answered ${positives.size} identifiers" +
                (if (positives.isNotEmpty()) " ($listed)" else "") + ", $refused refused, $silent without answer" + (stopReason?.let { "; $it" } ?: ""),
        )
        if (finished) {
            val attempts = current.attempts + 1
            progress[key] = if (transient) {
                // Counted, but not finished: the unit is asked again from here next time, up to MAX_TRANSIENT_ATTEMPTS times in all.
                current.copy(attempts = attempts, done = attempts >= MAX_TRANSIENT_ATTEMPTS)
            } else {
                current.copy(asked = if (current.replies > 0) total else 0, attempts = attempts, done = current.replies > 0 || attempts >= MAX_SILENT_ATTEMPTS)
            }
        }
    }

    /** An answer that says something about the request itself: a value, or a refusal of the service or the identifier (not "not now"). */
    private fun isDefinite(answer: Answer): Boolean =
        answer is Answer.Positive || (answer is Answer.Negative && (answer.code in REFUSALS || answer.code == NRC_SERVICE_NOT_SUPPORTED))

    private fun serviceOf(payload: ByteArray): Int = payload[0].toInt() and 0xFF

    private fun describe(answer: Answer, whole: Boolean): String = when (answer) {
        is Answer.Positive -> "positive, ${answer.data.size} data bytes" + if (whole) ": ${answer.data.take(MAX_LOGGED_BYTES).toByteArray().toHex()}" else ""
        is Answer.Negative -> "refused, code ${answer.code.toString(16).uppercase().padStart(2, '0')}"
        Answer.Partial -> "only a first frame"
        Answer.Silent -> "no answer"
    }

    private fun hex4(did: Int): String = did.toString(16).uppercase().padStart(4, '0')

    // Requests ------------------------------------------------------------------------------------------------

    private suspend fun askDid(check: CandidateCheck, did: Int): Answer {
        val payload = byteArrayOf(0x22, (did shr 8).toByte(), did.toByte())
        val first = ask(check, payload, 1, 0x22, did)
        if (first !is Answer.Partial) return first
        // The reply is longer than one frame and was cut after the first: wait for the unit to give up on it, then ask for it whole.
        delay(config.multiFrameSettle)
        return ask(check, payload, null, 0x22, did).let { if (it is Answer.Partial) Answer.Silent else it }
    }

    /** One request at the scan's pace, checking the limits and the speed before it. */
    private suspend fun ask(check: CandidateCheck, payload: ByteArray, count: Int?, service: Int, did: Int? = null): Answer {
        gate()
        if (driver.stats.reinits != reinitsAtSetup) {
            // The adapter was initialised again meanwhile (a reset): its receive settings are gone.
            note(SessionEvents.BODY_SETUP, "the adapter was initialised again; the receive setup is made again")
            adapterChanged = false
            listeningTo = null
            flowFor = null
            prepare(check)
        }
        pace()
        requests++
        val response = try {
            driver.request(check.requestCanId, payload, count).also { failures = 0 }
        } catch (e: AdapterTimeoutException) {
            if (++failures >= MAX_FAILURES_IN_A_ROW) throw e
            null
        }
        return answer(response, check.responseCanId, service, did)
    }

    /**
     * What [response] says to the request. Only the unit's own messages for this service (and, for a positive reply, this identifier) count: a late
     * reply to an earlier request (another service or identifier) is dropped, not taken for the answer. A reply cut to its first frame is a
     * [Answer.Partial] only when the unit sent nothing else.
     */
    private fun answer(response: ElmResponse?, responseId: Int, service: Int, did: Int?): Answer {
        if (response == null) return Answer.Silent
        val own = response.messages.filter { it.sourceId == responseId }
        own.filterIsInstance<DiagnosticResponse.Positive>().firstOrNull {
            it.service == service &&
                (did == null || (it.data.size >= 2 && ((it.data[0].toInt() and 0xFF) shl 8 or (it.data[1].toInt() and 0xFF)) == did))
        }?.let { return Answer.Positive(if (did != null) it.data.copyOfRange(2, it.data.size) else it.data) }
        own.filterIsInstance<DiagnosticResponse.Negative>().firstOrNull { it.service == service }?.let { return Answer.Negative(it.code) }
        if (own.isNotEmpty()) return Answer.Silent
        return if (responseId in response.respondingIds) Answer.Partial else Answer.Silent
    }

    private suspend fun pace() {
        lastRequest?.let { last ->
            val wait = (MILLIS_PER_SECOND / config.requestsPerSecond).milliseconds - last.elapsedNow()
            if (wait.isPositive()) delay(wait)
        }
        lastRequest = timeSource.markNow()
    }

    /** Before every request: the limits, and the speed when it is time. */
    private suspend fun gate() {
        limits.maxRequests?.let { if (requests >= it) throw Interrupted(BodyOutcome.BUDGET) }
        limits.maxTime?.let { cap -> if (started!!.elapsedNow() >= cap) throw Interrupted(BodyOutcome.TIME_CAP) }
        if (limits.watchSpeed &&
            (++sinceSpeedCheck >= config.speedCheckEvery || (lastSpeedCheck?.elapsedNow() ?: Duration.ZERO) >= config.speedCheckInterval)
        ) {
            checkSpeed()
        }
    }

    private suspend fun checkSpeed() {
        val speed = readSpeed()
        sinceSpeedCheck = 0
        lastSpeedCheck = timeSource.markNow()
        if (speed == null) {
            note(SessionEvents.BODY_SCAN, "the speed cannot be read; stopping")
            throw Interrupted(BodyOutcome.SPEED_UNKNOWN)
        }
        if (speed != 0) {
            note(SessionEvents.BODY_SCAN, "the car is moving ($speed km/h); stopping")
            throw Interrupted(BodyOutcome.MOVING)
        }
    }

    // The adapter's receive and flow-control settings ---------------------------------------------------------

    /** Listen to [check]'s response identifier and send its flow control there. False when the adapter does not take the receive address. */
    private suspend fun prepare(check: CandidateCheck): Boolean {
        reinitsAtSetup = driver.stats.reinits
        if (!listen(check.responseCanId)) return false
        if (flowFor != check.requestCanId) {
            val id = hex3(check.requestCanId)
            val set = setup("ATFCSH$id") && setup("ATFCSD$CLEAR_TO_SEND") && setup("ATFCSM1")
            // Automatic flow control may still work: carry on without.
            flowFor = if (set) check.requestCanId else null
        }
        return true
    }

    /** `ATCRA<id>`; when the adapter does not know it, a mask and a filter that pass exactly [id]. */
    private suspend fun listen(id: Int): Boolean {
        if (listeningTo == id) return true
        adapterChanged = true
        val ok = if (!craUnavailable && setup("ATCRA${hex3(id)}")) {
            true
        } else {
            craUnavailable = true
            setup("ATCM$EXACT_MASK") && setup("ATCF${hex3(id)}")
        }
        listeningTo = if (ok) id else null
        return ok
    }

    /** One adapter command; true when the adapter said OK. Written as a [SessionEvents.BODY_SETUP] event with the answer. */
    private suspend fun setup(command: String): Boolean {
        val reply = try {
            driver.command(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note(SessionEvents.BODY_SETUP, "$command -> failed: ${e.message ?: e::class.simpleName}")
            return false
        }
        note(SessionEvents.BODY_SETUP, "$command -> ${reply.joinToString(" ").take(MAX_LOGGED_REPLY)}")
        return reply.any { it.trim().equals("OK", ignoreCase = true) }
    }

    private fun hex3(id: Int): String = id.toString(16).uppercase().padStart(3, '0')

    private suspend fun note(name: String, detail: String) {
        try {
            events.emit(name, detail)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A broken log must not end the check.
        }
    }

    public companion object {
        /** A unit that never answered is tried this often over the app's lifetime for the vehicle, then left alone. */
        public const val MAX_SILENT_ATTEMPTS: Int = 3

        /** A unit that only ever said "not now" is tried this often, then left alone. */
        public const val MAX_TRANSIENT_ATTEMPTS: Int = 5

        /** About the pace of the full scan. */
        public const val DEFAULT_REQUESTS_PER_SECOND: Double = 16.0

        private const val MILLIS_PER_SECOND = 1000.0
        private const val SPEED_TRIES = 3
        private const val PID_SPEED: Byte = 0x0D
        private const val OBD_RESPONSE_ID = 0x7E8
        private const val MAX_FAILURES_IN_A_ROW = 3
        private const val MAX_LISTED = 24
        private const val MAX_LOGGED_BYTES = 24
        private const val MAX_LOGGED_REPLY = 40
        private const val NRC_SERVICE_NOT_SUPPORTED = 0x11
        private val REFUSALS = setOf(0x10, 0x12, 0x13, 0x14, 0x24, 0x25, 0x31, 0x33, 0x7E, 0x7F)
        private const val CLEAR_TO_SEND = "300000"
        private const val STANDARD_MASK = "7F8"
        private const val STANDARD_FILTER = "7E8"
        private const val EXACT_MASK = "7FF"
    }
}
