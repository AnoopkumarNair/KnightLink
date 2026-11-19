package knightlink.core.rules

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What a rule concluded this time. [Unknown] means its inputs are not trustworthy now: the alert is neither raised nor cleared by it. */
internal sealed interface Condition {
    data object Inactive : Condition

    data object Unknown : Condition

    /**
     * @property speakable whether this alert may be spoken at all right now
     * @property reannounce whether a repeat announcement is allowed right now (not while the link is down: the data is old)
     */
    data class Active(val priority: AlertPriority, val phrase: Phrase, val speakable: Boolean = true, val reannounce: Boolean = true) : Condition
}

/**
 * Turns rule conclusions into a list of active alerts and a decision what to say now. It is the only place that knows about time: de-duplication,
 * re-announcement, flapping suppression and spacing. Pure state machine on the caller's clock; it never reads a clock itself.
 */
internal class AlertManager(private val config: AlertsConfig) {
    private class Tracked(
        val id: String,
        var priority: AlertPriority,
        var phrase: Phrase,
        val firstSeen: Duration,
        var speakable: Boolean,
        var reannounce: Boolean,
        var lastAnnounced: Duration?,
        val expiresAfter: Duration?,
    )

    private class Cleared(val at: Duration, val lastAnnounced: Duration?)

    class Result(val alerts: List<Alert>, val announcements: List<Alert>)

    private val active = LinkedHashMap<String, Tracked>()
    private val cleared = HashMap<String, Cleared>()
    private var lastBatchAt: Duration? = null

    /** Something that happened rather than a condition that holds: it is spoken once and leaves the list after the event lifetime. */
    fun raiseEvent(id: String, priority: AlertPriority, phrase: Phrase, now: Duration) {
        active[id] =
            Tracked(id, priority, phrase, now, speakable = true, reannounce = false, lastAnnounced = null, expiresAfter = config.eventLifetimeMs.milliseconds)
    }

    /** Applies [conditions] (by alert id) at [now]. An alert whose id is not in the map is left as it is, like [Condition.Unknown]. */
    fun update(now: Duration, conditions: Map<String, Condition>): Result {
        active.values.removeAll { it.expiresAfter != null && now - it.firstSeen > it.expiresAfter }
        for ((id, condition) in conditions) {
            val existing = active[id]
            when (condition) {
                Condition.Unknown -> Unit
                Condition.Inactive -> if (existing != null && existing.expiresAfter == null) {
                    active.remove(id)
                    cleared[id] = Cleared(now, existing.lastAnnounced)
                }
                is Condition.Active -> if (existing != null) {
                    existing.priority = condition.priority
                    existing.phrase = condition.phrase
                    existing.speakable = condition.speakable
                    existing.reannounce = condition.reannounce
                } else {
                    // An alert that comes back soon after it cleared is flapping: it is active again, but the earlier announcement still stands.
                    val before = cleared.remove(id)?.takeIf { it.lastAnnounced != null && now - it.at < config.quietAfterClearMs.milliseconds }
                    active[id] = Tracked(id, condition.priority, condition.phrase, now, condition.speakable, condition.reannounce, before?.lastAnnounced, null)
                }
            }
        }

        // Only what a rule confirmed this time (or an event) is spoken: an alert kept through an unknown condition is not repeated on stale evidence.
        val due = active.values.filter { (it.expiresAfter != null || conditions[it.id] is Condition.Active) && isDue(it, now) }.sortedWith(ORDER)
        val critical = due.filter { it.priority == AlertPriority.CRITICAL }
        val gap = lastBatchAt?.let { now - it >= config.minGapMs.milliseconds } ?: true
        // A held alert is not remembered: it is still due on the next call, and goes out on the first one after the gap if it is still active.
        val batch = if (gap) due else critical
        if (batch.isNotEmpty()) {
            lastBatchAt = now
            batch.forEach { it.lastAnnounced = now }
        }
        return Result(active.values.sortedWith(ORDER).map { it.toAlert() }, batch.map { it.toAlert() })
    }

    private fun isDue(alert: Tracked, now: Duration): Boolean {
        if (!alert.speakable) return false
        val last = alert.lastAnnounced ?: return true
        val interval = config.reannounceMs.forPriority(alert.priority)
        return alert.reannounce && interval > 0 && now - last >= interval.milliseconds
    }

    private fun Tracked.toAlert() = Alert(id, priority, phrase, firstSeen, speakable, lastAnnounced)

    private companion object {
        val ORDER: Comparator<Tracked> = compareBy<Tracked>({ it.priority }, { it.firstSeen }, { it.id })
    }
}
