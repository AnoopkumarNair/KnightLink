package knightlink.core.runtime.drivetruth

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

public enum class FuelType { PETROL, DIESEL }

/** What the reducer needs to know about the car and the owner. Prices and the tank are the owner's input and may be missing. */
public data class DriveTruthConfig(
    val fuelType: FuelType,
    val pricePerLitre: Double? = null,
    val tankLitres: Double? = null,
    /** Density used to turn a fuel mass rate (PID 9D, or air flow over the air-fuel ratio) into litres. Assumed, so the report says so. */
    val densityKgPerLitre: Double = if (fuelType == FuelType.DIESEL) DIESEL_DENSITY else PETROL_DENSITY,
) {
    public companion object {
        public const val DIESEL_DENSITY: Double = 0.832
        public const val PETROL_DENSITY: Double = 0.745

        /** Stoichiometric air-fuel ratio of petrol, used only to turn PID 10 (air mass flow) into fuel. Diesel never uses air flow. */
        public const val PETROL_AFR: Double = 14.7
    }
}

/** One good GPS fix. Only a fix with coordinates is distance-capable; [speedMps] alone may still be used as a speed. */
public data class GpsFix(
    val latitude: Double?,
    val longitude: Double?,
    val speedMps: Double?,
    val accuracyM: Double?,
)

/**
 * One timestamped snapshot. Every field is the reading that arrived at [timeMs], or null when this sample carries no new reading of it
 * (the reducer keeps the last reading itself, but never for longer than [DriveTruth.MAX_GAP_MS] when integrating).
 *
 * [engineRunning] overrides the rpm-based guess (rpm above 400) when the session knows better.
 */
public data class DriveSample(
    val timeMs: Long,
    val speedKmh: Double? = null,
    val rpm: Double? = null,
    /** PID 5E, litres per hour. */
    val fuelRateLph: Double? = null,
    /** PID 9D engine fuel rate, grams per second. */
    val fuelRateGps: Double? = null,
    /** PID 10 mass air flow, grams per second. */
    val mafGps: Double? = null,
    /** PID 2F, percent of the tank. */
    val fuelLevelPercent: Double? = null,
    val coolantC: Double? = null,
    val throttlePercent: Double? = null,
    val gps: GpsFix? = null,
    val engineRunning: Boolean? = null,
)

/**
 * The Drive Truth reducer: feed samples in time order, ask for a [report] at any time. Pure and deterministic: no clock, no platform calls,
 * and the only per-sample allocation is an event when one is found.
 *
 * Decisions (the honesty rules of the trials plan):
 * - Car distance integrates PID 0D with trapezoids. Between two speed readings more than [MAX_GAP_MS] apart nothing is integrated and the
 *   gap is counted (only while the engine was running). Any gap adds SPEED_SIGNAL_GAPS; gaps above [MAX_GAP_SHARE] of the speed signal's span
 *   set the car distance to null. No GPS back-fill of the gaps: it would mix two methods in one number. The caller still sees GPS distance.
 * - GPS distance is the haversine sum over fixes with accuracy of at most [MAX_GPS_ACCURACY_M], skipping jumps that imply more than
 *   [MAX_SPEED_KMH]; null with fewer than [MIN_GOOD_FIXES] good fixes. A segment shorter than the fixes' accuracy is treated as
 *   standstill drift and does not move the reference fix.
 * - Fuel: PID 5E, else 9D (density assumed), else air flow for petrol only (stoichiometric, FUEL_FROM_AIRFLOW). Diesel never uses air flow and
 *   speed-density is never turned into litres. All are integrated like distance; gaps above [MAX_GAP_SHARE] drop that source. The fuel level
 *   drop (needs the tank size, at least [MIN_LEVEL_DROP_PERCENT] points, no refuel and no unexplained rise) is the fallback when no rate source
 *   is usable, and a cross-check otherwise: a disagreement of more than 40 percent adds FUEL_ONLY_APPROXIMATE. The level drop is always
 *   FUEL_ONLY_APPROXIMATE (the gauge is coarse and non-linear).
 * - Idle is speed 0 with the engine running; a drive ends after [END_AFTER_MS] of continuous standstill with the engine off (or when the session
 *   is reported asleep), never at a stop with the engine running. The idle-stop system of a car looks like engine off, so a stop of a full
 *   minute at a light on a car that stops its engine does end the drive.
 */
