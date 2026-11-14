package knightlink.core.scheduler

import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The one operation the loop needs from an adapter driver. */
public fun interface DiagnosticRequester {
    public suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int?): ElmResponse
}

public class PollResult(
    public val item: PollItem,
    /** Null when the request failed; see [error]. */
    public val response: ElmResponse?,
    public val error: Throwable?,
    /** Time since the loop started. */
    public val at: Duration,
    public val roundTrip: Duration,
) {
    public val answered: Boolean get() = response?.messages?.any { it is DiagnosticResponse.Positive } == true
}

/** Thrown when the adapter stops answering; the session layer should reconnect. */
public class PollingStalledException(failures: Int, cause: Throwable) :
    Exception("Adapter failed $failures requests in a row", cause)

/**
 * Runs a [PollScheduler] against an adapter and emits every result. Cold: polling starts on
 * collection and stops when the collector is cancelled.
 */
public class PollingLoop(
    private val requester: DiagnosticRequester,
    private val scheduler: PollScheduler,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val maxConsecutiveFailures: Int = 5,
    private val idleDelay: Duration = 1.seconds,
) {
    public fun results(): Flow<PollResult> = flow {
        val start = timeSource.markNow()
        var currentRequestId: Int? = null
        var failures = 0

        while (true) {
            val item = when (val decision = scheduler.next(start.elapsedNow(), currentRequestId)) {
                is PollDecision.Poll -> decision.item
                is PollDecision.Wait -> { delay(decision.duration); continue }
                PollDecision.Idle -> { delay(idleDelay); continue }
            }

            val sent = start.elapsedNow()
            var response: ElmResponse? = null
            var error: Throwable? = null
            try {
                response = requester.request(item.requestId, item.payload, item.expectedResponses)
                currentRequestId = item.requestId
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                // The header state is unknown after a failure; do not favour any ECU.
                currentRequestId = null
                if (++failures >= maxConsecutiveFailures) throw PollingStalledException(failures, e)
            }

            val now = start.elapsedNow()
            val result = PollResult(item, response, error, now, now - sent)
            scheduler.onResult(item, sent, result.answered)
            emit(result)
        }
    }
}
