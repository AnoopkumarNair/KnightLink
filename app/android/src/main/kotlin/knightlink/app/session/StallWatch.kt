package knightlink.app.session

/**
 * Notices that the app itself stopped running for a while, and says which kind of stop it was. A heartbeat calls [tick] every few seconds
 * with the two Android clocks: `elapsedRealtime` keeps counting while the processor is suspended, `uptimeMillis` does not. When a tick is
 * far later than expected:
 * - the processor was suspended for most of the gap (the clocks disagree): the phone slept, and no wake lock held it awake;
 * - both clocks moved together: the processor ran but this process did not (the system froze it, or it was throttled).
 *
 * On 2026-11-19 and 2026-11-20 the whole session stood still for minutes while the phone was in a pocket; the recordings could not say
 * which of the two it was. Pure logic, so that it is tested without a phone.
 */
class StallWatch(private val expectedMs: Long = TICK_MS, private val thresholdMs: Long = THRESHOLD_MS) {
    /**
     * @property gapMs how long since the previous tick
     * @property suspendedMs how much of it the processor was suspended (0 when it was running all along)
     */
    data class Stall(val gapMs: Long, val suspendedMs: Long) {
        /** True when the processor itself was asleep for most of the gap. */
        val processorSlept: Boolean get() = suspendedMs * 10 >= gapMs * 8

        fun describe(): String {
            val gap = gapMs / MS_PER_SECOND
            val asleep = suspendedMs / MS_PER_SECOND
            return if (processorSlept) {
                "stall: no heartbeat for $gap s; the processor was suspended for $asleep s (the phone slept; the wake lock did not hold it)"
            } else {
                "stall: no heartbeat for $gap s; the processor ran ${(gapMs - suspendedMs) / MS_PER_SECOND} s of it, so this process was frozen or starved"
            }
        }
    }

    private var lastElapsedMs: Long? = null
    private var lastUptimeMs = 0L

    /**
     * Takes one heartbeat.
     *
     * @param elapsedMs `SystemClock.elapsedRealtime()`
     * @param uptimeMs `SystemClock.uptimeMillis()`
     * @return the stall that just ended, or null when this tick was on time (and always for the first)
     */
    fun tick(elapsedMs: Long, uptimeMs: Long): Stall? {
        val previous = lastElapsedMs
        val previousUptime = lastUptimeMs
        lastElapsedMs = elapsedMs
        lastUptimeMs = uptimeMs
        if (previous == null) return null
        val gap = elapsedMs - previous
        if (gap - expectedMs < thresholdMs) return null
        val awake = (uptimeMs - previousUptime).coerceIn(0, gap)
        return Stall(gapMs = gap, suspendedMs = gap - awake)
    }

    companion object {
        const val TICK_MS = 5_000L
        const val THRESHOLD_MS = 10_000L
        private const val MS_PER_SECOND = 1000L
    }
}
