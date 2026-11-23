package knightlink.core.scan

import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import knightlink.core.protocol.DiagnosticLink
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import knightlink.core.vehicle.EcuReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Which list a stored fault code came from. */
@Serializable
public enum class FaultKind {
    /** Service 03 (or UDS "confirmed"): the emissions system has confirmed the fault. */
    CONFIRMED,

    /** Service 07 (or UDS "pending"): seen once, not confirmed yet. */
    PENDING,

    /** Service 0A: stored by the emissions system and not cleared by erasing codes; it goes by itself once the system has checked itself. */
    PERMANENT,
}

/**
 * One fault code of one control unit.
 *
 * @property code the SAE code, e.g. `P0301` (a UDS failure-type suffix is left out: `P0301-1F` is `P0301`)
 * @property ecu the request identifier of the control unit that reported it, upper-case hex
 * @property description what the code means, from the generic SAE list; null for a manufacturer-specific code or one the list lacks
 */
@Serializable
public data class FaultEntry(
    val code: String,
    val kind: FaultKind,
    val ecu: String,
    val description: String? = null,
    /** True when the code came from `19 02` and no OBD service listed it. */
    val viaUds: Boolean = false,
)

/**
 * What the emissions monitors say, pooled over the control units that answered.
 *
 * @property milOn whether the malfunction lamp is on, from service 01 PID 01; null when no unit answered it
 * @property storedCount the number of confirmed emission codes the unit says it holds (PID 01); null as [milOn]
 * @property compressionIgnition true for a diesel (PID 01 says which set of monitors it has); null when unknown
 * @property monitors every supported monitor with whether it has completed since the codes were cleared (PID 01), deduplicated by name
 */
@Serializable
public data class ReadinessSummary(
    val milOn: Boolean?,
    val storedCount: Int?,
    val compressionIgnition: Boolean?,
    val monitors: List<Monitor>,
) {
    public val supported: Int get() = monitors.count { it.supported }
    public val complete: Int get() = monitors.count { it.supported && it.complete == true }
    public val incomplete: Int get() = monitors.count { it.supported && it.complete == false }
}

/**
 * What one reading of the fault codes and the readiness found. Nothing in it says how serious a code is: that needs the car's own documents.
 *
 * @property faults every code once per control unit, in the order read
 * @property readiness null when no unit answered service 01 PID 01
 * @property unitsAsked the request identifiers asked
 * @property notAvailable per control unit, what it refused or ignored (service 03, 07, 0A, 19), in words; a refusal is final
 */
@Serializable
public data class FaultReport(
    val faults: List<FaultEntry>,
    val readiness: ReadinessSummary?,
    val unitsAsked: List<String>,
    val notAvailable: List<String> = emptyList(),
    /**
     * What answered, as `<request id>:obd:<kind>` and `<request id>:uds:<kind>` (kind in lower case): a list that is empty for a kind is
     * only a fact when the unit answered for it. Two readings are comparable for the kinds that answered in both.
     */
    val answered: Set<String> = emptySet(),
) {
    /** The key under which [entry]'s kind is listed in [answered]. */
    public fun answerKey(entry: FaultEntry): String = answerKey(entry.ecu, entry.viaUds, entry.kind)

    /** The report with each code's description filled in from [dictionary]. A manufacturer-specific code stays without one. */
    public fun described(dictionary: FaultCodeDictionary): FaultReport =
        copy(faults = faults.map { it.copy(description = dictionary.describe(it.code)) })

    public companion object {
        public fun answerKey(ecu: String, uds: Boolean, kind: FaultKind): String = "$ecu:${if (uds) "uds" else "obd"}:${kind.name.lowercase()}"

        public val EMPTY: FaultReport = FaultReport(emptyList(), null, emptyList())
    }
}

/**
 * Reads the fault codes and the readiness of the control units that answered the standard probe: services 03, 07 and 0A and, on a unit that
 * answered the probe, UDS `19 02 FF`; service 01 PIDs 01 and 41 for the monitors. Read-only (nothing is cleared, nothing is written), and each
 * request is asked once: a refusal (negative response 33, 7F or any other) or silence means "not available" for that unit and that service.
 *
 * The requests go to the units' physical addresses (never 7DF), at [Config.requestInterval] apart.
 */
