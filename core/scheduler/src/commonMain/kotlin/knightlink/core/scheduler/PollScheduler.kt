package knightlink.core.scheduler

import kotlin.time.Duration

/** One request to repeat. [period] is the wanted interval; the achieved one depends on the adapter's speed. */
public class PollItem(
    public val id: String,
    /** Physical request identifier of the ECU to ask. */
    public val requestId: Int,
    public val payload: ByteArray,
    public val period: Duration,
    /** Replies to wait for. 1 lets the adapter return immediately instead of waiting out its timeout. */
    public val expectedResponses: Int? = 1,
) {
    init {
        require(period > Duration.ZERO) { "period must be positive" }
    }

    override fun toString(): String = id
}

public sealed interface PollDecision {
    public class Poll(public val item: PollItem) : PollDecision
    public class Wait(public val duration: Duration) : PollDecision
    public data object Idle : PollDecision
}

/**
 * Decides what to ask next. An adapter handles one request at a time and only a limited number per
 * second, so wanted rates are a budget to share, not a promise.
 *
 * Rules:
 * - An item is due when its period has elapsed since its last request was sent.
 * - Among due items the most overdue relative to its own period goes first, so when demand exceeds
 *   capacity every item slows down by the same proportion instead of the slow ones starving.
 * - Changing ECU costs an extra adapter command, so a due item on the currently selected ECU is
 *   preferred unless another ECU's item is overdue by [switchThreshold] times as much.
 * - An item that keeps getting no answer is backed off (its period doubles, up to [maxBackoff] times)
 *   and returns to normal on its first answer.
 *
 * Pure and deterministic: time is passed in, nothing is read from a clock.
 */
public class PollScheduler(
    items: List<PollItem>,
    private val switchThreshold: Double = 1.5,
    private val missesBeforeBackoff: Int = 3,
    private val maxBackoff: Int = 32,
) {
    private class Entry(val item: PollItem) {
        var lastPolled: Duration? = null
        var misses = 0
        var backoff = 1
        val effectivePeriod: Duration get() = item.period * backoff
    }

    private val entries = items.sortedBy { it.period }.map(::Entry)

    public fun next(now: Duration, currentRequestId: Int? = null): PollDecision {
        if (entries.isEmpty()) return PollDecision.Idle

        var best: Entry? = null
        var bestUrgency = 0.0
        var bestOnCurrent: Entry? = null
        var bestOnCurrentUrgency = 0.0
        var soonest = Duration.INFINITE

        for (entry in entries) {
            val last = entry.lastPolled
            // Never polled: due at once. Entries are sorted by period, so fast items start first.
            val urgency = if (last == null) Double.MAX_VALUE else (now - last) / entry.effectivePeriod
            if (urgency < 1.0) {
                soonest = minOf(soonest, last!! + entry.effectivePeriod - now)
                continue
            }
            if (best == null || urgency > bestUrgency) {
                best = entry
                bestUrgency = urgency
            }
            if (entry.item.requestId == currentRequestId && (bestOnCurrent == null || urgency > bestOnCurrentUrgency)) {
                bestOnCurrent = entry
                bestOnCurrentUrgency = urgency
            }
        }

        val chosen = best ?: return PollDecision.Wait(soonest)
        val stay = bestOnCurrent
        return if (stay != null && bestUrgency <= bestOnCurrentUrgency * switchThreshold) {
            PollDecision.Poll(stay.item)
        } else {
            PollDecision.Poll(chosen.item)
        }
    }

    /**
     * Forgets everything learned about the items: every one is due at once and none is backed off. For a new start of the polling
     * (after the adapter was initialised again, when a silence taught nothing about the items) and for a loop whose clock has restarted.
     */
    public fun restart() {
        for (entry in entries) {
            entry.lastPolled = null
            entry.misses = 0
            entry.backoff = 1
        }
    }

    /**
     * Records the outcome of a poll.
     * @param sentAt when the request was sent. Periods run from send to send, so the time a reply
     *   takes does not stretch the interval.
     * @param answered false when the ECU gave no usable reply
     */
    public fun onResult(item: PollItem, sentAt: Duration, answered: Boolean) {
        val entry = entries.firstOrNull { it.item === item } ?: return
        entry.lastPolled = sentAt
        if (answered) {
            entry.misses = 0
            entry.backoff = 1
        } else if (++entry.misses >= missesBeforeBackoff) {
            entry.backoff = minOf(entry.backoff * 2, maxBackoff)
        }
    }
}
