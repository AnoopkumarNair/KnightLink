package knightlink.core.recorder

import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponseDecoder
import knightlink.core.protocol.ElmStreamFramer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingScrubberTest {
    private val vin = "SIM1234567890ABCD"
    private val scrubbedVin = "SIM00000000000000"

    /** ISO-TP segmentation written independently of the scrubber: first frame, then consecutive frames. */
    private fun segment(payload: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        frames += byteArrayOf((0x10 or (payload.size shr 8)).toByte(), payload.size.toByte()) + payload.copyOfRange(0, 6)
        var offset = 6
        var sequence = 1
        while (offset < payload.size) {
            val end = minOf(payload.size, offset + 7)
            frames += byteArrayOf((0x20 or sequence).toByte()) + payload.copyOfRange(offset, end)
            offset = end
            sequence++
        }
        return frames
    }

    private fun vinPayload(text: String) = byteArrayOf(0x49, 0x02, 0x01) + text.encodeToByteArray()

    private fun hex(bytes: ByteArray, spaces: Boolean) =
        bytes.joinToString(if (spaces) " " else "") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }

    /** The lines an adapter with headers on prints for one or more ECUs' multi-frame replies, frames interleaved round-robin. */
    private fun response(vararg replies: Pair<Int, String>, spaces: Boolean, eol: String = "\r"): String {
        val perEcu = replies.map { (id, text) ->
            segment(vinPayload(text)).map { frame ->
                id.toString(16).uppercase() + (if (spaces) " " else "") +
                    hex(frame, spaces)
            }
        }
        val lines = ArrayList<String>()
        for (row in 0 until perEcu.maxOf { it.size }) perEcu.forEach { frames -> frames.getOrNull(row)?.let(lines::add) }
        return lines.joinToString("") { it + eol } + eol + ">"
    }

    /** Cuts [text] into chunks of the given sizes, repeating the pattern; the cuts fall inside ids, byte pairs and line ends. */
    private fun chunks(text: String, pattern: List<Int>): List<ByteArray> {
        val bytes = text.encodeToByteArray()
        val result = ArrayList<ByteArray>()
        var offset = 0
        var i = 0
        while (offset < bytes.size) {
            val size = minOf(pattern[i++ % pattern.size], bytes.size - offset)
            result += bytes.copyOfRange(offset, offset + size)
            offset += size
        }
        return result
    }

    private fun session(command: String, responseChunks: List<ByteArray>, echo: String = ""): Recording {
        val events = ArrayList<RecordingEvent>()
        events += RecordingEvent.open(0)
        events += tx(10, "ATSH7E0\r")
        events += rx(40, "OK\r\r>")
        events += tx(100, command)
        var time = 140L
        responseChunks.forEachIndexed { i, chunk ->
            events += RecordingEvent.rx(time, if (i == 0) echo.encodeToByteArray() + chunk else chunk)
            time += 3
        }
        events += tx(400, "0100\r")
        events += rx(450, "7E8 06 41 00 BE 3F A8 13\r\r>")
        events += RecordingEvent.close(900)
        return Recording(RecordingHeader("adapters/x", "a note"), events)
    }

    /** What the framer and decoder of the real stack make of the rx bytes of [recording], one entry per adapter response. */
    private fun decodedPayloads(recording: Recording): List<List<ByteArray>> {
        val framer = ElmStreamFramer()
        val decoder = ElmResponseDecoder(extendedIds = false)
        return recording.events.filter { it.kind == EventKind.RX }
            .flatMap { framer.feed(it.bytes) }
            .map { lines -> decoder.decode(lines).messages.filterIsInstance<DiagnosticResponse.Positive>().map { byteArrayOf(0x49) + it.data } }
    }

    private fun rxBytes(recording: Recording): String = recording.events.filter { it.kind == EventKind.RX }.joinToString("") { it.bytes.decodeToString() }

    private fun assertEverythingButTheVinIsUnchanged(original: Recording, scrubbed: Recording) {
        assertEquals(original.header, scrubbed.header)
        assertEquals(original.events.map { it.kind to it.timeMs }, scrubbed.events.map { it.kind to it.timeMs })
        assertEquals(original.events.map { it.bytesLengthOrZero() }, scrubbed.events.map { it.bytesLengthOrZero() }, "chunk sizes")
        assertEquals(original.events.filter { it.kind == EventKind.TX }, scrubbed.events.filter { it.kind == EventKind.TX })
    }

    private fun RecordingEvent.bytesLengthOrZero() = if (kind.carriesBytes) payload.length / 2 else 0

    @Test
    fun spacesOnReplacesAllButTheManufacturerCodeAndKeepsEveryOtherByteAndChunkSize() {
        val text = response(0x7E8 to vin, spaces = true)
        val original = session("09021\r", chunks(text, listOf(5, 1, 9, 2, 13, 3)), echo = "09021\r")
        val result = RecordingScrubber.scrub(original)

        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertEverythingButTheVinIsUnchanged(original, result.recording)
        // The oracle: the adapter output for the scrubbed VIN, built independently, cut at the same places.
        val expected = "09021\r" + response(0x7E8 to scrubbedVin, spaces = true)
        assertEquals("OK\r\r>" + expected + "7E8 06 41 00 BE 3F A8 13\r\r>", rxBytes(result.recording))
        val payloads = decodedPayloads(result.recording)
        assertEquals("49 02 01 " + hex(scrubbedVin.encodeToByteArray(), true), hex(payloads[1].single(), true))
        // The first continuation frame held VIN characters 4 to 10.
        assertFalse(rxBytes(result.recording).contains("21 31 32 33"))
    }

    @Test
    fun spacesOffAndLinefeedsWithAwkwardChunksIsScrubbedTheSame() {
        val text = response(0x7E8 to vin, spaces = false, eol = "\r\n")
        val original = session("0902\r", chunks(text, listOf(1, 3, 7, 2, 11)))
        val result = RecordingScrubber.scrub(original)

        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertEverythingButTheVinIsUnchanged(original, result.recording)
        val scrubbedRx = result.recording.events.filter { it.kind == EventKind.RX }
        val vinReply = scrubbedRx.drop(1).dropLast(1).joinToString("") { it.bytes.decodeToString() }
        assertEquals(response(0x7E8 to scrubbedVin, spaces = false, eol = "\r\n"), vinReply)
        assertEquals("49 02 01 " + hex(scrubbedVin.encodeToByteArray(), true), hex(decodedPayloads(result.recording)[1].single(), true))
    }

    @Test
    fun theWholeResponseIsRewrittenWhateverTheChunkingIs() {
        val text = response(0x7E8 to vin, spaces = true)
        // Every chunk size from 1 to 25: the VIN digits end up split at every possible place.
        for (size in 1..25) {
            val original = session("09021\r", chunks(text, listOf(size)))
            val result = RecordingScrubber.scrub(original)
            assertTrue(result.warnings.isEmpty(), "chunk size $size: ${result.warnings}")
            assertEverythingButTheVinIsUnchanged(original, result.recording)
            assertEquals("49 02 01 " + hex(scrubbedVin.encodeToByteArray(), true), hex(decodedPayloads(result.recording)[1].single(), true), "chunk size $size")
        }
    }

    @Test
    fun everyEcuThatReportsAVinIsScrubbedWhenTheirFramesAreInterleaved() {
        val other = "SIN9876543210WXYZ"
        val text = response(0x7E8 to vin, 0x7E9 to other, spaces = true)
        val original = session("09022\r", chunks(text, listOf(6, 4)))
        val result = RecordingScrubber.scrub(original)
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        val payloads = decodedPayloads(result.recording)[1].map { hex(it, true) }
        assertEquals(
            listOf("49 02 01 " + hex(scrubbedVin.encodeToByteArray(), true), "49 02 01 " + hex("SIN00000000000000".encodeToByteArray(), true)),
            payloads,
        )
        assertFalse(rxBytes(result.recording).contains("7E9 21 39"), "second ECU's original continuation frame is gone")
    }

    @Test
    fun aRepeatedRequestIsScrubbedEachTime() {
        val first = session("09021\r", chunks(response(0x7E8 to vin, spaces = true), listOf(8)))
        val second = session("09021\r", chunks(response(0x7E8 to vin, spaces = true), listOf(11)))
        val joined = Recording(first.header, first.events + second.events.map { RecordingEvent(it.timeMs + 1000, it.kind, it.payload) })
        val result = RecordingScrubber.scrub(joined)
        assertTrue(result.warnings.isEmpty())
        assertEquals(2, decodedPayloads(result.recording).count { it.any { p -> p.size == 20 } })
        assertFalse(rxBytes(result.recording).contains("21 31 32 33"), "the continuation frames no longer carry VIN characters")
    }

    @Test
    fun aResponseThatIsNotInTheKnownLayoutBecomesNoDataAndIsReported() {
        // Headers off: the adapter prints a length and numbered rows, which the parser does not read as frames.
        val text = "014\r0: 49 02 01 53 49 4D\r1: 31 32 33 34 35 36 37\r2: 38 39 30 41 42 43 44\r\r>"
        val original = session("09021\r", chunks(text, listOf(10)))
        val result = RecordingScrubber.scrub(original)

        assertEquals(1, result.warnings.size)
        assertTrue("09021" in result.warnings.single(), result.warnings.single())
        val rx = result.recording.events.filter { it.kind == EventKind.RX }
        assertEquals(listOf("OK\r\r>", "NO DATA\r\r>", "7E8 06 41 00 BE 3F A8 13\r\r>"), rx.map { it.bytes.decodeToString() })
        // The replacement keeps the time of the first chunk of the response it replaces.
        assertEquals(140L, rx[1].timeMs)
        assertFalse(rxBytes(result.recording).contains("53 49 4D"))
    }

    @Test
    fun anIncompleteMessageBecomesNoDataToo() {
        // Two of the three frames: the VIN is half there and cannot be located with confidence.
        val full = response(0x7E8 to vin, spaces = true)
        val cut = full.lines().take(2).joinToString("\r") + "\r\r>"
        val original = session("09021\r", chunks(cut.replace("\n", ""), listOf(9)))
        val result = RecordingScrubber.scrub(original)
        assertEquals(1, result.warnings.size)
        assertFalse(rxBytes(result.recording).contains("7E8 21"))
    }

    @Test
    fun aVinInTheWrongShapeIsNotTrusted() {
        // 49 02 01 followed by 18 characters.
        val long = "SIM1234567890ABCDE"
        val original = session("09021\r", chunks(response(0x7E8 to long, spaces = true), listOf(12)))
        val result = RecordingScrubber.scrub(original)
        assertEquals(1, result.warnings.size)
        assertFalse(rxBytes(result.recording).contains("7E8 21"))
    }

    @Test
    fun noDataAndNegativeResponsesToTheVinRequestAreLeftAlone() {
        for (reply in listOf("NO DATA\r\r>", "7E8 03 7F 09 12\r\r>", "SEARCHING...\r\rNO DATA\r\r>")) {
            val original = session("09021\r", chunks(reply, listOf(4)))
            val result = RecordingScrubber.scrub(original)
            assertTrue(result.warnings.isEmpty(), "$reply: ${result.warnings}")
            assertEquals(original, result.recording)
        }
    }

    @Test
    fun otherRequestsAreNotTouched() {
        val original = session("0100\r", chunks("7E8 06 41 00 BE 3F A8 13\r\r>", listOf(5)))
        assertEquals(original, RecordingScrubber.scrub(original).recording)
    }

    @Test
    fun theVinIsAlsoRemovedWhereItAppearsAsTextElsewhere() {
        val base = session("09021\r", chunks(response(0x7E8 to vin, spaces = true), listOf(9)))
        // An echoed value in a command and its echo, with the text split between two rx chunks.
        val echoed = "ATSET $vin\r"
        val events = base.events.toMutableList()
        events += tx(1000, echoed)
        events += rx(1010, "ATSET SIM12345")
        events += rx(1011, "67890ABCD\r\rOK\r\r>")
        events += RecordingEvent.error(1100, "lost link to $vin")
        val original = Recording(RecordingHeader("adapters/x", "drive in $vin"), events)

        val result = RecordingScrubber.scrub(original).recording
        // The text split between two chunks is found in the stream, so look at each direction as one stream.
        val everyRx = rxBytes(result)
        val everyTx = result.events.filter { it.kind == EventKind.TX }.joinToString("") { it.bytes.decodeToString() }
        assertFalse(everyRx.contains(vin))
        assertFalse(everyTx.contains(vin))
        assertFalse(result.header.note!!.contains(vin))
        assertTrue(everyTx.contains("ATSET $scrubbedVin\r"))
        // Same length, so the chunks keep their sizes and the VIN's digits change in place.
        assertEquals("ATSET SIM00000", result.events[result.events.size - 3].bytes.decodeToString())
        assertEquals("000000000\r\rOK\r\r>", result.events[result.events.size - 2].bytes.decodeToString())
        assertEquals("lost link to $scrubbedVin", result.events.last().payload)
    }

    @Test
    fun aHangUpPassesThroughUntouchedAndWhatCameBeforeItIsStillScrubbed() {
        val base = session("09021\r", chunks(response(0x7E8 to vin, spaces = true), listOf(9)))
        // The recorder writes eof and then close; the writes the consumer still tried on the dead link sit in between.
        val events = base.events.dropLast(1) + tx(880, "0902\r") + RecordingEvent.eof(890) + RecordingEvent.close(900)
        val original = Recording(base.header, events)

        val result = RecordingScrubber.scrub(original)

        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertEquals(RecordingEvent.eof(890), result.recording.events[result.recording.events.size - 2])
        assertEquals(original.events.map { it.kind to it.timeMs }, result.recording.events.map { it.kind to it.timeMs })
        assertFalse(rxBytes(result.recording).contains(vin))
    }

    @Test
    fun theTruncatedFlagSurvives() {
        val original = session("09021\r", chunks(response(0x7E8 to vin, spaces = true), listOf(9))).copy(truncated = true)
        assertTrue(RecordingScrubber.scrub(original).recording.truncated)
    }

    // UDS: the VIN read as data identifier F190 -----------------------------------------------------------------

    private fun udsVinPayload(text: String) = byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte()) + text.encodeToByteArray()

    private fun udsResponse(id: Int, payload: ByteArray, spaces: Boolean): String =
        segment(payload).joinToString("") { frame -> id.toString(16).uppercase() + (if (spaces) " " else "") + hex(frame, spaces) + "\r" } + "\r>"

    private fun udsSession(command: String, reply: String): Recording = Recording(
        RecordingHeader("adapters/x", "a note"),
        listOf(
            RecordingEvent.open(0),
            tx(10, "ATSH700\r"),
            rx(40, "OK\r\r>"),
            tx(100, command),
            rx(140, reply),
            tx(400, "0100\r"),
            rx(450, "7E8 06 41 00 BE 3F A8 13\r\r>"),
            RecordingEvent.close(900),
        ),
    )

    @Test
    fun theVinReadAsDataIdentifierF190IsScrubbedLikeTheOneFromServiceNine() {
        for (spaces in listOf(true, false)) {
            val original = udsSession("22F190\r", udsResponse(0x708, udsVinPayload(vin), spaces))
            val result = RecordingScrubber.scrub(original)
            assertTrue(result.warnings.isEmpty(), result.warnings.toString())
            assertFalse(rxBytes(result.recording).contains(vin))
            val payload = decodedUdsPayloads(result.recording).single()
            assertEquals(
                "62F190" + scrubbedVin.encodeToByteArray().joinToString("") {
                    (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                },
                payload,
            )
            assertEverythingButTheVinIsUnchanged(original, result.recording)
        }
    }

    private fun decodedUdsPayloads(recording: Recording): List<String> {
        val framer = ElmStreamFramer()
        val decoder = ElmResponseDecoder(extendedIds = false)
        return recording.events.filter { it.kind == EventKind.RX }.flatMap { framer.feed(it.bytes) }
            .flatMap { lines -> decoder.decode(lines).messages.filterIsInstance<DiagnosticResponse.Positive>() }
            .filter { it.service == 0x22 }
            .map { "62" + it.data.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') } }
    }

    @Test
    fun theF190VinIsAlsoRemovedWhereverElseItAppearsAsText() {
        val base = udsSession("22F1901\r", udsResponse(0x708, udsVinPayload(vin), spaces = true))
        val original = Recording(
            base.header.copy(note = "drive of $vin"),
            base.events + RecordingEvent.event(950, "scan: unit 700 says $vin"),
        )
        val result = RecordingScrubber.scrub(original).recording
        assertFalse(result.header.note!!.contains(vin))
        assertFalse(result.events.last().payload.contains(vin))
        assertTrue(result.events.last().payload.contains(scrubbedVin))
    }

    @Test
    fun aRefusalOrASilenceInAnswerToF190IsLeftAlone() {
        for (reply in listOf("708 03 7F 22 31\r\r>", "NO DATA\r\r>")) {
            val original = udsSession("22F190\r", reply)
            val result = RecordingScrubber.scrub(original)
            assertTrue(result.warnings.isEmpty(), result.warnings.toString())
            assertEquals(original.events, result.recording.events, reply)
        }
    }

    @Test
    fun anF190ReplyCutToItsFirstFrameHoldsOnlyTheManufacturerCodeAndIsKept() {
        // The scan asks with "one frame is enough": the adapter stops after the first frame, which has three characters of the VIN.
        val firstFrameOnly = "708 10 14 62 F1 90 ${vin.take(3).encodeToByteArray().joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase() }}\r\r>"
        val original = udsSession("22F1901\r", firstFrameOnly)
        val result = RecordingScrubber.scrub(original)
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertEquals(original.events, result.recording.events)
    }

    @Test
    fun anF190ReplyThatIsNotAVinButAnUnprogrammedControlUnitsPaddingIsKept() {
        val padding = udsResponse(0x708, byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte()) + ByteArray(17) { 0xFF.toByte() }, spaces = true)
        val original = udsSession("22F190\r", padding)
        val result = RecordingScrubber.scrub(original)
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertEquals(original.events, result.recording.events)
    }

    @Test
    fun anF190ReplyThatCannotBeReadWithConfidenceFailsClosed() {
        // A reply of the wrong length for a VIN: not rewritten, replaced.
        val original = udsSession("22F190\r", udsResponse(0x708, udsVinPayload(vin + "X"), spaces = true))
        val result = RecordingScrubber.scrub(original)
        assertEquals(1, result.warnings.size)
        assertFalse(rxBytes(result.recording).contains(vin.take(10)))
    }

    @Test
    fun aKnownVinBroadcastAsRawFramesInAPassiveCaptureIsRemovedAndTheOtherFramesStay() {
        val known = session("09021\r", chunks(response(0x7E8 to vin, spaces = true), listOf(7)), echo = "09021\r")
        val bytes = vin.encodeToByteArray()
        fun line(id: String, data: ByteArray) = id + " " + hex(data, true) + "\r"
        // The VIN in plain ASCII over three frames of one identifier, then an ISO-TP style one, then an ordinary frame.
        val frames = line("3B0", bytes.copyOfRange(0, 8)) + line("3B0", bytes.copyOfRange(8, 16)) + line("3B0", byteArrayOf(bytes[16], 0, 0, 0)) +
            line("1A0", byteArrayOf(0x12, 0x34)) + "STOPPED\r\r>"
        val events = known.events.toMutableList()
        events.add(events.size - 1, RecordingEvent.tx(500, "ATMA\r".encodeToByteArray()))
        events.add(events.size - 1, RecordingEvent.rx(900, frames.encodeToByteArray()))
        val original = Recording(known.header, events)

        val text = rxBytes(RecordingScrubber.scrub(original).recording)
        val hexVin = hex(bytes.copyOfRange(0, 8), true)
        assertFalse(text.contains(hexVin), "the VIN is still in the frames")
        assertFalse(text.contains(hex(bytes.copyOfRange(8, 16), true)))
        assertTrue(text.contains("1A0 12 34"), "an unrelated frame was changed")
        // Manufacturer code kept, like everywhere else; sizes unchanged.
        assertTrue(text.contains("3B0 " + hex(scrubbedVin.encodeToByteArray().copyOfRange(0, 8), true)), text)
        assertEquals(rxBytes(original).length, text.length)
    }
}
