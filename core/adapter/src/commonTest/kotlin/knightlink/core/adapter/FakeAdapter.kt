package knightlink.core.adapter

import knightlink.core.transport.ByteStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * An adapter the test controls completely: [onCommand] sees every command (the carriage return removed) and decides what, if
 * anything, comes back, and when, through [emit]. Remembers what was written and at what virtual time.
 */
internal class FakeAdapter(
    private val now: () -> Long,
    private val onCommand: FakeAdapter.(String) -> Unit,
) : ByteStream {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    val written = ArrayList<String>()
    val writtenAt = ArrayList<Long>()

    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

    /** Sends [text] exactly as given, in chunks of five bytes like a slow BLE link. */
    fun emit(text: String) {
        text.encodeToByteArray().asList().chunked(5).forEach { channel.trySend(it.toByteArray()) }
    }

    /** A complete reply: [lines], each ended by a carriage return, then an empty line and the prompt. */
    fun reply(vararg lines: String) {
        emit(lines.joinToString("") { "$it\r" } + "\r>")
    }

    override suspend fun write(bytes: ByteArray) {
        val command = bytes.decodeToString().trimEnd('\r')
        written += command
        writtenAt += now()
        onCommand(command)
    }

    override suspend fun close() {
        channel.close()
    }

    /** Runs [block] in [scope] after the virtual [delayMs], for replies that take their time. */
    fun later(scope: CoroutineScope, delayMs: Long, block: () -> Unit) {
        scope.launch {
            delay(delayMs)
            block()
        }
    }
}
