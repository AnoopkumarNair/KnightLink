package knightlink.app.session

/** Where the drive is, for the guided flow. */
enum class DriveStage { NOT_STARTED, DRIVING, ENDED }

/**
 * Tells when a drive starts and ends from the car's own speed and rpm, once per screen refresh.
 *
 * A drive starts when the speed is above [movingKmh]. It ends only when the car has stood still with the engine off (rpm 0) for [endAfterMs],
 * or has gone to sleep: a traffic-light stop (engine on, or idle-stop with the speed signal still read) never ends it, and a speed or rpm
 * that cannot be read counts as "not known", never as zero. Moving again after an end starts a new drive.
 */
class DriveStageTracker(private val movingKmh: Double = 3.0, private val endAfterMs: Long = 60_000) {
    var stage: DriveStage = DriveStage.NOT_STARTED
        private set

    private var quietSinceMs: Long? = null

    /**
     * @param nowMs any steady clock, milliseconds
     * @param speedKmh the car's speed, null when it is not read (or stale)
     * @param rpm the engine speed, null when it is not read (or stale)
     * @param asleep the session says the car is asleep (ignition off)
     */
    fun update(nowMs: Long, speedKmh: Double?, rpm: Double?, asleep: Boolean = false): DriveStage {
        if (speedKmh != null && speedKmh > movingKmh && !asleep) {
            stage = DriveStage.DRIVING
            quietSinceMs = null
            return stage
        }
        if (stage != DriveStage.DRIVING) return stage
        val stoppedAndOff = asleep || (speedKmh != null && speedKmh <= movingKmh && rpm != null && rpm <= 0.0)
        if (!stoppedAndOff) {
            quietSinceMs = null
            return stage
        }
        val since = quietSinceMs ?: nowMs.also { quietSinceMs = it }
        if (nowMs - since >= endAfterMs) stage = DriveStage.ENDED
        return stage
    }

    /** Back to the start, for a new connection. */
    fun reset() {
        stage = DriveStage.NOT_STARTED
        quietSinceMs = null
    }
}
