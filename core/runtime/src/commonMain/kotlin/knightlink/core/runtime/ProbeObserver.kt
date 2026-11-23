package knightlink.core.runtime

import knightlink.core.protocol.DiagnosticLink
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse

/**
 * Passes every request through to [link] and counts the refusals (negative responses) that come back, by negative response code. A refusal
 * is a final answer: the probe does not retry it. What the count is for is telling a gateway that refuses (`33`, `7F`, `11`) from a car that
 * is silent, when the session has to say why it cannot read a car.
 */
internal class ProbeObserver(private val link: DiagnosticLink) : DiagnosticLink {
    private val codes = LinkedHashMap<Int, Int>()

    /** How many negative responses came back, all codes together. */
    val refusals: Int get() = codes.values.sum()

    /** How many of them were a refusal in the sense of a gateway: security access denied (`33`) or service not supported (`7F`, `11`). */
    val refused: Int get() = codes.filterKeys { it == 0x33 || it == 0x7F || it == 0x11 }.values.sum()

    /** The negative response codes seen and how often, as text for a log (`33 x8, 7F x2`). */
    fun summary(): String = codes.entries.joinToString(", ") { "${it.key.toString(16).uppercase().padStart(2, '0')} x${it.value}" }

    fun reset() {
        codes.clear()
    }

    override suspend fun command(text: String): List<String> = link.command(text)

    override suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int?): ElmResponse {
        val response = link.request(requestId, payload, expectedResponses)
        for (message in response.messages) {
            if (message is DiagnosticResponse.Negative && message.code != PENDING) codes[message.code] = (codes[message.code] ?: 0) + 1
        }
        return response
    }

    private companion object {
        /** Response pending is not a refusal: the unit is working on it. */
        const val PENDING = 0x78
    }
}
