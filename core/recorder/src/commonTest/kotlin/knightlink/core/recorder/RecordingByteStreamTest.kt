package knightlink.core.recorder

import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.transport.TransportException
import knightlink.core.transport.TransportKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** A stream whose incoming chunks and write behaviour are scripted by the test. */
private class ScriptedStream(
    private val script: suspend FlowCollector<ByteArray>.() -> Unit = {},
    private val onWrite: suspend (ByteArray) -> Unit = {},
) : ByteStream {
    val written = ArrayList<ByteArray>()
    var closed = 0

    override val incoming: Flow<ByteArray> = flow { script() }

    override suspend fun write(bytes: ByteArray) {
        onWrite(bytes)
        written += bytes
    }

    override suspend fun close() {
        closed++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingByteStreamTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun writesTheHeaderAndAnOpenEventWhenCreated() {
        val sink = InMemoryRecordSink()
        RecordingByteStream(ScriptedStream(), sink, TestTimeSource(), RecordingHeader("adapters/x", "note"))
        assertEquals(2, sink.lines.size)
        val recording = sink.toRecording()
        assertEquals(RecordingHeader("adapters/x", "note"), recording.header)
        assertEquals(listOf(RecordingEvent.open(0)), recording.events)
    }

    @Test
    fun recordsWritesAndChunksInOrderWithTheChunkBoundariesUntouched() = runTest {
        val clock = TestTimeSource()
        val sink = InMemoryRecordSink()
        val delivered = listOf(bytes(0x41, 0x54), bytes(0x00), bytes(0xFF, 0x3E))
        val inner = ScriptedStream(script = {
            clock += 10.milliseconds
            emit(delivered[0])
            clock += 5.milliseconds
            emit(delivered[1])
            emit(delivered[2])
        })
        val stream = RecordingByteStream(inner, sink, clock)

        clock += 3.milliseconds
        stream.write("ATZ\r".encodeToByteArray())
        val received = stream.incoming.toList()
        clock += 20.milliseconds
        stream.close()

        assertEquals(delivered.size, received.size)
        delivered.zip(received).forEach { (expected, actual) -> assertSame(expected, actual, "chunks are passed on as they are") }
        assertEquals(1, inner.written.size)
        assertContentEquals("ATZ\r".encodeToByteArray(), inner.written.single())
        assertEquals(1, inner.closed)
        assertEquals(
            listOf(
                RecordingEvent.open(0),
                RecordingEvent.tx(3, "ATZ\r".encodeToByteArray()),
                RecordingEvent.rx(13, delivered[0]),
                RecordingEvent.rx(18, delivered[1]),
                RecordingEvent.rx(18, delivered[2]),
                // The scripted adapter stops talking once it has said its piece: that is a hang-up, then the consumer closes.
                RecordingEvent.eof(18),
                RecordingEvent.close(38),
            ),
            sink.toRecording().events,
        )
    }

    @Test
    fun aWriteThatThrowsIsStillOnRecord() = runTest {
        val sink = InMemoryRecordSink()
        val stream = RecordingByteStream(ScriptedStream(onWrite = { throw TransportException("link gone") }), sink, TestTimeSource())
        assertFailsWith<TransportException> { stream.write(bytes(1, 2)) }
        assertEquals(listOf(EventKind.OPEN, EventKind.TX), sink.toRecording().events.map { it.kind })
        assertEquals("0102", sink.toRecording().events.last().payload)
    }

    @Test
    fun aFailingFlowIsRecordedAndRethrown() = runTest {
        val sink = InMemoryRecordSink()
        val stream = RecordingByteStream(
            ScriptedStream(script = {
                emit(bytes(0x31))
                throw TransportException("link dropped")
            }),
            sink,
            TestTimeSource(),
        )
        val seen = ArrayList<ByteArray>()
        val failure = assertFailsWith<TransportException> { stream.incoming.collect { seen += it } }
        assertEquals("link dropped", failure.message)
        assertEquals(1, seen.size)
        assertEquals(
            listOf(EventKind.OPEN, EventKind.RX, EventKind.ERROR),
            sink.toRecording().events.map { it.kind },
        )
        assertEquals("link dropped", sink.toRecording().events.last().payload)
    }

    @Test
    fun aConsumerFailureIsNotRecordedAsAnAdapterError() = runTest {
        val sink = InMemoryRecordSink()
        val stream = RecordingByteStream(ScriptedStream(script = { emit(bytes(0x31)) }), sink, TestTimeSource())
        assertFailsWith<IllegalStateException> { stream.incoming.collect { error("consumer bug") } }
        assertTrue(sink.toRecording().events.none { it.kind == EventKind.ERROR })
    }

    @Test
    fun interleavedReadsAndWritesKeepTimeOrderEqualToLineOrder() = runTest {
        val sink = InMemoryRecordSink()
        val inner = ScriptedStream(
            script = {
                repeat(5) {
                    delay(10)
                    emit(bytes(it))
                }
            },
            onWrite = { delay(7) },
        )
        val stream = RecordingByteStream(inner, sink, testTimeSource)
        val collector = launch { stream.incoming.collect { } }
        repeat(4) {
            delay(9)
            stream.write(bytes(0x40 + it))
        }
        collector.join()
        val times = sink.toRecording().events.map { it.timeMs }
        assertEquals(times.sorted(), times)
        // open, 5 rx, 4 tx, and the eof of the scripted adapter finishing.
        assertEquals(11, sink.toRecording().events.size)
    }

    @Test
    fun theDelegateCompletingIsRecordedAsEofOnceAndAConsumerCloseStillFollows() = runTest {
        val clock = TestTimeSource()
        val sink = InMemoryRecordSink()
        val stream = RecordingByteStream(ScriptedStream(script = { emit(bytes(0x41)) }), sink, clock)

        stream.incoming.toList()
        clock += 7.milliseconds
        // A second look at the same stream ending is not a second hang-up.
        stream.incoming.toList()
        clock += 3.milliseconds
        stream.close()

        assertEquals(
            listOf(
                RecordingEvent.open(0),
                RecordingEvent.rx(0, bytes(0x41)),
                RecordingEvent.eof(0),
                RecordingEvent.rx(7, bytes(0x41)),
                RecordingEvent.close(10),
            ),
            sink.toRecording().events,
        )
    }

    @Test
    fun aCancelledCollectorIsNotAHangUp() = runTest {
        val sink = InMemoryRecordSink()
        val stream = RecordingByteStream(
            ScriptedStream(script = {
                emit(bytes(0x41))
                awaitCancellation()
            }),
            sink,
            TestTimeSource(),
        )
        val collector = launch { stream.incoming.collect { } }
        runCurrent()
        collector.cancelAndJoin()
        assertEquals(listOf(EventKind.OPEN, EventKind.RX), sink.toRecording().events.map { it.kind })
    }

    @Test
    fun aStreamThatEndsBecauseTheConsumerClosedItIsNotAHangUp() = runTest {
        val sink = InMemoryRecordSink()
        val release = CompletableDeferred<Unit>()
        val stream = RecordingByteStream(ScriptedStream(script = { release.await() }), sink, TestTimeSource())
        val collector = launch { stream.incoming.collect { } }
        runCurrent()
        stream.close()
        release.complete(Unit)
        collector.join()
        assertEquals(listOf(EventKind.OPEN, EventKind.CLOSE), sink.toRecording().events.map { it.kind })
    }

    @Test
    fun aCollectorThatFailsIsNotAHangUpEither() = runTest {
        val sink = InMemoryRecordSink()
        val stream = RecordingByteStream(ScriptedStream(script = { emit(bytes(0x41)) }), sink, TestTimeSource())
        assertFailsWith<IllegalStateException> { stream.incoming.collect { error("consumer bug") } }
        assertTrue(sink.toRecording().events.none { it.kind == EventKind.EOF })
    }

    @Test
    fun closingTwiceRecordsOneCloseButStillClosesTheDelegate() = runTest {
        val sink = InMemoryRecordSink()
        val inner = ScriptedStream()
        val stream = RecordingByteStream(inner, sink, TestTimeSource())
        stream.close()
        stream.close()
        assertEquals(1, sink.toRecording().events.count { it.kind == EventKind.CLOSE })
        assertEquals(2, inner.closed)
    }

    @Test
    fun theTransportRecordsEachOpenIntoAFreshSink() = runTest {
        val sinks = ArrayList<InMemoryRecordSink>()
        val inner = ScriptedStream()
        val transport = RecordingTransport(
            delegate = object : Transport {
                override val kind = TransportKind.BLE

                override suspend fun open(): ByteStream = inner
            },
            sinkFactory = { InMemoryRecordSink().also { sinks += it } },
            timeSource = TestTimeSource(),
            header = RecordingHeader("adapters/x", null),
        )
        assertEquals(TransportKind.BLE, transport.kind)
        val first = transport.open()
        val second = transport.open()
        first.write(bytes(1))
        second.write(bytes(2))
        assertEquals(2, sinks.size)
        assertEquals("01", sinks[0].toRecording().events.last().payload)
        assertEquals("02", sinks[1].toRecording().events.last().payload)
        assertEquals("adapters/x", sinks[1].toRecording().header.adapter)
    }

    @Test
    fun aTransportThatCannotOpenStillLeavesARecording() = runTest {
        val sink = InMemoryRecordSink()
        val transport = RecordingTransport(
            delegate = object : Transport {
                override val kind = TransportKind.TCP

                override suspend fun open(): ByteStream = throw TransportException("no route")
            },
            sinkFactory = { sink },
            timeSource = TestTimeSource(),
        )
        assertFailsWith<TransportException> { transport.open() }
        assertEquals(listOf(EventKind.ERROR), sink.toRecording().events.map { it.kind })
        assertEquals("no route", sink.toRecording().events.single().payload)
    }
}
