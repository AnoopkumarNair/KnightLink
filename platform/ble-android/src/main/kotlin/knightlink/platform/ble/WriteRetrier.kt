package knightlink.platform.ble

import knightlink.core.transport.TransportException
import kotlinx.coroutines.delay

/** How the Bluetooth stack answered an attempt to start a write. */
internal sealed interface WriteAttempt {
    /** The write was handed over (and, for a write with response, acknowledged). */
    data object Accepted : WriteAttempt

    /** The stack is still busy with a previous operation; the same write may be tried again shortly. */
    data object Busy : WriteAttempt

    /** The write cannot succeed. */
    data class Rejected(
        val reason: String,
    ) : WriteAttempt
}

/**
 * Back-pressure for writes: when the stack says it is busy, wait a little and try again, with a short capped
 * exponential backoff and a hard bound on the number of tries so a wedged stack turns into an error instead of a
 * hang. Android offers no "ready to send" callback for writes without response, so this is how a fast producer is
 * slowed to the link's pace.
 */
internal class WriteRetrier(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialBackoffMs: Long = DEFAULT_INITIAL_BACKOFF_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialBackoffMs in 1..maxBackoffMs) { "backoff must satisfy 1 <= initial <= max" }
    }

    /** Pause before retry number [retry] (0 is the first retry): doubles each time, never beyond the cap. */
    fun backoffMs(retry: Int): Long = minOf(maxBackoffMs, initialBackoffMs shl minOf(retry, MAX_SHIFT))

    /** Runs [attempt] until it is [WriteAttempt.Accepted]. Throws [TransportException] on rejection or when still busy after the last try. */
    suspend fun run(attempt: suspend () -> WriteAttempt) {
        var busyCount = 0
        while (true) {
            when (val result = attempt()) {
                WriteAttempt.Accepted -> return
                is WriteAttempt.Rejected -> throw TransportException("Writing to the adapter failed: ${result.reason}")
                WriteAttempt.Busy -> {
                    busyCount++
                    if (busyCount >= maxAttempts) {
                        throw TransportException("Writing to the adapter failed: the phone's Bluetooth stack stayed busy for $maxAttempts attempts")
                    }
                    delay(backoffMs(busyCount - 1))
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 100
        const val DEFAULT_INITIAL_BACKOFF_MS = 2L
        const val DEFAULT_MAX_BACKOFF_MS = 40L
        const val MAX_SHIFT = 20
    }
}
