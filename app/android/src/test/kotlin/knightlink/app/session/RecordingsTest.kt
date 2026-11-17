package knightlink.app.session

import knightlink.app.data.RepoAssetSource
import knightlink.core.recorder.EventKind
import knightlink.core.recorder.Recording
import knightlink.core.recorder.RecordingEvent
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingsTest {
    private val directory: File = Files.createTempDirectory("knightlink-recordings").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** A clock that moves forward one second per reading, so every recording gets its own name. */
    private class SteppingClock(private var now: Instant = Instant.parse("2026-01-02T03:04:05.006Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now.also { now = now.plusSeconds(1) }
    }

    private class FrozenClock(private val at: Instant = Instant.parse("2026-01-02T03:04:05.006Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = at
    }

    @Test
    fun aRecordingIsNamedByItsUtcStartTimeAndHoldsOneLinePerAppend() {
        val factory = FileRecordSinkFactory(RecordingStore(directory), FrozenClock())
        val sink = factory.newSink()
        sink.append("""{"a":1}""")
        sink.append("""{"b":2}""")
        factory.finishCurrent()

        val file = File(directory, "20260102T030405.006Z.jsonl")
        assertEquals("{\"a\":1}\n{\"b\":2}\n", file.readText())
    }

    @Test
    fun linesReachTheFileBeforeTheRecordingIsFinished() {
        // A crash or a killed process must cost at most the line in flight, so nothing may wait in a buffer.
        val factory = FileRecordSinkFactory(RecordingStore(directory), FrozenClock())
        factory.newSink().append("""{"a":1}""")
        assertEquals("{\"a\":1}\n", File(directory, "20260102T030405.006Z.jsonl").readText())
        factory.finishCurrent()
    }

    @Test
    fun twoRecordingsStartedInTheSameMillisecondDoNotShareAFile() {
        val store = RecordingStore(directory)
        val factory = FileRecordSinkFactory(store, FrozenClock())
        factory.newSink().append("first")
        factory.newSink().append("second")
        factory.finishCurrent()

        assertEquals(listOf("20260102T030405.006Z_1.jsonl", "20260102T030405.006Z.jsonl"), store.list().map { it.name })
        assertEquals("second\n", store.list().first().readText())
        assertEquals("first\n", store.list().last().readText())
    }

    @Test
    fun onlyTheNewest20RecordingsAreKept() {
        val store = RecordingStore(directory)
        val factory = FileRecordSinkFactory(store, SteppingClock())
        val created = (1..25).map {
            factory.newSink().append("line $it")
            store.list().first().name
        }
        factory.finishCurrent()

        assertEquals(20, store.list().size)
        assertEquals(created.takeLast(20).reversed(), store.list().map { it.name })
        assertEquals(20, directory.listFiles()!!.size)
        // Nothing but the 6th to the 25th recording is left.
        assertEquals("line 25\n", store.newest()!!.readText())
        assertFalse(File(directory, created[4]).exists())
        assertTrue(File(directory, created[5]).exists())
    }

    @Test
    fun filesThatAreNotRecordingsAreNeverDeletedOrCounted() {
        val other = File(directory, "notes.txt").apply { writeText("keep me") }
        val store = RecordingStore(directory, keep = 1)
        val factory = FileRecordSinkFactory(store, SteppingClock())
        repeat(3) { factory.newSink().append("x") }
        factory.finishCurrent()

        assertEquals(1, store.list().size)
        assertTrue(other.exists())
    }

    @Test
    fun aSidecarFileIsWrittenNextToTheCurrentRecordingAndRemovedWithIt() {
        val store = RecordingStore(directory, keep = 1)
        val factory = FileRecordSinkFactory(store, SteppingClock())
        factory.writeSidecar(".scan.json", "nothing to write it next to")
        assertTrue(directory.listFiles().orEmpty().isEmpty())

        factory.newSink().append("x")
        factory.writeSidecar(".scan.json", "{}")
        val first = store.newest()!!
        val sidecar = File(directory, first.name.removeSuffix(".jsonl") + ".scan.json")
        assertEquals("{}", sidecar.readText())
        assertEquals(listOf(first), store.list(), "the sidecar is not a recording")
        assertEquals(RecordingsSummary(1, 2), store.summary())

        // A newer recording pushes the old one out, and its sidecar with it.
        factory.newSink().append("y")
        factory.finishCurrent()
        assertFalse(sidecar.exists())
        assertFalse(first.exists())
    }

    @Test
    fun theSummaryCountsFilesAndBytes() {
        val store = RecordingStore(directory)
        assertEquals(RecordingsSummary.EMPTY, store.summary())
        val factory = FileRecordSinkFactory(store, SteppingClock())
        factory.newSink().append("12345")
        factory.newSink().append("123")
        factory.finishCurrent()
        assertEquals(RecordingsSummary(count = 2, totalBytes = 6 + 4), store.summary())
    }

    @Test
    fun anUnwritableDirectoryCostsTheRecordingNotTheSession() {
        // The directory path is a regular file, so no recording can be created in it.
        val blocked = File(directory, "blocked").apply { writeText("not a directory") }
        val factory = FileRecordSinkFactory(RecordingStore(blocked), FrozenClock())

        val sink = factory.newSink()
        sink.append("dropped")
        factory.finishCurrent()
        assertEquals("not a directory", blocked.readText())
    }

    @Test
    fun theScrubbedCopyOfARecordingHoldsNoVin() {
        val source = File(directory, "20260102T030405.006Z.jsonl").apply { writeText(withVin(committedRecording()).toText()) }
        // 48415252494552 is "HARRIER" and 32303230303031 is "2020001" as an adapter prints them: the VIN's last fourteen characters.
        assertTrue(rxText(Recording.parse(source.readText())).contains(VIN_TAIL_HEX), "the test recording must hold a VIN")

        val prepared = RecordingShare.prepare(source, File(directory, "shared"))

        assertTrue(prepared.warnings.isEmpty(), prepared.warnings.toString())
        val scrubbed = rxText(Recording.parse(prepared.file.readText()))
        assertFalse(scrubbed.contains("48415252494552"))
        assertFalse(scrubbed.contains("32303230303031"))
        // The manufacturer code stays, the rest is zeros.
        assertTrue(scrubbed.contains("49020153494D"))
        assertEquals("20260102T030405.006Z-scrubbed.jsonl", prepared.file.name)
        // The original is untouched: it is the user's own record.
        assertTrue(rxText(Recording.parse(source.readText())).contains(VIN_TAIL_HEX))
    }

    @Test
    fun onlyTheLatestScrubbedCopyStaysOnTheDevice() {
        val source = File(directory, "20260102T030405.006Z.jsonl").apply { writeText(committedRecording().toText()) }
        val shared = File(directory, "shared").apply { mkdirs() }
        val stale = File(shared, "older-scrubbed.jsonl").apply { writeText("old") }

        RecordingShare.prepare(source, shared)

        assertFalse(stale.exists())
        assertEquals(1, shared.listFiles()!!.size)
    }

    /** The committed recording of a simulated probe. Its VIN is already scrubbed, as the repository must not hold a real or realistic one. */
    private fun committedRecording(): Recording =
        Recording.parse(File(RepoAssetSource.repoRoot(), "fixtures/recordings/sim-harrier-probe.jsonl").readText())

    /**
     * The same recording with a VIN in the response to the VIN request: the committed scrubbed VIN (manufacturer code and
     * zeros) is replaced by characters, in the same positions of the rx byte stream, so every chunk keeps its size.
     */
    private fun withVin(recording: Recording): Recording {
        val rx = recording.events.filter { it.kind == EventKind.RX }
        val text = rx.joinToString("") { it.bytes.decodeToString() }
        // The adapter prints one frame per line, so the VIN's two continuation frames are two lines: "7E821" and "7E822" plus 7 bytes each.
        val scrubbedTail = "7E82130303030303030\r7E82230303030303030"
        val withTail = "7E82148415252494552\r7E82232303230303031"
        require(scrubbedTail in text) { "the committed recording is not laid out as this test expects" }
        val bytes = text.replace(scrubbedTail, withTail).encodeToByteArray()
        var offset = 0
        val replaced = rx.map { event ->
            RecordingEvent.rx(event.timeMs, bytes.copyOfRange(offset, offset + event.bytes.size)).also { offset += event.bytes.size }
        }.iterator()
        return recording.copy(events = recording.events.map { if (it.kind == EventKind.RX) replaced.next() else it })
    }

    private fun rxText(recording: Recording): String =
        recording.events.filter { it.kind == EventKind.RX }.joinToString("") { it.bytes.decodeToString() }

    private companion object {
        const val VIN_TAIL_HEX = "48415252494552"
    }
}
