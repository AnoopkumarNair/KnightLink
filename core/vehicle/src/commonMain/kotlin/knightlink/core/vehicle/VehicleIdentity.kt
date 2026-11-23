package knightlink.core.vehicle

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Placeholder used wherever the make cannot be named. */
public const val UNKNOWN_MAKE: String = "Unknown make"

/**
 * One world manufacturer identifier (the first three characters of a VIN) and the make it stands for. Pure data from
 * `definitions/identity/wmi.json`.
 *
 * @property verified true only for entries that were checked against a source named in [source]; an entry that is not verified is still
 *   used to name the make, but a screen may show it with less certainty
 * @property source where the entry was checked (a URL or a short citation), or what is missing
 */
@Serializable
public data class WmiEntry(
    val wmi: String,
    val make: String,
    val country: String? = null,
    val verified: Boolean = false,
    val source: String = "",
)

/** The file `definitions/identity/wmi.json`. */
@Serializable
public data class WmiFile(
    val schemaVersion: Int,
    val entries: List<WmiEntry>,
    val notes: String? = null,
)

/** Looks a VIN's manufacturer code up. Pure and cheap: build it once from the data file. */
public class WmiTable(entries: List<WmiEntry>) {
    private val byWmi: Map<String, WmiEntry> = entries.associateBy { it.wmi.uppercase() }

    /** The entry for [vin]'s first three characters, or null when the VIN is missing, too short or not listed. */
    public fun lookup(vin: String?): WmiEntry? = vin?.takeIf { it.length >= WMI_LENGTH }?.take(WMI_LENGTH)?.uppercase()?.let(byWmi::get)

    /** The make of [vin], or [UNKNOWN_MAKE]. */
    public fun makeOf(vin: String?): String = lookup(vin)?.make ?: UNKNOWN_MAKE

    public val size: Int get() = byWmi.size

    public companion object {
        public const val SUPPORTED_SCHEMA: Int = 1
        private const val WMI_LENGTH = 3
        private val json = Json { ignoreUnknownKeys = false }

        /** @throws IllegalArgumentException if [text] is not a valid file of a schema this build reads. */
        public fun parse(text: String): WmiTable {
            val file = try {
                json.decodeFromString(WmiFile.serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Invalid WMI file: ${e.message}", e)
            }
            require(file.schemaVersion in 1..SUPPORTED_SCHEMA) { "The WMI file uses schema ${file.schemaVersion}; this build reads up to $SUPPORTED_SCHEMA" }
            file.entries.forEach {
                require(it.wmi.length == WMI_LENGTH && it.wmi.all { c -> c.isLetterOrDigit() && c.isUpperCase() || c.isDigit() }) {
                    "'${it.wmi}' is not a three-character manufacturer code"
                }
                require(!it.verified || it.source.isNotBlank()) { "${it.wmi}: a verified entry needs a source" }
            }
            require(file.entries.map { it.wmi }.toSet().size == file.entries.size) { "the WMI file lists a manufacturer code twice" }
            return WmiTable(file.entries)
        }
    }
}

/** The model year a VIN encodes in its tenth character (ISO 3779 / the common 30-year cycle). */
public object ModelYear {
    private const val CYCLE = 30
    private const val FIRST = 1980
    private const val POSITION = 9

    // The 30-year cycle: A..Y without I, O, Q, U, Z, then 1..9.
    private const val CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    /**
     * The model year [vin] says, or null when the VIN is too short or its tenth character is no year code. The code repeats every 30 years,
     * so the latest year that is not after [latestYear] is taken (a 2026 car's `T` is 2026, not 1996).
     */
    public fun fromVin(vin: String?, latestYear: Int): Int? {
        val code = vin?.takeIf { it.length > POSITION }?.get(POSITION)?.uppercaseChar() ?: return null
        val index = CODES.indexOf(code)
        if (index < 0) return null
        var year = FIRST + index
        while (year + CYCLE <= latestYear) year += CYCLE
        return year.takeIf { it <= latestYear }
    }
}

/** How much of a car the app understands. */
public enum class SupportTier {
    /** A vehicle definition of this car exists: its own signals, units and features. */
    FULL,

    /** Only what every car says in the standard (SAE J1979) services. */
    BASIC,
}

/**
 * Who the car is, in the terms a screen shows. Never the full VIN.
 *
 * @property make the manufacturer from the VIN's manufacturer code, or [UNKNOWN_MAKE]
 * @property makeVerified whether that manufacturer code is a verified entry of the data file
 * @property model the model the user picked (or a definition's model); null until known
 * @property year the model year from the VIN, or null
 * @property vinLast4 the last four characters of the VIN, or null when the car reported none
 * @property protocol the protocol the adapter found, in words (see [ObdProtocol.title]), or null before it is known
 * @property tier [SupportTier.FULL] only when a vehicle definition applies
 */
public data class VehicleIdentity(
    val make: String,
    val makeVerified: Boolean,
    val model: String?,
    val year: Int?,
    val vinLast4: String?,
    val protocol: String?,
    val tier: SupportTier,
)

/** A model the user can pick for a make; `models.json` lists the common ones per make. */
@Serializable
public data class MakeModels(val make: String, val models: List<String>)

@Serializable
public data class ModelListFile(val schemaVersion: Int, val makes: List<MakeModels>, val notes: String? = null)

/** The short lists of common models per make that the model picker offers. */
public class ModelList(private val makes: List<MakeModels>) {
    /** The models offered for [make] (case does not matter); empty for a make that has no list. */
    public fun modelsFor(make: String): List<String> = makes.firstOrNull { it.make.equals(make, ignoreCase = true) }?.models.orEmpty()

    public val allMakes: List<String> get() = makes.map { it.make }

    public companion object {
        private val json = Json { ignoreUnknownKeys = false }

        /** @throws IllegalArgumentException if [text] is not a valid model list. */
        public fun parse(text: String): ModelList {
            val file = try {
                json.decodeFromString(ModelListFile.serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Invalid model list: ${e.message}", e)
            }
            require(file.schemaVersion == 1) { "The model list uses schema ${file.schemaVersion}; this build reads 1" }
            return ModelList(file.makes)
        }
    }
}
