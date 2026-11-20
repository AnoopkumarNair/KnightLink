package knightlink.core.scheduler

import kotlin.time.Duration

/**
 * One request to repeat. [period] is the wanted interval; the achieved one depends on the adapter's speed. A period of
 * [Duration.INFINITE] asks once (and again only after [PollScheduler.restart]).
 */
public class PollItem(
    public val id: String,
    /** Physical request identifier of the ECU to ask. */
    public val requestId: Int,
    public val payload: ByteArray,
    public val period: Duration,
    /**
     * Replies to wait for, appended to the request as the adapter's response-count digit. Only safe when the reply fits one CAN
     * frame: with a count the adapter stops after the first frame of a longer reply and never sends flow control, so the rest of the
     * reply never arrives. Null (the default) lets the adapter wait out its timeout, which a multi-frame reply needs.
     */
    public val expectedResponses: Int? = null,
    /**
     * Number of data bytes after the PID in the positive reply, when known. Only an item with a known length can share a request with
     * others, because the reply is cut into PIDs by their lengths.
     */
    public val dataLength: Int? = null,
) {
    init {
        require(period > Duration.ZERO) { "period must be positive" }
        require(dataLength == null || dataLength >= 0) { "dataLength must not be negative" }
    }

    /** A service 01 request for one PID with a known reply length: may be asked together with others of the same ECU. */
    public val batchable: Boolean get() = dataLength != null && payload.size == 2 && payload[0] == SERVICE_LIVE_DATA

    /** The PID byte of a [batchable] item. */
    internal val pidByte: Byte get() = payload[1]

    override fun toString(): String = id

    internal companion object {
        const val SERVICE_LIVE_DATA: Byte = 0x01
    }
}

public sealed interface PollDecision {
    /**
     * Send one request now: [item] alone, or [item] together with [companions], the other service 01 PIDs of the same ECU that were
     * due as well. The request is then `01 <pid> <pid> ...`, in the order of [items].
     */
    public class Poll(public val item: PollItem, public val companions: List<PollItem> = emptyList()) : PollDecision {
        /** Every item this request serves, [item] first. */
        public val items: List<PollItem> = listOf(item) + companions

        public val requestId: Int get() = item.requestId

        public val payload: ByteArray =
            if (companions.isEmpty()) item.payload else byteArrayOf(PollItem.SERVICE_LIVE_DATA) + items.map { it.pidByte }.toByteArray()

        /**
         * The response-count digit: the item's own for a single request; for a batch 1 only when every reply together fits one CAN
         * frame (7 data bytes), otherwise none, because the adapter would cut the multi-frame reply short.
         */
        public val expectedResponses: Int? =
            if (companions.isEmpty()) {
                item.expectedResponses
            } else if (1 + items.sumOf { 1 + checkNotNull(it.dataLength) } <= SINGLE_FRAME_BYTES) {
                1
            } else {
                null
            }

