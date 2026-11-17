package knightlink.core.recorder

import knightlink.core.common.toHex
import knightlink.core.protocol.CanFrame
import knightlink.core.protocol.ElmLine
import knightlink.core.protocol.ElmLineParser
import knightlink.core.protocol.IsoTpReassembler
import knightlink.core.protocol.IsoTpResult

/**
 * The scrubbed copy of a recording and what the scrubber could not handle.
 *
 * @param warnings one entry per vehicle-identification response that was replaced by `NO DATA` because it could not be
 *   read with confidence. Never contains a VIN.
 */
public data class ScrubResult(val recording: Recording, val warnings: List<String>)

/**
 * Makes a recording safe to share by removing the vehicle identification number (ADR 0002: no full VIN leaves the
 * device). A recording is the raw adapter conversation, so a VIN is in it as hex digits printed by the adapter, split
 * over CAN frames, ISO-TP frames and arbitrary transport chunks, not as a string that could be searched for.
 *
 * What it does, and promises:
 * - For every exchange whose command is service 09 PID 02 (`0902`, optionally followed by the response-count digit),
 *   the rx bytes of the response are read as adapter lines, parsed with [ElmLineParser] (11-bit identifiers; spaces on
 *   or off; headers on), reassembled per ECU with ISO-TP, and the 17 VIN characters after `49 02 01` are located.
 *   The first 3 (the manufacturer code) are kept, the other 14 become `0`. The hex digits are rewritten in place: line
 *   structure, spacing, identifiers, PCI bytes, the number of bytes in every rx chunk and every timestamp stay as recorded.
 * - If such a response cannot be read with confidence (unknown line shape such as headers off or 29-bit identifiers,
 *   an incomplete or inconsistent ISO-TP message, a VIN that is not 17 printable characters), it is not guessed at:
 *   the whole response becomes a single rx event `NO DATA\r\r>` at the time of the response's first chunk, and a
 *   warning is returned. Failing closed means a VIN is never left in a response the scrubber did not understand.
 * - Every VIN found that way is also removed wherever it appears as ASCII text: in tx bytes (for example a command that
 *   contains it), in rx bytes (an echo, even when it is split over chunks), in error messages and in the header. The
 *   replacement has the same length, so chunk sizes do not change.
 *
 * What it does not promise:
 * - Nothing else is looked for. A VIN delivered another way (for example a UDS read of data identifier F190, or
 *   headers-off output on a `0902` exchange that was not recognised as one) is not found, and a VIN that was never
 *   in a recognised `0902` response is not known to the scrubber, so its text copies are not found either.
 * - Other identifying data is untouched: ECU names, calibration ids, timing, the free-text header note, and
 *   anything else the adapter said. Look at a recording before it leaves the device.
 * - Replaying a scrubbed recording gives a VIN of manufacturer code plus fourteen zeros; no check digit is valid.
 */
public object RecordingScrubber {
    private const val VIN_LENGTH = 17
    private const val WMI_LENGTH = 3
    private const val PAYLOAD_LENGTH = 3 + VIN_LENGTH
    private val vinRequest = Regex("0902[0-9A-F]?")
    private val noData = "NO DATA\r\r>".encodeToByteArray()

    /** @return the scrubbed copy; [Recording.truncated] and the order of events are kept. */
    public fun scrub(recording: Recording): ScrubResult {
        val events = recording.events.toMutableList<RecordingEvent?>()
        val warnings = ArrayList<String>()
        val vins = LinkedHashSet<String>()

        for (exchange in indexExchanges(recording.events)) {
            if (!vinRequest.matches(exchange.key) || exchange.rxIndices.isEmpty()) continue
            val sizes = exchange.rxIndices.map { recording.events[it].payload.length / 2 }
            val response = exchange.rxIndices.map { recording.events[it].bytes }.concat()
            when (val reading = readVinResponse(exchange.key, response)) {
                Reading.Untouched -> Unit
                is Reading.Found -> {
                    for (i in reading.digitPositions.indices step 2) {
                        response[reading.digitPositions[i]] = '3'.code.toByte()
                        response[reading.digitPositions[i + 1]] = '0'.code.toByte()
                    }
                    vins += reading.vins
                    var offset = 0
                    exchange.rxIndices.forEachIndexed { i, eventIndex ->
                        events[eventIndex] = RecordingEvent.rx(recording.events[eventIndex].timeMs, response.copyOfRange(offset, offset + sizes[i]))
                        offset += sizes[i]
                    }
                }
                Reading.Unreadable -> {
                    val first = exchange.rxIndices.first()
                    events[first] = RecordingEvent.rx(recording.events[first].timeMs, noData)
                    exchange.rxIndices.drop(1).forEach { events[it] = null }
                    warnings += "Event #${exchange.txIndex}: the response to ${exchange.key} could not be read with confidence and was replaced by NO DATA"
                }
            }
        }

        sweepBytes(events, EventKind.TX, vins)
        sweepBytes(events, EventKind.RX, vins)
        val header = RecordingHeader(recording.header.adapter?.let { it.scrubText(vins) }, recording.header.note?.let { it.scrubText(vins) })
        val scrubbed = events.filterNotNull().map {
            if (it.kind == EventKind.ERROR ||
                it.kind == EventKind.EVENT
            ) {
                it.copy(payload = it.payload.scrubText(vins))
            } else {
                it
            }
        }
        return ScrubResult(Recording(header, scrubbed, recording.truncated), warnings)
    }

