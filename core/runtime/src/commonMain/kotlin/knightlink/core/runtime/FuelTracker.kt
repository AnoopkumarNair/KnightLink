package knightlink.core.runtime

import kotlinx.serialization.Serializable

/**
 * One refuel seen in the fuel level: the level before and after, in percent of the tank, and when (the phone's clock, milliseconds since 1970;
 * 0 when the session has no wall clock).
 */
@Serializable
public data class FuelFill(val atMs: Long, val fromPercent: Double, val toPercent: Double) {
    val risePercent: Double get() = toPercent - fromPercent

    /** The fuel added, in litres, for a tank of [tankLitres]. */
    public fun litres(tankLitres: Double): Double = risePercent / 100.0 * tankLitres
}

/** What is remembered of the fuel between sessions: the last level seen (also the level the car was left at) and the refuels found. */
@Serializable
public data class FuelMemory(val lastPercent: Double? = null, val fills: List<FuelFill> = emptyList())

/**
 * Finds refuels in the stream of fuel level readings. The level sloshes by a point or two and the car may be switched off while it is filled,
 * so a refuel shows as a level that is several points above the last one the car left or the one before in this session. A rise counts after
 * [confirm] readings in a row that all stay [minRise] above the level before and close to each other, so slosh and one wild reading do not.
 *
 * On 2026-11-22 a 38 litre refuel (22.7 % to 100 %) was in the recording and nothing noticed it.
 */
public class FuelTracker(
    memory: FuelMemory = FuelMemory(),
    private val minRise: Double = DEFAULT_MIN_RISE,
    private val confirm: Int = DEFAULT_CONFIRM,
) {
    private var baseline: Double? = memory.lastPercent
    private val fills = ArrayList(memory.fills)
    private val rising = ArrayList<Double>()
    private val lows = ArrayList<Double>()
    private var savedBaseline: Double? = memory.lastPercent

    /** What to remember now. */
    public val model: FuelMemory get() = FuelMemory(baseline, fills.takeLast(MAX_FILLS))

    /** True once when [model] has changed enough to be worth saving. */
    public fun takeDirty(): Boolean {
        val current = baseline
        val saved = savedBaseline
        val dirty = current != null && (saved == null || kotlin.math.abs(current - saved) >= SAVE_STEP)
        if (dirty) savedBaseline = current
        return dirty
    }

    /** @return the refuel this reading completes, or null */
    public fun update(percent: Double, atMs: Long): FuelFill? {
        // 0 % is what a sender reports while it starts up, not an empty tank that the car is driving on.
        if (percent.isNaN() || percent <= 0.0 || percent > 100.0) return null
        val base = baseline
        if (base == null) {
            baseline = percent
            return null
        }
        if (percent >= base + minRise) {
            if (rising.isNotEmpty() && kotlin.math.abs(percent - rising.last()) > STEADY) rising.clear()
            rising += percent
            if (rising.size >= confirm) {
                val to = rising.sorted()[rising.size / 2]
                val fill = FuelFill(atMs, base, to)
                fills += fill
                baseline = to
                rising.clear()
                return fill
            }
            return null
        }
        rising.clear()
        // The level falls as fuel is used, but a dip of a moment (braking, a corner) is not the level: it is followed down only after [confirm]
        // readings in a row below it, to the highest of them; up only slowly (slosh).
        if (percent < base) {
            lows += percent
            if (lows.size >= confirm) {
                baseline = lows.takeLast(confirm).max()
                lows.clear()
            }
        } else {
            lows.clear()
            baseline = base + (percent - base) * UP_FOLLOW
        }
        return null
    }

    public companion object {
        public const val DEFAULT_MIN_RISE: Double = 5.0
        public const val DEFAULT_CONFIRM: Int = 3
        public const val MAX_FILLS: Int = 100
        private const val STEADY = 3.0
        private const val UP_FOLLOW = 0.1
        private const val SAVE_STEP = 0.5
    }
}
