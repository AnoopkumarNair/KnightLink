package knightlink.app.session

import knightlink.app.data.DashboardReadings
import knightlink.core.runtime.BodyOutcome
import knightlink.core.runtime.BodyScanState
import knightlink.core.runtime.CannotReadReason
import knightlink.core.runtime.CaptureMode
import knightlink.core.runtime.FaultsState
import knightlink.core.runtime.LadderState
import knightlink.core.runtime.ScanState
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.drivetruth.DriveTruthReport
import knightlink.core.scan.FaultKind
import knightlink.core.scan.ReadinessSummary
import knightlink.core.scan.ScanOutcome
import knightlink.core.scan.ScanStage
import knightlink.core.scan.ScanSummary
import knightlink.core.telemetry.TelemetrySnapshot
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.FuelMethod
import knightlink.core.vehicle.ProbeStage
import knightlink.core.vehicle.VehicleIdentity
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
    /** Tyre sensor capture; null when this session does not capture (no definition configured, or no source). */
    val tyres: TyreSensors? = null,
    /** The refuels found and the drive mode the driver last said; null before a session has any. */
    val drive: DriveSummary? = null,
    /** Whether the session polls the car or only listens to its broadcast (passive capture); live values are paused while it is PASSIVE. */
    val captureMode: CaptureMode = CaptureMode.ACTIVE,
    /** The scan of the car's other units (instrument cluster, body controller ...) that runs before polling starts; see [BodyScanProgress]. */
    val bodyScan: BodyScanProgress = BodyScanProgress.Idle,
    /** The odometer and range the driver typed (start and end of the drive). */
    val dashboard: DashboardReadings = DashboardReadings(),
    /** Who the car is (make, model, year, last four of the VIN, protocol, support tier); null until the probe has found it. Never a full VIN. */
    val identity: VehicleIdentity? = null,
    /**
     * False for a car the app has no definition for: only the standard signals are read, and the features of one car (drive mode buttons, odometer
     * and range entry, tyres, definition coverage, body scan, full scan, passive capture) do not work and should not be shown.
     */
    val knownVehicle: Boolean = true,
    /** The fault codes and the readiness: read once after the probe, and again on request. */
    val faults: FaultSummary = FaultSummary.NOT_READ,
    /** The models the picker offers for the car's make, from the data file; empty when the make has no list. */
    val modelChoices: List<String> = emptyList(),
    /** The learning ladder on a car the app has no definition for: which level runs, and how it ended. Idle for a known car. */
    val ladder: LadderProgress = LadderProgress.Idle,
    /** The deep scan switch: whether an unknown car is also swept for data identifiers (levels L3 and L4). The user can switch it off. */
    val deepScan: Boolean = true,
    /** Whether the car is one the app has met before (its profile is kept on this phone), or a new one to ask about. */
    val garage: GarageStatus = GarageStatus.Unknown,
    /** The fuel price per litre in rupees that the driver entered; null until they do. */
    val fuelPrice: Double? = null,
    /** Where the drive is: not started, under way, or over (see [DriveStageTracker]). */
    val driveStage: DriveStage = DriveStage.NOT_STARTED,
    /** What the drive that just ended can say about itself; null until a drive ends (and until the reducer feeds one). */
    val driveTruth: DriveTruthReport? = null,
    /** How the learning ladder last ended on this connection (it stays after the banner goes); [LadderProgress.Idle] before it has. */
    val ladderResult: LadderProgress = LadderProgress.Idle,
    /** How the parked scan of the other units last ended on this connection; [BodyScanProgress.Idle] before it has. */
    val bodyResult: BodyScanProgress = BodyScanProgress.Idle,
    /** How many of the car's other units still have identifiers to ask (the parked check can be run again while this is above 0). */
    val parkedCheckPending: Int = 0,
    /** The switch "check other units automatically when stopped": the parked check starts by itself after the car has stood still a few seconds. */
    val autoBodyCheck: Boolean = true,
    /** The switch "deeper check of the instrument cluster": the cluster is also read in its extended diagnostic session (parked only). */
    val clusterDeepCheck: Boolean = true,
    /** The estimated odometer of the digital twin; see [OdometerEstimateUi]. */
    val odometerEstimate: OdometerEstimateUi = OdometerEstimateUi.Unavailable,
    /** An error nothing else handled in this run (class and message), for a plain line on screen; null when there was none. */
    val internalError: String? = null,
) {
    /**
     * Whether "Run the parked check again" is offered: the car is connected and polled, the check is not running, and units are still pending
     * (it ended early, was skipped, or never ran on this connection). Not after it completed: a unit left then is one that did not answer.
     */
    val canRunParkedCheckAgain: Boolean
        get() = connection == ConnectionStatus.Live && knownVehicle && parkedCheckPending > 0 && bodyScan !is BodyScanProgress.Running &&
            bodyScan !is BodyScanProgress.Complete && bodyResult !is BodyScanProgress.Complete && captureMode == CaptureMode.ACTIVE

    /** True from the moment a connection is requested until it has been disconnected, failures included. */
    val isActive: Boolean get() = connection != ConnectionStatus.Idle

    companion object {
        val IDLE = UiState(ConnectionStatus.Idle, vehicle = null, liveValues = emptyList())
    }
}

