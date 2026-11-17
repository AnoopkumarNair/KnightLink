package knightlink.core.scan

import kotlinx.serialization.Serializable

/** One fault code with the status byte an ECU reported for it (UDS ReadDTCInformation, sub-function 02). */
@Serializable
public data class StatusDtc(val code: String, val status: String)

/**
 * What the status byte of a UDS fault code means (ISO 14229-1, annex D). A reply to `19 02 FF` lists every code the ECU knows, and most of
 * them only say that their test has not run since the codes were cleared: those are not faults and must never be presented as such.
 */
public object DtcStatus {
    public const val TEST_FAILED: Int = 0x01
    public const val TEST_FAILED_THIS_CYCLE: Int = 0x02
    public const val PENDING: Int = 0x04
    public const val CONFIRMED: Int = 0x08
    public const val TEST_NOT_COMPLETED_SINCE_CLEAR: Int = 0x10
    public const val TEST_FAILED_SINCE_CLEAR: Int = 0x20
    public const val TEST_NOT_COMPLETED_THIS_CYCLE: Int = 0x40
    public const val WARNING_INDICATOR_REQUESTED: Int = 0x80

    /** How a code with status [status] is presented. A code can be in several classes (a confirmed code often asks for the lamp too). */
    public fun classes(status: Int): Set<FaultClass> {
        val result = LinkedHashSet<FaultClass>()
        if (status and CONFIRMED != 0) result += FaultClass.CONFIRMED
        if (status and PENDING != 0) result += FaultClass.PENDING
        if (status and WARNING_INDICATOR_REQUESTED != 0) result += FaultClass.WARNING_LAMP
        // The test is failing now, or has failed since the codes were cleared: worth knowing, but not confirmed and not pending.
        if (result.isEmpty() && status and (TEST_FAILED or TEST_FAILED_THIS_CYCLE or TEST_FAILED_SINCE_CLEAR) != 0) result += FaultClass.FAILED_TEST
        if (result.isEmpty()) result += FaultClass.NOT_COMPLETED_ONLY
        return result
    }
}

/** How a fault code is presented. [NOT_COMPLETED_ONLY] is the only class that is not a fault. */
public enum class FaultClass { CONFIRMED, PENDING, WARNING_LAMP, FAILED_TEST, NOT_COMPLETED_ONLY }

/** Fault codes of one ECU, read with UDS `19 02 FF`, sorted into [FaultClass]es. The "not completed" codes are counted and listed apart. */
@Serializable
public data class UdsFaults(
    val total: Int,
    val confirmed: List<StatusDtc> = emptyList(),
    val pending: List<StatusDtc> = emptyList(),
    val warningLamp: List<StatusDtc> = emptyList(),
    val failedTest: List<StatusDtc> = emptyList(),
    val notCompletedOnly: Int = 0,
) {
    /** Codes that are faults: confirmed, pending, asking for the lamp, or failing a test; each code once. */
    public val faultCodes: Set<String>
        get() = (confirmed + pending + warningLamp + failedTest).map { it.code }.toSet()
}

/** Reads fault codes out of replies. */
public object DtcDecoder {
    /**
     * An SAE code from the first two bytes, e.g. `0x01 0x23` is `P0123`. The leading two bits are the system (P, C, B, U), the next two the
     * first digit, then three hex digits.
     */
    public fun sae(high: Int, low: Int): String {
        val system = "PCBU"[(high shr 6) and 0x03]
        return "$system${((high shr 4) and 0x03)}${(high and 0x0F).toString(16).uppercase()}${low.toString(16).uppercase().padStart(2, '0')}"
    }

    /**
     * The codes of an OBD reply to service 03, 07 or 0A: [data] is what follows the response service byte (`43`, `47`, `4A`): a count, then
     * two bytes per code. A pair of zeros is padding. When a count is present it is a lower bound on nothing: the pairs that are there are read.
     */
    public fun obd(data: ByteArray): List<String> {
        if (data.isEmpty()) return emptyList()
        val codes = ArrayList<String>()
        var i = 1
        while (i + 1 < data.size) {
            val high = data[i].toInt() and 0xFF
            val low = data[i + 1].toInt() and 0xFF
            if (high != 0 || low != 0) codes += sae(high, low)
            i += 2
        }
        return codes
    }

    /**
     * The codes of a UDS reply to `19 02 <mask>`: [data] is what follows `59 02`: the status availability mask, then four bytes per code
     * (three of code, one of status). A code's text is its SAE part and, when the failure type byte is not zero, a dash and that byte.
     */
    public fun uds(data: ByteArray): List<StatusDtc> {
        if (data.isEmpty()) return emptyList()
        val codes = ArrayList<StatusDtc>()
        var i = 1
        while (i + 3 < data.size) {
            val high = data[i].toInt() and 0xFF
            val low = data[i + 1].toInt() and 0xFF
            val failureType = data[i + 2].toInt() and 0xFF
            val status = data[i + 3].toInt() and 0xFF
            val code = sae(high, low) + if (failureType != 0) "-" + failureType.toString(16).uppercase().padStart(2, '0') else ""
            codes += StatusDtc(code, status.toString(16).uppercase().padStart(2, '0'))
            i += 4
        }
        return codes
    }

    /** Sorts [codes] by what their status bytes say. */
    public fun classify(codes: List<StatusDtc>): UdsFaults {
        val confirmed = ArrayList<StatusDtc>()
        val pending = ArrayList<StatusDtc>()
        val lamp = ArrayList<StatusDtc>()
        val failed = ArrayList<StatusDtc>()
        var notCompleted = 0
        for (code in codes) {
            for (cls in DtcStatus.classes(code.status.toInt(16))) {
                when (cls) {
                    FaultClass.CONFIRMED -> confirmed += code
                    FaultClass.PENDING -> pending += code
                    FaultClass.WARNING_LAMP -> lamp += code
                    FaultClass.FAILED_TEST -> failed += code
                    FaultClass.NOT_COMPLETED_ONLY -> notCompleted++
                }
            }
        }
        return UdsFaults(codes.size, confirmed, pending, lamp, failed, notCompleted)
    }
}
