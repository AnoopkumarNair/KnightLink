package knightlink.voice.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What [SpeechQueue.offer] did, and the queue that results. */
internal class Offered(val queue: SpeechQueue, val result: SpeakResult)

/** What [SpeechQueue.poll] found: the line to speak now (null if none may be spoken) and the queue that results. */
internal class Polled(val queue: SpeechQueue, val utterance: Utterance?)

/**
 * The policy of the voice, free of threads and coroutines so that it can be tested on its own: which line is next, which are
 * replaced, which are not worth saying again, which have gone stale.
 *
 * It is an immutable value: every operation returns the queue that results instead of changing this one. That is what lets
 * [QueuedSpeaker] apply a decision atomically with a compare-and-set on a `StateFlow` on every platform, without a lock.
 *
 * Order is higher [Priority] first and first come first served within a priority. At most one line waits per category.
 *
 * @param quietPeriod how long a line spoken in a category suppresses an identical line in the same category
 * @param capacity how many lines may wait; beyond it the lowest priority, oldest line is dropped, so a burst of chatter can never
 *   bury a warning
 * @param timeSource where "now" comes from; a test source makes the policy deterministic
 */
internal class SpeechQueue private constructor(
    private val quietPeriod: Duration,
    private val capacity: Int,
    private val timeSource: TimeSource,
    private val waiting: List<Entry>,
    private val spoken: Map<String, Spoken>,
    private val speaking: Utterance?,
    private val nextSeq: Long,
    private val nextFrontSeq: Long,
) {
    constructor(
        quietPeriod: Duration = 60.seconds,
        capacity: Int = DEFAULT_CAPACITY,
        timeSource: TimeSource = TimeSource.Monotonic,
    ) : this(quietPeriod, capacity, timeSource, emptyList(), emptyMap(), null, 0L, -1L)

    private class Entry(val utterance: Utterance, val enqueuedAt: TimeMark, val seq: Long)

    private class Spoken(val text: String, val at: TimeMark)

    init {
        require(capacity >= 1) { "A queue must hold at least one line" }
    }

    /** The lines waiting, in the order they would be spoken. */
    val waitingLines: List<Utterance> get() = waiting.map { it.utterance }

    /** Whether the line at the head may be spoken under [minPriority]; the head is the highest priority, so no other line can be. */
    fun hasEligible(minPriority: Priority): Boolean = waiting.firstOrNull()?.let { it.utterance.priority >= minPriority } == true

    /**
     * Offers [utterance]. With [force] (a repeat the driver asked for) the quiet-period check and the check against the line being
     * spoken are skipped.
     */
    fun offer(
        utterance: Utterance,
        force: Boolean = false,
    ): Offered {
        val category = utterance.category
        if (!force && speaking?.let { it.category == category && it.text == utterance.text } == true) return unchanged()
        val existing = waiting.firstOrNull { it.utterance.category == category }
        if (existing != null && existing.utterance.text == utterance.text) return unchanged()
        if (!force) {
            val last = spoken[category]
            if (last != null && last.text == utterance.text && last.at.elapsedNow() < quietPeriod) return unchanged()
        }
        val now = timeSource.markNow()
        if (existing != null) {
            // Same priority: the newer text takes over the place in line, so a status that keeps changing does not go to the back each
            // time. Another priority: it is placed where it now belongs.
            val samePriority = existing.utterance.priority == utterance.priority
            val seq = if (samePriority) existing.seq else nextSeq
            val list = ordered(waiting - existing + Entry(utterance, now, seq))
            return Offered(copy(waiting = list, nextSeq = if (samePriority) nextSeq else nextSeq + 1), SpeakResult.REPLACED)
        }
        return Offered(insert(Entry(utterance, now, nextSeq)).copy(nextSeq = nextSeq + 1), SpeakResult.QUEUED)
    }

    /**
     * Takes the next line to speak, if it has [minPriority] or more, and records it as spoken from now. Lines that have waited longer
     * than their [Utterance.maxAge] are dropped first, whatever [minPriority] is.
     */
    fun poll(minPriority: Priority = Priority.STATUS): Polled {
        val live = waiting.filterNot { e -> e.utterance.maxAge?.let { e.enqueuedAt.elapsedNow() >= it } == true }
        val base = if (live.size == waiting.size) this else copy(waiting = live)
        val head = live.firstOrNull()?.takeIf { it.utterance.priority >= minPriority } ?: return Polled(base, null)
        val next =
            base.copy(
                waiting = live - head,
                spoken = spoken + (head.utterance.category to Spoken(head.utterance.text, timeSource.markNow())),
                speaking = head.utterance,
            )
        return Polled(next, head.utterance)
    }

    /** The line being spoken has been heard in full. */
    fun finished(): SpeechQueue = if (speaking == null) this else copy(speaking = null)

    /**
     * The line being spoken was stopped.
     *
     * @param requeue put it back at the front of its priority, unless a newer line of its category already waits
     * @param heard the driver chose to stop it (skip), so it counts as spoken and stays quiet; otherwise nobody heard it through and it
     *   may be offered again at once
     */
    fun interrupted(
        requeue: Boolean,
        heard: Boolean,
    ): SpeechQueue {
        val line = speaking ?: return this
        val history = if (heard || spoken[line.category]?.text != line.text) spoken else spoken - line.category
        val base = copy(speaking = null, spoken = history)
        if (!requeue || waiting.any { it.utterance.category == line.category }) return base
        return base.insert(Entry(line, timeSource.markNow(), nextFrontSeq)).copy(nextFrontSeq = nextFrontSeq - 1)
    }

    /** Drops every waiting line. */
    fun cleared(): SpeechQueue = if (waiting.isEmpty()) this else copy(waiting = emptyList())

    private fun unchanged() = Offered(this, SpeakResult.UNCHANGED)

    private fun insert(entry: Entry): SpeechQueue {
        val room =
            if (waiting.size < capacity) {
                waiting
            } else {
                waiting - waiting.minWith(compareBy<Entry> { it.utterance.priority }.thenBy { it.seq })
            }
        return copy(waiting = ordered(room + entry))
    }

    private fun ordered(list: List<Entry>): List<Entry> =
        list.sortedWith(compareByDescending<Entry> { it.utterance.priority }.thenBy { it.seq })

    private fun copy(
        waiting: List<Entry> = this.waiting,
        spoken: Map<String, Spoken> = this.spoken,
        speaking: Utterance? = this.speaking,
        nextSeq: Long = this.nextSeq,
        nextFrontSeq: Long = this.nextFrontSeq,
    ) = SpeechQueue(quietPeriod, capacity, timeSource, waiting, spoken, speaking, nextSeq, nextFrontSeq)

    internal companion object {
        const val DEFAULT_CAPACITY: Int = 16
    }
}
