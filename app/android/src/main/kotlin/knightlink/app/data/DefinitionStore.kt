package knightlink.app.data

import knightlink.app.ui.voice.ThemePack
import knightlink.core.adapter.AdapterDefinition
import knightlink.core.scan.DidSweepPlan
import knightlink.core.signals.DefinitionException
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.sim.SimulationFile
import knightlink.core.tpms.TpmsDecoder
import knightlink.core.tpms.TpmsDefinition
import kotlinx.serialization.SerializationException
import java.io.IOException

/** A bundled data file is missing, unreadable or invalid. The message says which and why, for a status line or a log. */
class DefinitionStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Loads the bundled data: vehicle definitions (with the layers they extend), adapter definitions and simulation
 * scenarios. Files are addressed by the `id` their directory layout implies: `definitions/<id>.json` for definitions
 * and adapters (`vehicles/<make>/<model>`, `adapters/<name>`), `sim/<name>` for scenarios (stored at `sim/<name>.json`).
 *
 * Nothing is cached: a load happens once per connection, and the files are a few kilobytes.
 */
class DefinitionStore(private val assets: AssetSource) {
    /** Ids of the vehicle definitions that can be loaded with [vehicleCatalog], sorted. */
    fun vehicleIds(): List<String> = definitionIds().filter { it.startsWith(VEHICLES_PREFIX) }

    /** Ids of the adapter definitions that can be loaded with [adapter], sorted. */
    fun adapterIds(): List<String> = definitionIds().filter { it.startsWith(ADAPTERS_PREFIX) }

    /** Ids of the simulation scenarios that can be loaded with [simulation], sorted. */
    fun scenarioIds(): List<String> = idsUnder(SIM_ROOT, strip = "")

    /**
     * The signal catalog of vehicle definition [id]: every definition it extends, directly or not, then the vehicle itself, so
     * that later layers override earlier ones.
     *
     * @throws DefinitionStoreException if a file is missing or invalid, or the `extends` chain loops.
     */
    fun vehicleCatalog(id: String): SignalCatalog {
        val layers = LinkedHashMap<String, DefinitionFile>()
        collectLayers(id, layers, ArrayDeque())
        return try {
            SignalCatalog.of(layers.values.toList())
        } catch (e: DefinitionException) {
            throw DefinitionStoreException("Definition '$id' is not usable: ${e.message}", e)
        }
    }

