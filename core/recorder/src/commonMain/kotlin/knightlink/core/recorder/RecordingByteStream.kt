package knightlink.core.recorder

import knightlink.core.common.toHex
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.transport.TransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * Writes one session to a [RecordSink]. Writes and reads happen on different coroutines (and threads), so every
 * event takes its timestamp and reaches the sink inside one lock: line order and time order always agree.
 */
internal class SessionLog(private val sink: RecordSink, timeSource: TimeSource, header: RecordingHeader) {
    private val start = timeSource.markNow()
    private val lock = Mutex()
    private var closeLogged = false
    private var eofLogged = false

    init {
        sink.append(Recording.headerLine(header))
    }

    /** For the owner of the log before it is shared with any other coroutine. */
    fun logUnshared(kind: EventKind, payload: String = "") {
        sink.append(Recording.eventLine(RecordingEvent(start.elapsedNow().inWholeMilliseconds, kind, payload)))
    }

    suspend fun log(kind: EventKind, payload: String = "") {
        lock.withLock { logUnshared(kind, payload) }
    }

    /** A session ends once, however often the consumer closes the stream. */
    suspend fun logClose() {
        lock.withLock {
            if (!closeLogged) {
                closeLogged = true
                logUnshared(EventKind.CLOSE)
            }
        }
    }

    /**
     * The remote end hung up. Not recorded once the consumer has closed the stream, because the stream ending is then
     * the consumer's own doing, and recorded only once.
     */
    suspend fun logEof() {
        lock.withLock {
            if (!closeLogged && !eofLogged) {
                eofLogged = true
                logUnshared(EventKind.EOF)
            }
        }
    }
}

private fun Throwable.recordedMessage(): String = message ?: toString()

/**
 * Records everything that crosses a [ByteStream] and passes it on unchanged. Wrap the real transport's stream with
 * this and hand the wrapper to the driver: the driver, and everything above it, behaves exactly as without it.
 *
 * Writes the header and an `open` event when created, a `tx` event for every [write] (before the bytes are handed
 * on, so a write that throws is still on record), an `rx` event for every chunk of [incoming] with the chunk
 * boundaries exactly as delivered, an `error` event when the incoming flow fails (the failure is rethrown), an `eof`
 * event when the incoming flow completes by itself (the adapter hung up; not when the collector is cancelled, and not
 * after the consumer called [close]), and a `close` event on the first [close]. Nothing is buffered, merged or reordered.
 *
 * Timestamps are milliseconds since this object was created, measured on [timeSource]; use a test time source to
 * record on virtual time.
 *
 * @param header describes the session; the adapter definition id and a note go into the first line.
 */
public class RecordingByteStream internal constructor(
    private val delegate: ByteStream,
    private val log: SessionLog,
) : ByteStream {
    public constructor(
        delegate: ByteStream,
        sink: RecordSink,
        timeSource: TimeSource = TimeSource.Monotonic,
        header: RecordingHeader = RecordingHeader(),
    ) : this(delegate, SessionLog(sink, timeSource, header))

    init {
        log.logUnshared(EventKind.OPEN)
    }

    // catch comes before onEach on purpose: it then sees only failures of the delegate, never a failure of the
    // consumer or of the sink, which are not adapter errors.
    override val incoming: Flow<ByteArray> = delegate.incoming
        .catch { e ->
            // A cancelled consumer is not a link failure.
            if (e !is CancellationException) log.log(EventKind.ERROR, e.recordedMessage())
            throw e
        }
        .onEach { chunk -> log.log(EventKind.RX, chunk.toHex()) }
        // A null cause is the delegate completing and the consumer collecting to the end. A failure, whether of the link
        // (already an error event), of the consumer or of the sink, and a cancelled collector all arrive with a cause.
        .onCompletion { cause -> if (cause == null) log.logEof() }

    override suspend fun write(bytes: ByteArray) {
        log.log(EventKind.TX, bytes.toHex())
        delegate.write(bytes)
    }

    override suspend fun close() {
        log.logClose()
        delegate.close()
    }
}

/**
 * Records every link a [delegate] transport opens, each into its own sink from [sinkFactory]. A link that cannot be
 * opened still leaves a recording: the header and an `error` event with the reason.
 *
 * The `open` event's timestamp is how long connecting took, because the recording starts before the connection
 * attempt does.
 */
public class RecordingTransport(
    private val delegate: Transport,
    private val sinkFactory: () -> RecordSink,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val header: RecordingHeader = RecordingHeader(),
) : Transport {
    override val kind: TransportKind get() = delegate.kind

    override suspend fun open(): ByteStream {
        val log = SessionLog(sinkFactory(), timeSource, header)
        val stream = try {
            delegate.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.logUnshared(EventKind.ERROR, e.recordedMessage())
            throw e
        }
        return RecordingByteStream(stream, log)
    }
}
