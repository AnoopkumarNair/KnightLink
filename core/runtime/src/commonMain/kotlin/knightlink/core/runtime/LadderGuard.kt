package knightlink.core.runtime

import knightlink.core.adapter.ReadOnlyPolicy
import knightlink.core.adapter.ReadOnlyViolationException
import knightlink.core.protocol.DiagnosticLink
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import knightlink.core.protocol.ElmStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How far an abort of the learning ladder reaches. */
internal enum class AbortScope {
    /** One control unit is not asked any more at this level. */
    UNIT,

    /** The level ends; the bus is checked again (for bus errors) and the next level may still run. */
    LEVEL,

    /** Everything ends: the car moves, the engine state changed, the battery is low, a new fault appeared. */
    LADDER,
}

/** Thrown by [LadderGuard] when a rule of the ladder says to stop. [unit] is the request id for [AbortScope.UNIT]. */
internal class LevelAborted(val scope: AbortScope, val reason: String, val unit: Int? = null, val reprobe: Boolean = false) : RuntimeException(reason)

/**
 * Wraps the link for the learning ladder (see [LearningLadder]) and enforces its rules on every request, below the level code, so no level can
 * forget them:
 *
 * - Hard lines: only the reading services (01, 02, 03, 06, 07, 09, 0A, 19 with a reporting sub-function, 22) to a request identifier of 700-7FF.
 *   Services 2E, 27, 31, 10, 11, 14, 28, 85, 3E and everything else are refused before they leave ([ReadOnlyViolationException]), whatever the
 *   caller asks (the read-only policy of the adapter driver refuses them too; this is the second lock).
 * - Pace: at most one request every [Config.minInterval] (ten a second).
 * - The car: every [Config.checkEvery] requests or [Config.checkInterval], the speed, the engine state and the battery voltage are read; speed above 0,
 *   the engine started or stopped, or a battery under [Config.minVolts] with the engine off end the whole ladder.
 * - Refusals: [Config.unitRefusalLimit] security or conditions refusals (`33`, `22`) in a row from one unit stop that unit; such refusals from more
 *   than half of the units asked in a level stop the level. `31` (request out of range) is the ordinary "not here" of a sweep and counts for neither.
 * - Bus trouble: [Config.busErrorLimit] answers with CAN ERROR, BUS ERROR, an ERRxx status, LV RESET or BUFFER FULL in a level stop the level (and
 *   the caller probes the bus again).
 *
 * Not thread-safe: the ladder asks one thing at a time.
 */
