package knightlink.core.protocol

import knightlink.core.common.hexToBytes
import knightlink.core.common.toHex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ElmLineParserTest {
    private val parser = ElmLineParser(extendedIds = false)

    @Test
    fun parsesFrameWithOrWithoutSpaces() {
        val spaced = assertIs<ElmLine.Frame>(parser.parse("7E8 03 41 05 6B")).frame
        val compact = assertIs<ElmLine.Frame>(parser.parse("7E80341056B")).frame
        assertEquals(spaced, compact)
        assertEquals(0x7E8, spaced.id)
        assertEquals("0341056B", spaced.data.toHex())
    }

    @Test
    fun parses29BitFrame() {
        val frame = assertIs<ElmLine.Frame>(ElmLineParser(extendedIds = true).parse("18DAF110 03 41 0D 28")).frame
        assertEquals(0x18DAF110, frame.id)
        assertEquals("03410D28", frame.data.toHex())
    }

    @Test
    fun recognisesStatusWords() {
        assertEquals(ElmLine.Status(ElmStatus.NO_DATA), parser.parse("NO DATA"))
        assertEquals(ElmLine.Status(ElmStatus.SEARCHING), parser.parse("SEARCHING..."))
        assertEquals(ElmLine.Status(ElmStatus.UNKNOWN_COMMAND), parser.parse("?"))
        assertEquals(ElmLine.Status(ElmStatus.CAN_ERROR), parser.parse("CAN ERROR"))
        assertEquals(ElmLine.Status(ElmStatus.BUS_ERROR), parser.parse("BUS INIT: ...ERROR"))
        assertEquals(ElmLine.Status(ElmStatus.DATA_ERROR), parser.parse("<DATA ERROR"))
        assertEquals(ElmLine.Status(ElmStatus.OK), parser.parse("OK"))
    }

    @Test
    fun everythingElseIsText() {
        assertEquals(ElmLine.Text("ELM327 v2.3"), parser.parse("ELM327 v2.3"))
        assertIs<ElmLine.Text>(parser.parse("7E8 03 4")) // odd digit count after the header
    }
}

class ElmStreamFramerTest {
    private val stream = "7E8 03 41 05 6B\r\r>SEARCHING...\r7E8 04 41 0C 0C 7C\r\n\r\n>\u0000OK\r\r>"
    private val expected = listOf(
        listOf("7E8 03 41 05 6B"),
        listOf("SEARCHING...", "7E8 04 41 0C 0C 7C"),
        listOf("OK"),
    )

    @Test
    fun framesWholeInput() {
        assertEquals(expected, ElmStreamFramer().feed(stream.encodeToByteArray()))
    }

    @Test
    fun framingIsIndependentOfChunkBoundaries() {
        val bytes = stream.encodeToByteArray()
        repeat(500) { seed ->
            val random = Random(seed)
            val framer = ElmStreamFramer()
            val out = ArrayList<List<String>>()
            var pos = 0
            while (pos < bytes.size) {
                val end = minOf(bytes.size, pos + random.nextInt(1, 8))
                out += framer.feed(bytes.copyOfRange(pos, end))
                pos = end
            }
            assertEquals(expected, out, "seed $seed")
        }
    }

    @Test
    fun incompleteResponseIsHeldBack() {
        val framer = ElmStreamFramer()
        assertTrue(framer.feed("7E8 03 41".encodeToByteArray()).isEmpty())
        assertEquals(listOf(listOf("7E8 03 41 05 6B")), framer.feed(" 05 6B\r>".encodeToByteArray()))
    }
}

class IsoTpReassemblerTest {
    private fun frame(id: Int, hex: String) = CanFrame(id, extended = false, data = hex.hexToBytes())

    @Test
    fun singleFrame() {
        val result = assertIs<IsoTpResult.Complete>(IsoTpReassembler().accept(frame(0x7E8, "03 41 05 6B")))
        assertEquals("41056B", result.message.payload.toHex())
    }

    @Test
    fun singleFrameIgnoresPadding() {
        val result = assertIs<IsoTpResult.Complete>(IsoTpReassembler().accept(frame(0x7E8, "03 41 05 6B AA AA AA AA")))
        assertEquals("41056B", result.message.payload.toHex())
    }

