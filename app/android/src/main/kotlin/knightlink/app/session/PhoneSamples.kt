package knightlink.app.session

import knightlink.core.recorder.ExternalSamples
import knightlink.core.recorder.GpsSample
import knightlink.core.recorder.MotionSample

/**
 * Where the phone's own sensor readings come from while a session is active. The session host starts it with a function that takes one
 * line of external samples (see [ExternalSamples]) and stops it when the session ends. Behind an interface so that the host is
 * tested without a phone.
 */
interface SampleSource {
    /** Starts delivering lines to [emit], which may be called from any thread and must not block. */
    fun start(emit: (String) -> Unit)

    /** Stops delivering. Safe to call more than once, and without [start]. */
    fun stop()
}

/**
 * Collects motion readings into batches, one line each. Acceleration and rotation arrive separately and at their own pace; each acceleration
 * reading is paired with the latest rotation reading (zero before the first). A batch is written once its first sample is older than
 * [maxAgeMs] (so a line holds about half a second, a dozen samples at 25 Hz) or holds [maxSamples], and by [flush].
 *
 * Not thread-safe: one thread calls everything.
 */
class MotionBatcher(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
    /**
     * When above 0, readings are averaged over bins of this many milliseconds and one sample is kept per bin: at the 40 to 115 readings a
     * second a phone delivers, motion made 85 % of a recording (2026-11-19: 8 MB of 9.4 MB in 16 minutes), and the analysis it serves
     * (matching what a control unit's identifier does with how the car moves) needs 10 Hz, not 100.
     */
    private val binMs: Long = 0,
) {
    private val batch = ArrayList<MotionSample>()
    private var binCount = 0
    private var binStartMs = 0L
    private var binSum = DoubleArray(BIN_VALUES)
    private var binTimeSum = 0.0
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0

    /** Remembers the latest rotation rate (rad/s). */
    fun rotation(x: Double, y: Double, z: Double) {
        gx = x
        gy = y
        gz = z
    }

    /**
     * Adds an acceleration reading (m/s²) taken at [timeMs], on the same clock as [nowMs].
     *
     * @return a line (see [ExternalSamples.motionLine]) when the batch is due, otherwise null
     */
    fun acceleration(timeMs: Long, x: Double, y: Double, z: Double, nowMs: Long): String? {
        if (binMs > 0) {
            if (binCount > 0 && timeMs - binStartMs >= binMs) closeBin()
            if (binCount == 0) binStartMs = timeMs
            binCount++
            binTimeSum += timeMs - binStartMs
            val values = doubleArrayOf(x, y, z, gx, gy, gz)
            for (i in values.indices) binSum[i] += values[i]
            if (batch.isEmpty()) return null
        } else {
            batch += MotionSample(timeMs, x, y, z, gx, gy, gz)
        }
        return if (nowMs - batch.first().timeMs >= maxAgeMs || batch.size >= maxSamples) write(nowMs) else null
    }

    /** Turns the readings of the open bin into one sample, at their mean time. */
    private fun closeBin() {
        if (binCount == 0) return
        val n = binCount.toDouble()
        batch += MotionSample(
            binStartMs + (binTimeSum / n).toLong(),
            binSum[0] / n,
            binSum[1] / n,
            binSum[2] / n,
            binSum[3] / n,
            binSum[4] / n,
            binSum[5] / n,
        )
        binCount = 0
        binTimeSum = 0.0
        binSum = DoubleArray(BIN_VALUES)
    }

    /** The line for what is held, and an empty batch; null when nothing is held. */
    fun flush(nowMs: Long): String? {
        closeBin()
        return write(nowMs)
    }

    /** The line for the finished samples (not the bin still filling, which a periodic write must not cut short). */
    private fun write(nowMs: Long): String? {
        if (batch.isEmpty()) return null
        // Never a sample from after the line's time: a clock that steps back must not make a negative age.
        val line = ExternalSamples.motionLine(maxOf(nowMs, batch.maxOf { it.timeMs }), batch.toList())
        batch.clear()
        return line
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS = 500L
        const val DEFAULT_MAX_SAMPLES = 40

        /** What a recording keeps of the phone's motion: one averaged sample per 100 ms, written in lines of a second. */
        const val RECORDING_BIN_MS = 100L
        const val RECORDING_MAX_AGE_MS = 1000L
        private const val BIN_VALUES = 6
    }
}

/** The line for a position fix: speed from m/s to km/h, and every value the phone did not give left out. */
fun gpsLineOf(
    timeMs: Long,
    latitude: Double,
    longitude: Double,
    speedMetresPerSecond: Float?,
    bearingDegrees: Float?,
    accuracyMetres: Float?,
): String = ExternalSamples.gpsLine(
    GpsSample(
        timeMs = timeMs,
        latitude = latitude,
        longitude = longitude,
        speedKmh = speedMetresPerSecond?.let { it * KMH_PER_MS },
        bearingDeg = bearingDegrees?.toDouble(),
        accuracyM = accuracyMetres?.toDouble(),
    ),
)

private const val KMH_PER_MS = 3.6