public class DriveTruth(private val config: DriveTruthConfig) {
    private var started = false
    private var startMs = 0L
    private var lastMs = 0L
    private var asleep = false

    // Latest readings with their times.
    private var speedKmh = Double.NaN
    private var speedAt = NONE
    private var rpm = Double.NaN
    private var rpmAt = NONE
    private var coolantC = Double.NaN
    private var gpsSpeedKmh = Double.NaN
    private var gpsSpeedAt = NONE

    // Previous sample's classification, applied to the interval up to the next sample.
    private var prevMoving = false
    private var prevIdle = false
    private var movingMs = 0L
    private var idleMs = 0L
    private var idleBlockMs = 0L
    private var idleBlockStart = 0L
    private var idleBlockReported = false
    private var stoppedSince = NONE

    private val speedStream = Stream()
    private val rateStream = Stream()
    private val massStream = Stream()
    private val airStream = Stream()

    // Fuel level segments, split at refuels.
    private var levelStart = Double.NaN
    private var levelNow = Double.NaN
    private var levelMin = Double.NaN
    private var levelDropBanked = 0.0
    private var levelUnreliable = false

    // GPS.
    private var goodFixes = 0
    private var fixLat = Double.NaN
    private var fixLon = Double.NaN
    private var fixAt = NONE
    private var fixAcc = Double.NaN
    private var gpsKm = 0.0

    private var lastColdAt = NONE
    private val events = ArrayList<DriveEvent>()

    public fun add(sample: DriveSample) {
        val t = sample.timeMs
        if (started && t < lastMs) return
        if (!started) {
            started = true
            startMs = t
        }
        val dt = if (lastMs == t || !firstAfter) 0L else t - lastMs
        // Account the interval since the previous sample using the previous classification.
        if (firstAfter && dt in 1..MAX_GAP_MS) {
            if (prevMoving) movingMs += dt
            if (prevIdle) {
                if (idleBlockMs == 0L) idleBlockStart = lastMs
                idleMs += dt
                idleBlockMs += dt
                if (!idleBlockReported && idleBlockMs >= LONG_IDLE_MS) {
                    idleBlockReported = true
                    events += DriveEvent((idleBlockStart - startMs) / 1000.0, DriveEventKind.LONG_IDLE, "idle for at least ${LONG_IDLE_MS / 1000} s")
                }
            } else {
                idleBlockMs = 0
                idleBlockReported = false
            }
        } else if (firstAfter && dt > MAX_GAP_MS) {
            idleBlockMs = 0
            idleBlockReported = false
        }
        firstAfter = true
        lastMs = t

        sample.speedKmh?.let {
            speedKmh = it
            speedAt = t
        }
        sample.rpm?.let {
            rpm = it
            rpmAt = t
        }
        sample.coolantC?.let { coolantC = it }
        sample.gps?.let { fix ->
            fix.speedMps?.takeIf { fix.accuracyM != null && fix.accuracyM <= MAX_GPS_ACCURACY_M }?.let {
                gpsSpeedKmh = it * 3.6
                gpsSpeedAt = t
            }
        }

        val rpmFresh = rpmAt != NONE && t - rpmAt <= MAX_GAP_MS
        val running = sample.engineRunning ?: (rpmFresh && rpm > RUNNING_RPM)
        val speedNow = when {
            speedAt != NONE && t - speedAt <= MAX_GAP_MS -> speedKmh
            gpsSpeedAt != NONE && t - gpsSpeedAt <= MAX_GAP_MS -> gpsSpeedKmh
            else -> Double.NaN
        }
        prevMoving = speedNow > 0.0
        prevIdle = running && speedNow == 0.0
        // Standstill with the engine off. An unknown speed counts as standing only when the engine is off too (the adapter goes quiet then).
        val stopped = !running && (speedNow.isNaN() || speedNow == 0.0)
        if (stopped) {
            if (stoppedSince == NONE) stoppedSince = t
        } else {
            stoppedSince = NONE
        }

        sample.speedKmh?.let { speedStream.add(t, it, running, false) }
        sample.fuelRateLph?.let { rateStream.add(t, it, running, prevIdle) }
        sample.fuelRateGps?.let { massStream.add(t, it * 3.6 / config.densityKgPerLitre, running, prevIdle) }
        if (config.fuelType == FuelType.PETROL) {
            sample.mafGps?.let { airStream.add(t, it / PETROL_AFR_STOICH * 3.6 / config.densityKgPerLitre, running, prevIdle) }
        }
        sample.fuelLevelPercent?.let { level(it) }
        sample.gps?.let { gpsFix(t, it) }
        coldRevs(t, sample)
    }