internal class LadderGuard(
    private val link: DiagnosticLink,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val config: Config = Config(),
) : DiagnosticLink {
    data class Config(
        val speedRequestId: Int = 0x7E0,
        val minInterval: Duration = 100.milliseconds,
        val checkEvery: Int = 20,
        val checkInterval: Duration = 3.seconds,
        val minVolts: Double = 12.0,
        val unitRefusalLimit: Int = 3,
        val busErrorLimit: Int = 2,
    )

    /** What the car was doing when the ladder started. */
    class Baseline(val engineRunning: Boolean?, val volts: Double?)

    private var baseline: Baseline? = null
    private var last: TimeMark? = null
    private var sinceCheck = 0
    private var checkMark: TimeMark = timeSource.markNow()
    private var checking = false

    private val refusalRun = HashMap<Int, Int>()
    private val askedInLevel = LinkedHashSet<Int>()
    private val refusedInLevel = LinkedHashSet<Int>()
    private var busErrors = 0

    /** How many requests the guard let through, in all. */
    var sent = 0
        private set

    /**
     * Reads the speed, the engine state and the voltage; the ladder runs only when the speed reads 0.
     *
     * @return null when it may run, else why it may not
     */
    suspend fun begin(): String? {
        val speed = readSpeed() ?: return "the car's speed cannot be read, so the ladder cannot tell that it is parked"
        if (speed != 0) return "the car moves ($speed km/h)"
        val running = engineRunning()
        val volts = readVolts()
        if (running == false && volts != null && volts < config.minVolts) return "the battery reads $volts V with the engine off"
        baseline = Baseline(running, volts)
        checkMark = timeSource.markNow()
        sinceCheck = 0
        return null
    }

    /** A new level starts: its counters are its own. */
    fun beginLevel() {
        refusalRun.clear()
        askedInLevel.clear()
        refusedInLevel.clear()
        busErrors = 0
    }

    /** A unit that was stopped starts again at the next level with a clean count. */
    fun forgetUnit(requestId: Int) {
        refusalRun.remove(requestId)
    }

    override suspend fun command(text: String): List<String> = link.command(text)

    override suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int?): ElmResponse {
        hardLines(requestId, payload)
        if (!checking) {
            if (++sinceCheck >= config.checkEvery || checkMark.elapsedNow() >= config.checkInterval) checkCar()
        }
        pace()
        sent++
        val response = link.request(requestId, payload, expectedResponses)
        askedInLevel += requestId
        countBusErrors(response)
        countRefusals(requestId, response)
        return response
    }

    private fun hardLines(requestId: Int, payload: ByteArray) {
        ReadOnlyPolicy.requestIdViolation(requestId)?.let { throw ReadOnlyViolationException("ladder: $it") }
        val service = payload.firstOrNull()?.toInt()?.and(0xFF) ?: throw ReadOnlyViolationException("ladder: an empty request")
        if (service !in READING_SERVICES) throw ReadOnlyViolationException("ladder: service ${service.toString(16).uppercase()} is never sent")
        if (service == SERVICE_READ_DTC && (payload.size < 2 || (payload[1].toInt() and 0x7F) != SUB_BY_STATUS_MASK)) {
            throw ReadOnlyViolationException("ladder: only 19 02 is sent of service 19")
        }
        ReadOnlyPolicy.violation(payload.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })?.let {
            throw ReadOnlyViolationException("ladder: $it")
        }
    }

    private suspend fun pace() {
        last?.let { mark ->
            val wait = config.minInterval - mark.elapsedNow()
            if (wait.isPositive()) delay(wait)
        }
        last = timeSource.markNow()
    }

    // The car --------------------------------------------------------------------------------------------------

    private suspend fun checkCar() {
        val start = baseline ?: return
        checking = true
        try {
            sinceCheck = 0
            checkMark = timeSource.markNow()
            val speed = readSpeed() ?: throw LevelAborted(AbortScope.LADDER, "the car's speed can no longer be read")
            if (speed != 0) throw LevelAborted(AbortScope.LADDER, "the car moves ($speed km/h)")
            val running = engineRunning()
            if (start.engineRunning != null && running != null && running != start.engineRunning) {
                throw LevelAborted(AbortScope.LADDER, "the engine ${if (running) "started" else "stopped"}")
            }
            val volts = readVolts()
            if (volts != null && volts < config.minVolts && running != true) {
                throw LevelAborted(AbortScope.LADDER, "the battery reads $volts V with the engine off")
            }
        } finally {
            checking = false
        }
    }

    private suspend fun readSpeed(): Int? = ask(PID_SPEED)?.let { it.data.getOrNull(1)?.toInt()?.and(0xFF) }

    /** True when the engine turns (rpm above 0), false when it reads 0, null when it cannot be read. */
    private suspend fun engineRunning(): Boolean? {
        val data = ask(PID_RPM)?.data ?: return null
        if (data.size < 3) return null
        return (((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)) > 0
    }

    private suspend fun ask(pid: Int): DiagnosticResponse.Positive? {
        pace()
        sent++
        val response = try {
            link.request(config.speedRequestId, byteArrayOf(SERVICE_LIVE_DATA.toByte(), pid.toByte()), 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: knightlink.core.adapter.AdapterDisconnectedException) {
            throw e
        } catch (e: knightlink.core.transport.TransportException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return response.messages.filterIsInstance<DiagnosticResponse.Positive>()
            .firstOrNull { it.service == SERVICE_LIVE_DATA && it.data.firstOrNull()?.toInt()?.and(0xFF) == pid }
    }

    private suspend fun readVolts(): Double? {
        val text = try {
            link.command("ATRV").firstOrNull { it.isNotBlank() && it.trim() != "?" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: knightlink.core.adapter.AdapterDisconnectedException) {
            throw e
        } catch (e: knightlink.core.transport.TransportException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return VOLTS.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    // The answers -----------------------------------------------------------------------------------------------

    private fun countBusErrors(response: ElmResponse) {
        val bad = response.statuses.count {
            it == ElmStatus.CAN_ERROR ||
                it == ElmStatus.BUS_ERROR ||
                it == ElmStatus.LV_RESET ||
                it == ElmStatus.BUFFER_FULL ||
                it == ElmStatus.DATA_ERROR ||
                it == ElmStatus.RX_ERROR ||
                it == ElmStatus.FB_ERROR
        }
        if (bad == 0) return
        busErrors++
        if (busErrors >= config.busErrorLimit) {
            throw LevelAborted(
                AbortScope.LEVEL,
                "the adapter reported bus trouble $busErrors times (${response.statuses.joinToString {
                    it.name
                }})",
                reprobe = true,
            )
        }
    }

    private fun countRefusals(requestId: Int, response: ElmResponse) {
        val refusal = response.messages.filterIsInstance<DiagnosticResponse.Negative>().firstOrNull { it.code == NRC_SECURITY || it.code == NRC_CONDITIONS }
        if (refusal == null) {
            if (response.messages.any { it is DiagnosticResponse.Positive }) refusalRun[requestId] = 0
            return
        }
        refusedInLevel += requestId
        val run = (refusalRun[requestId] ?: 0) + 1
        refusalRun[requestId] = run
        if (run >= config.unitRefusalLimit) {
            throw LevelAborted(AbortScope.UNIT, "the unit refused $run requests in a row (${refusal.code.toString(16).uppercase()})", unit = requestId)
        }
        if (askedInLevel.size >= MIN_UNITS_FOR_STORM && refusedInLevel.size * 2 > askedInLevel.size) {
            throw LevelAborted(AbortScope.LEVEL, "more than half of the units asked refuse (${refusedInLevel.size} of ${askedInLevel.size})")
        }
    }

    private companion object {
        const val SERVICE_LIVE_DATA = 0x01
        const val SERVICE_READ_DTC = 0x19
        const val SERVICE_READ_DATA = 0x22
        const val SUB_BY_STATUS_MASK = 0x02
        const val PID_SPEED = 0x0D
        const val PID_RPM = 0x0C
        const val NRC_SECURITY = 0x33
        const val NRC_CONDITIONS = 0x22
        const val MIN_UNITS_FOR_STORM = 2
        val READING_SERVICES = setOf(SERVICE_LIVE_DATA, 0x02, 0x03, 0x06, 0x07, 0x09, 0x0A, SERVICE_READ_DTC, SERVICE_READ_DATA)
        val VOLTS = Regex("""(\d+(?:\.\d+)?)\s*V""", RegexOption.IGNORE_CASE)
    }
}
