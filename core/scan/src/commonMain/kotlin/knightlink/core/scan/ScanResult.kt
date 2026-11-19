package knightlink.core.scan

import knightlink.core.signals.Provenance
import knightlink.core.vehicle.EcuReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** How a full scan ended. */
@Serializable
public enum class ScanOutcome {
    /** Every stage ran to its end. */
    COMPLETED,

    /** Someone stopped it (or the link went away) part-way; what was found until then is in the result. */
    CANCELLED,

    /** The scan stopped itself for a safety reason (a control unit stopped answering); [ScanResult.detail] says which. */
    STOPPED,

    /** The link to the adapter was lost part-way; what was found until then is in the result, and the scan can be resumed from its last completed stage. */
    LINK_LOST,
}

/**
 * One identifier a control unit answered, from the identification range F180–F1FF. The VIN is never here: [text] holds its manufacturer code
 * and asterisks, and [hex] is left out.
 */
@Serializable
public data class Identifier(val did: String, val label: String?, val hex: String?, val text: String?)

/** A control unit that answered, found by the standard probe or by the discovery of request identifiers. */
@Serializable
public data class ScanEcu(
    /** The identifier the requests go to, as upper-case hex. */
    val requestId: String,
    /** The identifier its replies come from. */
    val responseId: String,
    /** How it was found: `probe` (it answers OBD service 01) or `discovery`. */
    val foundBy: String,
    /** What it calls itself over service 09, when it said. */
    val name: String? = null,
    val identity: List<Identifier> = emptyList(),
    /** OBD service 03 codes (confirmed emission codes), null when it was not asked or did not answer. */
    val storedCodes: List<String>? = null,
    /** OBD service 07 codes (pending). */
    val pendingCodes: List<String>? = null,
    /** OBD service 0A codes (permanent). */
    val permanentCodes: List<String>? = null,
    /** UDS `19 02 FF`, null when it was not asked or the control unit does not do it. */
    val udsFaults: UdsFaults? = null,
    /** Why its reading was cut short, in words, when it was. */
    val note: String? = null,
)

/** A data identifier that answered a service 22 request, with the bytes it sent. */
@Serializable
public data class DidAnswer(
    val did: String,
    /** The data after the identifier, upper-case hex; empty when the reply was seen but could not be read whole. */
    val hex: String,
    /** Nothing in a scan says what a value means: whatever answers is a guess about the car until it is verified. */
    val provenance: Provenance = Provenance.INFERRED,
    /** What is known about the identifier without a drive, e.g. that it mirrors a Mode 01 PID; null when nothing. */
    val label: String? = null,
)

/** What the sweep of one control unit found. */
@Serializable
public data class SweepResult(
    val requestId: String,
    val label: String,
    /** How many identifiers were to be asked, and how many were. */
    val planned: Int,
    val asked: Int,
    val positive: List<DidAnswer> = emptyList(),
    /** Identifiers answered "request out of range" (the ordinary "not here"). */
    val refused: Int = 0,
    /** Identifiers the control unit says exist but are protected (security access denied). */
    val protectedDids: List<String> = emptyList(),
    val silent: Int = 0,
    /** Why the sweep of this control unit ended early, in words; null when it ran to its end. */
    val stoppedBecause: String? = null,
)

/** One battery voltage reading taken during the scan. [text] is as the adapter reported it, e.g. `14.1V`. */
@Serializable
public data class BatteryReading(val atMs: Long, val text: String)

/**
 * The outcome of a full scan. Contains no full VIN: every VIN is reduced to its manufacturer code and asterisks, so the result can be
 * written to a file, logged and shared.
 *
 * @property durationMs the time from start to end, pauses included
 * @property pausedMs of that, the time the scan waited because the car was moving
 * @property stagesDone the stages that ran to their end
 */
@Serializable
public data class ScanResult(
    val schemaVersion: Int = SCHEMA_VERSION,
    val outcome: ScanOutcome,
    val detail: String? = null,
    val durationMs: Long = 0,
    val pausedMs: Long = 0,
    val stagesDone: List<String> = emptyList(),
    val ecus: List<ScanEcu> = emptyList(),
    val readiness: List<EcuReadiness> = emptyList(),
    val sweeps: List<SweepResult> = emptyList(),
    val battery: List<BatteryReading> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** The control units the standard probe found, VIN masked; what a resumed scan needs to carry on from the readiness stage. */
    val probeEcus: List<EcuReport> = emptyList(),
) {
    /** True when the scan can be resumed: it ended part-way (link lost, cancelled) rather than completing or stopping for safety. */
    public val resumable: Boolean get() = outcome == ScanOutcome.LINK_LOST || outcome == ScanOutcome.CANCELLED

    /** Counts for the summary: control units found, codes by status, identifiers per unit. */
    public val summary: ScanSummary
        get() = ScanSummary(
            ecus = ecus.size,
            // The same fault is often in the OBD list and in the UDS one (as P0123 and P0123-1F): once per control unit.
            confirmed = ecus.sumOf { ecu ->
                ((ecu.storedCodes ?: emptyList()) + (ecu.udsFaults?.confirmed?.map { it.code } ?: emptyList())).map(::sae).toSet().size
            },
            pending = ecus.sumOf { ecu ->
                ((ecu.pendingCodes ?: emptyList()) + (ecu.udsFaults?.pending?.map { it.code } ?: emptyList())).map(::sae).toSet().size
            },
            warningLamp = ecus.sumOf { it.udsFaults?.warningLamp?.size ?: 0 },
            notCompleted = ecus.sumOf { it.udsFaults?.notCompletedOnly ?: 0 },
            didsPerEcu = sweeps.associate { it.requestId to it.positive.size },
        )

    /** Compact JSON on one line, for an event; [pretty] for a file. */
    public fun toJson(pretty: Boolean = false): String = (if (pretty) prettyJson else compactJson).encodeToString(serializer(), this)

    public companion object {
        public const val SCHEMA_VERSION: Int = 1

        private val compactJson = Json { encodeDefaults = true }
        private val prettyJson = Json {
            encodeDefaults = true
            prettyPrint = true
        }
        private val reader = Json { ignoreUnknownKeys = true }

        /** @throws IllegalArgumentException if [text] is not a scan result of a schema this build reads. */
        public fun fromJson(text: String): ScanResult {
            val result = try {
                reader.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Invalid scan result: ${e.message}", e)
            }
            require(result.schemaVersion <= SCHEMA_VERSION) { "The scan result uses schema ${result.schemaVersion}; this build reads up to $SCHEMA_VERSION" }
            return result
        }
    }
}

/** The numbers an end-of-scan message is made of. */
public data class ScanSummary(
    val ecus: Int,
    val confirmed: Int,
    val pending: Int,
    val warningLamp: Int,
    /** Codes that only say their test has not run: not faults. */
    val notCompleted: Int,
    /** Identifiers that answered the sweep, by control unit request id. */
    val didsPerEcu: Map<String, Int>,
)

/** The SAE part of a fault code text: `P0123-1F` is `P0123`. */
private fun sae(code: String): String = code.substringBefore('-')

/** The first three characters of a VIN and asterisks, whatever the length: the same mask as [EcuReport]'s. */
internal fun maskVin(vin: String): String = vin.take(3).padEnd(3, '*') + "*".repeat(VIN_LENGTH - 3)

private const val VIN_LENGTH = 17
