package knightlink.core.protocol

import knightlink.core.common.hexToBytes
import knightlink.core.common.isHexDigit

/** Status words an ELM327-style adapter can print instead of, or alongside, data. */
public enum class ElmStatus(internal val prefix: String) {
    OK("OK"),
    NO_DATA("NODATA"),
    SEARCHING("SEARCHING"),
    STOPPED("STOPPED"),
    UNABLE_TO_CONNECT("UNABLETOCONNECT"),
    CAN_ERROR("CANERROR"),
    BUS_ERROR("BUS"),
    BUFFER_FULL("BUFFERFULL"),
    DATA_ERROR("DATAERROR"),
    RX_ERROR("RXERROR"),
    FB_ERROR("FBERROR"),
    LV_RESET("LVRESET"),
    UNKNOWN_COMMAND("?"),
}

public sealed interface ElmLine {
    public data class Frame(val frame: CanFrame) : ElmLine
    public data class Status(val status: ElmStatus) : ElmLine
    public data class Text(val text: String) : ElmLine
}

/**
 * Classifies one line of adapter output. Requires headers on (ATH1); spaces are optional.
 *
 * @param extendedIds true when the bus uses 29-bit identifiers, false for 11-bit.
 */
public class ElmLineParser(private val extendedIds: Boolean) {
    private val idDigits = if (extendedIds) 8 else 3

    public fun parse(line: String): ElmLine {
        val compact = line.filterNot { it.isWhitespace() }.uppercase()
        val word = compact.trimStart('<').trimEnd('.')
        ElmStatus.entries.firstOrNull { status ->
            if (status == ElmStatus.UNKNOWN_COMMAND || status == ElmStatus.OK) {
                word == status.prefix
            } else {
                word.startsWith(status.prefix)
            }
        }?.let { return ElmLine.Status(it) }

        val isFrame = compact.length >= idDigits + 2 &&
            compact.length <= idDigits + 16 &&
            (compact.length - idDigits) % 2 == 0 &&
            compact.all { it.isHexDigit() }
        if (!isFrame) return ElmLine.Text(line.trim())

        return ElmLine.Frame(
            CanFrame(
                id = compact.substring(0, idDigits).toInt(16),
                extended = extendedIds,
                data = compact.substring(idDigits).hexToBytes(),
            ),
        )
    }
}

/**
 * Turns the raw byte stream from an adapter into complete responses. A response ends at the `>` prompt
 * and is returned as its non-blank lines. Safe against arbitrary chunking of the input.
 */
public class ElmStreamFramer {
    private val buffer = StringBuilder()

    public fun feed(chunk: ByteArray): List<List<String>> {
        var completed: MutableList<List<String>>? = null
        for (byte in chunk) {
            val c = (byte.toInt() and 0xFF).toChar()
            when (c) {
                '\u0000' -> Unit // ELM documentation: null bytes may appear and must be ignored
                '>' -> {
                    val lines = buffer.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                    buffer.clear()
                    (completed ?: mutableListOf<List<String>>().also { completed = it }).add(lines)
                }
                else -> buffer.append(c)
            }
        }
        return completed ?: emptyList()
    }

    public fun reset() {
        buffer.clear()
    }
}