    // The scrubbed form of a VIN: manufacturer code kept, the rest zeros, same length.
    private fun String.placeholder(): String = take(WMI_LENGTH) + "0".repeat(length - WMI_LENGTH)

    private fun String.scrubText(vins: Set<String>): String = vins.fold(this) { text, vin -> text.replace(vin, vin.placeholder(), ignoreCase = true) }

    /**
     * Replaces every occurrence of a known VIN in the bytes of all [kind] events, looking at them as one stream so that
     * text split between two chunks is found. Replacement is in place, so every event keeps its size.
     */
    private fun sweepBytes(events: MutableList<RecordingEvent?>, kind: EventKind, vins: Set<String>) {
        if (vins.isEmpty()) return
        val indices = events.indices.filter { events[it]?.kind == kind }
        if (indices.isEmpty()) return
        val parts = indices.map { events[it]!!.bytes }
        val stream = parts.concat()
        val text = CharArray(stream.size) { stream[it].toAdapterChar().uppercaseChar() }.concatToString()
        var changed = false
        for (vin in vins) {
            var at = text.indexOf(vin)
            while (at >= 0) {
                vin.placeholder().forEachIndexed { i, c -> stream[at + i] = c.code.toByte() }
                changed = true
                at = text.indexOf(vin, at + vin.length)
            }
        }
        if (!changed) return
        var offset = 0
        indices.forEachIndexed { i, eventIndex ->
            val old = events[eventIndex]!!
            events[eventIndex] = RecordingEvent(old.timeMs, kind, stream.copyOfRange(offset, offset + parts[i].size).toHex())
            offset += parts[i].size
        }
    }

    // Reading a response ------------------------------------------------------------------------------------

    private sealed interface Reading {
        /** Nothing in it that could hold a VIN. */
        data object Untouched : Reading

        /** The VINs it holds, and the positions in the response bytes of every hex digit that must change (two per byte). */
        class Found(val vins: List<String>, val digitPositions: List<Int>) : Reading

        data object Unreadable : Reading
    }

    /** One line of adapter output with the position of each of its characters in the response bytes. NUL bytes are dropped, as the framer drops them. */
    private class Line(val text: String, val positions: IntArray)

    private class Frame(val id: Int, val data: ByteArray, val digitPositions: IntArray)

    private class Assembly(val total: Int) {
        val payload = ByteArray(total)
        val digitPositions = IntArray(total * 2)
        var filled = 0
        var nextSequence = 1
    }

    private class Message(val sourceId: Int, val payload: ByteArray, val digitPositions: IntArray)

    private fun readVinResponse(command: String, response: ByteArray): Reading {
        val frames = ArrayList<Frame>()
        val parser = ElmLineParser(extendedIds = false)
        for (line in splitLines(response)) {
            val compact = StringBuilder()
            val digits = ArrayList<Int>()
            line.text.forEachIndexed { i, c ->
                if (!c.isWhitespace()) {
                    compact.append(c)
                    digits += line.positions[i]
                }
            }
            // The adapter echoes the command with echo on; the driver ignores that line, and so does this.
            if (compact.toString().uppercase() == command) continue
            when (val parsed = parser.parse(line.text)) {
                is ElmLine.Status -> Unit
                // Anything that is not a status word and not a frame might be data in a layout this does not know.
                is ElmLine.Text -> return Reading.Unreadable
                is ElmLine.Frame -> {
                    val data = parsed.frame.data
                    // Three identifier digits, then two per data byte; the positions below rely on it.
                    if (compact.length != ID_DIGITS + 2 * data.size) return Reading.Unreadable
                    frames += Frame(parsed.frame.id, data, digits.drop(ID_DIGITS).toIntArray())
                }
            }
        }
        val messages = reassemble(frames) ?: return Reading.Unreadable
        if (!agreesWithReference(frames, messages)) return Reading.Unreadable

        val vins = ArrayList<String>()
        val positions = ArrayList<Int>()
        for (message in messages) {
            val p = message.payload
            if (p.size < 2 || p[0] != POSITIVE_SERVICE_09 || p[1] != PID_VIN) continue
            // 49 02 <item count 1> <17 characters>: anything else is not understood well enough to rewrite.
            if (p.size != PAYLOAD_LENGTH || p[2] != ONE_ITEM) return Reading.Unreadable
            val characters = p.copyOfRange(3, PAYLOAD_LENGTH)
            if (characters.any { it.toInt() !in PRINTABLE }) return Reading.Unreadable
            vins += characters.decodeToString()
            for (i in 3 + WMI_LENGTH until PAYLOAD_LENGTH) {
                positions += message.digitPositions[2 * i]
                positions += message.digitPositions[2 * i + 1]
            }
        }
        return if (vins.isEmpty()) Reading.Untouched else Reading.Found(vins, positions)
    }

