package knightlink.core.scan

import kotlinx.serialization.Serializable

/** One emissions monitor and what the ECU says about it. [complete] is null while the monitor is not supported. */
@Serializable
public data class Monitor(val name: String, val supported: Boolean, val complete: Boolean?)

/**
 * The answer to service 01 PID 01 (monitor status since the codes were cleared) or PID 41 (this drive cycle).
 *
 * @property milOn whether the malfunction lamp is on; null for PID 41, which has no lamp bit
 * @property dtcCount the number of confirmed emission codes; null for PID 41
 * @property compressionIgnition true for a diesel: the second group of monitors has different members
 */
@Serializable
public data class MonitorStatus(
    val milOn: Boolean?,
    val dtcCount: Int?,
    val compressionIgnition: Boolean,
    val monitors: List<Monitor>,
) {
    public companion object {
        private val CONTINUOUS = listOf("misfire", "fuel system", "components")
        private val SPARK = listOf(
            "catalyst",
            "heated catalyst",
            "evaporative system",
            "secondary air",
            "A/C refrigerant",
            "oxygen sensor",
            "oxygen sensor heater",
            "EGR/VVT",
        )
        private val COMPRESSION = listOf(
            "NMHC catalyst",
            "NOx/SCR aftertreatment",
            null,
            "boost pressure",
            null,
            "exhaust gas sensor",
            "particulate filter",
            "EGR/VVT",
        )

        /**
         * Reads the four data bytes of PID 01 or 41 ([data] is what follows the PID byte), or null when there are fewer. A monitor whose
         * test is not supported is listed as unsupported; a supported one is complete when its "not complete" bit is clear.
         */
        public fun decode(data: ByteArray, withLamp: Boolean): MonitorStatus? {
            if (data.size < 4) return null
            val a = data[0].toInt() and 0xFF
            val b = data[1].toInt() and 0xFF
            val c = data[2].toInt() and 0xFF
            val d = data[3].toInt() and 0xFF
            val diesel = b and 0x08 != 0
            val monitors = ArrayList<Monitor>()
            for (i in CONTINUOUS.indices) {
                val supported = b and (1 shl i) != 0
                monitors += Monitor(CONTINUOUS[i], supported, if (supported) b and (1 shl (i + 4)) == 0 else null)
            }
            val names = if (diesel) COMPRESSION else SPARK
            for (i in names.indices) {
                val name = names[i] ?: continue
                val supported = c and (1 shl i) != 0
                monitors += Monitor(name, supported, if (supported) d and (1 shl i) == 0 else null)
            }
            return MonitorStatus(
                milOn = if (withLamp) a and 0x80 != 0 else null,
                dtcCount = if (withLamp) a and 0x7F else null,
                compressionIgnition = diesel,
                monitors = monitors,
            )
        }
    }
}

/**
 * One on-board test from service 06: its identifier, the unit and scaling it reports in, the value and the limits, as raw numbers.
 * [withinLimits] compares the three: unsigned, except when the minimum is above the maximum, which is how a test with signed limits shows
 * up (a minimum of 0xFF38 and a maximum of 0x00C8 are -200 and 200); then all three are read as signed 16-bit numbers, [signed] is true and
 * [value], [min] and [max] hold the signed numbers. The scaling identifier says how to turn the numbers into a physical value, which this scan
 * does not do.
 */
@Serializable
public data class TestResult(
    val testId: String,
    val scaling: String,
    val value: Int,
    val min: Int,
    val max: Int,
    val withinLimits: Boolean,
    /** True when the limits were signed 16-bit (the minimum was above the maximum as unsigned numbers). */
    val signed: Boolean = false,
)

/** The tests of one on-board monitoring identifier (MID) of service 06. */
@Serializable
public data class MidResult(val mid: String, val tests: List<TestResult>)

/** Reads service 06 replies. */
public object Mode06Decoder {
    /**
     * The test results in a reply to `06 <mid>`: [data] is what follows the response service byte (`46`). Each result is nine bytes: the
     * monitor id, the test id, the scaling id, then value, minimum and maximum as 16-bit numbers (the standard repeats the monitor id in
     * every block). A reply that is a monitor id followed by blocks of eight bytes is read too. Anything else gives no results.
     */
    public fun results(data: ByteArray): List<MidResult> {
        val blocks = ArrayList<Pair<Int, TestResult>>()
        when {
            data.isNotEmpty() && data.size % BLOCK == 0 -> for (i in data.indices step BLOCK) blocks += block(data, data[i].toInt() and 0xFF, i + 1)
            data.size >= BLOCK && (data.size - 1) % (BLOCK - 1) == 0 ->
                for (i in 1 until data.size step BLOCK - 1) blocks += block(data, data[0].toInt() and 0xFF, i)
        }
        return blocks.groupBy({ it.first }, { it.second }).map { (mid, tests) -> MidResult(hex(mid), tests) }
    }

    private fun block(data: ByteArray, mid: Int, testAt: Int): Pair<Int, TestResult> {
        fun word(at: Int) = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
        var value = word(testAt + 2)
        var min = word(testAt + 4)
        var max = word(testAt + 6)
        // A minimum above the maximum is only possible with signed limits: read all three as 16-bit two's complement.
        val signed = min > max
        if (signed) {
            value = value.toShort().toInt()
            min = min.toShort().toInt()
            max = max.toShort().toInt()
        }
        return mid to TestResult(
            testId = hex(data[testAt].toInt() and 0xFF),
            scaling = hex(data[testAt + 1].toInt() and 0xFF),
            value = value,
            min = min,
            max = max,
            withinLimits = value in min..max,
            signed = signed,
        )
    }

    /** The MIDs a support bitmap lists, for the range starting at [base]; bit 0 of the last byte says whether the next range is supported too. */
    public fun supported(base: Int, bitmap: ByteArray): Pair<List<Int>, Boolean> {
        val mids = ArrayList<Int>()
        for (offset in 1..RANGE) {
            val byte = bitmap[(offset - 1) / 8].toInt() and 0xFF
            if (byte and (0x80 shr ((offset - 1) % 8)) != 0 && offset < RANGE) mids += base + offset
        }
        return mids to (bitmap.last().toInt() and 0x01 != 0)
    }

    private fun hex(value: Int) = value.toString(16).uppercase().padStart(2, '0')

    private const val BLOCK = 9
    private const val RANGE = 0x20
}

/** What one OBD ECU said about readiness: the monitors, this drive cycle's, and the on-board test results of service 06. */
@Serializable
public data class EcuReadiness(
    val requestId: String,
    val sinceClear: MonitorStatus? = null,
    val thisCycle: MonitorStatus? = null,
    val supportedMids: List<String> = emptyList(),
    val midResults: List<MidResult> = emptyList(),
)
