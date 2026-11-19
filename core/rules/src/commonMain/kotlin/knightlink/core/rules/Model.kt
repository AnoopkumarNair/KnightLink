package knightlink.core.rules

import kotlin.time.Duration

/** How the connection to the car is doing; the app maps its session state onto this. */
public enum class LinkStatus {
    /** Polling normally. */
    LIVE,

    /** The adapter answers but the car does not: ignition is off. */
    NO_IGNITION,

    /** No adapter link (connecting, reconnecting, failed). The snapshot still holds the last values; the rules do not trust them for long. */
    LINK_DOWN,
}

/** Something to say: a key into the [PhraseBook] and the numbers that go in it, as plain digit strings. */
public data class Phrase(val key: String, val params: Map<String, String> = emptyMap())

/** Declaration order is importance, most important first. */
public enum class AlertPriority { CRITICAL, WARNING, ADVISORY, INFO }

/**
 * An alert that is active now.
 *
 * @property id stable per rule, so a repeat is recognised as the same alert
 * @property firstSeen when it became active, in the snapshot's timebase; kept while it stays active
 * @property speakable whether it may be spoken at all at the moment
 * @property lastAnnounced when it was last spoken, null if never
 */
public data class Alert(
    val id: String,
    val priority: AlertPriority,
    val phrase: Phrase,
    val firstSeen: Duration,
    val speakable: Boolean,
    val lastAnnounced: Duration?,
)

/** Where the particulate filter is in its cycle. */
public enum class DpfPhase { UNKNOWN, NORMAL, SOOT_RISING, REGEN_ACTIVE, REGEN_INTERRUPTED, REGEN_COMPLETED }

/** How a regeneration ended. */
public enum class RegenOutcome { COMPLETED, STOPPED_EARLY, ENGINE_OFF }

/**
 * One regeneration. [start] and [end] are in the snapshot's timebase; the soot figures are null where no trusted reading was available,
 * never guessed.
 */
public data class RegenRecord(
    val start: Duration,
    val end: Duration?,
    val sootBefore: Double?,
    val sootAfter: Double?,
    val peakSoot: Double?,
    val outcome: RegenOutcome?,
)

/**
 * The filter as the rules see it.
 *
 * @property sootPct the soot load if a fresh trusted reading exists, else null
 * @property current the regeneration in progress, if any
 * @property last the most recent regeneration that ended, kept after the phase has fallen back to normal
 */
public data class DpfStatus(val phase: DpfPhase, val sootPct: Double?, val current: RegenRecord?, val last: RegenRecord?)

/** Why it is not yet time to switch off. */
public enum class SwitchOffReason { REGEN_ACTIVE, HOT_EXHAUST }

/** The answer to "can I switch off?". Unknown is an honest answer: it never means safe. */
public sealed interface SwitchOffAdvice {
    public data object Safe : SwitchOffAdvice

    /** @property remaining a plausible estimate of the wait, or null when none can be given */
    public data class NotYet(val reason: SwitchOffReason, val remaining: Duration?) : SwitchOffAdvice

    public data object EngineOff : SwitchOffAdvice

    /** @property missing the inputs that are not available right now */
    public data class Unknown(val missing: Set<String>) : SwitchOffAdvice
}

/**
 * Everything the rules concluded at one moment.
 *
 * @property alerts active alerts, most important first, then oldest first
 * @property announcements what to speak now: new alerts and those due again, in the same order. Empty on most calls.
 * @property staleInputs inputs the rules need that have no fresh, trusted reading right now (by role: `regenStatus`, `soot`, `egt`, `rpm`, `speed`,
 *   `coolant`, `adblueLevel`)
 * @property warningsCheckable whether at least one alert rule has fresh inputs. When false, "no alerts" means "could not look", and the answer says so.
 */
public data class RulesState(
    val at: Duration,
    val dpf: DpfStatus,
    val switchOff: SwitchOffAdvice,
    val alerts: List<Alert>,
    val announcements: List<Alert>,
    val staleInputs: Set<String>,
    val warningsCheckable: Boolean = true,
)
