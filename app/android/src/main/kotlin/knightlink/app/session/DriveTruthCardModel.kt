package knightlink.app.session

import knightlink.core.runtime.drivetruth.DriveTruthNote
import knightlink.core.runtime.drivetruth.DriveTruthReport
import knightlink.core.runtime.drivetruth.FuelSource
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One line of the Drive Truth card. Only figures the report holds appear: a null figure has no line at all (the notes say why), and nothing is
 * ever shown as zero when it is not known.
 */
sealed interface DriveTruthLine {
    /** "₹142 for 24.8 km (₹5.7/km)": [km] is the car's own distance, else GPS's; either part may be missing. */
    data class Cost(val rupees: Double, val km: Double?, val perKm: Double?) : DriveTruthLine

    data class Fuel(val litres: Double, val kmPerLitre: Double?) : DriveTruthLine

    data class Idle(val minutes: Double, val costRupees: Double?) : DriveTruthLine

    data class Distance(val carKm: Double?, val gpsKm: Double?) : DriveTruthLine

    /** Car-reported speed against GPS; positive reads high. Never called a speedometer error: the signal is the engine computer's, not the cluster's. */
    data class CarVsGps(val percent: Double) : DriveTruthLine

    data class Time(val totalMinutes: Double, val movingMinutes: Double) : DriveTruthLine

    /** How the litres were obtained, shown beside any money or fuel figure. */
    data class Method(val source: FuelSource) : DriveTruthLine
}

/** The card: [lines] for the figures that exist, [notes] for what is missing or approximate. */
data class DriveTruthCardModel(val lines: List<DriveTruthLine>, val notes: List<DriveTruthNote>)

fun driveTruthCard(report: DriveTruthReport): DriveTruthCardModel {
    val lines = ArrayList<DriveTruthLine>()
    report.costRupees?.let { lines += DriveTruthLine.Cost(it, report.carDistanceKm ?: report.gpsDistanceKm, report.costPerKmRupees) }
    report.fuelLitres?.let { lines += DriveTruthLine.Fuel(it, report.kmPerLitre) }
    // The method belongs right under the figures it explains.
    val source = report.fuelSource
    if (source != null && (report.costRupees != null || report.fuelLitres != null)) lines += DriveTruthLine.Method(source)
    if (report.idleMinutes > 0.0) lines += DriveTruthLine.Idle(report.idleMinutes, report.idleCostRupees)
    if (report.carDistanceKm != null || report.gpsDistanceKm != null) lines += DriveTruthLine.Distance(report.carDistanceKm, report.gpsDistanceKm)
    report.carVsGpsPercent?.let { lines += DriveTruthLine.CarVsGps(it) }
    lines += DriveTruthLine.Time(report.durationMinutes, report.movingMinutes)
    return DriveTruthCardModel(lines, report.notes.distinct())
}

/** Whole rupees from 100 up, one decimal below: `₹142`, `₹5.7`. */
fun formatRupees(value: Double): String =
    "₹" + if (abs(value) >= RUPEES_WHOLE_FROM) value.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", value)

fun formatKm(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/** A signed percentage with one decimal: `+2.1 %`, `-0.4 %`. */
fun formatSignedPercent(value: Double): String = String.format(Locale.ROOT, "%+.1f %%", value)

/** `42 min` or `1 h 5 min`. */
fun formatMinutes(minutes: Double): String {
    val total = minutes.roundToInt().coerceAtLeast(0)
    return if (total < MINUTES_PER_HOUR) "$total min" else "${total / MINUTES_PER_HOUR} h ${total % MINUTES_PER_HOUR} min"
}

private const val RUPEES_WHOLE_FROM = 100.0
private const val MINUTES_PER_HOUR = 60
