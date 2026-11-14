package knightlink.core.vehicle

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What a [CapabilityProbe] found out about the adapter and the vehicle. Contains the full VIN when the
 * vehicle reported one, so it is for local use only: anything exported or shared must go through [redacted]
 * (ADR 0002 and 0003: no full VIN leaves the device).
 */
@Serializable
public data class CapabilityReport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val adapter: AdapterInfo,
    val timing: Timing,
    /** ECUs that answered, in ascending request id order. */
    val ecus: List<EcuReport>,
    val derived: DerivedFacts,
    /** Things that went wrong without stopping the probe, e.g. a range request that timed out. Never contains a VIN. */
    val warnings: List<String> = emptyList(),
) {
    /** Copy that is safe to export: every VIN keeps only its first three characters (the manufacturer code). */
    public fun redacted(): CapabilityReport = copy(ecus = ecus.map { it.copy(vin = it.vin?.let { vin -> redactVin(vin) }) })

    /** Pretty-printed JSON, for files and the CLI. */
    public fun toJson(): String = json.encodeToString(serializer(), this)

    public companion object {
        public const val SCHEMA_VERSION: Int = 1

        // encodeDefaults so that schemaVersion is always written; a reader needs it to recognise newer files.
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

        /**
         * @throws IllegalArgumentException if the text is not a valid report or uses a newer schema than this build understands.
         */
        public fun fromJson(text: String): CapabilityReport {
            val report = try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Invalid capability report: ${e.message}", e)
            }
            require(report.schemaVersion <= SCHEMA_VERSION) {
                "Report uses schema ${report.schemaVersion}; this build supports up to $SCHEMA_VERSION"
            }
            return report
        }

        private const val WMI_LENGTH = 3
        private const val VIN_LENGTH = 17

        // Always VIN_LENGTH characters, whatever the ECU sent, so the mask does not leak the length either.
        internal fun redactVin(vin: String): String = vin.take(WMI_LENGTH).padEnd(WMI_LENGTH, '*') + "*".repeat(VIN_LENGTH - WMI_LENGTH)
    }
}

/** What the adapter said about itself. A field is null when the adapter answered `?` or nothing useful. */
@Serializable
public data class AdapterInfo(
    /** Reply to `ATI`. */
    val banner: String?,
    /** Reply to `AT@1`. */
    val description: String?,
    /** Reply to `ATRV`, as sent, e.g. `14.1V`. */
    val voltageText: String?,
    /** [voltageText] as a number of volts, when it parses. */
    val voltage: Double?,
    /** Reply to `ATDPN`: the adapter's protocol number, e.g. `A6`. */
    val protocolNumber: String?,
)

/** How long the probe took and how fast the vehicle answered. */
@Serializable
public data class Timing(
    /** Duration of the whole probe. */
    val probeMs: Double,
    /** Benchmark requests that were answered and therefore count towards the median. */
    val benchmarkSamples: Int,
    /** Null when there was no ECU to benchmark or no sample was answered. */
    val medianRoundTripMs: Double?,
    /** One request at a time, back to back: `1000 / medianRoundTripMs`. Null together with [medianRoundTripMs], or when that is zero. */
    val estimatedRequestsPerSecond: Double?,
)

/** One ECU that answered the probe. */
@Serializable
public data class EcuReport(
    /** Physical request CAN id as upper-case hex, e.g. `7E0`. */
    val requestId: String,
    /** CAN id the replies came from, as upper-case hex. */
    val responseId: String,
    /** From service 09 PID 0A. */
    val name: String?,
    /** From service 09 PID 04. */
    val calibrationIds: List<String>,
    /** Service 01 PIDs this ECU says it supports, ascending, as upper-case 2-digit hex. Range PIDs (00, 20, ...) are left out. */
    val supportedPids: List<String>,
    /** From service 09 PID 02. Full VIN: see [CapabilityReport.redacted]. */
    val vin: String?,
) {
    val requestCanId: Int get() = requestId.toInt(16)
    val responseCanId: Int get() = responseId.toInt(16)

    /** True when this ECU lists service 01 PID [pid] as supported. */
    public fun supports(pid: Int): Boolean = pid in 0..0xFF && pid.toPidHex() in supportedPids

    /** Masks the VIN: this text ends up in logs and crash reports, and a full VIN must not (ADR 0002). */
    override fun toString(): String =
        "EcuReport(requestId=$requestId, responseId=$responseId, name=$name, calibrationIds=$calibrationIds, " +
            "supportedPids=$supportedPids, vin=${vin?.let(CapabilityReport::redactVin)})"
}
