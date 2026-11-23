package knightlink.core.scheduler

import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import knightlink.core.protocol.ElmStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The one operation the loop needs from an adapter driver. */
public fun interface DiagnosticRequester {
    public suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int?): ElmResponse
}

public class PollResult(
    /** The item the request was made for; in a batch the first of [items]. */
    public val item: PollItem,
    /** Null when the request failed; see [error]. */
    public val response: ElmResponse?,
    public val error: Throwable?,
    /** Time since the loop started. */
    public val at: Duration,
    public val roundTrip: Duration,
    /** Every item the request served: just [item] unless the request was a batch. */
    public val items: List<PollItem> = listOf(item),
    /** The items whose data the reply held. For a batch this can be fewer than [items]. */
    public val answeredItems: List<PollItem> = if (response?.messages?.any { it is DiagnosticResponse.Positive } == true) items else emptyList(),
) {
    public val answered: Boolean get() = response?.messages?.any { it is DiagnosticResponse.Positive } == true

    /** The status word that says the vehicle bus could not be reached, when the reply held one. */
    public val busFault: ElmStatus? get() = response?.busFault
}

/** Thrown when the adapter stops answering; the session layer should reconnect. */
public class PollingStalledException(public val failures: Int, cause: Throwable) :
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
    /**
     * The zero of the loop's clock, which is the zero the scheduler's times run from. Null: every collection starts its own clock at its own start
     * (then [PollScheduler.restart] must be called before it, as the old times mean nothing on a new clock). With a mark the clock keeps running
     * across collections, so a pause in polling (to ask something else) does not make every item due at once.
     */
    private val origin: TimeMark? = null,
) {
    public fun results(): Flow<PollResult> = flow {
        val start = origin ?: timeSource.markNow()
        var currentRequestId: Int? = null
        var failures = 0

        while (true) {
            val poll = when (val decision = scheduler.next(start.elapsedNow(), currentRequestId)) {
                is PollDecision.Poll -> decision
                is PollDecision.Wait -> {
                    // A wait of "forever" (only items that are asked once) still looks again now and then.
                    delay(minOf(decision.duration, idleDelay))
                    continue
                }
                PollDecision.Idle -> {
                    delay(idleDelay)
                    continue
                }
            }

            val sent = start.elapsedNow()
            var response: ElmResponse? = null
            var error: Throwable? = null
            try {
                response = requester.request(poll.requestId, poll.payload, poll.expectedResponses)
                currentRequestId = poll.requestId
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
            val answered = answeredItems(poll, response)
            val result = PollResult(poll.item, response, error, now, now - sent, poll.items, answered)
            scheduler.onResult(poll, sent, answered.toSet())
            emit(result)
        }
    }
}

/**
 * The items of [poll] whose data [response] holds. A single request is answered by any positive reply. A batch's reply is
 * `<pid> <data> <pid> <data> ...` for the PIDs the ECU supports, in request order, cut by the items' lengths; it is read until it ends
 * or something unexpected comes (an unknown PID, a reply cut short), so what arrived before is still counted.
 */
public fun answeredItems(poll: PollDecision.Poll, response: ElmResponse?): List<PollItem> {
    val positives = response?.messages?.filterIsInstance<DiagnosticResponse.Positive>().orEmpty()
    if (poll.items.size == 1) return if (positives.isEmpty()) emptyList() else poll.items
    val answered = ArrayList<PollItem>()
    for (message in positives) {
        if (message.service != PollItem.SERVICE_LIVE_DATA.toInt()) continue
        var offset = 0
        while (offset < message.data.size) {
            val item = poll.items.firstOrNull { it.pidByte == message.data[offset] } ?: break
            val end = offset + 1 + checkNotNull(item.dataLength)
            if (end > message.data.size) break
            if (item !in answered) answered += item
            offset = end
        }
    }
    return answered
}
