package knightlink.app.data

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
