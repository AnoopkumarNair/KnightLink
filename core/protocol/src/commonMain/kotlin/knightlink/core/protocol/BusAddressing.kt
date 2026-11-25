package knightlink.core.protocol

/**
 * Encapsulates CAN diagnostic addressing (11-bit vs 29-bit normal fixed addressing per ISO 15765-4 / ISO 14229) so
 * [Elm327Driver], [CapabilityProbe], and scan/runtime modules stay free of raw protocol bit-twiddling.
 */
public sealed interface BusAddressing {
    /** True when frames on the wire carry 29-bit (8-hex-digit) CAN identifiers. */
    public val extendedIds: Boolean

    /** Functional broadcast CAN identifier used for initial `01 00` discovery. */
    public val functionalRequestId: Int

    /** Default OBD response filter base and mask commands sent to restore normal OBD reception. */
    public val defaultFilterCommands: List<String>

    /** Wide receive filter commands to hear body/chassis ECUs outside the standard OBD response range. */
    public val wideFilterCommands: List<String>

    /** Commands sent to configure the adapter's transmit header for [requestId]. */
    public fun selectHeaderCommands(requestId: Int): List<String>

    /** Commands sent to configure receive filter + ISO-TP flow control for a physical [requestId] and [responseId]. */
    public fun flowControlCommands(requestId: Int, responseId: Int): List<String>

    /** Formats a CAN identifier in uppercase hex (3 digits for 11-bit, 8 digits for 29-bit). */
    public fun formatId(canId: Int): String

    /** Maps a physical [requestId] to its expected [responseId], or null when not deterministic. */
    public fun expectedResponseId(requestId: Int): Int?

    /** Maps a [responseId] observed from a functional broadcast back to its physical request CAN ID, or null if unknown. */
    public fun requestIdForResponse(responseId: Int): Int?

    /** Standard 11-bit CAN addressing (ISO 15765-4: `7DF` functional, `7E0..7E7` OBD, `700..7FF` diagnostic block). */
    public data object Can11Bit : BusAddressing {
        override val extendedIds: Boolean = false
        override val functionalRequestId: Int = 0x7DF
        override val defaultFilterCommands: List<String> = listOf("ATAR", "ATCM7F8", "ATCF7E8", "ATFCSM0")
        override val wideFilterCommands: List<String> = listOf("ATCF700", "ATCM700")

        override fun selectHeaderCommands(requestId: Int): List<String> =
            listOf("ATSH${formatId(requestId)}")

        override fun flowControlCommands(requestId: Int, responseId: Int): List<String> =
            listOf("ATCRA${formatId(responseId)}", "ATFCSH${formatId(requestId)}", "ATFCSD300000", "ATFCSM1")

        override fun formatId(canId: Int): String =
            canId.toString(16).uppercase().padStart(3, '0')

        override fun expectedResponseId(requestId: Int): Int? =
            if (requestId in 0x700..0x7F7 && requestId != functionalRequestId) requestId + 8 else null

        override fun requestIdForResponse(responseId: Int): Int? =
            if (responseId in 0x708..0x7FF) responseId - 8 else null
    }

    /**
     * Standard 29-bit CAN normal fixed addressing (ISO 15765-4: `18DB33F1` functional, `18DA<ecu>F1` physical request,
     * `18DAF1<ecu>` physical response).
     */
    public data object Can29Bit : BusAddressing {
        public const val PRIORITY_PREFIX: Int = 0x18
        public const val TESTER_ADDRESS: Int = 0xF1
        public const val FUNCTIONAL_ID: Int = 0x18DB33F1

        override val extendedIds: Boolean = true
        override val functionalRequestId: Int = FUNCTIONAL_ID
        override val defaultFilterCommands: List<String> = listOf("ATAR", "ATCM1FFFFF00", "ATCF18DAF100", "ATFCSM0")
        override val wideFilterCommands: List<String> = listOf("ATCF18DAF100", "ATCM1FFFFF00")

        override fun selectHeaderCommands(requestId: Int): List<String> {
            // ELM327 ATSH takes the lower 24 bits (6 hex digits); ATCP sets the priority byte (18).
            val lower24 = (requestId and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
            val priority = ((requestId ushr 24) and 0xFF).toString(16).uppercase().padStart(2, '0')
            return listOf("ATCP$priority", "ATSH$lower24")
        }

        override fun flowControlCommands(requestId: Int, responseId: Int): List<String> =
            listOf("ATCRA${formatId(responseId)}", "ATFCSH${formatId(requestId)}", "ATFCSD300000", "ATFCSM1")

        override fun formatId(canId: Int): String =
            (canId.toLong() and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')

        override fun expectedResponseId(requestId: Int): Int? {
            if ((requestId and MASK_REQUEST) != BASE_REQUEST) return null
            val ecu = (requestId ushr 8) and 0xFF
            return BASE_RESPONSE or ecu
        }

        override fun requestIdForResponse(responseId: Int): Int? {
            if ((responseId and MASK_RESPONSE) != BASE_RESPONSE) return null
            val ecu = responseId and 0xFF
            return BASE_REQUEST or (ecu shl 8)
        }

        public fun physicalRequestId(ecuByte: Int): Int = BASE_REQUEST or ((ecuByte and 0xFF) shl 8)

        private const val BASE_REQUEST = 0x18DA00F1
        private const val MASK_REQUEST = 0xFFFF00FF.toInt()
        private const val BASE_RESPONSE = 0x18DAF100
        private const val MASK_RESPONSE = 0xFFFFFF00.toInt()
    }
}
