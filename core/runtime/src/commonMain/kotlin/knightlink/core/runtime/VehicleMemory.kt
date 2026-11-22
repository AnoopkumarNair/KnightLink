package knightlink.core.runtime

import knightlink.core.scan.ScanResult
import knightlink.core.vehicle.CapabilityReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the app remembers about one vehicle between sessions. Holds no VIN: the probe report is stored redacted (manufacturer code and
 * asterisks), the scan result has its VINs masked, and the vehicle is known by [key], a salted hash of its VIN and calibration ids.
 *
 * @property key see [VehicleKey]
 * @property probe the last complete probe, VIN masked; reused on the next connection when every ECU in it still answers `01 00` alike
 * @property scan the last full scan (finished or part-way), whose discovery identifiers are polled again without scanning again
 * @property discovery for each discovery item (by poll item id), what polling has shown of its value: it decides how often it is asked
 * @property gear what the calculated gear learned (see [GearModel])
 * @property fuel the last fuel level and the refuels found (see [FuelTracker])
 */
@Serializable
public data class VehicleMemory(
    val schemaVersion: Int = SCHEMA_VERSION,
    val key: String,
    val probe: CapabilityReport,
    val scan: ScanResult? = null,
    val discovery: Map<String, DidHistory> = emptyMap(),
    val gear: GearModel = GearModel(),
    val fuel: FuelMemory = FuelMemory(),
) {
    public fun toJson(): String = writer.encodeToString(serializer(), this)

    public companion object {
        public const val SCHEMA_VERSION: Int = 1
        private val writer = Json { encodeDefaults = true }
        private val reader = Json { ignoreUnknownKeys = true }

        /** @return the memory in [text], or null when it is not one this build can read (a damaged file is a fresh start, not an error) */
        public fun fromJson(text: String): VehicleMemory? = try {
            reader.decodeFromString(serializer(), text).takeIf { it.schemaVersion <= SCHEMA_VERSION }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * What polling has shown of one discovery identifier: how many answers, the last value (hex) and whether the value has ever differed. An
 * identifier that never changed over [MIN_SAMPLES_FOR_CONSTANT] answers is asked once per session; one that changed is asked slowly.
 */
@Serializable
public data class DidHistory(val samples: Int = 0, val lastHex: String = "", val changed: Boolean = false) {
    val constant: Boolean get() = !changed && samples >= MIN_SAMPLES_FOR_CONSTANT

    public companion object {
        public const val MIN_SAMPLES_FOR_CONSTANT: Int = 10
    }
}

/** Where [VehicleMemory] is kept between sessions (a file per vehicle in the app). Small, so calls are plain, not suspending. */
public interface VehicleMemoryStore {
    /** A secret that is the same on every run of this installation and differs between installations; mixed into [VehicleKey]. */
    public val salt: String

    /** The key of the vehicle saved most recently, or null when there is none. */
    public fun lastKey(): String?

    public fun load(key: String): VehicleMemory?

    /** Saves [memory] and makes it the most recent one. */
    public fun save(memory: VehicleMemory)
}

/** A store that keeps everything in memory: for tests, and for sessions that must not remember. */
public class InMemoryVehicleMemoryStore(override val salt: String = "test-salt") : VehicleMemoryStore {
    private val items = LinkedHashMap<String, String>()
    private var last: String? = null

    override fun lastKey(): String? = last

    override fun load(key: String): VehicleMemory? = items[key]?.let { VehicleMemory.fromJson(it) }

    override fun save(memory: VehicleMemory) {
        items[memory.key] = memory.toJson()
        last = memory.key
    }

    /** The stored text of every vehicle, to check that no VIN is in it. */
    public fun texts(): List<String> = items.values.toList()
}

/** The identity of a vehicle in storage: a hash, never the VIN. */
public object VehicleKey {
    private const val KEY_DIGITS = 24

    /**
     * A salted SHA-256 (24 hex digits) of the VIN and the calibration ids of every ECU in [report], which must be the full (unredacted) report of
     * a probe; null when the report has neither (nothing then tells vehicles apart, so nothing is remembered).
     */
    public fun of(report: CapabilityReport, salt: String): String? {
        val vin = report.ecus.firstNotNullOfOrNull { it.vin }
        val calibration = report.ecus.flatMap { it.calibrationIds }.sorted()
        if (vin == null && calibration.isEmpty()) return null
        return Sha256.hex("$salt|${vin.orEmpty()}|${calibration.joinToString(",")}").take(KEY_DIGITS)
    }
}
