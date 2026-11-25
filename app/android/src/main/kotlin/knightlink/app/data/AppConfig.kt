package knightlink.app.data

import knightlink.app.ble.StringStore
import knightlink.core.recorder.ExternalSamples
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Which bundled data the app uses by default. It lives in `assets/app-config.json` so that no vehicle, adapter or
 * scenario name appears in Kotlin code (ADR 0002); supporting another car is a change to that file.
 *
 * All four values are ids as [DefinitionStore] understands them.
 */
@Serializable
data class AppConfig(
    val schemaVersion: Int,
    /** Vehicle definition whose signals are polled, e.g. `vehicles/<make>/<model>`. */
    val vehicleDefinition: String,
    /** Adapter definition whose init script is sent, e.g. `adapters/<name>`. */
    val adapterDefinition: String,
    /** Simulation scenario the demo mode plays, e.g. `sim/<name>`. */
    val demoScenario: String,
    /** Theme pack (colours and visualiser style of the speaking screen), e.g. `themes/<name>`. */
    val themePack: String = DEFAULT_THEME_PACK,
    /**
     * Tyre sensor definitions (`tpms/<brand>`) whose company ids are listened for during a session; every payload heard is shown read with
     * each of them, side by side, until a capture says which layout the car's sensors use. Empty for no capture.
     */
    val tpmsDefinitions: List<String> = emptyList(),
) {
    companion object {
        const val PATH = "app-config.json"
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val DEFAULT_THEME_PACK = "themes/ember"

        // Strict, like every other data file of the project: an unknown key is a typo or a newer schema.
        private val json = Json { ignoreUnknownKeys = false }

        /** @throws DefinitionStoreException if the text is not a valid config of a schema this build understands. */
        fun parse(text: String): AppConfig {
            val config = try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw DefinitionStoreException("Invalid $PATH: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw DefinitionStoreException("Invalid $PATH: ${e.message}", e)
            }
            if (config.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
                throw DefinitionStoreException("$PATH uses schema ${config.schemaVersion}; this build supports up to $SUPPORTED_SCHEMA_VERSION")
            }
            return config
        }

        fun load(assets: AssetSource): AppConfig = parse(DefinitionStore.read(assets, PATH))
    }
}

/**
 * What the dashboard showed, typed by the driver: the odometer and the distance to empty (range), in whole kilometres, at the start and at the
 * end of a drive. They go into the recording (`odo` and `dte` lines, see [ExternalSamples]) so that whatever the car sends for those values can be
 * checked against them afterwards. Null is "not entered".
 */
