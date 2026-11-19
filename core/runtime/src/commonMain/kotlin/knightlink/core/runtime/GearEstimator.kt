package knightlink.core.runtime

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** One learned ratio: engine revs per km/h at which the car has run steadily, and for how many half-second steps. */
@Serializable
public data class GearCluster(val ratio: Double, val count: Int)

/** What the gear estimate has learned about a vehicle; kept between sessions. */
@Serializable
public data class GearModel(val clusters: List<GearCluster> = emptyList())

/**
 * The calculated gear (`transmission.gear_calc`, provenance `inferred`): nothing in the vehicle's standard data says which gear is engaged, so
 * it is worked out from engine revs and vehicle speed. With the torque converter locked, revs / speed is a constant per gear; this learns those
 * constants online (clusters of steady ratios) and numbers them from the lowest gear it has met, highest ratio first.
 *
 * A gear is reported only while the car is above [minSpeedKmh], the ratio has been steady for [steadyMs] (a converter that slips or a shift in
 * progress is not steady) and it matches a cluster seen for at least [establishedCount] steps; otherwise [UNKNOWN]. Reverse has the same kind
 * of ratio as a low gear and cannot be told apart by this; at speed it is rare, and it is never reported. The numbering is the rank among the
 * ratios learned, so it is right only once the lowest gear has been driven steadily above [minSpeedKmh]; until then every number can be off
 * by what is missing.
 *
 * Not thread-safe.
 */
public class GearEstimator(
    model: GearModel = GearModel(),
    private val minSpeedKmh: Double = 10.0,
    private val steadyMs: Long = 800,
    private val maxSpread: Double = 0.025,
    private val mergeWithin: Double = 0.06,
    private val establishedCount: Int = 8,
    private val stepMs: Long = 500,
) {
    private class Cluster(var ratio: Double, var count: Int)

    private class Sample(val atMs: Long, val ratio: Double, val speed: Double)

    private val clusters = model.clusters.map { Cluster(it.ratio, it.count) }.toMutableList()
    private val window = ArrayList<Sample>()
    private var lastStepMs: Long? = null

    /** What has been learned so far. */
    public val model: GearModel get() = GearModel(clusters.map { GearCluster(it.ratio, it.count) })

    /** Bumped whenever the learned clusters change in a way worth saving (a new ratio, or a ratio becoming established). */
    public var revision: Int = 0
        private set

    /**
     * Takes engine speed [rpm] and vehicle speed [speedKmh] read at [atMs] (any clock that does not go backwards) and returns the gear, 1 and
     * up, or [UNKNOWN] (0).
     */
    public fun update(atMs: Long, rpm: Double, speedKmh: Double): Int {
        if (!rpm.isFinite() || !speedKmh.isFinite() || speedKmh <= minSpeedKmh || rpm < MIN_RPM) {
            window.clear()
            return UNKNOWN
        }
        window += Sample(atMs, rpm / speedKmh, speedKmh)
        window.removeAll { atMs - it.atMs > steadyMs }
        if (window.size < MIN_SAMPLES || atMs - window.first().atMs < steadyMs * STEADY_SHARE) return UNKNOWN
        val ratios = window.map { it.ratio }
        val centre = ratios.sorted()[ratios.size / 2]
        // Speed is a whole number of km/h, so half a km/h of every reading is noise, which weighs more the slower the car goes.
        val tolerance = maxSpread + HALF_KMH / window.minOf { it.speed }
        if ((ratios.max() - ratios.min()) / centre > tolerance) return UNKNOWN
        // Revs follow speed in a locked gear, so the speed itself must not be changing fast either (a slipping converter, a shift).
        if (window.maxOf { it.speed } - window.minOf { it.speed } > MAX_SPEED_SPREAD_KMH) return UNKNOWN
        val cluster = learn(atMs, centre)
        return if (cluster.count >= establishedCount) gearOf(cluster) else UNKNOWN
    }

    private fun learn(atMs: Long, ratio: Double): Cluster {
        val near = clusters.minByOrNull { abs(it.ratio - ratio) }?.takeIf { abs(it.ratio - ratio) / ratio <= mergeWithin }
        val cluster = near ?: Cluster(ratio, 0).also {
            clusters += it
            revision++
        }
        if (lastStepMs.let { it == null || atMs - it >= stepMs }) {
            lastStepMs = atMs
            val wasEstablished = cluster.count >= establishedCount
            cluster.ratio += (ratio - cluster.ratio) / (cluster.count + 1).coerceAtMost(MAX_AVERAGING)
            cluster.count++
            if (!wasEstablished && cluster.count >= establishedCount) revision++
        }
        return cluster
    }

    private fun gearOf(cluster: Cluster): Int {
        val established = clusters.filter { it.count >= establishedCount }.sortedByDescending { it.ratio }
        return established.indexOf(cluster) + 1
    }

    public companion object {
        /** The value of the signal while the gear is not known. */
        public const val UNKNOWN: Int = 0
        public const val SIGNAL_ID: String = "transmission.gear_calc"
        public const val UNIT: String = "gear"
        private const val HALF_KMH = 0.5
        private const val MIN_RPM = 500.0
        private const val MIN_SAMPLES = 4
        private const val STEADY_SHARE = 0.75
        private const val MAX_SPEED_SPREAD_KMH = 6.0
        private const val MAX_AVERAGING = 50
    }
}
