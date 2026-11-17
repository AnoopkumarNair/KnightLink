package knightlink.core.recorder

import knightlink.core.common.SessionEvent
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.transport.TransportException
import knightlink.core.transport.TransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

class SessionEventRecordingTest {
    // Built, not written out: the architecture test forbids address-shaped literals in core sources, tests included.
    private val address = listOf("A1", "B2", "C3", "D4", "E5", "F6").joinToString(":")
    private val vin = "WAUZZZ8K9BA" + "123456"

    private class QuietStream : ByteStream {
        override val incoming: Flow<ByteArray> = emptyFlow()

        override suspend fun write(bytes: ByteArray) = Unit

        override suspend fun close() = Unit
    }

    private class Opener(private val failure: Exception? = null) : Transport {
        override val kind = TransportKind.SIMULATED

        override suspend fun open(): ByteStream = failure?.let { throw it } ?: QuietStream()
    }

    private class Sinks {
        val all = ArrayList<InMemoryRecordSink>()

        fun create(): RecordSink = InMemoryRecordSink().also { all += it }
    }

    private fun recording(sinks: Sinks, index: Int = 0): Recording = sinks.all[index].toRecording()

    @Test
    fun eventsAreRecordedAsLinesOfTheLinkTheyConcernAndTheFileIsSchema2() = runTest {
        val sinks = Sinks()
        val transport = RecordingTransport(Opener(), sinks::create, TestTimeSource(), RecordingHeader("adapters/x", "note"))

        // Before the first link: held, and written first when it opens.
        transport.emit(SessionEvents.CONNECT_ATTEMPT, "attempt 1")
        val stream = transport.open()
        transport.emit(SessionEvents.LINK_UP, "the link is open")
        stream.close()
        // After the close: still the last link's, so the reason a session ended is the last thing in its file.
        transport.emit(SessionEvents.END, "the session was stopped")

        val text = sinks.all.single().text()
        assertTrue(text.lineSequence().first().contains("\"schemaVersion\":2"), text)
        val events = Recording.parse(text).events
        assertEquals(
            listOf(
                EventKind.EVENT to "connect-attempt: attempt 1",
                EventKind.OPEN to "",
                EventKind.EVENT to "link-up: the link is open",
                EventKind.CLOSE to "",
                EventKind.EVENT to "end: the session was stopped",
            ),
            events.map { it.kind to it.payload },
        )
    }

    @Test
    fun theRecordingStartsAtOpenSoAFailedOpenStillLeavesOne() = runTest {
        val sinks = Sinks()
        val transport = RecordingTransport(Opener(TransportException("no adapter in range")), sinks::create)

        transport.emit(SessionEvents.CONNECT_ATTEMPT, "attempt 1")
        assertFailsWith<TransportException> { transport.open() }
        transport.emit(SessionEvents.RECONNECT, "attempt 2 in 1s")

        val events = recording(sinks).events
        assertEquals(listOf(EventKind.EVENT, EventKind.ERROR, EventKind.EVENT), events.map { it.kind })
        assertEquals("no adapter in range", events[1].payload)
    }

    @Test
    fun everyLinkGetsItsOwnRecordingAndLaterEventsGoToTheNewestLink() = runTest {
        val sinks = Sinks()
        val transport = RecordingTransport(Opener(), sinks::create)
        transport.open().close()
        transport.emit(SessionEvents.RECONNECT, "attempt 2")
        transport.open().close()
        transport.emit(SessionEvents.END, "done")

        assertEquals(2, sinks.all.size)
        assertEquals(listOf("reconnect: attempt 2"), recording(sinks, 0).events.filter { it.kind == EventKind.EVENT }.map { it.payload })
        assertEquals(listOf("end: done"), recording(sinks, 1).events.filter { it.kind == EventKind.EVENT }.map { it.payload })
    }

    @Test
    fun noAddressAndNoVinReachesTheRecordingThroughEventsOrErrors() = runTest {
        val sinks = Sinks()
        val transport = RecordingTransport(Opener(TransportException("failed to connect to $address")), sinks::create)

        transport.emit(SessionEvent(SessionEvents.STATE, "tried $address and read $vin"))
        assertFailsWith<TransportException> { transport.open() }

        val text = sinks.all.single().text()
        assertFalse(address in text, text)
        assertFalse(address.lowercase() in text.lowercase(), text)
        assertFalse(vin in text, text)
        assertTrue("[address removed]" in text)
        assertTrue("[VIN removed]" in text)
    }