/** Where the reading of the fault codes is. */
enum class FaultsStatus { NOT_READ, READING, READY, FAILED }

/**
 * One fault code to show: [description] is the generic SAE text, or null (a manufacturer code, or one the list lacks: show the code alone).
 * [kind] says confirmed, pending, or permanent (stored by the emissions system). No severity is implied.
 */
data class FaultItem(val code: String, val description: String?, val kind: FaultKind, val ecu: String)

/** The fault codes and the readiness as the screen shows them. [readiness] is null when no unit answered. */
data class FaultSummary(
    val status: FaultsStatus,
    val items: List<FaultItem>,
    val readiness: ReadinessSummary?,
    val failure: String? = null,
) {
    companion object {
        val NOT_READ = FaultSummary(FaultsStatus.NOT_READ, emptyList(), null)
    }
}

fun FaultsState.toSummary(): FaultSummary = when (this) {
    FaultsState.Idle -> FaultSummary.NOT_READ
    FaultsState.Reading -> FaultSummary(FaultsStatus.READING, emptyList(), null)
    is FaultsState.Ready -> FaultSummary(
        FaultsStatus.READY,
        report.faults.map { FaultItem(it.code, it.description, it.kind, it.ecu) },
        report.readiness,
    )
    is FaultsState.Failed -> FaultSummary(FaultsStatus.FAILED, emptyList(), null, reason)
}

/**
 * The learning ladder in the app's terms: the banner says which level [Running] and, at the end, that the car may be driven ([Complete], or
 * [Stopped] with the reason a rule gave); [Flagged] means a new fault code or the lamp appeared and nothing more was asked.
 */
sealed interface LadderProgress {
    data object Idle : LadderProgress

    /** [level] is `L1`..`L4`; [done] of [total] requests, about [secondsLeft] seconds of the level's time box. */
    data class Running(val level: String, val label: String, val done: Int, val total: Int, val secondsLeft: Int) : LadderProgress {
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    data class Complete(val level: String, val answers: Int) : LadderProgress

    data class Stopped(val level: String, val reason: String) : LadderProgress

    data class Flagged(val reason: String) : LadderProgress

    /** True for the two that tell the driver it is over: shown for a while, then gone. A flag stays. */
    val isOutcome: Boolean get() = this is Complete || this is Stopped
}

fun LadderState.toProgress(): LadderProgress = when (this) {
    LadderState.Idle -> LadderProgress.Idle
    is LadderState.Running -> LadderProgress.Running(level.name, label, done, total, secondsLeft)
    is LadderState.Complete -> LadderProgress.Complete(highest.name, answers)
    is LadderState.Stopped -> LadderProgress.Stopped(level.name, reason)
    is LadderState.Flagged -> LadderProgress.Flagged(reason)
}

/**
 * The parked scan of the car's other units in the app's terms: the driver is asked to keep the car parked while it [Running], told when it is
 * [Complete] or [Partial] (they may drive), or that it was [Skipped] (it runs in the background). Texts are in string resources.
 */
sealed interface BodyScanProgress {
    data object Idle : BodyScanProgress

    /** [done] of [total] identifiers asked, about [secondsLeft] seconds to go; [fraction] is for a progress bar. */
    /** [auto]: started by itself while the car stood still; shown as one low-key line, never announced. */
    data class Running(val label: String, val done: Int, val total: Int, val secondsLeft: Int, val auto: Boolean = false) : BodyScanProgress {
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    data class Complete(val answered: Int, val auto: Boolean = false) : BodyScanProgress

    /**
     * The parked stage ended before every unit was done, [ended] says why (and [speedKmh] for confirmed movement). Only [mayDrive] outcomes
     * (the time cap, the user's skip) tell the driver they can drive; the others say the scan stopped early and why.
     */
    data class Partial(val answered: Int, val ended: BodyOutcome = BodyOutcome.TIME_CAP, val speedKmh: Int? = null, val auto: Boolean = false) : BodyScanProgress {
        val mayDrive: Boolean get() = ended == BodyOutcome.TIME_CAP || ended == BodyOutcome.SKIPPED || ended == BodyOutcome.COMPLETED
    }

    /** [moving] is true when the car moves, false when its speed could not be read. */
    data class Skipped(val moving: Boolean, val retried: Boolean = false) : BodyScanProgress

    /** True for the three that tell the driver the scan is over (or will not run now): shown for a while, then gone. */
    val isOutcome: Boolean get() = this is Complete || this is Partial || this is Skipped

    /** Started by itself while the car stood still: silent, and its outcome is not shown as a banner. */
    val isAuto: Boolean get() = (this as? Running)?.auto == true || (this as? Complete)?.auto == true || (this as? Partial)?.auto == true
}

/** The estimated odometer as the Live screen shows it. */
sealed interface OdometerEstimateUi {
    /** No start odometer was typed for this drive (or nothing is connected): the screen says "estimate unavailable". */
    data object Unavailable : OdometerEstimateUi