    private fun splitLines(bytes: ByteArray): List<Line> {
        val lines = ArrayList<Line>()
        val text = StringBuilder()
        val positions = ArrayList<Int>()
        fun flush() {
            if (text.isNotBlank()) lines += Line(text.toString(), positions.toIntArray())
            text.clear()
            positions.clear()
        }
        for ((i, byte) in bytes.withIndex()) {
            when (val c = byte.toAdapterChar()) {
                '\r', '\n', '>' -> flush()
                '\u0000' -> Unit
                else -> {
                    text.append(c)
                    positions += i
                }
            }
        }
        flush()
        return lines
    }

    /**
     * ISO 15765-2 reassembly that remembers where every payload byte's two hex digits sit. Returns null when the frames
     * do not make complete, consistent messages. [IsoTpReassembler] is the reference for what the frames mean; this
     * repeats its rules only to keep the positions, and [agreesWithReference] checks that the two agree.
     */
    private fun reassemble(frames: List<Frame>): List<Message>? {
        val pending = HashMap<Int, Assembly>()
        val messages = ArrayList<Message>()

        fun take(assembly: Assembly, frame: Frame, from: Int, count: Int) {
            for (j in 0 until count) {
                assembly.payload[assembly.filled + j] = frame.data[from + j]
                assembly.digitPositions[2 * (assembly.filled + j)] = frame.digitPositions[2 * (from + j)]
                assembly.digitPositions[2 * (assembly.filled + j) + 1] = frame.digitPositions[2 * (from + j) + 1]
            }
            assembly.filled += count
        }

        for (frame in frames) {
            val data = frame.data
            val pci = data[0].toInt() and 0xFF
            val assembly: Assembly
            when (pci ushr 4) {
                PCI_SINGLE -> {
                    val length = pci and 0x0F
                    if (length == 0 || data.size < 1 + length) return null
                    pending.remove(frame.id)
                    assembly = Assembly(length).also { take(it, frame, 1, length) }
                }
                PCI_FIRST -> {
                    if (data.size < 2) return null
                    val total = ((pci and 0x0F) shl 8) or (data[1].toInt() and 0xFF)
                    if (total == 0) return null
                    assembly = Assembly(total).also { take(it, frame, 2, minOf(total, data.size - 2)) }
                    pending[frame.id] = assembly
                }
                PCI_CONSECUTIVE -> {
                    assembly = pending[frame.id] ?: return null
                    if (pci and 0x0F != assembly.nextSequence) return null
                    take(assembly, frame, 1, minOf(assembly.total - assembly.filled, data.size - 1))
                    assembly.nextSequence = (assembly.nextSequence + 1) and 0x0F
                }
                PCI_FLOW_CONTROL -> continue
                else -> return null
            }
            if (assembly.filled == assembly.total) {
                pending.remove(frame.id)
                messages += Message(frame.id, assembly.payload, assembly.digitPositions)
            }
        }
        return if (pending.isEmpty()) messages else null
    }

    private fun agreesWithReference(frames: List<Frame>, messages: List<Message>): Boolean {
        val reassembler = IsoTpReassembler()
        val reference = ArrayList<Pair<Int, ByteArray>>()
        for (frame in frames) {
            when (val result = reassembler.accept(CanFrame(frame.id, extended = false, data = frame.data))) {
                IsoTpResult.Incomplete -> Unit
                is IsoTpResult.Error -> return false
                is IsoTpResult.Complete -> reference += result.message.sourceId to result.message.payload
            }
        }
        return reference.size == messages.size &&
            reference.zip(messages).all { (ref, mine) -> ref.first == mine.sourceId && ref.second.contentEquals(mine.payload) }
    }

    private const val ID_DIGITS = 3
    private const val POSITIVE_SERVICE_09: Byte = 0x49
    private const val PID_VIN: Byte = 0x02
    private const val ONE_ITEM: Byte = 0x01
    private const val PCI_SINGLE = 0
    private const val PCI_FIRST = 1
    private const val PCI_CONSECUTIVE = 2
    private const val PCI_FLOW_CONTROL = 3
    private val PRINTABLE = 0x20..0x7E
}
