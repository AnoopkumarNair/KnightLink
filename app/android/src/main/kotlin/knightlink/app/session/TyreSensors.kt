package knightlink.app.session

import knightlink.core.common.toHex
import knightlink.core.recorder.ExternalSamples
import knightlink.core.recorder.TpmsSample
import knightlink.core.signals.Provenance
import knightlink.core.tpms.TpmsDataKind
import knightlink.core.tpms.TpmsDecoder
import knightlink.platform.ble.AdvertisementMatch
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Where tyre sensor advertisements come from while a session runs (on Android, a passive Bluetooth LE scan filtered in hardware on where the
 * data is and what it starts with). Behind an interface so the session host is tested without a radio. Never connects to anything and never
 * sends; delivers only the data and signal strength, never a device address.
 */
interface AdvertisementSource {
    /**
     * Starts delivering advertisements that fit any of [matches] to [onAdvertisement] (service data or manufacturer data, the UUID or company
     * id it came under, the data after it, RSSI in dBm), from any thread; it must not block.
     *
     * @return whether it is listening; false when it cannot (no permission, Bluetooth off), which only means no capture
     */
    fun start(matches: List<AdvertisementMatch>, onAdvertisement: (serviceData: Boolean, key: Int, data: ByteArray, rssi: Int) -> Unit): Boolean

    /** Stops delivering. Safe to call more than once, and without [start]. */
    fun stop()
}

/** The tyre-sensor part of the screen: whether the session listens, with which layouts, and what it heard lately. */
data class TyreSensors(
    val listening: Boolean,
    /** The titles of the layouts every payload is read with. */
    val layouts: List<String>,
    /** True while any layout is only inferred: values are shown as unconfirmed, and no alert uses them. */
    val unconfirmed: Boolean,
    /** Most recently heard first. */
    val readings: List<TyreReading>,
)

/**
 * One distinct payload heard lately. A sensor is told apart by what it sends (no address is ever kept), so a sensor whose pressure changes
 * shows up as a new row; the old one ages out.
 *
 * @property where `s:` and the service UUID for service data, or the company id for manufacturer data, e.g. `s:FEAA`
 * @property decoded the payload read with each layout that fits it, e.g. `Brand: temp_c 28 degC · pressure_psi 33 psi`
 * @property count advertisements with this payload heard so far
 */
data class TyreReading(
    val where: String,
    val payloadHex: String,
    val decoded: List<String>,
    val rssi: Int,
    val ageSeconds: Long,
    val count: Int,
)

/**
 * Turns tyre sensor advertisements into recording lines (see [ExternalSamples.tpmsLine]) and into what the screen shows.
 *
 * The recording gets at most one line per distinct payload (and company id) per [minIntervalMs], and at most [maxLinesPerSecond] lines in
 * any one second of the clock. A sensor sends the same packet about every 0.85 s for some 10 s once a minute, so five seconds keeps two or
 * three lines of each burst (and every change of value at once, since a change is a new payload) without filling the recording with copies.
 * Every advertisement still updates the screen.
 *
 * Every advertisement is read with each layout whose place and prefix it fits.
 *
 * Thread-safe.
 */
