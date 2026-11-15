package knightlink.core.recorder

import knightlink.core.transport.ByteStream
import knightlink.core.transport.TransportException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

/** How a [ReplayByteStream] decides what to answer. */
public enum class ReplayMode {
    /**
     * The consumer must write exactly what was recorded, in the recorded order; anything else is a
     * [ReplayMismatchException]. Reproduces the session including its timing, and its end: a recorded `eof` completes
     * the incoming stream at that time and makes later writes fail with a [TransportException].
     */
    STRICT,

    /**
     * Each command is answered with a recorded response to the same command, whatever the order. For a consumer that
     * behaves differently from the one that was recorded, for example after a change to the scheduler.
     *
     * Which ECU a request goes to is part of what it is, so a diagnostic request (hex digits only, such as `0100` or
     * `010C1`) is matched on the header selected at that moment as well as on its text. The selected header is followed
     * the way the adapter follows it, on the recording and on the replay alike: `ATSH<id>` selects `<id>` if it was
     * answered `OK` (an `ATSH` answered `?` changes nothing), and `ATZ` and `ATD` deselect it. A request is answered
     * only from what was recorded for the same header, never from another ECU's answer: a request that was never
     * recorded for the selected header is unknown, answered with `?`, and counted in
     * [ReplayByteStream.unknownCommands] as `<header>:<request>`. Every other command (`AT...`, `ST...`) is answered by
     * the adapter itself whatever the header, so it is matched on its text alone.
     *
     * For one key, the recorded responses are used in turn and start again from the first when they run out, so a
     * consumer may ask more or less often than the recording did.
     *
     * The recording's `eof` event is ignored: the replayed link never hangs up by itself, and writes made after
     * the recorded hang-up, which never reached the adapter, are not replayed either. Use [STRICT] to reproduce a hang-up.
     */
    BY_COMMAND,
}

/**
 * In [ReplayMode.STRICT] the consumer wrote something other than the recording says it wrote.
 *
 * @param eventIndex index in [Recording.events] of the `tx` event the consumer deviated from; the number of events when
 *   the consumer wrote after the recording had run out of writes.
 * @param expected the recorded command, with `\r` and non-printable bytes spelled out.
 * @param actual what the consumer wrote, in the same notation.
 */
public class ReplayMismatchException(
    public val eventIndex: Int,
    public val expected: String,
    public val actual: String,
) : Exception("Replay mismatch at event #$eventIndex: the recording has '$expected' but the consumer wrote '$actual'")

/** The recorded session ended with an `error` event; a strict replay fails its incoming stream the same way. */
public class ReplayedFailureException(message: String) : TransportException(message)

/**
 * Plays a [Recording] back as if it were the adapter, so that the unmodified driver, probe, scheduler and decoder
 * can be run against what a real adapter once said. Time is spent with `delay`, so tests run it on virtual time.
 *
 * Responses are delivered from the collector of [incoming], not from [write]: a [write] returns at once and the
 * recorded latency then counts against the consumer's reply timeout, as it did on the real link.
 *
 * In [ReplayMode.STRICT] a recorded `eof` (the adapter hung up) completes [incoming] normally at the recorded time, and
 * from then on every [write] throws [TransportException], as on a dead link.
 *
 * Not thread-safe: use it from one dispatcher, like the simulator.
 *
 * @param speed recorded delays are divided by it; 2.0 replays twice as fast. Must be positive.
 */