data class DashboardReadings(
    val odometerStartKm: Long? = null,
    val rangeStartKm: Long? = null,
    val odometerEndKm: Long? = null,
    val rangeEndKm: Long? = null,
    /** When the start readings were last saved (wall clock, ms since the epoch); null when unknown (saved by an older build). */
    val startSavedAtMs: Long? = null,
    /** When the end readings were last saved; as [startSavedAtMs]. */
    val endSavedAtMs: Long? = null,
) {
    val hasStart: Boolean get() = odometerStartKm != null || rangeStartKm != null
    val hasEnd: Boolean get() = odometerEndKm != null || rangeEndKm != null

    /**
     * Whether the start readings belong to the drive under way at [nowMs]: saved by this build, not closed by end readings saved after them,
     * and not older than [maxAgeMs]. Readings of an earlier drive must not be stamped into the recording of a later one.
     */
    fun startIsCurrent(nowMs: Long, maxAgeMs: Long = CURRENT_DRIVE_MAX_AGE_MS): Boolean {
        val at = startSavedAtMs ?: return false
        if (!hasStart) return false
        val closed = endSavedAtMs?.let { it >= at } == true && hasEnd
        return !closed && nowMs - at in 0..maxAgeMs
    }

    /**
     * Whether the end readings belong to the same drive as the start readings: saved after them (or no start at all), and not an odometer below
     * the start's. End readings of an earlier drive are neither shown nor taken as this drive's.
     */
    val endIsCurrent: Boolean
        get() {
            if (!hasEnd) return false
            if (!hasStart) return true
            val start = startSavedAtMs
            val end = endSavedAtMs
            if (start != null && (end == null || end < start)) return false
            val odoStart = odometerStartKm
            val odoEnd = odometerEndKm
            return odoStart == null || odoEnd == null || odoEnd >= odoStart
        }

    /** True when the drive under way at [nowMs] has start readings and no end readings yet: the driver is asked for them when it ends. */
    fun needsEnd(nowMs: Long): Boolean = startIsCurrent(nowMs) && !endIsCurrent

    /**
     * The start readings [odo] and [dte] saved at [nowMs]. When they start a new drive (the start held is not current), the previous drive's
     * readings, its end ones included, are dropped, so a stale end below the new start is never shown or recorded. Within the same drive they
     * correct the start (an empty field keeps what was there).
     */
    fun withStart(odo: Long?, dte: Long?, nowMs: Long): DashboardReadings {
        if (odo == null && dte == null) return this
        if (!startIsCurrent(nowMs)) return DashboardReadings(odometerStartKm = odo, rangeStartKm = dte, startSavedAtMs = nowMs)
        val updated = copy(odometerStartKm = odo ?: odometerStartKm, rangeStartKm = dte ?: rangeStartKm, startSavedAtMs = nowMs)
        // A corrected start above the end held means that end is not this drive's.
        return if (updated.hasEnd && !updated.copy(endSavedAtMs = nowMs).endIsCurrent) {
            updated.copy(odometerEndKm = null, rangeEndKm = null, endSavedAtMs = null)
        } else {
            updated
        }
    }

    /** The end readings [odo] and [dte] saved at [nowMs]; an empty field keeps this drive's value, never an earlier drive's. */
    fun withEnd(odo: Long?, dte: Long?, nowMs: Long): DashboardReadings {
        if (odo == null && dte == null) return this
        val keep = endIsCurrent
        return copy(
            odometerEndKm = odo ?: odometerEndKm.takeIf { keep },
            rangeEndKm = dte ?: rangeEndKm.takeIf { keep },
            endSavedAtMs = nowMs,
        )
    }

    companion object {
        /** Start readings older than this are taken to be of an earlier drive. */
        const val CURRENT_DRIVE_MAX_AGE_MS: Long = 12L * 60 * 60 * 1000

        /** Whole kilometres from what the driver typed; null for nothing, text that is not a number, or a number no dashboard shows. */
        fun parseKm(text: String): Long? = text.trim().takeIf { it.isNotEmpty() && it.length <= MAX_DIGITS && it.all { c -> c in '0'..'9' } }
            ?.toLongOrNull()?.takeIf { it <= ExternalSamples.MAX_KM }

        private const val MAX_DIGITS = 7
    }
}

/** Keeps the [DashboardReadings] in [store] (SharedPreferences in the app) so that what was typed survives the app being closed. */
class DashboardStore(private val store: StringStore) {
    fun load(): DashboardReadings = DashboardReadings(
        DashboardReadings.parseKm(store.get(ODO_START).orEmpty()),
        DashboardReadings.parseKm(store.get(DTE_START).orEmpty()),
        DashboardReadings.parseKm(store.get(ODO_END).orEmpty()),
        DashboardReadings.parseKm(store.get(DTE_END).orEmpty()),
        store.get(START_AT)?.toLongOrNull(),
        store.get(END_AT)?.toLongOrNull(),
    )

    fun save(readings: DashboardReadings) {
        store.put(ODO_START, readings.odometerStartKm?.toString())
        store.put(DTE_START, readings.rangeStartKm?.toString())
        store.put(ODO_END, readings.odometerEndKm?.toString())
        store.put(DTE_END, readings.rangeEndKm?.toString())
        store.put(START_AT, readings.startSavedAtMs?.toString())
        store.put(END_AT, readings.endSavedAtMs?.toString())
    }

    private companion object {
        const val START_AT = "dashboard_start_at"
        const val END_AT = "dashboard_end_at"
        const val ODO_START = "dashboard_odo_start"
        const val DTE_START = "dashboard_dte_start"
        const val ODO_END = "dashboard_odo_end"
        const val DTE_END = "dashboard_dte_end"
    }
}
