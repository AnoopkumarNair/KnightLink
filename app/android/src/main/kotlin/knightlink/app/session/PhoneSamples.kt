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
class MotionBatcher(private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS, private val maxSamples: Int = DEFAULT_MAX_SAMPLES) {
    private val batch = ArrayList<MotionSample>()
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
        batch += MotionSample(timeMs, x, y, z, gx, gy, gz)
        return if (nowMs - batch.first().timeMs >= maxAgeMs || batch.size >= maxSamples) flush(nowMs) else null
    }

    /** The line for what is held, and an empty batch; null when nothing is held. */
    fun flush(nowMs: Long): String? {
        if (batch.isEmpty()) return null
        // Never a sample from after the line's time: a clock that steps back must not make a negative age.
        val line = ExternalSamples.motionLine(maxOf(nowMs, batch.maxOf { it.timeMs }), batch.toList())
        batch.clear()
        return line
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS = 500L
        const val DEFAULT_MAX_SAMPLES = 40
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
