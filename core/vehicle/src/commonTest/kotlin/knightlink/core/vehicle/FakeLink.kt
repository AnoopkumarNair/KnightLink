package knightlink.core.vehicle

import knightlink.core.common.hexToBytes
import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import knightlink.core.protocol.ElmStatus
import kotlinx.coroutines.delay
import kotlin.time.Duration

/** What a scripted ECU does with one request. */
sealed interface Script {
    /** Answers with [hex] as the diagnostic payload (SID first), from [from] or from the ECU's own response id. */
    class Reply(val hex: String, val from: Int? = null) : Script

    /** The link itself fails, as a timeout would. */
    class Fail(val error: Exception) : Script
}

/** A scripted ECU: its response id and its replies by request (service + PID as hex). Unlisted requests get `NO DATA`. */
class EcuScript(val responseId: Int, val replies: Map<String, Script>)

/** Reply to a request as the real adapter would give it: payload bytes, SID first. */
fun diagnosticMessage(sourceId: Int, payloadHex: String): DiagnosticResponse {
    val bytes = payloadHex.hexToBytes()
    val sid = bytes[0].toInt() and 0xFF
    return if (sid == 0x7F) {
        DiagnosticResponse.Negative(sourceId, bytes[1].toInt() and 0xFF, bytes[2].toInt() and 0xFF)
    } else {
        DiagnosticResponse.Positive(sourceId, sid - 0x40, bytes.copyOfRange(1, bytes.size))
    }
}

/**
 * A [ProbeLink] driven by a script, with no simulator behind it. Records everything sent so tests can
 * assert on the traffic, and can delay each answer to exercise timing on virtual time.
 */
class FakeLink(
    private val ecus: Map<Int, EcuScript> = emptyMap(),
    private val commandReplies: Map<String, List<String>> = DEFAULT_COMMANDS,
    /** Delay before the answer to the [nth] (1-based) identical request to the same ECU. */
    private val latency: (requestId: Int, payloadHex: String, nth: Int) -> Duration = { _, _, _ -> Duration.ZERO },
    /** Requests for which the ECU stays silent (`NO DATA`) even though a reply is scripted; takes the same arguments as [latency]. */
    private val silentWhen: (requestId: Int, payloadHex: String, nth: Int) -> Boolean = { _, _, _ -> false },
    private val commandError: Exception? = null,
) : ProbeLink {
    val commands = ArrayList<String>()
    val requests = ArrayList<Pair<Int, String>>()

    override suspend fun command(text: String): List<String> {
        commands += text
        commandError?.let { throw it }
        return commandReplies[text] ?: listOf("?")
    }

    override suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int?): ElmResponse {
        val hex = payload.toHex()
        requests += requestId to hex
        require(expectedResponses == 1) { "The probe addresses one ECU, so it should ask for exactly one reply" }
        val nth = requests.count { it == requestId to hex }
        delay(latency(requestId, hex, nth))
        val scripted = if (silentWhen(requestId, hex, nth)) null else ecus[requestId]?.replies?.get(hex)
        return when (val script = scripted) {
            null -> ElmResponse(emptyList(), listOf(ElmStatus.NO_DATA), emptyList(), emptyList())
            is Script.Fail -> throw script.error
            is Script.Reply -> ElmResponse(
                listOf(diagnosticMessage(script.from ?: ecus.getValue(requestId).responseId, script.hex)),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        }
    }

    companion object {
        val DEFAULT_COMMANDS: Map<String, List<String>> = mapOf(
            "ATI" to listOf("ELM327 v2.3"),
            "AT@1" to listOf("OBDII to RS232 Interpreter"),
            "ATRV" to listOf("14.1V"),
            "ATDPN" to listOf("A6"),
        )
    }
}

fun reply(hex: String, from: Int? = null): Script = Script.Reply(hex.filterNot { it.isWhitespace() }, from)

fun String.asciiHex(): String = encodeToByteArray().toHex()
