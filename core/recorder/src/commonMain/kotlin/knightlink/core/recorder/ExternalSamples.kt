package knightlink.core.recorder

import kotlin.math.abs
import kotlin.math.roundToLong

/** One reading of the phone's motion sensors. Acceleration in m/s² without gravity where the phone can tell, rotation rate in rad/s. */
public data class MotionSample(
    val timeMs: Long,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val gx: Double,
    val gy: Double,
    val gz: Double,
)

/**
 * One position fix. Every value but the time can be missing (null): a phone without permission for its location still records motion, and
 * a fix does not always know its bearing.
 *
 * @property speedKmh ground speed in km/h
 * @property bearingDeg direction of travel in degrees from north
 * @property accuracyM horizontal accuracy in metres
 */
public data class GpsSample(
    val timeMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speedKmh: Double?,
    val bearingDeg: Double?,
    val accuracyM: Double?,
)

/**
 * The text of [EventKind.EXTERNAL] events: samples that did not come over the link, as compact lines. The recorder does not interpret
 * them except where it must (the scrubber coarsens positions); a source tag says what a line holds, and a reader skips tags it does not know.
 *
 * - `mot <ms before> <ax> <ay> <az> <gx> <gy> <gz>;<…>`: a batch of motion samples. Every sample says how many milliseconds before the
 *   event's time it was taken (the event is written when the batch is handed over, so even the last sample is a little older).
 *   Acceleration in m/s² (2 decimals), rotation in rad/s (3 decimals).
 * - `sensors <what the phone has>`: written once when sampling starts, e.g. `sensors accel=linear gyro=yes gps=on`; for a person.
 * - `gps <lat> <lon> <speed km/h> <bearing deg> <accuracy m>`: one fix; `-` for a value that is not known. Latitude and longitude have
 *   5 decimals on the device and are rounded to [SHARED_DECIMALS] decimals (about a kilometre) in a copy that is shared.
 */
public object ExternalSamples {
    public const val MOTION: String = "mot"
    public const val GPS: String = "gps"

    /** Decimals of a shared coordinate: 0.01 degrees is about 1.1 km of latitude, and less of longitude. */
    public const val SHARED_DECIMALS: Int = 2

    private const val DEVICE_DECIMALS = 5
    private const val MISSING = "-"

    /** The line for a batch of motion samples; [eventTimeMs] is the time the event will carry, which should not be earlier than any sample. */
    public fun motionLine(eventTimeMs: Long, samples: List<MotionSample>): String =
        MOTION + " " + samples.joinToString(";") { sample ->
            listOf(
                (eventTimeMs - sample.timeMs).coerceAtLeast(0).toString(),
                fixed(sample.ax, 2),
                fixed(sample.ay, 2),
                fixed(sample.az, 2),
                fixed(sample.gx, 3),
                fixed(sample.gy, 3),
                fixed(sample.gz, 3),
            ).joinToString(" ")
        }

    /** The samples of a `mot` line from an event at [eventTimeMs], or null when [payload] is not a well-formed motion line. */
    public fun parseMotion(eventTimeMs: Long, payload: String): List<MotionSample>? {
        if (!payload.startsWith("$MOTION ")) return null
        val samples = ArrayList<MotionSample>()
        for (part in payload.removePrefix("$MOTION ").split(';')) {
            val fields = part.trim().split(' ')
            if (fields.size != MOTION_FIELDS) return null
            val before = fields[0].toLongOrNull() ?: return null
            val values = fields.drop(1).map { it.toDoubleOrNull() ?: return null }
            samples += MotionSample(eventTimeMs - before, values[0], values[1], values[2], values[3], values[4], values[5])
        }
        return samples
    }

    /** The line for one fix, with the precision the device keeps. */
    public fun gpsLine(sample: GpsSample): String =
        listOf(
            GPS,
            sample.latitude.text(DEVICE_DECIMALS),
            sample.longitude.text(DEVICE_DECIMALS),
            sample.speedKmh.text(1),
            sample.bearingDeg.text(0),
            sample.accuracyM.text(0),
        ).joinToString(" ")

    /** The fix of a `gps` line from an event at [eventTimeMs], or null when [payload] is not a well-formed fix. */
    public fun parseGps(eventTimeMs: Long, payload: String): GpsSample? {
        val fields = payload.trim().split(' ')
        if (fields.size != GPS_FIELDS || fields[0] != GPS) return null
        val values = fields.drop(1).map { if (it == MISSING) null else it.toDoubleOrNull() ?: return null }
        return GpsSample(eventTimeMs, values[0], values[1], values[2], values[3], values[4])
    }

    /**
     * The copy of an external line that may leave the device: a position is rounded to [SHARED_DECIMALS] decimals, which is about a
     * kilometre, so a shared drive shows where in the world it was, not at which door. Speed, bearing and accuracy stay (a speed trace does
     * not locate anything). A `gps` line that cannot be read is replaced by a fix with no position, failing closed. Other lines are unchanged.
     */
    public fun scrub(payload: String): String {
        if (!payload.startsWith("$GPS ") && payload != GPS) return payload
        val fix = parseGps(0, payload) ?: return "$GPS $MISSING $MISSING $MISSING $MISSING $MISSING"
        return listOf(
            GPS,
            fix.latitude.text(SHARED_DECIMALS),
            fix.longitude.text(SHARED_DECIMALS),
            fix.speedKmh.text(1),
            fix.bearingDeg.text(0),
            fix.accuracyM.text(0),
        ).joinToString(" ")
    }

    private fun Double?.text(decimals: Int): String = if (this == null || !isFinite()) MISSING else fixed(this, decimals)

    private const val MOTION_FIELDS = 7
    private const val GPS_FIELDS = 6

    /** [value] with exactly [decimals] decimals and a point, whatever the platform's locale (common code has no String.format). */
    internal fun fixed(value: Double, decimals: Int): String {
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = (abs(value) * scale).roundToLong()
        val negative = value < 0 && scaled != 0L
        val whole = scaled / scale
        val fraction = (scaled % scale).toString().padStart(decimals, '0')
        return (if (negative) "-" else "") + whole + (if (decimals > 0) ".$fraction" else "")
    }
}
