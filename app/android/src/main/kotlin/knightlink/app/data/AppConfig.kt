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
) {
    companion object {
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
    )

    fun save(readings: DashboardReadings) {
        store.put(ODO_START, readings.odometerStartKm?.toString())
        store.put(DTE_START, readings.rangeStartKm?.toString())
        store.put(ODO_END, readings.odometerEndKm?.toString())
        store.put(DTE_END, readings.rangeEndKm?.toString())
    }

    private companion object {
        const val ODO_START = "dashboard_odo_start"
        const val DTE_START = "dashboard_dte_start"
        const val ODO_END = "dashboard_odo_end"
        const val DTE_END = "dashboard_dte_end"
    }
}