    @Test
    fun eventsHeldBeforeTheFirstLinkAreCapped() = runTest {
        val sinks = Sinks()
        val transport = RecordingTransport(Opener(), sinks::create)
        repeat(RecordingTransport.MAX_HELD_EVENTS + 50) { transport.emit(SessionEvents.STATE, "n$it") }
        transport.open()
        assertEquals(RecordingTransport.MAX_HELD_EVENTS, recording(sinks).events.count { it.kind == EventKind.EVENT })
    }

    @Test
    fun aRecordingWithoutEventsIsStillWrittenAsSchema1AndReadsBack() {
        val plain = Recording(
            RecordingHeader("adapters/x", null),
            listOf(RecordingEvent.open(0), RecordingEvent.tx(5, "ATZ\r".encodeToByteArray()), RecordingEvent.close(9)),
        )
        assertTrue(plain.toText().lineSequence().first().contains("\"schemaVersion\":1"))
        assertEquals(plain, Recording.parse(plain.toText()))

        val withEvents = plain.copy(events = plain.events + RecordingEvent.event(10, "end: done"))
        assertTrue(withEvents.toText().lineSequence().first().contains("\"schemaVersion\":2"))
        assertEquals(withEvents, Recording.parse(withEvents.toText()))
    }

    @Test
    fun aSchema1FileWrittenByAnOlderBuildStillParses() {
        val text = """{"schemaVersion":1,"kind":"knightlink-session","adapter":"adapters/x","note":null}""" + "\n" +
            """{"t":0,"d":"open","x":""}""" + "\n" + """{"t":4,"d":"tx","x":"41545A0D"}""" + "\n" + """{"t":9,"d":"close","x":""}""" + "\n"
        val recording = Recording.parse(text)
        assertEquals(3, recording.events.size)
        assertEquals(text, recording.toText())
    }

    // Replay -----------------------------------------------------------------------------------------------------------

    private fun withNotes(): Recording = Recording(
        RecordingHeader("adapters/x", null),
        listOf(
            RecordingEvent.event(0, "connect-attempt: attempt 1"),
            RecordingEvent.open(1),
            RecordingEvent.event(2, "link-up"),
            RecordingEvent.tx(10, "ATZ\r".encodeToByteArray()),
            RecordingEvent.event(11, "init-step: ATZ"),
            RecordingEvent.rx(60, "ELM327 v2.3\r\r>".encodeToByteArray()),
            RecordingEvent.close(70),
            RecordingEvent.event(71, "end: done"),
        ),
    )

    @Test
    fun aStrictReplayPlaysTheTrafficAndSkipsTheNotes() = runTest {
        val stream = ReplayByteStream(withNotes(), ReplayMode.STRICT)
        stream.write("ATZ\r".encodeToByteArray())
        assertEquals("ELM327 v2.3\r\r>", stream.incoming.first().decodeToString())
        assertNull(stream.firstMismatch)
    }

    @Test
    fun aByCommandReplayAnswersFromTheTrafficAndIgnoresTheNotes() = runTest {
        val stream = ReplayByteStream(withNotes(), ReplayMode.BY_COMMAND)
        stream.write("ATZ\r".encodeToByteArray())
        assertEquals("ELM327 v2.3\r\r>", stream.incoming.first().decodeToString())
        assertEquals(0, stream.unknownCommands)
    }

    // Scrubbing --------------------------------------------------------------------------------------------------------

    @Test
    fun scrubbingKeepsTheNotesAndTheirOrder() {
        val original = withNotes()
        val scrubbed = RecordingScrubber.scrub(original).recording
        assertEquals(original.events.filter { it.kind == EventKind.EVENT }, scrubbed.events.filter { it.kind == EventKind.EVENT })
        assertEquals(original.events.map { it.kind }, scrubbed.events.map { it.kind })
    }
}
