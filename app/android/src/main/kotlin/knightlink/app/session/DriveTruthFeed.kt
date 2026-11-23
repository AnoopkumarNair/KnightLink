package knightlink.app.session

import knightlink.core.runtime.drivetruth.DriveSample
import knightlink.core.runtime.drivetruth.DriveTruth
import knightlink.core.runtime.drivetruth.DriveTruthConfig
import knightlink.core.runtime.drivetruth.DriveTruthReport
import knightlink.core.runtime.drivetruth.GpsFix
import knightlink.core.telemetry.TelemetrySnapshot
import java.util.TreeMap
import knightlink.core.runtime.drivetruth.FuelType as ReducerFuelType

/** What the feed needs to know at one tick of the screen refresh. All times are milliseconds on the session's clock (the zero of every reading's `at`). */
data class DriveFeedInput(
    val nowMs: Long,
    /** The session reports the car asleep. */
    val asleep: Boolean,
    /** How many refuels the session knows of (remembered ones included); a rise during a drive is a refuel. */
    val fillCount: Int,
    /** The fault codes of the last reading, null when they have not been read. */
    val faultCodes: Set<String>?,
)

/**
 * Turns the session's readings into [DriveSample]s for the [DriveTruth] reducer and decides when a drive starts and ends. One per connection.
 *
 * Samples are made when a reading arrives, not when the screen refreshes: each reading carries the session-clock time it was taken, and
 * readings of the same millisecond share a sample. A signal that is not read (polling paused, passive capture, link lost) simply makes no
 * samples, which the reducer sees as a gap and says so. A drive starts at the first sample where the car moves with the engine running (so
 * a car warming up in the drive does not start one), and the report is handed over once, when the reducer says the drive is over.
 *
 * [accept] is called with every new snapshot of the session's readings, [update] on every screen refresh; [addGps] may come from any thread.
 * Everything is under one lock.
 *
 * @param config what the reducer needs about the car and the owner, asked when a drive starts
 */
class DriveTruthFeed(private val config: () -> DriveTruthConfig) {
    private val lock = Any()
    private val pendingGps = ArrayList<Pair<Long, GpsFix>>()
    private val lastAt = HashMap<String, Long>()

    private var truth: DriveTruth? = null
    private var ended = false
    private var slept = false

    private var speedKmh: Double? = null
    private var speedAt = Long.MIN_VALUE
    private var rpm: Double? = null
    private var rpmAt = Long.MIN_VALUE

    private var fillBaseline: Int? = null
    private var knownFaults: Set<String>? = null
    private var driveFaults: Set<String>? = null
    private val reportedFaults = HashSet<String>()

    /** Whether a drive has started and its report has not been handed over yet. */
    val inProgress: Boolean get() = truth != null && !ended

    /** A good position fix from the phone, taken at [timeMs] on the session clock. Fixes without coordinates still carry a speed. */
    fun addGps(timeMs: Long, fix: GpsFix) {
        synchronized(lock) { pendingGps += timeMs to fix }
    }

    /** The report of a drive that was under way when the connection ends, or null when none was. It says that the drive had not ended. */
    fun unfinished(): DriveTruthReport? = synchronized(lock) { truth?.takeIf { !ended }?.report() }

    /** @return the report, exactly once, when the drive that was under way has ended; otherwise null */
    fun update(input: DriveFeedInput): DriveTruthReport? = synchronized(lock) {
        trackEvents(input)
        val current = truth ?: return null
        if (ended) return null
        if (input.asleep && !slept) {
            slept = true
            current.markSessionAsleep()
        }
        if (!current.isDriveEnded(input.nowMs)) return null
        ended = true
        return current.report()
    }

    /** Takes what is new in [telemetry] (the session's latest readings, seen at [nowMs]) and the phone's fixes since the last call, in time order. */
    fun accept(telemetry: TelemetrySnapshot, nowMs: Long) {
        synchronized(lock) { feedSamples(telemetry, nowMs) }
    }

