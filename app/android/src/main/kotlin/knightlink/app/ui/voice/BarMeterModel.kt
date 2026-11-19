package knightlink.app.ui.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The maths of the three-column bar meter, with no Android or Compose in it. A column has an odd number of segments; a segment's
 * *distance* is how many rows it is from the middle one, so distance 0..half means the same on both sides of the middle.
 */
object BarMeterModel {
    /** Smoothing of the centre column: quick to rise, slower to fall, like an LED meter. */
    const val ATTACK_MS = 30f
    const val RELEASE_MS = 150f

    /** The side columns lag the centre and respond with less gain, so the three do not look identical. */
    const val SIDE_ATTACK_MS = 70f
    const val SIDE_RELEASE_MS = 240f
    const val SIDE_GAIN = 0.8f

    /** Fill shown when motion is reduced. */
    const val STATIC_LEVEL = 0.5f

    /** Segments from the middle one to the end of a column of [count] segments. */
    fun half(count: Int): Int = count / 2

    /** The target level of the side columns for a centre level. */
    fun sideTarget(level: Float): Float = clamp(level) * SIDE_GAIN

    /**
     * Furthest distance from the middle that is lit at [level] (0..1), or -1 when nothing is lit (level below one step).
     * Level 1 lights the whole column; every step outward adds one segment on each side.
     */
    fun litRadius(level: Float, count: Int): Int {
        val half = half(count)
        val steps = floor(clamp(level) * (half + 1)).toInt()
        return minOf(half, steps - 1)
    }

    fun isLit(distance: Int, level: Float, count: Int): Boolean = distance <= litRadius(level, count)

    /** Position on the colour ramp, 0 (low colour, middle) to 1 (high colour, end of the column). */
    fun rampPosition(distance: Int, count: Int): Float {
        val half = half(count)
        return if (half == 0) 0f else clamp(distance.toFloat() / half)
    }

    /** Blends two ARGB colours; [t] 0 gives [from], 1 gives [to]. */
    fun lerpColor(from: Int, to: Int, t: Float): Int {
        val k = clamp(t)
        fun channel(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * k).roundToInt().coerceIn(0, 255) shl shift
        }
        return channel(24) or channel(16) or channel(8) or channel(0)
    }

    /**
     * One smoothing step from [previous] towards [target] over [dtMillis]: exponential, with the [attackMs] time constant when
     * rising and [releaseMs] when falling. Frame-rate independent; never overshoots.
     */
    fun smooth(previous: Float, target: Float, dtMillis: Float, attackMs: Float = ATTACK_MS, releaseMs: Float = RELEASE_MS): Float {
        if (dtMillis <= 0f) return previous
        val tau = if (target > previous) attackMs else releaseMs
        val alpha = 1f - exp(-dtMillis / tau)
        return previous + (target - previous) * alpha
    }

    // Syllable-like cells: a bump per cell, an occasional pause, never a smooth wave.
    private const val CELL_MS = 170L

    /**
     * A stand-in loudness for speech whose level is unknown: a pseudo-random syllable rhythm, deterministic for a [seed] and a time
     * in milliseconds, always in 0..1.
     */
    fun syntheticLevel(timeMillis: Long, seed: Int = 0): Float {
        val t = if (timeMillis < 0) 0L else timeMillis
        val cell = t / CELL_MS
        val phase = (t % CELL_MS).toFloat() / CELL_MS
        val h = hash(cell, seed)
        val pause = h % 6 == 0L
        val amplitude = if (pause) 0.06f else 0.35f + 0.55f * ((h / 7) % 100) / 100f
        val bump = sin(PI.toFloat() * phase).pow(0.7f)
        return clamp(amplitude * (0.25f + 0.75f * bump))
    }

    private fun hash(cell: Long, seed: Int): Long {
        var x = cell * -7046029254386353131L + seed.toLong() * 2654435761L
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        x = x xor (x ushr 31)
        return abs(x % 1_000_000_007L)
    }

    private const val BREATH_PERIOD_MS = 3200L

    /** Slow breathing for the listening state: 0..1, one cycle per 3.2 s. */
    fun breath(timeMillis: Long): Float {
        val phase = (timeMillis % BREATH_PERIOD_MS).toFloat() / BREATH_PERIOD_MS
        return 0.5f - 0.5f * cos(2f * PI.toFloat() * phase)
    }

    /** Glow of a segment at [distance] from the middle for a [breath] value: the middle rows only, falling to nothing at distance 3. */
    fun listeningGlow(distance: Int, breath: Float): Float = clamp(breath) * clamp(1f - distance / 3f)

    private const val SWEEP_MS = 1100L

    /** Row (0 = bottom .. count-1 = top) of the head of the thinking light at [timeMillis]: up and down the column, a triangle wave. */
    fun thinkingHead(timeMillis: Long, count: Int): Float {
        val p = (timeMillis % (2 * SWEEP_MS)).toFloat() / SWEEP_MS
        val tri = if (p <= 1f) p else 2f - p
        return tri * (count - 1)
    }

    /** Brightness 0..1 of [row] when the head is at [head]: full at the head with a short tail on both sides. */
    fun thinkingGlow(row: Int, head: Float): Float = clamp(1f - abs(row - head) / 2.5f)

    private fun clamp(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)
}
