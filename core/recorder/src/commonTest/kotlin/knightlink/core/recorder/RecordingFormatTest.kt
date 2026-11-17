package knightlink.core.recorder

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingFormatTest {
    private val header = RecordingHeader(adapter = "adapters/example", note = "line one\nline \"two\"")

    private fun sample() = Recording(
        header,
        listOf(
            RecordingEvent.open(0),
            RecordingEvent.tx(12, "ATZ\r".encodeToByteArray()),
            RecordingEvent.rx(57, byteArrayOf(0x00, 0xFF.toByte(), 0x7F, 0x80.toByte(), '>'.code.toByte())),
            RecordingEvent.error(90, "link lost: é中"),
            RecordingEvent.close(91),
        ),
    )

    @Test
    fun headerIsTheFirstLineAndEventsUseTheDocumentedKeys() {
        val lines = sample().toText().lines()
        assertEquals("""{"schemaVersion":1,"kind":"knightlink-session","adapter":"adapters/example","note":"line one\nline \"two\""}""", lines[0])
        assertEquals("""{"t":0,"d":"open","x":""}""", lines[1])
        assertEquals("""{"t":12,"d":"tx","x":"41545A0D"}""", lines[2])
        assertEquals("""{"t":57,"d":"rx","x":"00FF7F803E"}""", lines[3])
        assertEquals("", lines.last(), "the text ends with a newline")
    }

    @Test
    fun aRemoteHangUpIsWrittenAsDEofAndKeepsSchemaVersion1() {
        val original = Recording(header, listOf(RecordingEvent.open(0), RecordingEvent.eof(7), RecordingEvent.close(9)))
        val lines = original.toText().lines()
        assertTrue(lines[0].startsWith("""{"schemaVersion":1,"""), lines[0])
        assertEquals("""{"t":7,"d":"eof","x":""}""", lines[2])
        assertEquals(original, Recording.parse(original.toText()))
        assertEquals(EventKind.EOF, Recording.parse(original.toText()).events[1].kind)
    }

    @Test
    fun nullHeaderFieldsAreWrittenExplicitly() {
        assertEquals(
            """{"schemaVersion":1,"kind":"knightlink-session","adapter":null,"note":null}""",
            Recording(RecordingHeader(), emptyList()).toText().trimEnd(),
        )
    }

    @Test
    fun roundTripKeepsEverythingIncludingBinaryBytesAndOddText() {
        val original = sample()
        val parsed = Recording.parse(original.toText())
        assertEquals(original, parsed)
        assertFalse(parsed.truncated)
        assertContentEquals(byteArrayOf(0x00, 0xFF.toByte(), 0x7F, 0x80.toByte(), '>'.code.toByte()), parsed.events[2].bytes)
        assertEquals("link lost: é中", parsed.events[3].payload)
    }

    @Test
    fun lowerCaseHexIsAcceptedAndNormalised() {
        val text = Recording(header, emptyList()).toText() + """{"t":1,"d":"rx","x":"0aff"}""" + "\n"
        assertEquals("0AFF", Recording.parse(text).events.single().payload)
    }

    @Test
    fun anEmptyWriteIsPreserved() {
        val original = Recording(header, listOf(RecordingEvent.tx(3, ByteArray(0))))
        assertEquals(original, Recording.parse(original.toText()))
    }

    @Test
    fun windowsLineEndingsAndTrailingBlankLinesAreTolerated() {
        val text = sample().toText().replace("\n", "\r\n") + "\r\n\r\n"
        assertEquals(sample(), Recording.parse(text))
    }

    @Test
    fun aMissingFinalNewlineIsNotTruncation() {
        val parsed = Recording.parse(sample().toText().trimEnd('\n'))
        assertEquals(sample(), parsed)
        assertFalse(parsed.truncated)
    }

    @Test
    fun anIncompleteFinalLineIsDroppedAndFlagged() {
        val full = sample().toText()
        // A crash in the middle of the last write leaves part of the line and no newline.
        val cut = full.trimEnd('\n').dropLast(9)
        val parsed = Recording.parse(cut)
        assertTrue(parsed.truncated)
        assertEquals(sample().events.dropLast(1), parsed.events)
        assertEquals(header, parsed.header)
    }

    @Test
    fun aFinalLineCutInsideTheHexPayloadIsAlsoDropped() {
        val text = Recording(header, listOf(RecordingEvent.open(0))).toText() + """{"t":5,"d":"rx","x":"41"""
        val parsed = Recording.parse(text)
        assertTrue(parsed.truncated)
        assertEquals(1, parsed.events.size)
    }

    @Test
    fun aBrokenLineInTheMiddleIsAnErrorNotTruncation() {
        val lines = sample().toText().lines().toMutableList()
        lines[2] = lines[2].dropLast(4)
        val error = assertFailsWith<RecordingFormatException> { Recording.parse(lines.joinToString("\n")) }
        assertTrue("Line 3" in error.message!!, error.message)
    }

    @Test
    fun aCompleteButInvalidFinalLineIsAnErrorNotTruncation() {
        val text = Recording(header, emptyList()).toText() + """{"t":1,"d":"rx","x":"ZZ"}"""
        assertFailsWith<RecordingFormatException> { Recording.parse(text) }
    }

    @Test
    fun aFileWithoutACompleteHeaderIsUnreadable() {
        assertFailsWith<RecordingFormatException> { Recording.parse("") }
        assertFailsWith<RecordingFormatException> { Recording.parse("""{"schemaVersion":1,"kind":"knightlink-sess""") }
    }

    @Test
    fun unknownKeysAreRejectedInTheHeaderAndInEvents() {
        val headerLine = """{"schemaVersion":1,"kind":"knightlink-session","adapter":null,"note":null}"""
        assertFailsWith<RecordingFormatException> {
            Recording.parse("""{"schemaVersion":1,"kind":"knightlink-session","adapter":null,"note":null,"vin":"x"}""" + "\n")
        }
        assertFailsWith<RecordingFormatException> { Recording.parse(headerLine + "\n" + """{"t":1,"d":"rx","x":"41","extra":1}""" + "\n") }
    }

    @Test
    fun aNewerSchemaIsRejectedWithAClearMessageEvenWhenItAddsKeys() {
        val error = assertFailsWith<RecordingFormatException> {
            Recording.parse("""{"schemaVersion":4,"kind":"knightlink-session","adapter":null,"note":null,"clock":"utc"}""" + "\n")
        }
        assertTrue("schema 4" in error.message!! && "up to 3" in error.message!!, error.message)
    }

    @Test
    fun aNewerSchemaOnAnUnterminatedHeaderIsStillAnError() {
        assertFailsWith<RecordingFormatException> {
            Recording.parse("""{"schemaVersion":4,"kind":"knightlink-session","adapter":null,"note":null}""")
        }
    }

    @Test
    fun otherInvalidContentIsRejected() {
        val headerLine = """{"schemaVersion":1,"kind":"knightlink-session","adapter":null,"note":null}"""
        fun parse(vararg eventLines: String) = Recording.parse((listOf(headerLine) + eventLines).joinToString("\n", postfix = "\n"))
        assertFailsWith<RecordingFormatException> { Recording.parse("""{"schemaVersion":1,"kind":"something-else","adapter":null,"note":null}""" + "\n") }
        assertFailsWith<RecordingFormatException> { Recording.parse("""{"schemaVersion":0,"kind":"knightlink-session","adapter":null,"note":null}""" + "\n") }
        assertFailsWith<RecordingFormatException> { parse("""{"t":1,"d":"nope","x":""}""") }
        assertFailsWith<RecordingFormatException> { parse("""{"t":-1,"d":"open","x":""}""") }
        assertFailsWith<RecordingFormatException> { parse("""{"t":1.5,"d":"open","x":""}""") }
        assertFailsWith<RecordingFormatException> { parse("""{"t":1,"d":"tx","x":"ABC"}""") }
        assertFailsWith<RecordingFormatException> { parse("""{"d":"tx","x":"AB"}""") }
        assertFailsWith<RecordingFormatException> { parse("[1,2]") }
    }

    @Test
    fun eventsRejectInvalidHexAtConstruction() {
        assertFailsWith<IllegalArgumentException> { RecordingEvent(0, EventKind.TX, "abc") }
        assertFailsWith<IllegalArgumentException> { RecordingEvent(-1, EventKind.OPEN) }
    }
}
