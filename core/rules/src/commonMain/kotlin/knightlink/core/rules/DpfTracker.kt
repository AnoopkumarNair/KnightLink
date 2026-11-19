package knightlink.core.rules

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Follows the particulate filter through its cycle from the regeneration status and the soot load.
 *
 * The status is read from the car several times a second, so a regeneration is something the tracker remembers: it starts on the first fresh
 * "active" reading and ends only on several consecutive "not active" ones, so a single glitch does not end it. If the status goes stale in the
 * middle of one, the tracker keeps saying "active" rather than guess; the engine reports the input as stale next to it.
 */
internal class DpfTracker(private val config: DpfConfig, private val sootMaxAge: Duration) {
    private var phase = DpfPhase.UNKNOWN
    private var current: RegenRecord? = null
    private var last: RegenRecord? = null
    private var endedAt: Duration? = null
    private var rising = false
    private var lastStatusAt: Duration? = null
    private var notActiveCount = 0
    private var lastNotActiveAt: Duration? = null
    private var firstNotActiveAt: Duration? = null
    private val sootSamples = ArrayDeque<Fresh>()
    private val regenPoints = ArrayList<Fresh>()

    /** The regeneration that ended during the latest [update], if one did. */
    var justFinished: RegenRecord? = null
        private set

    /** Whether the regeneration status was fresh in the latest [update]. */
    var statusFresh: Boolean = false
        private set

    /** Time left in the regeneration in progress, estimated from how fast soot has been dropping; null when that cannot be said or is not believable. */
    fun remaining(): Duration? {
        val first = regenPoints.firstOrNull() ?: return null
        val latest = regenPoints.lastOrNull() ?: return null
        val seconds = (latest.at - first.at).toDouble(DurationUnit.SECONDS)
        val drop = first.value - latest.value
        if (seconds <= 0.0 || drop <= 0.0 || latest.value <= config.completedSootPct) return null
        val estimate = ((latest.value - config.completedSootPct) / (drop / seconds)).seconds
        return estimate.takeIf { it <= config.maxRemainingEstimateMs.milliseconds }
    }

    fun update(now: Duration, engineOff: Boolean, status: Fresh?, soot: Fresh?): DpfStatus {
        justFinished = null
        statusFresh = status != null
        if (soot != null && (sootSamples.isEmpty() || soot.at > sootSamples.last().at)) {
            sootSamples.addLast(soot)
            if (sootSamples.size > MAX_SOOT_SAMPLES) sootSamples.removeFirst()
            if (phase == DpfPhase.REGEN_ACTIVE) {
                regenPoints += soot
                current = current?.let { it.copy(peakSoot = maxOf(it.peakSoot ?: soot.value, soot.value)) }
            }
        }

        val statusNew = status != null && lastStatusAt.let { it == null || status.at > it }
        val active = status?.let { (it.value.toInt() and config.regenActiveMask) != 0 }
        val newStatusAt = if (statusNew) status?.at else null
        if (phase == DpfPhase.REGEN_ACTIVE) {
            if (engineOff) {
                finish(RegenOutcome.ENGINE_OFF, now, now, soot?.value ?: sootSamples.lastOrNull()?.value)
            } else {
                if (statusNew && active == true) {
                    notActiveCount = 0
                    firstNotActiveAt = null
                } else if (newStatusAt != null && active == false && newStatusAt != lastNotActiveAt) {
                    if (notActiveCount == 0) firstNotActiveAt = newStatusAt
                    notActiveCount++
                    lastNotActiveAt = newStatusAt
                }
                // Once the end is confirmed it is concluded as soon as a soot value is known, so a missing soot reading delays the verdict
                // instead of guessing it.
                if (notActiveCount >= config.regenEndConfirmReadings) conclude(now, soot)
            }
        } else if (newStatusAt != null && active == true && !engineOff) {
            start(newStatusAt)
        }
        if (newStatusAt != null) lastStatusAt = newStatusAt

        if (phase != DpfPhase.REGEN_ACTIVE) settle(now, status, soot)
        return DpfStatus(phase, soot?.value, current, last)
    }

    private fun start(at: Duration) {
        val before = sootSamples.lastOrNull { it.at <= at && at - it.at <= sootMaxAge }
        current = RegenRecord(start = at, end = null, sootBefore = before?.value, sootAfter = null, peakSoot = before?.value, outcome = null)
        regenPoints.clear()
        if (before != null) regenPoints += before
        phase = DpfPhase.REGEN_ACTIVE
        notActiveCount = 0
        lastNotActiveAt = null
        firstNotActiveAt = null
        endedAt = null
    }

    private fun conclude(now: Duration, soot: Fresh?) {
        // Only a soot value read after the regeneration was seen to stop says how it ended; one from the middle of it would turn a finished
        // regeneration into a false "stopped early".
        val since = firstNotActiveAt ?: now
        val after = (soot?.takeIf { it.at >= since } ?: sootSamples.lastOrNull { it.at >= since })?.value ?: return
        val outcome = if (after <= config.completedSootPct) RegenOutcome.COMPLETED else RegenOutcome.STOPPED_EARLY
        finish(outcome, lastNotActiveAt ?: now, now, after)
    }

    private fun finish(outcome: RegenOutcome, end: Duration, now: Duration, sootAfter: Double?) {
        val record = checkNotNull(current).copy(end = end, sootAfter = sootAfter, outcome = outcome)
        last = record
        justFinished = record
        current = null
        regenPoints.clear()
        endedAt = now
        notActiveCount = 0
        lastNotActiveAt = null
        firstNotActiveAt = null
        phase = if (outcome == RegenOutcome.COMPLETED) DpfPhase.REGEN_COMPLETED else DpfPhase.REGEN_INTERRUPTED
    }

    /** Outside a regeneration: show the result of the last one for a while, then fall back to what the soot says. */
    private fun settle(now: Duration, status: Fresh?, soot: Fresh?) {
        val ended = endedAt
        if ((phase == DpfPhase.REGEN_COMPLETED || phase == DpfPhase.REGEN_INTERRUPTED) &&
            ended != null &&
            now - ended < config.resultHoldMs.milliseconds
        ) {
            return
        }
        if (soot != null) rising = if (rising) soot.value >= config.sootRisingClearPct else soot.value >= config.sootRisingPct
        phase = when {
            status == null -> DpfPhase.UNKNOWN
            soot != null && rising -> DpfPhase.SOOT_RISING
            else -> DpfPhase.NORMAL
        }
    }

    private companion object {
        const val MAX_SOOT_SAMPLES = 64
    }
}