public class ReplayByteStream(
    private val recording: Recording,
    private val mode: ReplayMode,
    private val speed: Double = 1.0,
) : ByteStream {
    init {
        require(speed > 0.0 && speed.isFinite()) { "speed must be a positive number, was $speed" }
    }

    /** What the incoming flow does next, [delayMs] after the previous delivery. */
    private sealed class Delivery(val delayMs: Long, val consumedUpTo: Int) {
        class Chunk(delayMs: Long, val bytes: ByteArray, consumedUpTo: Int) : Delivery(delayMs, consumedUpTo)

        class End(consumedUpTo: Int) : Delivery(0, consumedUpTo)

        /** The adapter hung up: [incoming] completes and the link is dead for writes from then on. */
        class Eof(delayMs: Long, consumedUpTo: Int) : Delivery(delayMs, consumedUpTo)

        class Fail(delayMs: Long, val message: String, consumedUpTo: Int) : Delivery(delayMs, consumedUpTo)
    }

    /** The consecutive `tx` events of the recording (what the consumer must write) and what the adapter answered. */
    private class Run {
        var bytes = ByteArray(0)
        val txEventIndices = ArrayList<Int>()

        /** Total byte count after each `tx` event, parallel to [txEventIndices]. */
        val endOffsets = ArrayList<Int>()
        val deliveries = ArrayList<Delivery>()
        var consumedUpTo = 0

        fun add(eventIndex: Int, chunk: ByteArray) {
            bytes += chunk
            txEventIndices += eventIndex
            endOffsets += bytes.size
            consumedUpTo = eventIndex + 1
        }

        /** The `tx` event that holds the byte at [offset] of [bytes]. */
        fun eventAt(offset: Int): Int = txEventIndices[endOffsets.indexOfFirst { it > offset }]
    }

    private val deliveries = Channel<Delivery>(Channel.UNLIMITED)
    private var closed = false

    /** STRICT: the recorded hang-up has happened; the link is dead. */
    private var remoteClosed = false

    // STRICT state
    private val runs = ArrayList<Run>()
    private var runIndex = 0
    private var runMatched = 0
    private var consumed = 0
    private var mismatch: ReplayMismatchException? = null

    // BY_COMMAND state
    private val responses = HashMap<ExchangeKey, List<List<Delivery.Chunk>>>()
    private val timesUsed = HashMap<ExchangeKey, Int>()
    private val pendingCommand = StringBuilder()
    private val headers = HeaderTracker()
    private val unknown = ArrayList<String>()

    /**
     * BY_COMMAND: how many commands the recording has no response for. Those are answered with `?`, as an ELM327 would.
     * A request counts as unknown when nothing was recorded for it under the header selected now, even if another ECU
     * was asked the same thing.
     */
    public val unknownCommands: Int get() = unknown.size

    /**
     * BY_COMMAND: the unknown commands in the order they were sent, normalised (upper case, no whitespace). A diagnostic
     * request is shown with the header it was sent under, `7E1:010C1`; with no header selected, and for any other
     * command, it is the text alone.
     */
    public val unknownCommandTexts: List<String> get() = unknown.toList()

    /**
     * STRICT: how many recorded events have not been played yet; 0 once the session has been replayed to its end.
     * BY_COMMAND has no order to consume, so it reports null.
     */
    public val remainingEvents: Int? get() = if (mode == ReplayMode.STRICT) recording.events.size - consumed else null

    /** STRICT: the first deviation of the consumer, which is also what every later [write] throws. */
    public val firstMismatch: ReplayMismatchException? get() = mismatch

    init {
        when (mode) {
            ReplayMode.STRICT -> prepareStrict()
            ReplayMode.BY_COMMAND -> prepareByCommand()
        }
    }

    override val incoming: Flow<ByteArray> = flow {
        for (delivery in deliveries) {
            if (closed) return@flow
            if (delivery.delayMs > 0) delay((delivery.delayMs / speed).milliseconds)
            if (closed) return@flow
            when (delivery) {
                is Delivery.Chunk -> {
                    emit(delivery.bytes)
                    advance(delivery.consumedUpTo)
                }
                is Delivery.End -> {
                    advance(delivery.consumedUpTo)
                    return@flow
                }
                is Delivery.Eof -> {
                    advance(delivery.consumedUpTo)
                    remoteClosed = true
                    return@flow
                }
                is Delivery.Fail -> {
                    advance(delivery.consumedUpTo)
                    throw ReplayedFailureException(delivery.message)
                }
            }
        }
    }

    override suspend fun write(bytes: ByteArray) {
        check(!closed) { "The replay stream is closed" }
        // Like a socket whose peer has gone: the consumer finds out by writing, and it is a link failure, not a mismatch.
        if (remoteClosed) throw TransportException("The replayed adapter closed the connection")
        mismatch?.let { throw it }
        when (mode) {
            ReplayMode.STRICT -> matchStrict(bytes)
            ReplayMode.BY_COMMAND -> answerByCommand(bytes)
        }
    }

    override suspend fun close() {
        closed = true
        deliveries.close()
    }

    private fun advance(upTo: Int) {
        if (upTo > consumed) consumed = upTo
    }

    // STRICT -----------------------------------------------------------------------------------------------

    /**
     * Cuts the recording into runs of consecutive `tx` events, each followed by the `rx` events that answered it.
     * Delays are measured from the event just before, so the first chunk of a response carries the adapter's latency.
     * Events after the first `close`, `error` or `eof` are not replayed: nothing happens on a link after it ends. (After
     * an `eof` that includes the writes the consumer still attempted; the replay rejects those itself, see [write].)
     */
    private fun prepareStrict() {
        val events = recording.events
        // The chunks before the first command (an adapter's greeting) are delivered when the stream is first read.
        val leading = ArrayList<Delivery>()
        var target: MutableList<Delivery> = leading
        var run: Run? = null
        var previousTime = events.firstOrNull()?.timeMs ?: 0L
        var previousWasTx = false
        for ((index, event) in events.withIndex()) {
            val gap = maxOf(0L, event.timeMs - previousTime)
            previousTime = event.timeMs
            when (event.kind) {
                EventKind.OPEN -> Unit
                EventKind.TX -> {
                    val chunk = event.bytes
                    // Nothing for the consumer to match; an empty write is not a command.
                    if (chunk.isNotEmpty()) {
                        // Consecutive writes are one run, however the consumer chunked them.
                        val current = run?.takeIf { previousWasTx } ?: Run().also {
                            runs += it
                            target = it.deliveries
                        }
                        current.add(index, chunk)
                        run = current
                        previousWasTx = true
                    }
                }
                EventKind.RX -> {
                    target.add(Delivery.Chunk(gap, event.bytes, index + 1))
                    previousWasTx = false
                }
                EventKind.CLOSE -> {
                    target.add(Delivery.End(events.size))
                    break
                }
                EventKind.EOF -> {
                    target.add(Delivery.Eof(gap, events.size))
                    break
                }
                EventKind.ERROR -> {
                    target.add(Delivery.Fail(gap, event.payload, events.size))
                    break
                }
            }
        }
        // Skipping the leading `open` events keeps remainingEvents at 0 for a session that was replayed completely.
        consumed = events.takeWhile { it.kind == EventKind.OPEN }.size
        leading.forEach { deliveries.trySend(it) }
    }

    private fun matchStrict(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val run = runs.getOrNull(runIndex) ?: throw fail(recording.events.size, END_OF_RECORDING, renderUntilCommandEnd(bytes, offset))
            val take = minOf(run.bytes.size - runMatched, bytes.size - offset)
            for (i in 0 until take) {
                if (bytes[offset + i] != run.bytes[runMatched + i]) {
                    val sent = renderBytes(run.bytes, 0, runMatched) + renderUntilCommandEnd(bytes, offset)
                    throw fail(run.eventAt(runMatched + i), renderBytes(run.bytes), sent)
                }
            }
            runMatched += take
            offset += take
            if (runMatched == run.bytes.size) {
                advance(run.consumedUpTo)
                run.deliveries.forEach { deliveries.trySend(it) }
                runIndex++
                runMatched = 0
            }
        }
    }

    private fun fail(eventIndex: Int, expected: String, actual: String): ReplayMismatchException =
        ReplayMismatchException(eventIndex, expected, actual).also { mismatch = it }

    /** The consumer's bytes from [from] to the end of that command, so a message shows the command and not a whole burst. */
    private fun renderUntilCommandEnd(bytes: ByteArray, from: Int): String {
        val end = bytes.indexOf('\r'.code.toByte(), from).let { if (it < 0) bytes.size else it + 1 }
        return renderBytes(bytes, from, end)
    }

    private fun ByteArray.indexOf(value: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }

    // BY_COMMAND -------------------------------------------------------------------------------------------

    private fun prepareByCommand() {
        val events = recording.events
        val byKey = LinkedHashMap<ExchangeKey, MutableList<List<Delivery.Chunk>>>()
        for (exchange in indexExchanges(events)) {
            var previousTime = events[exchange.txIndex].timeMs
            val chunks = exchange.rxIndices.map { rxIndex ->
                val event = events[rxIndex]
                Delivery.Chunk(maxOf(0L, event.timeMs - previousTime), event.bytes, 0).also { previousTime = event.timeMs }
            }
            byKey.getOrPut(exchange.replayKey) { ArrayList() }.add(chunks)
        }
        responses.putAll(byKey)
    }

    private fun answerByCommand(bytes: ByteArray) {
        for (byte in bytes) {
            val c = byte.toAdapterChar()
            if (c != '\r') {
                pendingCommand.append(c)
                continue
            }
            val command = commandKey(pendingCommand)
            pendingCommand.clear()
            // Only what was recorded for this very header answers a request: another ECU's answer would be a wrong one.
            val key = ExchangeKey.of(command, headers.selected)
            val recorded = responses[key]
            if (recorded == null) {
                unknown += key.toString()
                deliveries.trySend(Delivery.Chunk(0, UNKNOWN_COMMAND_REPLY, 0))
                headers.observe(command) { false }
                continue
            }
            // Next unused response; when every recorded one has been used, start again with the first.
            val used = timesUsed.getOrElse(key) { 0 }
            timesUsed[key] = used + 1
            val answer = recorded[used % recorded.size]
            answer.forEach { deliveries.trySend(it) }
            headers.observe(command) { isOkResponse(answer.map { it.bytes }.concat()) }
        }
    }

    private companion object {
        const val END_OF_RECORDING = "<end of recording>"

        /** What an ELM327 prints for a command it does not know. */
        val UNKNOWN_COMMAND_REPLY = "?\r\r>".encodeToByteArray()
    }
}