    /** [km] now; [distanceKm] integrated since the link came up; [calibrated] when a scale from an earlier drive's readings applies. */
    data class Known(val km: Double, val distanceKm: Double, val calibrated: Boolean) : OdometerEstimateUi
}

fun BodyScanState.toProgress(): BodyScanProgress = when (this) {
    BodyScanState.Idle -> BodyScanProgress.Idle
    is BodyScanState.Running -> BodyScanProgress.Running(label, done, total, secondsLeft, auto)
    is BodyScanState.Complete -> BodyScanProgress.Complete(answered, auto)
    is BodyScanState.Partial -> BodyScanProgress.Partial(answered, ended, speedKmh, auto)
    is BodyScanState.Skipped -> BodyScanProgress.Skipped(moving, retried)
}

/** The drive modes of the car; [tag] is what goes into the recording (see ExternalSamples.modeLine). */
enum class DriveMode(val tag: String) {
    ECO("ECO"),
    CITY("CITY"),
    SPORT("SPORT"),
}

/**
 * The refuels found (newest last) and the drive mode the driver last said.
 * @property tankLitres the tank's size, to turn a rise in percent into litres
 */
data class DriveSummary(
    val mode: DriveMode?,
    val lastFill: FuelFillSummary?,
    val fillCount: Int,
)

/** One refuel in the screen's terms: [litres] is the rise in percent of the tank times the tank's size, [atMs] the phone's clock (0 when unknown). */
data class FuelFillSummary(val atMs: Long, val fromPercent: Int, val toPercent: Int, val litres: Int)

/** The size of the Harrier's tank (`tankCapacityL` of its vehicle definition), for litres from a rise in the fuel level. */
const val TANK_LITRES = 50.0

/** The refuels and the mode as the screen shows them; null when there is nothing to show yet. */
fun driveSummary(fills: List<knightlink.core.runtime.FuelFill>, mode: DriveMode?, tankLitres: Double = TANK_LITRES): DriveSummary? {
    if (fills.isEmpty() && mode == null) return null
    val last = fills.lastOrNull()?.let {
        FuelFillSummary(it.atMs, round(it.fromPercent).toInt(), round(it.toPercent).toInt(), round(it.litres(tankLitres)).toInt())
    }
    return DriveSummary(mode, last, fills.size)
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
    data class Finished(
        val outcome: ScanOutcome,
        val detail: String?,
        val summary: ScanSummary,
        val minutes: Int,
        val resumable: Boolean = false,
    ) : ScanStatus

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
        result.resumable,
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

    /** The learning ladder runs on a car without a definition (see [LadderProgress]); [level] is `L1`..`L4`. The car is to stay parked. */
    data class Learning(val level: String) : ConnectionStatus

    /** The adapter is finding out which protocol the car speaks; [round] counts up while the car does not answer. */
    data class Negotiating(val round: Int) : ConnectionStatus

    /**
     * The session gave up on this car for now; show [reason] and offer Retry (`SessionController.retry`). [protocol] is what the adapter named,
     * in words, when it named one (for an unsupported protocol it is the reason itself).
     */
    data class CannotRead(val reason: CannotReadReason, val protocol: String?) : ConnectionStatus

    /** The link is up but no ECU answers, usually because the ignition is off; [busFault] is set when the adapter said the vehicle bus is not reachable. */
    data class WaitingForIgnition(val probes: Int, val busFault: Boolean) : ConnectionStatus

    /** Every ECU answered NO DATA (the ignition is off and the car is asleep); [checks] wake-up attempts have been sent so far. */
    data class VehicleAsleep(val checks: Int) : ConnectionStatus

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
    is SessionState.Negotiating -> ConnectionStatus.Negotiating(round)
    is SessionState.CannotReadVehicle -> ConnectionStatus.CannotRead(reason, detectedProtocol)
    is SessionState.WaitingForIgnition -> ConnectionStatus.WaitingForIgnition(probes, busFault != null)
    is SessionState.VehicleAsleep -> ConnectionStatus.VehicleAsleep(checks)
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
fun vehicleSummary(report: CapabilityReport, coverage: DefinitionCoverage, diesel: Boolean? = null): VehicleSummary {
    val safe = report.redacted()
    // A fuel rate estimated from the air flow assumes a petrol engine's air-fuel ratio; on a diesel it would be wrong, so no estimate is offered.
    val fuelMethod = safe.derived.fuelMethod.let { if (diesel == true && (it == FuelMethod.MAF || it == FuelMethod.SPEED_DENSITY)) FuelMethod.UNKNOWN else it }
    return VehicleSummary(
        ecus = safe.ecus.map { EcuSummary(it.name, it.requestId) },
        vinRedacted = safe.ecus.firstNotNullOfOrNull { it.vin },
        adapterBanner = safe.adapter.banner,
        requestsPerSecond = safe.timing.estimatedRequestsPerSecond,
        fuelMethod = fuelMethod,
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
