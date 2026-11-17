package knightlink.core.recorder

/**
 * The comparison key of a command: the text up to and including the carriage return, ignoring whitespace and case.
 * `"at z\r"`, `"ATZ\r"` and `"ATZ"` are the same command to an ELM327.
 */
internal fun commandKey(text: CharSequence): String = text.toString().filterNot { it.isWhitespace() }.uppercase()

/** One byte as the character an adapter's text protocol treats it as. Adapters speak ASCII; anything else is kept distinguishable. */
internal fun Byte.toAdapterChar(): Char = (toInt() and 0xFF).toChar()

internal fun List<ByteArray>.concat(): ByteArray {
    val result = ByteArray(sumOf { it.size })
    var offset = 0
    for (part in this) {
        part.copyInto(result, offset)
        offset += part.size
    }
    return result
}

/** Whether a command key (see [commandKey]) is a diagnostic request: hex digits only, as the adapter reads it. AT and ST commands are not. */
internal fun isDiagnosticRequest(key: String): Boolean = key.isNotEmpty() && key.all { it in '0'..'9' || it in 'A'..'F' }

/**
 * What a recorded answer belongs to, for [ReplayMode.BY_COMMAND]. A diagnostic request goes to whichever ECU the last
 * `ATSH` selected, so the same request text asked of two ECUs is two different keys. Every other command (`AT...`, `ST...`)
 * is answered by the adapter itself, whatever header is selected, so it is keyed by its text alone ([header] is then null).
 */
internal data class ExchangeKey(val header: String?, val command: String) {
    /** The form used in reports: `7E1:010C1` for a request sent to 7E1, and just the text when no header applies. */
    override fun toString(): String = if (header == null) command else "$header:$command"

    companion object {
        /**
         * The key of [command] (already through [commandKey]) when [selectedHeader] is selected. A diagnostic request is keyed without its
         * response-count digit (an odd number of hex digits): the digit only says when the adapter may stop waiting, so a replay of a
         * session recorded with it can answer the same request sent without it, as the stack now does for replies longer than one frame.
         */
        fun of(command: String, selectedHeader: String?): ExchangeKey =
            if (isDiagnosticRequest(command)) {
                ExchangeKey(
                    selectedHeader,
                    command.let {
                        if (it.length % 2 ==
                            1
                        ) {
                            it.dropLast(1)
                        } else {
                            it
                        }
                    },
                )
            } else {
                ExchangeKey(null, command)
            }
    }
}

/** Whether an adapter's answer holds an `OK` line; echo, prompt and blank lines around it do not matter. */
internal fun isOkResponse(response: ByteArray): Boolean {
    val text = CharArray(response.size) { response[it].toAdapterChar() }.concatToString()
    return text.split('\r', '\n', '>').any { line -> line.filterNot { it.isWhitespace() }.uppercase() == "OK" }
}

/**
 * Follows which CAN identifier the adapter sends requests to, from the commands it was given and how it answered, the
 * way the adapter itself does. Used both on a recording and on a replay, so the two agree on what a request meant.
 *
 * `ATSH<id>` selects a header, but only when the adapter said `OK`: an `ATSH` it answered `?` changed nothing. `ATZ` and
 * `ATD` put the adapter back to its defaults, which is no selected header (a request is then sent to the functional
 * address); that does not depend on the answer, because an adapter that is resetting does not say `OK`.
 */
internal class HeaderTracker {
    /** The selected header as upper-case hex without whitespace, or null when none is selected. */
    var selected: String? = null
        private set

    /** Applies the effect of [command] (through [commandKey]); [answeredOk] is only asked for an `ATSH`. */
    fun observe(command: String, answeredOk: () -> Boolean) {
        when {
            // Reset, defaults and warm start all drop the selected header on a real adapter.
            command == "ATZ" || command == "ATD" || command == "ATWS" -> selected = null
            command.startsWith(SELECT_HEADER) -> {
                val id = command.removePrefix(SELECT_HEADER)
                if (id.isNotEmpty() && isDiagnosticRequest(id) && answeredOk()) selected = id
            }
        }
    }

    private companion object {
        const val SELECT_HEADER = "ATSH"
    }
}

/**
 * A command the consumer sent and the response chunks that followed it, as indices into [Recording.events].
 *
 * @param key the command text, through [commandKey].
 * @param header the header the adapter had selected when the command was sent, as [HeaderTracker] reports it.
 */
internal class RecordedExchange(val key: String, val txIndex: Int, header: String?) {
    val rxIndices = ArrayList<Int>()

    /** What [ReplayMode.BY_COMMAND] matches a request against. */
    val replayKey: ExchangeKey = ExchangeKey.of(key, header)
}

/**
 * Splits a recording into exchanges. The `tx` bytes are read as a stream, so a command that was written in
 * several pieces is one command, and the command ends at its `\r`. The response is every `rx` event after the
 * `tx` event that completed the command and before the next `tx` event.
 *
 * An adapter is half-duplex, so that is the whole response. Chunks that arrive before the first command, and
 * unterminated text at the end, belong to no exchange. If one write held several commands, the response is
 * attributed to the last one: nothing marks where one command's answer ends in that case, so the earlier ones count as
 * unanswered (an `ATSH` among them selects nothing).
 *
 * Each exchange also records the header selected when it was sent. Everything after an `eof` event is left out: those
 * writes were made to a link that was already gone and never reached the adapter, so they are not exchanges, and an
 * unanswered one would otherwise be replayed as a silent response.
 */
internal fun indexExchanges(events: List<RecordingEvent>): List<RecordedExchange> {
    val exchanges = ArrayList<RecordedExchange>()
    val headers = HeaderTracker()
    val pending = StringBuilder()
    var open: RecordedExchange? = null

    // What a command did to the selected header is known only once its answer is complete, which is when the next
    // command starts (or the link ends).
    fun settle() {
        open?.let { done ->
            headers.observe(done.key) { isOkResponse(done.rxIndices.map { events[it].bytes }.concat()) }
        }
        open = null
    }

    loop@ for ((index, event) in events.withIndex()) {
        when (event.kind) {
            EventKind.TX -> {
                settle()
                for (byte in event.bytes) {
                    val c = byte.toAdapterChar()
                    if (c == '\r') {
                        settle()
                        open = RecordedExchange(commandKey(pending), index, headers.selected).also { exchanges += it }
                        pending.clear()
                    } else {
                        pending.append(c)
                    }
                }
            }
            EventKind.RX -> open?.rxIndices?.add(index)
            EventKind.CLOSE, EventKind.ERROR -> settle()
            EventKind.EOF -> {
                settle()
                break@loop
            }
            EventKind.OPEN, EventKind.EVENT, EventKind.EXTERNAL -> Unit
        }
    }
    return exchanges
}

/** Printable form of bytes for messages: `\r` and `\n` spelled out, other non-printable bytes as `\xNN`. */
internal fun renderBytes(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String = buildString {
    for (i in from until to) {
        val c = bytes[i].toAdapterChar()
        when {
            c == '\r' -> append("\\r")
            c == '\n' -> append("\\n")
            c == '\\' -> append("\\\\")
            c.code in PRINTABLE -> append(c)
            else -> append("\\x").append(c.code.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

private val PRINTABLE = 0x20..0x7E
