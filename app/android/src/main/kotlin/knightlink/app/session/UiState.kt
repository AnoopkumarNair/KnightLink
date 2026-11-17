package knightlink.app.session

import knightlink.core.runtime.ScanState
import knightlink.core.runtime.SessionState
import knightlink.core.scan.ScanOutcome
import knightlink.core.scan.ScanStage
import knightlink.core.scan.ScanSummary
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
    /** Requests per second sent to the vehicle over the last few seconds, as measured while polling; null before there is a measurement. */
    val pollingRate: Double? = null,
    /** The full scan; [ScanStatus.Unavailable] outside a live session. */
    val scan: ScanStatus = ScanStatus.Unavailable,
) {
    /** True from the moment a connection is requested until it has been disconnected, failures included. */
    val isActive: Boolean get() = connection != ConnectionStatus.Idle

    companion object {
        val IDLE = UiState(ConnectionStatus.Idle, vehicle = null, liveValues = emptyList())
    }
}

/** The full scan in the app's terms. Texts are in string resources, so this carries only the facts. */
sealed interface ScanStatus {
    /** This session cannot scan (no plan, or the demo of an older build). */
    data object Unavailable : ScanStatus

    /** A scan can be started when the connection is live. */
    data object Ready : ScanStatus

    data object Starting : ScanStatus

    /**
     * @property stage the stage running; [subject] is the control unit or the step it is on, [done] of [total] its progress
     * @property paused the car is moving: the scan waits, at [speedKmh]
     */
    data class Running(
        val stage: ScanStage?,
        val subject: String?,
        val done: Int,
        val total: Int,
        val paused: Boolean,
        val speedKmh: Int?,
    ) : ScanStatus

    /** The scan ended, however it ended; [summary] counts what it found. */
    data class Finished(val outcome: ScanOutcome, val detail: String?, val summary: ScanSummary, val minutes: Int) : ScanStatus

    data class Failed(val reason: String) : ScanStatus
}

fun ScanState.toStatus(): ScanStatus = when (this) {
    ScanState.Unavailable -> ScanStatus.Unavailable
    ScanState.Idle -> ScanStatus.Ready
    ScanState.Starting -> ScanStatus.Starting
    is ScanState.Running -> ScanStatus.Running(progress.stage, progress.subject, progress.done, progress.total, progress.paused, progress.speedKmh)
    is ScanState.Finished -> ScanStatus.Finished(
        result.outcome,
        result.detail,
        result.summary,
        ((result.durationMs + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt(),
    )
    is ScanState.Failed -> ScanStatus.Failed(reason)
}

private const val MILLIS_PER_MINUTE = 60_000L

/** The connection in the app's own terms. Texts are in string resources, so this carries only the facts. */
sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus

    data class Connecting(val attempt: Int) : ConnectionStatus

    data object Initialising : ConnectionStatus

    data class Probing(val stage: ProbeStage) : ConnectionStatus

    /** The link is up but no ECU answers, usually because the ignition is off; [busFault] is set when the adapter said the vehicle bus is not reachable. */
    data class WaitingForIgnition(val probes: Int, val busFault: Boolean) : ConnectionStatus

    data object Live : ConnectionStatus

    /** Live data stopped; the adapter is being set up again on the same link. [reason] is in words and may name the vehicle bus. */
    data class Recovering(val attempt: Int, val reason: String) : ConnectionStatus

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
    is SessionState.WaitingForIgnition -> ConnectionStatus.WaitingForIgnition(probes, busFault != null)
    is SessionState.Recovering -> ConnectionStatus.Recovering(attempt, reason)
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
 *
 * @param periods how often each signal is asked for (by signal id). A value is stale only after [staleAfter] and after missing
 *   [PERIODS_MISSED] of its own periods, so a signal that is refreshed every few seconds is not flagged between refreshes, and one that is
 *   asked only once (an infinite period) is never stale.
 */
fun liveValues(snapshot: TelemetrySnapshot, now: Duration, staleAfter: Duration, periods: Map<String, Duration> = emptyMap()): List<LiveValue> =
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
                stale = age > staleLimit(staleAfter, periods[signal.id]),
            )
        }
        .sortedWith(compareBy({ it.signalId }, { it.ecu.orEmpty() }))

private fun staleLimit(staleAfter: Duration, period: Duration?): Duration =
    if (period == null) staleAfter else maxOf(staleAfter, period * PERIODS_MISSED)

private const val PERIODS_MISSED = 3

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