    private var firstAfter = false

    /** The caller saw a refuel (from the fuel tracker). Splits the fuel level segment so a rise is never read as a drop. */
    public fun addRefuel(atMs: Long, detail: String? = null) {
        events += DriveEvent((atMs - startMs) / 1000.0, DriveEventKind.REFUEL, detail)
        if (!levelStart.isNaN()) levelDropBanked += max(0.0, levelStart - levelNow)
        levelStart = Double.NaN
        levelNow = Double.NaN
        levelMin = Double.NaN
    }

    /** The caller saw a fault code appear during the drive. */
    public fun addFaultAppeared(atMs: Long, detail: String? = null) {
        events += DriveEvent((atMs - startMs) / 1000.0, DriveEventKind.FAULT_APPEARED, detail)
    }

    /** The session reported the car asleep: the drive is over whatever the last readings were. */
    public fun markSessionAsleep() {
        asleep = true
    }

    /** Whether the drive is over: asleep, or standing with the engine off for [END_AFTER_MS] up to [nowMs] (default the last sample). */
    public fun isDriveEnded(nowMs: Long = lastMs): Boolean =
        started && (asleep || (stoppedSince != NONE && nowMs - stoppedSince >= END_AFTER_MS))

    private fun level(percent: Double) {
        if (levelStart.isNaN()) {
            levelStart = percent
            levelMin = percent
        }
        levelNow = percent
        levelMin = min(levelMin, percent)
        if (percent > levelMin + UNEXPLAINED_RISE) levelUnreliable = true
    }

    private fun gpsFix(t: Long, fix: GpsFix) {
        val lat = fix.latitude ?: return
        val lon = fix.longitude ?: return
        val acc = fix.accuracyM ?: return
        if (acc > MAX_GPS_ACCURACY_M || !lat.isFinite() || !lon.isFinite()) return
        if (fixAt == NONE) {
            fixLat = lat
            fixLon = lon
            fixAt = t
            fixAcc = acc
            goodFixes = 1
            return
        }
        val dtS = (t - fixAt) / 1000.0
        if (dtS <= 0.0) return
        val km = haversineKm(fixLat, fixLon, lat, lon)
        if (km / dtS * 3600.0 > MAX_SPEED_KMH) return // a jump: ignore the fix, keep the reference
        goodFixes++
        // Standstill drift: movement smaller than the fixes' own accuracy is noise, and the reference stays put so slow real movement still adds up.
        if (km * 1000.0 < max(acc, fixAcc)) return
        gpsKm += km
        fixLat = lat
        fixLon = lon
        fixAt = t
        fixAcc = acc
    }

