package knightlink.app.session

import knightlink.core.runtime.SessionState
import knightlink.core.telemetry.TelemetrySnapshot
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.FuelMethod
import knightlink.core.vehicle.ProbeStage
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round
import kotlin.time.Duration

/**
 * Everything the screen and the notification show, as plain data. It is built by pure functions in this file from the
 * session's state, so formatting, grouping and staleness are tested on the JVM, and the screen only lays it out.
 *
 * It never holds a full VIN: the report is redacted before it is summarised.
 */
data class UiState(
    val connection: ConnectionStatus,
    /** Null until the vehicle has been probed. Kept while reconnecting, so the card does not vanish with the link. */
    val vehicle: VehicleSummary?,
    /** Sorted by signal id, then ECU. */
    val liveValues: List<LiveValue>,
) {
    /** True from the moment a connection is requested until it has been disconnected, failures included. */
    val isActive: Boolean get() = connection != ConnectionStatus.Idle

    companion object {
        val IDLE = UiState(ConnectionStatus.Idle, vehicle = null, liveValues = emptyList())
    }
}

/** The connection in the app's own terms. Texts are in string resources, so this carries only the facts. */
sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus

    data class Connecting(val attempt: Int) : ConnectionStatus

    data object Initialising : ConnectionStatus

    data class Probing(val stage: ProbeStage) : ConnectionStatus

    data object Live : ConnectionStatus

    data class Reconnecting(val attempt: Int, val retryInSeconds: Int, val cause: String) : ConnectionStatus

    data class Failed(val reason: String) : ConnectionStatus
}

/**
 * One signal's latest value.
 *
 * @property ecu id of the ECU that reported it, from the vehicle definition; null when the definition ties it to none
 * @property valueText the number, formatted by [formatValue]
 * @property unit what to show after the number, from [displayUnit]; empty for unitless signals
 * @property ageSeconds whole seconds since it was read. Coarse on purpose: a row is redrawn only when it changes.
 * @property stale true when it is older than the staleness limit, i.e. it has stopped updating
 */
data class LiveValue(
    val signalId: String,
    val ecu: String?,
    val valueText: String,
    val unit: String,
    val ageSeconds: Long,
    val stale: Boolean,
) {
    /** Unique per (ECU, signal): the key of the row in a list. */
    val key: String get() = "$signalId|${ecu.orEmpty()}"
}

/** What the probe learned about the vehicle, ready to show. */
data class VehicleSummary(
    val ecus: List<EcuSummary>,
    /** First three characters, then asterisks: see [CapabilityReport.redacted]. Null when no ECU reported a VIN. */
    val vinRedacted: String?,
    val adapterBanner: String?,
    /** What the vehicle and adapter achieved when measured, requests per second; null when it could not be measured. */
    val requestsPerSecond: Double?,
    val fuelMethod: FuelMethod,
    val hasDpfData: Boolean,
    /** Defined and supported, one line each. */
    val confirmed: List<String>,
    /** Defined but not supported, one line each. */
    val missing: List<String>,
    /** Supported but not defined, one line per ECU. */
    val unmapped: List<String>,
)

/** [name] is what the ECU calls itself, or null when it did not say; [requestId] is its CAN address. */
data class EcuSummary(val name: String?, val requestId: String)

/** The values whose signal ids start with [prefix] (the text before the first dot). */
data class ValueGroup(val prefix: String, val values: List<LiveValue>)

fun SessionState.toStatus(): ConnectionStatus = when (this) {
    SessionState.Idle, SessionState.Stopped -> ConnectionStatus.Idle
    is SessionState.Connecting -> ConnectionStatus.Connecting(attempt)
    SessionState.Initialising -> ConnectionStatus.Initialising
    is SessionState.Probing -> ConnectionStatus.Probing(stage)
    is SessionState.Polling -> ConnectionStatus.Live
    is SessionState.Reconnecting -> ConnectionStatus.Reconnecting(attempt, retryIn.wholeSecondsRoundedUp(), cause)
    is SessionState.Failed -> ConnectionStatus.Failed(reason)
}

private fun Duration.wholeSecondsRoundedUp(): Int = ceil(inWholeMilliseconds / MILLIS_PER_SECOND).toInt()

/**
 * A number for display: integers without a decimal point, otherwise two decimals (one from 100 upwards, where a hundredth
 * is noise). Always a point as decimal separator, so the text is the same on every device and in tests.
 */
fun formatValue(value: Double): String = when {
    !value.isFinite() -> "-"
    value == round(value) && abs(value) < MAX_EXACT_INTEGER -> value.toLong().toString()
    abs(value) >= ONE_DECIMAL_FROM -> String.format(Locale.ROOT, "%.1f", value)
    else -> String.format(Locale.ROOT, "%.2f", value)
}

/** The unit as shown: the definitions spell `degC` and `percent`, and mark unitless signals (`enum`, `bitmap`) with a word that means nothing to a driver. */
fun displayUnit(unit: String): String = when (unit) {
    "degC" -> "°C"
    "percent" -> "%"
    "enum", "bitmap" -> ""
    else -> unit
}

/** The part of a signal id before its first dot; the whole id when it has none. */
fun signalPrefix(signalId: String): String = signalId.substringBefore('.')

/**
 * The live values for the screen: sorted by signal id then ECU (so the order is the same on every refresh), each aged against [now],
 * which is on the clock of the readings' timestamps (time since the session started).
 */
fun liveValues(snapshot: TelemetrySnapshot, now: Duration, staleAfter: Duration): List<LiveValue> =
    snapshot.readings
        .map { timed ->
            val age = (now - timed.at).coerceAtLeast(Duration.ZERO)
            val signal = timed.reading.signal
            LiveValue(
                signalId = signal.id,
                ecu = timed.reading.ecu,
                valueText = formatValue(signal.value),
                unit = displayUnit(signal.unit),
                ageSeconds = age.inWholeSeconds,
                stale = age > staleAfter,
            )
        }
        .sortedWith(compareBy({ it.signalId }, { it.ecu.orEmpty() }))

/** Groups [values] by [signalPrefix]; groups sorted by prefix, values in the order given. */
fun groupByPrefix(values: List<LiveValue>): List<ValueGroup> =
    values.groupBy { signalPrefix(it.signalId) }.toSortedMap().map { (prefix, group) -> ValueGroup(prefix, group) }

/** Summarises what the probe found. Redacts first, so nothing downstream can show a full VIN. */
fun vehicleSummary(report: CapabilityReport, coverage: DefinitionCoverage): VehicleSummary {
    val safe = report.redacted()
    return VehicleSummary(
        ecus = safe.ecus.map { EcuSummary(it.name, it.requestId) },
        vinRedacted = safe.ecus.firstNotNullOfOrNull { it.vin },
        adapterBanner = safe.adapter.banner,
        requestsPerSecond = safe.timing.estimatedRequestsPerSecond,
        fuelMethod = safe.derived.fuelMethod,
        hasDpfData = safe.derived.hasDpfData,
        confirmed = coverage.confirmed.map { "${it.pid}  ${it.signals.joinToString(", ")}" + it.catalogEcu.inBrackets() },
        missing = coverage.missing.map { "${it.pid}  ${it.signals.joinToString(", ")}" + it.catalogEcu.inBrackets() },
        unmapped = coverage.unmapped.map { "${it.requestId}: ${it.pids.joinToString(" ")}" },
    )
}

private fun String?.inBrackets(): String = if (this == null) "" else "  ($this)"

private const val MILLIS_PER_SECOND = 1000.0
private const val MAX_EXACT_INTEGER = 1e15
private const val ONE_DECIMAL_FROM = 100.0