class TyreSensorCapture(
    private val decoders: List<TpmsDecoder>,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val maxLinesPerSecond: Int = DEFAULT_MAX_LINES_PER_SECOND,
    private val keepMs: Long = DEFAULT_KEEP_MS,
    private val maxShown: Int = DEFAULT_MAX_SHOWN,
) {
    private class Seen(val serviceData: Boolean, val key: Int, val payloadHex: String, val decoded: List<String>) {
        var lastSeenMs = 0L
        var lastWrittenMs: Long? = null
        var rssi = 0
        var count = 0
    }

    private val lock = Any()
    private val seen = LinkedHashMap<String, Seen>()
    private var windowSecond = Long.MIN_VALUE
    private var linesInWindow = 0

    /** The layouts' titles, for the screen. */
    val layouts: List<String> = decoders.map { it.definition.title }

    /** What the listener has to filter on: one match per place and prefix of the layouts. */
    val matches: List<AdvertisementMatch> = decoders
        .distinctBy { Triple(it.kind, it.key, it.definition.dataPrefix.uppercase()) }
        .map { AdvertisementMatch(it.kind == TpmsDataKind.SERVICE, it.key, it.prefix) }

    /** Whether any layout is short of standard or verified. */
    val unconfirmed: Boolean = decoders.any { it.definition.provenance != Provenance.STANDARD && it.definition.provenance != Provenance.VERIFIED }

    /**
     * Takes one advertisement received at [timeMs] (any clock that does not go backwards, the same as [readings]').
     *
     * @return the line to record, or null when the rate limit holds it back (or the payload is longer than a line may carry)
     */
    fun accept(timeMs: Long, serviceData: Boolean, key: Int, payload: ByteArray, rssi: Int): String? {
        if (payload.size > ExternalSamples.MAX_TPMS_PAYLOAD_BYTES || key !in 0..0xFFFF) return null
        val hex = payload.toHex()
        synchronized(lock) {
            prune(timeMs)
            val entry = seen.getOrPut("$serviceData:$key:$hex") { Seen(serviceData, key, hex, decode(serviceData, key, payload)) }
            entry.lastSeenMs = timeMs
            entry.rssi = rssi
            entry.count++
            val last = entry.lastWrittenMs
            if (last != null && timeMs - last < minIntervalMs) return null
            val second = Math.floorDiv(timeMs, MS_PER_SECOND)
            if (second != windowSecond) {
                windowSecond = second
                linesInWindow = 0
            }
            if (linesInWindow >= maxLinesPerSecond) return null
            linesInWindow++
            entry.lastWrittenMs = timeMs
            return ExternalSamples.tpmsLine(TpmsSample(timeMs, key, hex, rssi, serviceData))
        }
    }

    /** The payloads heard within the last [keepMs] before [nowMs], most recent first, at most [maxShown]. */
    fun readings(nowMs: Long): List<TyreReading> = synchronized(lock) {
        seen.values
            .filter { nowMs - it.lastSeenMs <= keepMs }
            .sortedByDescending { it.lastSeenMs }
            .take(maxShown)
            .map {
                TyreReading(
                    where = (if (it.serviceData) "s:" else "") + it.key.toString(HEX).uppercase().padStart(4, '0'),
                    payloadHex = it.payloadHex,
                    decoded = it.decoded,
                    rssi = it.rssi,
                    ageSeconds = (nowMs - it.lastSeenMs).coerceAtLeast(0) / MS_PER_SECOND,
                    count = it.count,
                )
            }
    }

    private fun decode(serviceData: Boolean, key: Int, payload: ByteArray): List<String> = decoders.mapNotNull { decoder ->
        val kind = if (serviceData) TpmsDataKind.SERVICE else TpmsDataKind.MANUFACTURER
        val values = decoder.decode(kind, key, payload) ?: return@mapNotNull null
        val id = decoder.sensorId(payload)?.let { "id $it · " }.orEmpty()
        "${decoder.definition.title}: $id" + values.joinToString(" · ") { "${it.id} ${formatValue(it.value)} ${it.unit}" }
    }

    // Forget what has not been heard for a while, and never hold more than a bounded number of payloads: memory stays flat on a long drive.
    private fun prune(nowMs: Long) {
        seen.values.removeAll { nowMs - it.lastSeenMs > keepMs }
        while (seen.size >= MAX_TRACKED) seen.remove(seen.keys.first())
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 5000L
        const val DEFAULT_MAX_LINES_PER_SECOND = 20

        /** A sensor bursts about once a minute; three minutes of silence before a row leaves the screen. */
        const val DEFAULT_KEEP_MS = 180_000L
        const val DEFAULT_MAX_SHOWN = 10
        private const val MAX_TRACKED = 256
        private const val MS_PER_SECOND = 1000L
        private const val HEX = 16

        /** One decimal, or none for a whole number, with a point whatever the locale. */
        internal fun formatValue(value: Double): String {
            if (!value.isFinite()) return value.toString()
            val tenths = (value * 10).roundToLong()
            val sign = if (tenths < 0) "-" else ""
            val magnitude = abs(tenths)
            return if (magnitude % 10 == 0L) "$sign${magnitude / 10}" else "$sign${magnitude / 10}.${magnitude % 10}"
        }
    }
}