public class FaultReader(
    private val link: DiagnosticLink,
    private val events: SessionEventSink = SessionEventSink.None,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val config: Config = Config(),
) {
    public data class Config(
        /** Shortest time between two requests. */
        val requestInterval: Duration = 50.milliseconds,
        /** Ask UDS `19 02 FF` of units that answered the probe. */
        val udsFaults: Boolean = true,
        /** Tells an error that means the link to the adapter is gone: it ends the read instead of counting as "no answer". */
        val isLinkLoss: (Throwable) -> Boolean = { false },
    )

    private var last: TimeMark? = null

    /** What the units refused or did not answer, in words. */
    private val notAvailable = ArrayList<String>()
    private val answered = LinkedHashSet<String>()

    /**
     * @param units the control units that answered the standard probe
     * @throws kotlinx.coroutines.CancellationException when cancelled; a lost link surfaces as the link's own exception
     */
    public suspend fun read(units: List<EcuReport>): FaultReport {
        notAvailable.clear()
        answered.clear()
        val faults = ArrayList<FaultEntry>()
        val states = ArrayList<MonitorStatus>()
        for (unit in units) {
            states += monitors(unit)
            faults += obdCodes(unit, SERVICE_STORED, FaultKind.CONFIRMED)
            faults += obdCodes(unit, SERVICE_PENDING, FaultKind.PENDING)
            faults += obdCodes(unit, SERVICE_PERMANENT, FaultKind.PERMANENT)
            if (config.udsFaults) faults += udsCodes(unit)
        }
        val unique = faults.distinctBy { Triple(it.code, it.kind, it.ecu) }
        val report = FaultReport(unique, readiness(states), units.map { it.requestId }, notAvailable.toList(), answered.toSet())
        note(
            "${unique.size} codes (${unique.count { it.kind == FaultKind.CONFIRMED }} confirmed, ${unique.count { it.kind == FaultKind.PENDING }} pending, " +
                "${unique.count { it.kind == FaultKind.PERMANENT }} permanent) from ${units.size} units; " +
                "lamp ${report.readiness?.milOn?.let { if (it) "on" else "off" } ?: "unknown"}",
        )
        return report
    }

    private suspend fun monitors(unit: EcuReport): List<MonitorStatus> {
        val result = ArrayList<MonitorStatus>()
        for (pid in listOf(PID_MONITOR_STATUS, PID_MONITOR_STATUS_CYCLE)) {
            if (!unit.supports(pid)) continue
            val answer = ask(unit, byteArrayOf(SERVICE_LIVE.toByte(), pid.toByte()), SERVICE_LIVE, count = 1)
            val data = (answer as? Answer.Positive)?.data?.takeIf { it.size >= 5 && (it[0].toInt() and 0xFF) == pid } ?: continue
            MonitorStatus.decode(data.copyOfRange(1, data.size), withLamp = pid == PID_MONITOR_STATUS)?.let { result += it }
        }
        return result
    }

    private suspend fun obdCodes(unit: EcuReport, service: Int, kind: FaultKind): List<FaultEntry> =
        when (val answer = ask(unit, byteArrayOf(service.toByte()), service, count = null)) {
            is Answer.Positive -> {
                answered += FaultReport.answerKey(unit.requestId, uds = false, kind = kind)
                DtcDecoder.obd(answer.data).map { FaultEntry(it, kind, unit.requestId) }
            }
            is Answer.Refused -> emptyList<FaultEntry>().also { unavailable(unit, service, "refused with ${answer.code.toString(16).uppercase()}") }
            Answer.Silent -> emptyList<FaultEntry>().also { unavailable(unit, service, "no answer") }
        }

    private suspend fun udsCodes(unit: EcuReport): List<FaultEntry> {
        val payload = byteArrayOf(SERVICE_READ_DTC.toByte(), SUB_BY_STATUS_MASK.toByte(), STATUS_MASK_ALL.toByte())
        return when (val answer = ask(unit, payload, SERVICE_READ_DTC, count = null)) {
            is Answer.Positive -> {
                // data: 02 <availability mask>, then four bytes per code.
                val codes = DtcDecoder.uds(answer.data.copyOfRange(1.coerceAtMost(answer.data.size), answer.data.size))
                val classified = DtcDecoder.classify(codes)
                answered += FaultReport.answerKey(unit.requestId, uds = true, kind = FaultKind.CONFIRMED)
                answered += FaultReport.answerKey(unit.requestId, uds = true, kind = FaultKind.PENDING)
                classified.confirmed.map { FaultEntry(sae(it.code), FaultKind.CONFIRMED, unit.requestId, viaUds = true) } +
                    classified.pending.map { FaultEntry(sae(it.code), FaultKind.PENDING, unit.requestId, viaUds = true) }
            }
            is Answer.Refused -> emptyList<FaultEntry>().also { unavailable(unit, SERVICE_READ_DTC, "refused with ${answer.code.toString(16).uppercase()}") }
            Answer.Silent -> emptyList<FaultEntry>().also { unavailable(unit, SERVICE_READ_DTC, "no answer") }
        }
    }

    private fun readiness(states: List<MonitorStatus>): ReadinessSummary? {
        val withLamp = states.filter { it.milOn != null }
        if (states.isEmpty()) return null
        // PID 01 holds the lamp, the code count and the monitors since the codes were cleared; PID 41 the monitors of this drive cycle only.
        val since = withLamp.ifEmpty { states }
        return ReadinessSummary(
            milOn = withLamp.takeIf { it.isNotEmpty() }?.any { it.milOn == true },
            storedCount = withLamp.mapNotNull { it.dtcCount }.takeIf { it.isNotEmpty() }?.sum(),
            compressionIgnition = states.any { it.compressionIgnition },
            monitors = since.flatMap { it.monitors }.groupBy { it.name }.map { (name, group) ->
                val supported = group.any { it.supported }
                Monitor(name, supported, if (supported) group.filter { it.supported }.all { it.complete == true } else null)
            },
        )
    }

    private sealed interface Answer {
        class Positive(val data: ByteArray) : Answer

        class Refused(val code: Int) : Answer

        data object Silent : Answer
    }

    private suspend fun ask(unit: EcuReport, payload: ByteArray, service: Int, count: Int?): Answer {
        last?.let { mark ->
            val wait = config.requestInterval - mark.elapsedNow()
            if (wait.isPositive()) delay(wait)
        }
        last = timeSource.markNow()
        val response: ElmResponse = try {
            link.request(unit.requestCanId, payload, count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (config.isLinkLoss(e)) throw e
            return Answer.Silent
        }
        val own = response.messages.filter { it.sourceId == unit.responseCanId }
        own.filterIsInstance<DiagnosticResponse.Positive>().firstOrNull { it.service == service }?.let { return Answer.Positive(it.data) }
        // A refusal (33 security access denied, 7F not in this session, 11, 12, 31 ...) is the unit's final word: it is never asked again.
        own.filterIsInstance<DiagnosticResponse.Negative>().firstOrNull { it.service == service }?.let { return Answer.Refused(it.code) }
        return Answer.Silent
    }

    private fun unavailable(unit: EcuReport, service: Int, why: String) {
        notAvailable += "${unit.requestId} service ${service.toString(16).uppercase().padStart(2, '0')}: $why"
    }

    private suspend fun note(detail: String) {
        try {
            events.emit(SessionEvents.FAULTS, detail)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A broken log must not stop a read.
        }
    }

    private fun sae(code: String): String = code.substringBefore('-')

    private companion object {
        const val SERVICE_LIVE = 0x01
        const val SERVICE_STORED = 0x03
        const val SERVICE_PENDING = 0x07
        const val SERVICE_PERMANENT = 0x0A
        const val SERVICE_READ_DTC = 0x19
        const val SUB_BY_STATUS_MASK = 0x02
        const val STATUS_MASK_ALL = 0xFF
        const val PID_MONITOR_STATUS = 0x01
        const val PID_MONITOR_STATUS_CYCLE = 0x41
    }
}

/** The file `definitions/faults/sae-j2012.json`. */
@Serializable
public data class FaultCodeFile(
    val schemaVersion: Int,
    val source: String,
    val notes: String? = null,
    val codes: Map<String, String>,
)

/**
 * Generic descriptions of the standard fault codes (SAE J2012 and ISO 15031-6): the code in `P0xxx`/`P2xxx`/`B0xxx`/`C0xxx`/`U0xxx` form to
 * a short phrase. A manufacturer-specific code (P1xxx, P3xxx, and the manufacturer ranges of the others) has no description here and gets none.
 */
public class FaultCodeDictionary(private val descriptions: Map<String, String>) {
    public val size: Int get() = descriptions.size

    /** The description of [code] (a UDS failure-type suffix is ignored), or null. */
    public fun describe(code: String): String? = descriptions[code.substringBefore('-').uppercase()]

    public companion object {
        public const val SUPPORTED_SCHEMA: Int = 1
        private val json = Json { ignoreUnknownKeys = false }

        /** @throws IllegalArgumentException if [text] is not a valid dictionary file. */
        public fun parse(text: String): FaultCodeDictionary {
            val file = try {
                json.decodeFromString(FaultCodeFile.serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Invalid fault code file: ${e.message}", e)
            }
            require(file.schemaVersion in 1..SUPPORTED_SCHEMA) { "The fault code file uses schema ${file.schemaVersion}" }
            require(file.source.isNotBlank()) { "The fault code file must name its source" }
            val shape = Regex("[PCBU][0-3][0-9A-F]{3}")
            file.codes.keys.forEach { require(shape.matches(it)) { "'$it' is not a fault code" } }
            return FaultCodeDictionary(file.codes)
        }

        /** No descriptions at all. */
        public val EMPTY: FaultCodeDictionary = FaultCodeDictionary(emptyMap())
    }
}