    private fun feedSamples(telemetry: TelemetrySnapshot, nowMs: Long) {
        val bins = TreeMap<Long, Bin>()
        val oldest = nowMs - MAX_READING_AGE_MS
        val newest = HashMap<String, Pair<Long, Double>>()
        for (timed in telemetry.readings) {
            val id = timed.reading.signal.id
            if (id !in SIGNALS) continue
            val at = timed.at.inWholeMilliseconds
            if (newest[id]?.first?.let { it >= at } == true) continue
            newest[id] = at to timed.reading.signal.value
        }
        for ((id, reading) in newest) {
            val (at, value) = reading
            if (at <= (lastAt[id] ?: Long.MIN_VALUE)) continue
            lastAt[id] = at
            // Old values the session still holds (from before a reconnect) are not a drive.
            if (at < oldest || !value.isFinite()) continue
            bins.getOrPut(at) { Bin() }.set(id, value)
        }
        val fixes = pendingGps.toList().also { pendingGps.clear() }
        for ((at, fix) in fixes) bins.getOrPut(at) { Bin() }.gps = fix

        for ((at, bin) in bins) {
            bin.speed?.let {
                speedKmh = it
                speedAt = at
            }
            bin.rpm?.let {
                rpm = it
                rpmAt = at
            }
            if (truth == null || ended) {
                if (!movingWithEngineOn(at)) continue
                truth = DriveTruth(config())
                ended = false
                slept = false
                reportedFaults.clear()
                driveFaults = knownFaults
                // The first sample carries what is known of the car, so the reducer does not start blind.
                bin.speed = bin.speed ?: speedKmh
                bin.rpm = bin.rpm ?: rpm
            }
            truth?.add(bin.toSample(at))
        }
    }

    private fun movingWithEngineOn(at: Long): Boolean {
        val speed = speedKmh ?: return false
        val revs = rpm ?: return false
        return speed > 0.0 && revs > DriveTruth.RUNNING_RPM && at - speedAt <= DriveTruth.MAX_GAP_MS && at - rpmAt <= DriveTruth.MAX_GAP_MS
    }

    /** Refuels and new fault codes during the drive; between drives only the baselines move, so what was there before the drive is never "new". */
    private fun trackEvents(input: DriveFeedInput) {
        val current = truth?.takeIf { !ended }
        val fills = input.fillCount
        val before = fillBaseline
        if (current != null && before != null && fills > before) {
            repeat(fills - before) { current.addRefuel(input.nowMs, "refuel") }
        }
        fillBaseline = fills

        val codes = input.faultCodes
        if (current == null) {
            if (codes != null) knownFaults = codes
            return
        }
        if (codes == null) return
        val start = driveFaults
        if (start == null) {
            // The codes were not read when the drive began: the first reading during it is the baseline, nothing can be called new.
            driveFaults = codes
            return
        }
        for (code in codes.sorted()) {
            if (code !in start && reportedFaults.add(code)) current.addFaultAppeared(input.nowMs, code)
        }
    }

    private class Bin {
        var speed: Double? = null
        var rpm: Double? = null
        private var fuelRateLph: Double? = null
        private var fuelRateGps: Double? = null
        private var maf: Double? = null
        private var level: Double? = null
        private var coolant: Double? = null
        private var throttle: Double? = null
        var gps: GpsFix? = null

        fun set(id: String, value: Double) {
            when (id) {
                SPEED -> speed = value
                RPM -> rpm = value
                FUEL_RATE_LPH -> fuelRateLph = value
                FUEL_RATE_GPS -> fuelRateGps = value
                MAF -> maf = value
                FUEL_LEVEL -> level = value
                COOLANT -> coolant = value
                THROTTLE -> throttle = value
            }
        }

        fun toSample(at: Long) = DriveSample(
            timeMs = at,
            speedKmh = speed,
            rpm = rpm,
            fuelRateLph = fuelRateLph,
            fuelRateGps = fuelRateGps,
            mafGps = maf,
            fuelLevelPercent = level,
            coolantC = coolant,
            throttlePercent = throttle,
            gps = gps,
            engineRunning = rpm?.let { it > DriveTruth.RUNNING_RPM },
        )
    }

    companion object {
        const val SPEED = "vehicle.speed"
        const val RPM = "engine.rpm"
        const val FUEL_RATE_LPH = "fuel.rate_lph"
        const val FUEL_RATE_GPS = "fuel.rate_gps"
        const val MAF = "intake.maf"
        const val FUEL_LEVEL = "fuel.level_pct"
        const val COOLANT = "engine.coolant_temp"
        const val THROTTLE = "engine.throttle_pct"

        private val SIGNALS = setOf(SPEED, RPM, FUEL_RATE_LPH, FUEL_RATE_GPS, MAF, FUEL_LEVEL, COOLANT, THROTTLE)

        /** A reading older than this when first seen was held over from before; it is not fed. */
        private const val MAX_READING_AGE_MS = 10_000L

        /** Only a car the driver says is a petrol one may have fuel worked out from air flow; every other answer, and no answer, is the safe diesel rule. */
        fun reducerFuel(type: FuelType?): ReducerFuelType = if (type == FuelType.PETROL) ReducerFuelType.PETROL else ReducerFuelType.DIESEL
    }
}
