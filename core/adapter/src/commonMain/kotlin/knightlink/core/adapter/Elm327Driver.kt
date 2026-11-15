package knightlink.core.adapter

import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticLink
import knightlink.core.protocol.ElmResponse
import knightlink.core.protocol.ElmResponseDecoder
import knightlink.core.protocol.ElmStreamFramer
import knightlink.core.transport.ByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public open class AdapterException(message: String, cause: Throwable? = null) : Exception(message, cause)

public class AdapterTimeoutException(command: String, timeout: Duration) :
    AdapterException("No reply to '$command' within $timeout")

/**
 * The link to the adapter has ended: the stream completed or failed, or the driver was stopped. Every call
 * fails with this at once, instead of waiting out its timeout for a reply that can no longer arrive.
 *
 * @property reason what happened to the link, in words
 * @param cause the error that ended the stream, when it failed rather than completed
 */
public class AdapterDisconnectedException(public val reason: String, cause: Throwable? = null) :
    AdapterException("Adapter disconnected: $reason", cause)

/** What the adapter said about itself during initialisation. */
public data class AdapterIdentity(val banner: String?)

/**
 * Talks to an ELM327-compatible adapter over any [ByteStream].
 *
 * The adapter is strictly half-duplex: one command, then wait for the `>` prompt. All access goes
 * through a single lock, so callers on different coroutines can never interleave commands.
 *
 * @param scope owns the reader coroutine; cancel it to stop the driver.
 */
public class Elm327Driver(
    private val stream: ByteStream,
    scope: CoroutineScope,
    private val extendedIds: Boolean = false,
    private val defaultTimeout: Duration = 3.seconds,
) : DiagnosticLink {
    private val framer = ElmStreamFramer()
    private val decoder = ElmResponseDecoder(extendedIds)
    private val responses = Channel<List<String>>(Channel.UNLIMITED)
    private val lock = Mutex()
    private var currentHeader: Int? = null

    /** Set once, when the stream ends for any reason. Read under [lock], written by the reader or its completion handler. */
    @Volatile
    private var disconnection: AdapterDisconnectedException? = null

    private val reader: Job = scope.launch {
        try {
            stream.incoming.collect { chunk -> framer.feed(chunk).forEach { responses.send(it) } }
            disconnect(AdapterDisconnectedException("the adapter closed the connection"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not rethrown: an uncaught exception here would cancel the caller's whole scope, and the session
            // layer needs to survive a dropped link to reconnect.
            disconnect(AdapterDisconnectedException("the connection failed: ${e.message ?: e::class.simpleName}", e))
        }
    }

    init {
        // Also covers cancellation of the scope (stop(), or the session tearing the connection down): without it, a
        // call still waiting for a reply would run into its timeout. A no-op when the reader already reported its end.
        reader.invokeOnCompletion { disconnect(AdapterDisconnectedException("the driver was stopped")) }
    }

    override suspend fun command(text: String): List<String> = command(text, defaultTimeout)

    /** Sends one command and returns the reply lines, without the echo. */
    public suspend fun command(text: String, timeout: Duration): List<String> =
        lock.withLock { exchange(text, timeout) }

    /**
     * Runs the adapter's initialisation script (from its definition file).
     * @throws AdapterException if the adapter rejects a command.
     */
    public suspend fun initialize(script: List<String>, resetTimeout: Duration = 6.seconds): AdapterIdentity =
        lock.withLock {
            currentHeader = null
            var banner: String? = null
            for (step in script) {
                val isReset = step.compact() == "ATZ"
                val reply = exchange(step, if (isReset) resetTimeout else defaultTimeout)
                if (reply.any { it.trim() == "?" }) throw AdapterException("Adapter rejected '$step'")
                reply.firstOrNull { it.contains("ELM", ignoreCase = true) }?.let { banner = it }
            }
            AdapterIdentity(banner)
        }

    /**
     * Sends a diagnostic request to one ECU by its physical request identifier.
     *
     * @param expectedResponses when set, tells the adapter to stop waiting after that many replies,
     *   which removes the adapter's timeout from every poll.
     */
    public suspend fun request(
        requestId: Int,
        payload: ByteArray,
        expectedResponses: Int?,
        timeout: Duration,
    ): ElmResponse = lock.withLock {
        require(!extendedIds) { "29-bit header selection is not implemented yet" }
        require(expectedResponses == null || expectedResponses in 1..15) { "expectedResponses must be 1..15" }
        if (currentHeader != requestId) {
            val header = requestId.toString(16).uppercase().padStart(3, '0')
            val reply = exchange("ATSH$header", timeout)
            if (reply.none { it.compact() == "OK" }) throw AdapterException("Adapter refused header $header: $reply")
            currentHeader = requestId
        }
        val suffix = expectedResponses?.toString(16)?.uppercase().orEmpty()
        decoder.decode(exchange(payload.toHex() + suffix, timeout))
    }

    override suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int?): ElmResponse =
        request(requestId, payload, expectedResponses, defaultTimeout)

    public fun stop() {
        reader.cancel()
    }

    private fun disconnect(reason: AdapterDisconnectedException) {
        if (disconnection != null) return
        // The reason is published before the channel is closed, so a receiver that sees the close also sees the reason.
        disconnection = reason
        responses.close()
    }

    private suspend fun exchange(text: String, timeout: Duration): List<String> {
        disconnection?.let { throw it }
        // Anything still queued belongs to a command that timed out earlier.
        while (responses.tryReceive().isSuccess) Unit
        stream.write((text + "\r").encodeToByteArray())
        // receiveCatching, not receive: a closed channel is the stream ending, which is not an ordinary failure to wait out.
        val result = withTimeoutOrNull(timeout) { responses.receiveCatching() } ?: throw AdapterTimeoutException(text, timeout)
        val lines = result.getOrNull() ?: throw checkNotNull(disconnection) { "responses closed without a reason" }
        val sent = text.compact()
        return lines.filterNot { it.compact() == sent }
    }

    private fun String.compact(): String = filterNot { it.isWhitespace() }.uppercase()
}