    private fun coldRevs(t: Long, s: DriveSample) {
        if (coolantC.isNaN() || coolantC >= COLD_COOLANT_C) return
        val hard = (s.rpm != null && s.rpm > COLD_RPM) || (s.throttlePercent != null && s.throttlePercent >= COLD_THROTTLE)
        if (!hard) return
        if (lastColdAt != NONE && t - lastColdAt < COLD_WINDOW_MS) return
        lastColdAt = t
        events += DriveEvent((t - startMs) / 1000.0, DriveEventKind.COLD_HARD_REVS, "coolant ${coolantC.toInt()} C")
    }

    public fun report(): DriveTruthReport {
        val notes = LinkedHashSet<DriveTruthNote>()
        val ended = isDriveEnded()
        if (!ended) notes += DriveTruthNote.DRIVE_NOT_ENDED
        val endMs = if (ended && !asleep && stoppedSince != NONE) stoppedSince else lastMs
        val durationMs = if (started) max(0L, endMs - startMs) else 0L

        // Car distance.
        val carKm: Double? = when {
            speedStream.count < 2 -> null
            else -> {
                if (speedStream.gapMs > 0) notes += DriveTruthNote.SPEED_SIGNAL_GAPS
                if (speedStream.tooGappy()) null else speedStream.integral / MS_PER_HOUR
            }
        }

        // GPS distance.
        val gpsKmOrNull = if (goodFixes >= MIN_GOOD_FIXES) gpsKm else null
        if (gpsKmOrNull == null) notes += DriveTruthNote.GPS_POOR

        val compare = carKm != null && gpsKmOrNull != null && carKm >= DriveTruthReport.MIN_COMPARE_KM && gpsKmOrNull >= DriveTruthReport.MIN_COMPARE_KM
        val percent = if (compare) (carKm!! - gpsKmOrNull!!) / gpsKmOrNull * 100.0 else null
        if (percent == null && carKm != null && gpsKmOrNull != null) notes += DriveTruthNote.TOO_SHORT_TO_COMPARE

        // Fuel.
        var source: FuelSource? = null
        var litres: Double? = null
        var idleLitres: Double? = null
        val chosen: Pair<Stream, FuelSource>? = when {
            rateStream.usable() -> rateStream to FuelSource.FUEL_RATE_LPH
            massStream.usable() -> massStream to FuelSource.FUEL_RATE_GPS
            config.fuelType == FuelType.PETROL && airStream.usable() -> airStream to FuelSource.MAF
            else -> null
        }
        if (chosen != null) {
            val (stream, kind) = chosen
            source = kind
            litres = stream.integral / MS_PER_HOUR
            idleLitres = stream.idleIntegral / MS_PER_HOUR
            if (kind == FuelSource.FUEL_RATE_GPS) notes += DriveTruthNote.FUEL_DENSITY_ASSUMED
            if (kind == FuelSource.MAF) {
                notes += DriveTruthNote.FUEL_FROM_AIRFLOW
                notes += DriveTruthNote.FUEL_DENSITY_ASSUMED
            }
            if (stream.gapMs > 0) notes += DriveTruthNote.FUEL_ONLY_APPROXIMATE
        }
        val levelLitres = levelLitres()
        if (chosen == null) {
            if (levelLitres != null) {
                source = FuelSource.FUEL_LEVEL_DROP
                litres = levelLitres
                notes += DriveTruthNote.FUEL_ONLY_APPROXIMATE
            }
            notes += DriveTruthNote.FUEL_NOT_REPORTED
        } else if (levelLitres != null && litres != null && litres > 0.0 && kotlin.math.abs(levelLitres - litres) / litres > CROSS_CHECK_TOLERANCE) {
            notes += DriveTruthNote.FUEL_ONLY_APPROXIMATE
        }

        // Money.
        val price = config.pricePerLitre
        val cost = if (litres != null && price != null) litres * price else null
        if (litres != null && price == null) notes += DriveTruthNote.FUEL_PRICE_MISSING
        val distance = carKm ?: gpsKmOrNull
        val costPerKm = if (cost != null && distance != null && distance >= MIN_RATIO_KM) cost / distance else null
        val idleCost = if (idleLitres != null && price != null) idleLitres * price else null
        val kmPerLitre = if (litres != null && litres > MIN_RATIO_LITRES && distance != null && distance > 0.0) distance / litres else null

        return DriveTruthReport(
            durationMinutes = durationMs / MS_PER_MINUTE,
            movingMinutes = movingMs / MS_PER_MINUTE,
            idleMinutes = idleMs / MS_PER_MINUTE,
            carDistanceKm = carKm,
            gpsDistanceKm = gpsKmOrNull,
            carVsGpsPercent = percent,
            fuelLitres = litres,
            fuelSource = source,
            costRupees = cost,
            costPerKmRupees = costPerKm,
            idleCostRupees = idleCost,
            kmPerLitre = kmPerLitre,
            notes = notes.toList(),
            events = events.sortedBy { it.atSeconds },
        )
    }

