package knightlink.core.protocol

/** A decoded OBD-II / UDS response from one ECU. */
public sealed interface DiagnosticResponse {
    public val sourceId: Int

    /** [service] is the request service (response SID minus 0x40); [data] is everything after the SID. */
    public class Positive(
        override val sourceId: Int,
        public val service: Int,
        public val data: ByteArray,
    ) : DiagnosticResponse

    public class Negative(
        override val sourceId: Int,
        public val service: Int,
        public val code: Int,
    ) : DiagnosticResponse {
        public val reason: NegativeResponseCode? get() = NegativeResponseCode.of(code)
    }

    public companion object {
        private const val NEGATIVE_SID = 0x7F
        private const val POSITIVE_OFFSET = 0x40

        /** Returns null when the payload is not a diagnostic response. */
        public fun parse(message: IsoTpMessage): DiagnosticResponse? {
            val p = message.payload
            if (p.isEmpty()) return null
            val sid = p[0].toInt() and 0xFF
            return when {
                sid == NEGATIVE_SID ->
                    if (p.size >= 3) Negative(message.sourceId, p[1].toInt() and 0xFF, p[2].toInt() and 0xFF) else null
                sid >= POSITIVE_OFFSET -> Positive(message.sourceId, sid - POSITIVE_OFFSET, p.copyOfRange(1, p.size))
                else -> null
            }
        }
    }
}

/** ISO 14229 negative response codes the app reacts to. Unlisted codes are still surfaced as raw numbers. */
public enum class NegativeResponseCode(public val code: Int) {
    GENERAL_REJECT(0x10),
    SERVICE_NOT_SUPPORTED(0x11),
    SUB_FUNCTION_NOT_SUPPORTED(0x12),
    INCORRECT_MESSAGE_LENGTH(0x13),
    BUSY_REPEAT_REQUEST(0x21),
    CONDITIONS_NOT_CORRECT(0x22),
    REQUEST_OUT_OF_RANGE(0x31),
    SECURITY_ACCESS_DENIED(0x33),
    RESPONSE_PENDING(0x78),
    SERVICE_NOT_SUPPORTED_IN_SESSION(0x7F),
    ;

    public companion object {
        public fun of(code: Int): NegativeResponseCode? = entries.firstOrNull { it.code == code }
    }
}

/** Everything one adapter response contained, after line classification and ISO-TP reassembly. */
public class ElmResponse(
    public val messages: List<DiagnosticResponse>,
    public val statuses: List<ElmStatus>,
    public val protocolErrors: List<String>,
    public val text: List<String>,
)

/**
 * What the layers above an adapter driver need from it. Declared here, not in the driver's module, so
 * discovery and polling code can be tested with a scripted fake and never depends on a concrete adapter.
 */
public interface DiagnosticLink {
    /** Sends one adapter (AT) command and returns the reply lines without the echo. */
    public suspend fun command(text: String): List<String>

    /**
     * Sends a diagnostic request to the ECU with physical request id [requestId].
     *
     * @param expectedResponses when set, the adapter stops waiting after that many replies.
     */
    public suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int? = null): ElmResponse
}

/** Decodes the lines of one adapter response (as produced by [ElmStreamFramer]). Stateless between calls. */
public class ElmResponseDecoder(extendedIds: Boolean) {
    private val parser = ElmLineParser(extendedIds)

    public fun decode(lines: List<String>): ElmResponse {
        val reassembler = IsoTpReassembler()
        val messages = ArrayList<DiagnosticResponse>()
        val statuses = ArrayList<ElmStatus>()
        val errors = ArrayList<String>()
        val text = ArrayList<String>()
        for (line in lines) {
            when (val parsed = parser.parse(line)) {
                is ElmLine.Status -> statuses += parsed.status
                is ElmLine.Text -> text += parsed.text
                is ElmLine.Frame -> when (val result = reassembler.accept(parsed.frame)) {
                    IsoTpResult.Incomplete -> Unit
                    is IsoTpResult.Error -> errors += "${result.sourceId.toString(16).uppercase()}: ${result.reason}"
                    is IsoTpResult.Complete -> {
                        val response = DiagnosticResponse.parse(result.message)
                        if (response != null) messages += response else errors += "not a diagnostic response: $line"
                    }
                }
            }
        }
        return ElmResponse(messages, statuses, errors, text)
    }
}
