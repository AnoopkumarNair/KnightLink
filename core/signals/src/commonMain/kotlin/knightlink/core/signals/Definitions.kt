package knightlink.core.signals

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Highest definition schema this build understands. Newer files are rejected, not half-read. */
public const val SUPPORTED_SCHEMA_VERSION: Int = 1

/** How much a definition entry can be trusted. */
@Serializable
public enum class Provenance {
    /** Published standard (SAE J1979 / ISO 15031). */
    @SerialName("standard")
    STANDARD,

    /** Confirmed against captures from a real vehicle. */
    @SerialName("verified")
    VERIFIED,

    /** Submitted by users, not yet confirmed by the maintainers. */
    @SerialName("community")
    COMMUNITY,

    /** Guessed by the discovery engine. */
    @SerialName("inferred")
    INFERRED,
}

/**
 * How often a parameter is wanted. The periods are app policy and live here, in one place; which tier a parameter belongs
 * to is data (the `poll` key of its definition). The achieved rate is lower when the adapter cannot keep up: the scheduler
 * shares the available requests between everything that is due.
 */
@Serializable
public enum class PollTier(public val period: Duration) {
    /** Driving feel: RPM, speed, load, pedal, torque, boost. */
    @SerialName("fast")
    FAST(150.milliseconds),

    /** Flow and slowly moving pressures: MAF, fuel rate, EGT, DPF soot and pressure, rail pressure. */
    @SerialName("normal")
    NORMAL(1.seconds),

    /** Temperatures, levels, voltage, ambient, distances, counters. */
    @SerialName("slow")
    SLOW(6.seconds),

    /** Static items: asked once per connection, and again only after the adapter was initialised again. */
    @SerialName("once")
    ONCE(Duration.INFINITE),
}

/** One definition file: either the shared standard layer or a vehicle profile layered on top of it. */
@Serializable
public data class DefinitionFile(
    val schemaVersion: Int,
    val id: String,
    val kind: Kind,
    val title: String,
    val extends: List<String> = emptyList(),
    val vehicle: VehicleInfo? = null,
    val ecus: List<EcuDefinition> = emptyList(),
    val parameters: List<ParameterDefinition> = emptyList(),
) {
    @Serializable
    public enum class Kind {
        @SerialName("standard")
        STANDARD,

        @SerialName("vehicle")
        VEHICLE,
    }
}

@Serializable
public data class VehicleInfo(
    val make: String,
    val model: String,
    val yearFrom: Int,
    val yearTo: Int? = null,
    val engine: String? = null,
    val fuel: String? = null,
    /** World manufacturer identifier: the first three VIN characters. Never a full VIN. */
    val wmi: String? = null,
    val tankCapacityL: Double? = null,
    val fuelDensityKgPerL: Double? = null,
    /** Drive mode labels supported by this vehicle (e.g. ["ECO", "CITY", "SPORT"]). Empty when the car has none. */
    val driveModes: List<String> = emptyList(),
    /**
     * What the standard probe of this very car showed: each listed unit answered with exactly that many supported PIDs. The manufacturer code
     * only pre-selects; the car must also look like this one (another model of the make has other units). Empty for no requirement.
     */
    val probeFingerprint: List<ProbeFingerprint> = emptyList(),
)

/** One unit of a [VehicleInfo.probeFingerprint]: the request id (`7E0`) and how many service 01 PIDs it listed. */
@Serializable
public data class ProbeFingerprint(val requestId: String, val pids: Int)

@Serializable
public data class EcuDefinition(
    val id: String,
    val name: String,
    /** CAN identifiers as hex strings, e.g. `7E0` / `7E8`. */
    val requestId: String,
    val responseId: String,
    val description: String? = null,
) {
    val requestCanId: Int get() = requestId.toInt(16)
    val responseCanId: Int get() = responseId.toInt(16)
}

/** One request (service + PID or DID) and the signals carried in its reply. */
@Serializable
public data class ParameterDefinition(
    /** Service as hex, e.g. `01` or `22`. */
    val service: String,
    /** PID or DID as hex, e.g. `0C` or `F190`. */
    val pid: String,
    /** ECU id this entry applies to; null means any ECU. */
    val ecu: String? = null,
    val provenance: Provenance,
    val signals: List<SignalDefinition>,
    val notes: String? = null,
    /**
     * Number of data bytes after the PID in a reply. Needed to ask for several PIDs in one request (the reply is cut into
     * PIDs by their lengths) and to know whether a reply fits one CAN frame. Without it the PID is always asked alone, and
     * the adapter waits out its timeout for the reply.
     */
    val length: Int? = null,
    /** How often to ask; [PollTier.NORMAL] when absent. */
    val poll: PollTier? = null,
)

@Serializable
public data class SignalDefinition(
    /** Canonical signal id shared by every vehicle, e.g. `engine.rpm`. */
    val id: String,
    /** See [Expression]. Variables are the data bytes after the PID. */
    val formula: String,
    val unit: String,
    val min: Double? = null,
    val max: Double? = null,
)

public class DefinitionException(message: String, cause: Throwable? = null) : Exception(message, cause)

public object DefinitionJson {
    // Strict on purpose: an unknown key means a typo or a newer schema, and both should fail loudly.
    internal val json = Json { ignoreUnknownKeys = false }

    public fun parse(text: String): DefinitionFile {
        val file = try {
            json.decodeFromString(DefinitionFile.serializer(), text)
        } catch (e: IllegalArgumentException) {
            throw DefinitionException("Invalid definition file: ${e.message}", e)
        }
        if (file.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            throw DefinitionException("'${file.id}' uses schema ${file.schemaVersion}; this build supports up to $SUPPORTED_SCHEMA_VERSION")
        }
        return file
    }
}