    private fun levelLitres(): Double? {
        val capacity = config.tankLitres ?: return null
        if (levelUnreliable || levelStart.isNaN()) return null
        val drop = levelDropBanked + max(0.0, levelStart - levelNow)
        return if (drop >= MIN_LEVEL_DROP_PERCENT) drop / 100.0 * capacity else null
    }

    /** Trapezoid integral of one signal with the gap rule; values in per-hour units, times in ms. */
    private class Stream {
        var count = 0
        var firstAt = NONE
        var lastAt = NONE
        var lastValue = 0.0
        var lastRunning = false
        var integral = 0.0
        var idleIntegral = 0.0
        var gapMs = 0L

        fun add(t: Long, v: Double, running: Boolean, idle: Boolean) {
            if (!v.isFinite()) return
            if (lastAt == NONE) {
                firstAt = t
            } else {
                val dt = t - lastAt
                if (dt <= 0) return
                if (dt <= MAX_GAP_MS) {
                    val area = (lastValue + v) / 2.0 * dt
                    integral += area
                    if (idle) idleIntegral += area
                } else if (lastRunning) {
                    gapMs += dt
                }
            }
            count++
            lastAt = t
            lastValue = v
            lastRunning = running
        }

        fun span(): Long = if (lastAt == NONE) 0 else lastAt - firstAt

        fun tooGappy(): Boolean = span() > 0 && gapMs.toDouble() / span() > MAX_GAP_SHARE

        fun usable(): Boolean = count >= 2 && !tooGappy()
    }

    public companion object {
        public const val MAX_GAP_MS: Long = 2_000
        public const val MAX_GAP_SHARE: Double = 0.10
        public const val MAX_GPS_ACCURACY_M: Double = 20.0
        public const val MAX_SPEED_KMH: Double = 250.0
        public const val MIN_GOOD_FIXES: Int = 5
        public const val RUNNING_RPM: Double = 400.0
        public const val LONG_IDLE_MS: Long = 120_000
        public const val END_AFTER_MS: Long = 60_000
        public const val COLD_COOLANT_C: Double = 60.0
        public const val COLD_RPM: Double = 3_000.0
        public const val COLD_THROTTLE: Double = 60.0
        public const val COLD_WINDOW_MS: Long = 60_000
        public const val MIN_LEVEL_DROP_PERCENT: Double = 5.0
        private const val UNEXPLAINED_RISE: Double = 5.0
        private const val CROSS_CHECK_TOLERANCE: Double = 0.40
        private const val MIN_RATIO_KM: Double = 0.1
        private const val MIN_RATIO_LITRES: Double = 0.01
        private const val PETROL_AFR_STOICH: Double = DriveTruthConfig.PETROL_AFR
        private const val MS_PER_HOUR: Double = 3_600_000.0
        private const val MS_PER_MINUTE: Double = 60_000.0
        private const val NONE: Long = Long.MIN_VALUE
        private const val EARTH_RADIUS_KM: Double = 6371.0088

        internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = lat1 * PI / 180.0
            val p2 = lat2 * PI / 180.0
            val dp = p2 - p1
            val dl = (lon2 - lon1) * PI / 180.0
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
        }
    }
}