        private companion object {
            const val SINGLE_FRAME_BYTES = 7
        }
    }

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
 * - With [maxBatch] above 1, service 01 items of one ECU whose reply length is known share a request: the chosen item plus up to
 *   `maxBatch - 1` others of the same ECU that are due or nearly due (at least [BATCH_EARLY] of their period has passed), the most
 *   overdue first. Fewer requests carry the same readings, which is what lets fast items be fast.
 * - Batching is dropped where it does not work. An item that is left out of a batch's answer twice is asked alone from then on. An ECU
 *   that does not answer a batch at all is asked one single request next; if that is answered, the batch was the problem, and after two
 *   such confirmed failures every request to that ECU is single for good ([singleRequestOnly]). A silent ECU proves nothing about
 *   batching, so a failed single request does not count.
 *
 * Pure and deterministic: time is passed in, nothing is read from a clock.
 */
public class PollScheduler(
    items: List<PollItem>,
    private val switchThreshold: Double = 1.5,
    private val missesBeforeBackoff: Int = 3,
    private val maxBackoff: Int = 32,
    private val maxBatch: Int = 1,
) {
    private class Entry(val item: PollItem) {
        var lastPolled: Duration? = null
        var misses = 0
        var backoff = 1
        var batchMisses = 0
        val effectivePeriod: Duration get() = item.period * backoff

        /** Asked alone because it was left out of batch answers. */
        val solo: Boolean get() = batchMisses >= BATCH_MISSES_BEFORE_SOLO
    }

    init {
        require(maxBatch in 1..MAX_BATCH) { "maxBatch must be 1..$MAX_BATCH" }
    }

    private val entries = ArrayList(items.sortedBy { it.period }.map(::Entry))
    private val entryOf = HashMap<PollItem, Entry>(entries.associateBy { it.item })
    private var extras: List<PollItem> = emptyList()

    private val suspects = HashSet<Int>()
    private val confirmedBatchFailures = HashMap<Int, Int>()
    private val singleOnly = HashSet<Int>()

    /** Request ids of the ECUs that failed batches and are now asked one PID at a time. */
    public val singleRequestOnly: Set<Int> get() = singleOnly

    /**
     * Replaces the items added by an earlier call (not the ones given at construction) with [items], which share the request budget with the
     * rest as every item does: the most overdue relative to its own period goes first. New items are due at once; an item whose id was among
     * the earlier extras keeps what was learned about its timing (when it was last asked, its misses and back-off), so changing the list
     * does not make everything due at once again. For items that become known while the session runs, such as the data identifiers a scan
     * found. Call it between polling rounds, not while [next] is running.
     */
    public fun replaceExtras(items: List<PollItem>) {
        val earlier = extras.mapNotNull { entryOf[it] }.associateBy { it.item.id }
        entries.removeAll { it.item in extras }
        extras.forEach { entryOf.remove(it) }
        extras = items.toList()
        for (item in extras) {
            val entry = Entry(item)
            earlier[item.id]?.let { old ->
                entry.lastPolled = old.lastPolled
                entry.misses = old.misses
                entry.backoff = old.backoff
                entry.batchMisses = old.batchMisses
            }
            entries += entry
            entryOf[item] = entry
        }
        entries.sortBy { it.item.period }
    }

    public fun next(now: Duration, currentRequestId: Int? = null): PollDecision {
        if (entries.isEmpty()) return PollDecision.Idle

        var best: Entry? = null
        var bestUrgency = 0.0
        var bestOnCurrent: Entry? = null
        var bestOnCurrentUrgency = 0.0
        var soonest = Duration.INFINITE
        val urgencies = HashMap<Entry, Double>()

        for (entry in entries) {
            val last = entry.lastPolled
            // Never polled: due at once. Entries are sorted by period, so fast items start first.
            val urgency = if (last == null) Double.MAX_VALUE else (now - last) / entry.effectivePeriod
            urgencies[entry] = urgency
            if (urgency < 1.0) {
                if (last != null) soonest = minOf(soonest, last + entry.effectivePeriod - now)
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
        val pick = if (stay != null && bestUrgency <= bestOnCurrentUrgency * switchThreshold) stay else chosen
        return PollDecision.Poll(pick.item, companionsOf(pick, urgencies))
    }

    private fun companionsOf(pick: Entry, urgencies: Map<Entry, Double>): List<PollItem> {
        val requestId = pick.item.requestId
        if (maxBatch == 1 || !pick.item.batchable || pick.solo || requestId in singleOnly || requestId in suspects) return emptyList()
        return entries
            .filter { it !== pick && it.item.requestId == requestId && it.item.batchable && !it.solo && urgencies.getValue(it) >= BATCH_EARLY }
            .sortedByDescending { urgencies.getValue(it) }
            .take(maxBatch - 1)
            .map { it.item }
    }

    /**
     * Forgets everything learned about the items' timing: every one is due at once and none is backed off. For a new start of the polling
     * (after the adapter was initialised again, when a silence taught nothing about the items) and for a loop whose clock has restarted.
     * What was learned about batching stays: it is about the vehicle, not about the outage.
     */
    public fun restart() {
        for (entry in entries) {
            entry.lastPolled = null
            entry.misses = 0
            entry.backoff = 1
        }
        suspects.clear()
    }

    /**
     * Records the outcome of a poll of one item.
     * @param sentAt when the request was sent. Periods run from send to send, so the time a reply
     *   takes does not stretch the interval.
     * @param answered false when the ECU gave no usable reply
     */
    public fun onResult(item: PollItem, sentAt: Duration, answered: Boolean) {
        onResult(PollDecision.Poll(item), sentAt, if (answered) setOf(item) else emptySet())
    }

    /**
     * Records the outcome of a request that [poll] described.
     * @param answered the items whose data the reply held
     */
    public fun onResult(poll: PollDecision.Poll, sentAt: Duration, answered: Set<PollItem>) {
        for (item in poll.items) {
            val entry = entryOf[item] ?: continue
            entry.lastPolled = sentAt
            if (item in answered) {
                entry.misses = 0
                entry.backoff = 1
            } else if (++entry.misses >= missesBeforeBackoff) {
                entry.backoff = minOf(entry.backoff * 2, maxBackoff)
            }
        }
        learnBatching(poll, answered)
    }

    private fun learnBatching(poll: PollDecision.Poll, answered: Set<PollItem>) {
        val ecu = poll.requestId
        if (poll.items.size > 1) {
            when {
                poll.items.all { it in answered } -> {
                    confirmedBatchFailures.remove(ecu)
                    suspects.remove(ecu)
                }
                // Some PIDs missing from the answer: those are the problem, not batching.
                poll.items.any { it in answered } -> poll.items.filter { it !in answered }.forEach { entryOf[it]?.let { e -> e.batchMisses++ } }
                // Nothing came back: the ECU may not take batches, or may be silent. The next single request tells which.
                else -> suspects += ecu
            }
        } else if (suspects.remove(ecu) && answered.isNotEmpty()) {
            val failures = (confirmedBatchFailures[ecu] ?: 0) + 1
            confirmedBatchFailures[ecu] = failures
            if (failures >= BATCH_FAILURES_BEFORE_SINGLE_ONLY) singleOnly += ecu
        }
    }

    public companion object {
        /** Largest request: service 01 and six PIDs fit the seven data bytes of one CAN frame. */
        public const val MAX_BATCH: Int = 6

        /** How much of its period an item must have waited to ride along with a batch that is on its way anyway. */
        public const val BATCH_EARLY: Double = 0.7

        private const val BATCH_MISSES_BEFORE_SOLO = 2
        private const val BATCH_FAILURES_BEFORE_SINGLE_ONLY = 2
    }
}