    @Test
    fun multiFrameFromSrsEgtCapture() {
        val r = IsoTpReassembler()
        assertEquals(IsoTpResult.Incomplete, r.accept(frame(0x7E8, "10 0B 41 78 0F 06 F2 05")))
        val done = assertIs<IsoTpResult.Complete>(r.accept(frame(0x7E8, "21 89 03 97 02 C9")))
        assertEquals("41780F06F20589039702C9", done.message.payload.toHex())
    }

    @Test
    fun interleavedEcusAreKeptApart() {
        val r = IsoTpReassembler()
        r.accept(frame(0x7E8, "10 09 41 8B 73 00 E3 00"))
        r.accept(frame(0x7EC, "10 08 41 00 11 22 33 44"))
        val ecm = assertIs<IsoTpResult.Complete>(r.accept(frame(0x7E8, "21 EF 00 B3 00 00")))
        val rdcm = assertIs<IsoTpResult.Complete>(r.accept(frame(0x7EC, "21 55 66 00 00 00")))
        assertEquals("418B7300E300EF00B3", ecm.message.payload.toHex())
        assertEquals("4100112233445566", rdcm.message.payload.toHex())
    }

    @Test
    fun threeFrameMessageWithSequenceWrap() {
        val r = IsoTpReassembler()
        val payload = ByteArray(6 + 7 * 17) { it.toByte() }
        fun ff() = byteArrayOf(0x10, payload.size.toByte()) + payload.copyOfRange(0, 6)
        assertEquals(IsoTpResult.Incomplete, r.accept(CanFrame(0x7E8, false, ff())))
        var last: IsoTpResult = IsoTpResult.Incomplete
        for (i in 0 until 17) {
            val seq = (i + 1) and 0x0F
            val chunk = payload.copyOfRange(6 + i * 7, 6 + (i + 1) * 7)
            last = r.accept(CanFrame(0x7E8, false, byteArrayOf((0x20 or seq).toByte()) + chunk))
        }
        assertEquals(payload.toHex(), assertIs<IsoTpResult.Complete>(last).message.payload.toHex())
    }

    @Test
    fun reportsErrors() {
        val r = IsoTpReassembler()
        assertIs<IsoTpResult.Error>(r.accept(frame(0x7E8, "21 00 00")))
        r.accept(frame(0x7E8, "10 0B 41 78 0F 06 F2 05"))
        assertIs<IsoTpResult.Error>(r.accept(frame(0x7E8, "22 89 03 97 02 C9")))
        assertIs<IsoTpResult.Error>(r.accept(frame(0x7E8, "05 41 05")))
        assertEquals(IsoTpResult.Incomplete, r.accept(frame(0x7E0, "30 00 00")))
    }
}

class ElmResponseDecoderTest {
    private val decoder = ElmResponseDecoder(extendedIds = false)

    @Test
    fun positiveResponse() {
        val response = decoder.decode(listOf("7E8 04 41 0C 0C 7C"))
        val message = assertIs<DiagnosticResponse.Positive>(response.messages.single())
        assertEquals(0x7E8, message.sourceId)
        assertEquals(0x01, message.service)
        assertEquals("0C0C7C", message.data.toHex())
    }

    @Test
    fun negativeResponseFromSrsTcmCapture() {
        val message = assertIs<DiagnosticResponse.Negative>(decoder.decode(listOf("7E9 03 7F 22 31")).messages.single())
        assertEquals(0x7E9, message.sourceId)
        assertEquals(0x22, message.service)
        assertEquals(NegativeResponseCode.REQUEST_OUT_OF_RANGE, message.reason)
    }

    @Test
    fun functionalBroadcastYieldsOneMessagePerEcu() {
        val response = decoder.decode(listOf("7E8 03 41 05 6B", "7EC 03 41 05 6A"))
        assertEquals(listOf(0x7E8, 0x7EC), response.messages.map { it.sourceId })
    }

    @Test
    fun statusAndErrorsAreSurfaced() {
        val response = decoder.decode(listOf("SEARCHING...", "NO DATA", "7E8 21 00 00"))
        assertEquals(listOf(ElmStatus.SEARCHING, ElmStatus.NO_DATA), response.statuses)
        assertTrue(response.messages.isEmpty())
        assertEquals(1, response.protocolErrors.size)
    }
}