    /** @throws DefinitionStoreException if the file is missing or invalid. */
    fun adapter(id: String): AdapterDefinition {
        val text = read(assets, "$DEFINITIONS_ROOT/${checked(id)}.json")
        return try {
            AdapterDefinition.parse(text)
        } catch (e: SerializationException) {
            throw DefinitionStoreException("Invalid adapter definition '$id': ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw DefinitionStoreException("Invalid adapter definition '$id': ${e.message}", e)
        }
    }

    /**
     * What the full scan sweeps: `definitions/discovery/did-sweep.json`.
     *
     * @throws DefinitionStoreException if the file is missing or invalid.
     */
    fun sweepPlan(): DidSweepPlan {
        val text = read(assets, "$DEFINITIONS_ROOT/$SWEEP_PLAN.json")
        return try {
            DidSweepPlan.parse(text)
        } catch (e: IllegalArgumentException) {
            throw DefinitionStoreException("Invalid sweep plan: ${e.message}", e)
        }
    }

    /**
     * The decoder of tyre sensor definition [id] (`tpms/<brand>`).
     *
     * @throws DefinitionStoreException if the file is missing or invalid, or a formula does not compile.
     */
    fun tpms(id: String): TpmsDecoder {
        if (!id.startsWith(TPMS_PREFIX)) throw DefinitionStoreException("'$id' is not a tyre sensor definition id")
        val text = read(assets, "$DEFINITIONS_ROOT/${checked(id)}.json")
        return try {
            TpmsDecoder(TpmsDefinition.parse(text))
        } catch (e: DefinitionException) {
            throw DefinitionStoreException("Invalid tyre sensor definition '$id': ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw DefinitionStoreException("Invalid tyre sensor definition '$id': ${e.message}", e)
        }
    }

    /** @throws DefinitionStoreException if the file is missing or invalid. */
    fun simulation(id: String): SimulationFile {
        if (!id.startsWith("$SIM_ROOT/")) throw DefinitionStoreException("'$id' is not a simulation scenario id")
        val text = read(assets, "${checked(id)}.json")
        return try {
            SimulationFile.parse(text)
        } catch (e: SerializationException) {
            throw DefinitionStoreException("Invalid simulation scenario '$id': ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw DefinitionStoreException("Invalid simulation scenario '$id': ${e.message}", e)
        }
    }

    /**
     * The theme pack [id] (`themes/<name>`, stored at `definitions/themes/<name>.json`).
     *
     * @throws DefinitionStoreException if the id is not a theme pack id, or the file is missing or invalid.
     */
    fun themePack(id: String): ThemePack {
        if (!id.startsWith(THEMES_PREFIX)) throw DefinitionStoreException("'$id' is not a theme pack id")
        return ThemePack.parse(read(assets, "$DEFINITIONS_ROOT/${checked(id)}.json"), id)
    }

    private fun definition(id: String): DefinitionFile {
        val text = read(assets, "$DEFINITIONS_ROOT/${checked(id)}.json")
        return try {
            DefinitionJson.parse(text)
        } catch (e: DefinitionException) {
            throw DefinitionStoreException("Invalid definition '$id': ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw DefinitionStoreException("Invalid definition '$id': ${e.message}", e)
        }
    }

    // Depth first, so an extended layer always precedes the layer that extends it; a layer reached twice is kept once.
    private fun collectLayers(id: String, layers: MutableMap<String, DefinitionFile>, path: ArrayDeque<String>) {
        if (id in layers) return
        if (id in path) throw DefinitionStoreException("Definitions extend each other in a loop: ${(path + id).joinToString(" -> ")}")
        path.addLast(id)
        val file = definition(id)
        file.extends.forEach { collectLayers(it, layers, path) }
        path.removeLast()
        layers[id] = file
    }

    private fun definitionIds(): List<String> = idsUnder(DEFINITIONS_ROOT, strip = "$DEFINITIONS_ROOT/")

    /** Ids (path without [strip] and without `.json`) of every JSON file below [dir], sorted. Directories are entries without that suffix. */
    private fun idsUnder(dir: String, strip: String): List<String> =
        assets.list(dir).sorted().flatMap { name ->
            val path = "$dir/$name"
            if (name.endsWith(JSON)) listOf(path.removePrefix(strip).removeSuffix(JSON)) else idsUnder(path, strip)
        }

    companion object {
        private const val DEFINITIONS_ROOT = "definitions"
        private const val SIM_ROOT = "sim"
        private const val SWEEP_PLAN = "discovery/did-sweep"
        private const val VEHICLES_PREFIX = "vehicles/"
        private const val ADAPTERS_PREFIX = "adapters/"
        private const val TPMS_PREFIX = "tpms/"
        private const val THEMES_PREFIX = "themes/"
        private const val JSON = ".json"
        private val ID = Regex("[a-z0-9][a-z0-9_.-]*(/[a-z0-9][a-z0-9_.-]*)*")

        // Ids become file paths; refusing anything but plain lower-case path segments keeps a bad config from reaching outside the data directories.
        private fun checked(id: String): String {
            if (!ID.matches(id) || ".." in id) throw DefinitionStoreException("'$id' is not a valid data file id")
            return id
        }

        internal fun read(assets: AssetSource, path: String): String =
            try {
                assets.open(path)
            } catch (e: IOException) {
                throw DefinitionStoreException("Bundled file '$path' is missing or unreadable: ${e.message}", e)
            }
    }
}
