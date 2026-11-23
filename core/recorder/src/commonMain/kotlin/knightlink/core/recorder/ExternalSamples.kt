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
 * One Bluetooth LE advertisement of a tyre sensor, as received: the data filed under one company identifier (manufacturer data) or one
 * 16-bit service UUID (service data). The sending device's address is never part of it (ADR 0002; iOS does not reveal it either): a sensor
 * is told apart by what it sends.
 *
 * @property key the company identifier, or the 16-bit service UUID when [serviceData], 0..0xFFFF
 * @property payloadHex the data after the company identifier or UUID, upper-case hex without spaces (empty when there was none)
 * @property rssi the received signal strength in dBm, null when not known
 * @property serviceData whether this is service data (under a UUID) rather than manufacturer data (under a company id)
 */
public data class TpmsSample(
    val timeMs: Long,
    val key: Int,
    val payloadHex: String,
    val rssi: Int?,
    val serviceData: Boolean = false,
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
 * - `mode <name>`: the driver said the car is now in drive mode `ECO`, `CITY` or `SPORT` (a button in the app), so the mode can be read off the recording.
 * - `capture <passive|active>`: the app switched the session to passive capture (the adapter only listens to the bus broadcast, no request goes to
 *   the car) or back to active polling, so a reader knows which stretches of the raw traffic are broadcast only.
 * - `odo <km> <start|end>` and `dte <km> <start|end>`: what the dashboard showed, typed by the driver at the start and at the end of a drive: the
 *   odometer in whole kilometres and the distance to empty in whole kilometres (`-` when not known). They calibrate whatever the car
 *   broadcasts or answers for those values; a shared copy keeps only a whole number (or `-`) and the word start or end.
 * - `tpms <where> <payload> <rssi>`: one advertisement of a tyre sensor (see [TpmsSample]), at the event's time. Where is the company id as
 *   four hex digits for manufacturer data, or `s:` and the 16-bit service UUID for service data (e.g. `s:FEAA`), the payload is hex (`-` when empty, at most [MAX_TPMS_PAYLOAD_BYTES] bytes), the RSSI is in dBm (`-` when not known). There is
 *   no device address in the line, and a shared copy keeps only what parses as these three fields.
 */
public object ExternalSamples {
    public const val MOTION: String = "mot"
    public const val GPS: String = "gps"
    public const val TPMS: String = "tpms"
    public const val MODE: String = "mode"
    public const val CAPTURE: String = "capture"
    public const val ODOMETER: String = "odo"
    public const val RANGE: String = "dte"

    private const val START_WORD = "start"
    private const val END_WORD = "end"

    /** The most kilometres an `odo` or `dte` line holds: seven digits cover any odometer. */
    public const val MAX_KM: Long = 9_999_999

    /** The line for the odometer reading [km] (null for unknown) at the [end] of a drive, or its start: `odo 12345 start`. */
    public fun odometerLine(km: Long?, end: Boolean): String = dashboardLine(ODOMETER, km, end)

    /** The line for the distance to empty [km] (null for unknown): `dte 310 end`. */
    public fun rangeLine(km: Long?, end: Boolean): String = dashboardLine(RANGE, km, end)

    private fun dashboardLine(tag: String, km: Long?, end: Boolean): String =
        "$tag " + (km?.takeIf { it in 0..MAX_KM }?.toString() ?: MISSING) + " " + if (end) END_WORD else START_WORD

    private const val PASSIVE_WORD = "passive"
    private const val ACTIVE_WORD = "active"

    /** The line for a change of capture mode: `capture passive` or `capture active`. */
    public fun captureLine(passive: Boolean): String = "$CAPTURE " + if (passive) PASSIVE_WORD else ACTIVE_WORD

    /** The drive modes a `mode` line may name. */
    public val DRIVE_MODES: Set<String> = setOf("ECO", "CITY", "SPORT")

    /** The line for a drive mode change; a name that is not one of [DRIVE_MODES] is written as `-`. */
    public fun modeLine(mode: String): String = "$MODE " + (mode.uppercase().takeIf { it in DRIVE_MODES } ?: MISSING)

    /** The longest manufacturer payload a `tpms` line holds: an extended advertisement carries at most 251 bytes of data in all. */
    public const val MAX_TPMS_PAYLOAD_BYTES: Int = 251

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

    /** The line for one tyre-sensor advertisement. */
    public fun tpmsLine(sample: TpmsSample): String {
        require(sample.key in 0..0xFFFF) { "a company id or service UUID has 16 bits, not ${sample.key}" }
        val payload = sample.payloadHex.uppercase()
        require(payload.length % 2 == 0 && payload.length <= 2 * MAX_TPMS_PAYLOAD_BYTES && payload.all { it in HEX_DIGITS }) {
            "the payload is at most $MAX_TPMS_PAYLOAD_BYTES bytes of hex, not '${sample.payloadHex}'"
        }
        val where = (if (sample.serviceData) SERVICE_MARK else "") + hex4(sample.key)
        return listOf(TPMS, where, payload.ifEmpty { MISSING }, sample.rssi?.toString() ?: MISSING).joinToString(" ")
    }

    /** The advertisement of a `tpms` line from an event at [eventTimeMs], or null when [payload] is not a well-formed `tpms` line. */
    public fun parseTpms(eventTimeMs: Long, payload: String): TpmsSample? {
        val fields = payload.trim().split(' ')
        if (fields.size != TPMS_FIELDS || fields[0] != TPMS) return null
        val serviceData = fields[1].startsWith(SERVICE_MARK)
        val key = fields[1].removePrefix(SERVICE_MARK).takeIf { it.length == 4 && it.all { c -> c in HEX_DIGITS } }?.toInt(16) ?: return null
        val hex = if (fields[2] == MISSING) "" else fields[2]
        if (hex.length % 2 != 0 || hex.length > 2 * MAX_TPMS_PAYLOAD_BYTES || !hex.all { it in HEX_DIGITS }) return null
        val rssi = if (fields[3] == MISSING) null else fields[3].toIntOrNull()?.takeIf { it in RSSI_RANGE } ?: return null
        return TpmsSample(eventTimeMs, key, hex, rssi, serviceData)
    }

    /**
     * The copy of an external line that may leave the device: a position is rounded to [SHARED_DECIMALS] decimals, which is about a
     * kilometre, so a shared drive shows where in the world it was, not at which door. Speed, bearing and accuracy stay (a speed trace does
     * not locate anything). A `gps` line that cannot be read is replaced by a fix with no position, failing closed. A `tpms` line is written
     * again from its parsed fields, so nothing else (an address, say) can ride along; one that cannot be read becomes `tpms - - -`.
     * Other lines are unchanged.
     */
    public fun scrub(payload: String): String {
        if (payload.startsWith("$TPMS ") || payload == TPMS) {
            return parseTpms(0, payload)?.let(::tpmsLine) ?: "$TPMS $MISSING $MISSING $MISSING"
        }
        if (payload.startsWith("$MODE ") || payload == MODE) return modeLine(payload.removePrefix(MODE).trim())
        for (tag in listOf(ODOMETER, RANGE)) {
            if (payload.startsWith("$tag ") || payload == tag) {
                val fields = payload.trim().split(' ')
                val km = fields.getOrNull(1)?.takeIf { it.length <= MAX_KM_DIGITS && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
                return dashboardLine(tag, km, fields.getOrNull(2) == END_WORD)
            }
        }
        // Only the two words survive; anything else (which should not exist) becomes `capture active`, the harmless reading.
        if (payload.startsWith("$CAPTURE ") || payload == CAPTURE) return captureLine(payload.removePrefix(CAPTURE).trim() == PASSIVE_WORD)
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

    private const val MAX_KM_DIGITS = 7
    private const val MOTION_FIELDS = 7
    private const val GPS_FIELDS = 6
    private const val TPMS_FIELDS = 4
    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** What a receiver can report: -127..+20 dBm covers every radio, and anything outside is not a reading. */
    private val RSSI_RANGE = -127..20

    private const val SERVICE_MARK = "s:"

    private fun hex4(id: Int): String = id.toString(16).uppercase().padStart(4, '0')

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
