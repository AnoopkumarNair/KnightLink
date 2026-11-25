package knightlink.core.runtime

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The odometer of the digital twin, estimated: the distance driven is integrated from the polled vehicle speed (PID 0D), and where the speed
 * was not read for a while (a parked stage, a lost link, passive capture) from the phone's GPS fixes of that gap; the odometer is then the
 * driver's start reading plus that distance, times a scale calibrated from an earlier drive's start and end readings
 * ([calibrate]: (end - start) / integrated).
 *
 * Pure and defensive: an invalid speed (negative, not finite, 255 = "not available"), a sample that goes back in time, a fix too inaccurate
 * or one that would mean a jump faster than [Config.maxGpsKmh] is dropped; the result is never NaN or infinite. Not thread-safe.
 */
public class OdometerEstimator(private val config: Config = Config()) {
    /**
     * @property maxSpeedGapMs speed samples further apart than this are not joined (the car may have stopped or moved meanwhile); the GPS
     *   distance of that gap is used instead, when there is one
     * @property maxGpsAccuracyM fixes less accurate than this are dropped
     * @property maxGpsKmh a fix that is this fast from the last one is a jump, not a drive: dropped
     * @property maxGpsGapMs fixes further apart than this are not joined
     * @property minGpsKmh segments slower than this are GPS noise of a standing car: not counted
     */
    public data class Config(
        val maxSpeedGapMs: Long = 5_000,
        val maxGpsAccuracyM: Double = 50.0,
        val maxGpsKmh: Double = 250.0,
        val maxGpsGapMs: Long = 60_000,
        val minGpsKmh: Double = 3.0,
    )

    private var lastSpeedMs: Long? = null
    private var lastSpeedKmh = 0.0
    private var lastFix: Fix? = null
    private var gpsSinceSpeedKm = 0.0

    private class Fix(val lat: Double, val lon: Double, val atMs: Long)

    /** Distance from the speed alone, km. */
    public var speedKm: Double = 0.0
        private set

    /** Distance from the GPS alone, km. */
    public var gpsKm: Double = 0.0
        private set

    /** The estimate's distance: the speed, with gaps in it filled from the GPS, km. */
    public var distanceKm: Double = 0.0
        private set

    /** Speed samples taken. */
    public var speedSamples: Int = 0
        private set

    /** A polled speed of [kmh] at [atMs] (any clock that does not go backwards, the same as [addGps]'s). */
    public fun addSpeed(kmh: Double, atMs: Long) {
        if (!kmh.isFinite() || kmh < 0.0 || kmh >= INVALID_KMH) return
        val last = lastSpeedMs
        if (last != null && atMs <= last) return
        if (last != null) {
            val gap = atMs - last
            val add = if (gap <= config.maxSpeedGapMs) (lastSpeedKmh + kmh) / 2.0 * gap / MS_PER_HOUR else gpsSinceSpeedKm
            speedKm += if (gap <= config.maxSpeedGapMs) add else 0.0
            distanceKm += add
        }
        gpsSinceSpeedKm = 0.0
        lastSpeedMs = atMs
        lastSpeedKmh = kmh
        speedSamples++
    }

    /** A GPS fix; [accuracyM] null is taken as good enough. */
    public fun addGps(lat: Double, lon: Double, accuracyM: Double?, atMs: Long) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        if (accuracyM != null && (!accuracyM.isFinite() || accuracyM > config.maxGpsAccuracyM)) return
        val last = lastFix
        if (last != null && atMs <= last.atMs) return
        if (last == null || atMs - last.atMs > config.maxGpsGapMs) {
            lastFix = Fix(lat, lon, atMs)
            return
        }
        val km = haversineKm(last.lat, last.lon, lat, lon)
        val kmh = km / ((atMs - last.atMs) / MS_PER_HOUR)
        if (!kmh.isFinite() || kmh > config.maxGpsKmh) return // a jump: keep the last good fix
        lastFix = Fix(lat, lon, atMs)
        if (kmh < config.minGpsKmh) return
        gpsKm += km
        gpsSinceSpeedKm += km
    }

    /** The odometer now: [startKm] + distance x [scale]; null without a start reading (the screen says "estimate unavailable"). */
    public fun odometerKm(startKm: Double?, scale: Double = 1.0): Double? {
        if (startKm == null || !startKm.isFinite() || startKm < 0.0) return null
        val s = if (scale.isFinite() && scale in MIN_SCALE..MAX_SCALE) scale else 1.0
        return startKm + distanceKm * s
    }

    public companion object {
        /** PID 0D's 255 is "not available" on many cars. */
        private const val INVALID_KMH = 255.0
        private const val MS_PER_HOUR = 3_600_000.0
        private const val EARTH_RADIUS_KM = 6371.0088

        /** A calibration outside this is a typo or a different drive, not a speedometer error. */
        public const val MIN_SCALE: Double = 0.85
        public const val MAX_SCALE: Double = 1.15

        /** Below this much integrated distance a calibration says more about rounding (the dashboard shows whole km) than about the car. */
        public const val MIN_CALIBRATION_KM: Double = 5.0

        /**
         * The scale that makes the integrated distance [integratedKm] match the dashboard readings [startKm] and [endKm]; null when it cannot be
         * told (no distance, an end at or below the start, too short a drive) or is implausible (outside [MIN_SCALE]..[MAX_SCALE]).
         */
        public fun calibrate(startKm: Double?, endKm: Double?, integratedKm: Double): Double? {
            if (startKm == null || endKm == null || !startKm.isFinite() || !endKm.isFinite() || !integratedKm.isFinite()) return null
            if (endKm <= startKm || integratedKm < MIN_CALIBRATION_KM) return null
            val scale = (endKm - startKm) / integratedKm
            return scale.takeIf { it in MIN_SCALE..MAX_SCALE }
        }

        internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = lat1 * PI / 180.0
            val p2 = lat2 * PI / 180.0
            val dp = p2 - p1
            val dl = (lon2 - lon1) * PI / 180.0
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
