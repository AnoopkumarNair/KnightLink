package knightlink.core.runtime.drivetruth

/**
 * What one drive can honestly say about itself. Every figure is nullable: null means "not available", never zero. The UI shows
 * [notes] beside the figures so nothing appears without its method or its reason for being missing.
 */
public data class DriveTruthReport(
    val durationMinutes: Double,
    val movingMinutes: Double,
    val idleMinutes: Double,
    /** Distance by the car's own speed signal (PID 0D integrated), null when the speed signal had too many gaps. */
    val carDistanceKm: Double?,
    /** Distance by GPS, null without enough good-accuracy fixes. */
    val gpsDistanceKm: Double?,
    /** (car - gps) / gps in percent, only with at least [MIN_COMPARE_KM] of both. Positive means the car-reported speed reads high. */
    val carVsGpsPercent: Double?,
    val fuelLitres: Double?,
    val fuelSource: FuelSource?,
    val costRupees: Double?,
    val costPerKmRupees: Double?,
    val idleCostRupees: Double?,
    val kmPerLitre: Double?,
    val notes: List<DriveTruthNote>,
    val events: List<DriveEvent>,
) {
    public companion object {
        public const val MIN_COMPARE_KM: Double = 3.0
    }
}

/** How the litres were obtained. Cost is only ever shown for these; speed-density estimates are never turned into rupees. */
public enum class FuelSource { FUEL_RATE_LPH, FUEL_RATE_GPS, MAF, FUEL_LEVEL_DROP }

/** Why a figure is missing or how it was made. The UI maps each to a sentence. */
public enum class DriveTruthNote {
    FUEL_NOT_REPORTED,
    FUEL_ONLY_APPROXIMATE,
    FUEL_PRICE_MISSING,
    GPS_POOR,
    SPEED_SIGNAL_GAPS,
    TOO_SHORT_TO_COMPARE,
    DRIVE_NOT_ENDED,
    FUEL_FROM_AIRFLOW,
    FUEL_DENSITY_ASSUMED,
}

public data class DriveEvent(val atSeconds: Double, val kind: DriveEventKind, val detail: String? = null)

public enum class DriveEventKind { LONG_IDLE, COLD_HARD_REVS, REFUEL, FAULT_APPEARED }
