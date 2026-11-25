package knightlink.core.runtime

import kotlin.time.Duration

/**
 * Whether the car moves or stands, from the vehicle speed that polling reads anyway (no request of its own). Used to keep the background body
 * check off the adapter while the car is driven, and to start the parked stage by itself when the car stands (at a red light, in traffic).
 *
 * - Moving: two readings in a row at or above [movingKmh]. It stays so (through a slow roll at 1 to 4 km/h) until a reading of 0.
 * - Standing: the readings have been 0 without a break since [stillFor] ago.
 * - A reading older than the caller's maximum age says nothing: neither moving nor standing.
 *
 * Times are the session's (time since it started), the same as the readings'. Not thread-safe; the poll loop feeds it and reads it.
 */
internal class MotionWatch(private val movingKmh: Int) {
    private var lastAt: Duration? = null
    private var highRun = 0
    private var stillSince: Duration? = null
    private var moving = false

    /** The latest reading of the speed, [kmh] at [at]. */
    fun update(kmh: Double, at: Duration) {
        lastAt = at
        if (kmh >= movingKmh) {
            highRun++
            if (highRun >= CONFIRM_READINGS) moving = true
        } else {
            highRun = 0
        }
        if (kmh <= 0.0) {
            moving = false
            if (stillSince == null) stillSince = at
        } else {
            stillSince = null
        }
    }

    fun fresh(now: Duration, maxAge: Duration): Boolean = lastAt?.let { now - it <= maxAge } == true

    /** Confirmed movement, and the latest reading is no older than [maxAge]. */
    fun moving(now: Duration, maxAge: Duration): Boolean = moving && fresh(now, maxAge)

    /** How long the speed has read 0 without a break; null when it does not read 0 now or the latest reading is older than [maxAge]. */
    fun stillFor(now: Duration, maxAge: Duration): Duration? {
        if (!fresh(now, maxAge)) return null
        val since = stillSince ?: return null
        return (now - since).coerceAtLeast(Duration.ZERO)
    }

    /** Forgets everything (a stage or a lost link interrupted the readings: "standing since" must start again from the next one). */
    fun reset() {
        lastAt = null
        highRun = 0
        stillSince = null
        moving = false
    }

    private companion object {
        const val CONFIRM_READINGS = 2
    }
}
