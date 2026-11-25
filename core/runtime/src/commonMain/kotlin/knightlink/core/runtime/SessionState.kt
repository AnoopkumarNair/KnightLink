package knightlink.core.runtime

import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.ProbeStage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where a [VehicleSession] is in its life: what a notification or a status screen shows. */
public sealed interface SessionState {
    /** Created, [VehicleSession.start] not called yet. */
    public data object Idle : SessionState

    /**
     * Opening the link.
     * @property attempt 1 for the first try, counting up while attempts keep failing. It starts again at 1 once a
     *   connection has reached [Polling] and was then lost.
     */
    public data class Connecting(val attempt: Int) : SessionState

    /** The link is open; the adapter's initialisation script is running. */
    public data object Initialising : SessionState

    /**
     * The adapter is finding out which protocol the car speaks (see [ProtocolNegotiator]): the first thing that happens on a link, before the
     * probe. It can take several seconds on a car the adapter has to search.
     *
     * @property round 1 for the first negotiation of this connection, counting up while the car does not answer
     */
    public data class Negotiating(val round: Int) : SessionState

    /**
     * The session gave up on reading this car for now: [reason] says why. The link is kept; [VehicleSession.retry] starts the negotiation and
     * the probe again (switch the ignition on first, when that is the reason).
     *
     * @property detectedProtocol the protocol the adapter reported (`ATDPN`), in words, when it named one; for [CannotReadReason.UnsupportedProtocol] it is the reason itself
     */
    public data class CannotReadVehicle(val reason: CannotReadReason, val detectedProtocol: String? = null) : SessionState

    /** The capability probe is running; [stage] is the step it has just started. Skipped on a reconnect that reuses the last report. */
    public data class Probing(val stage: ProbeStage) : SessionState

    /**
     * The link is open and the adapter is set up, but no ECU answered the probe: typically the ignition is off. The link is kept
     * and the probe repeated until something answers, then the session goes on to [Polling].
     *
     * @property probes how many probes have found nothing so far
     * @property busFault the adapter said it could not reach the vehicle bus (the status word, e.g. `CAN_ERROR`), which points at the
     *   vehicle side rather than at a sleeping car; null when the probe was simply unanswered
     */
    public data class WaitingForIgnition(val probes: Int, val busFault: String?) : SessionState

    /**
     * Every ECU answered "NO DATA" for a few seconds in a row: the ignition is off and the car is asleep. Polling is stopped (asking a sleeping
     * car again and again helps nobody) and one cheap `01 00` is sent every few seconds; the session goes back to [Polling] when it is answered.
     *
     * @property checks how many of those wake-up requests have gone unanswered so far
     */
    public data class VehicleAsleep(val checks: Int) : SessionState

    /**
     * Live data is being polled.
     *
     * @property report what the probe found on this vehicle. Contains the full VIN: never log or export it unredacted
     *   (see [CapabilityReport.redacted]).
     * @property coverage how the vehicle definition compares with [report]; only its confirmed parameters are polled
     */
    public data class Polling(val report: CapabilityReport, val coverage: DefinitionCoverage) : SessionState

    /**
     * Polling got no positive answer for the silence window, or the adapter kept failing: the adapter is being initialised
     * again on the same link. Goes back to [Polling] as soon as something answers; after [VehicleSession.Config.maxReinits] tries
     * that did not help, the connection is closed and opened again ([Reconnecting]).
     *
     * @property attempt 1 for the first re-initialisation of an outage
     * @property reason what was noticed, in words (for example that the vehicle bus could not be reached)
     */
    public data class Recovering(val attempt: Int, val reason: String) : SessionState

    /**
     * The link could not be opened or was lost, and the session is waiting before it tries again.
     *
     * @property attempt the number of the [Connecting] attempt that will follow the wait
     * @property retryIn how long the wait is, from the moment this state is entered
     * @property cause why the previous connection ended, in words for a log or a status line
     */
    public data class Reconnecting(val attempt: Int, val retryIn: Duration, val cause: String) : SessionState

    /** [VehicleSession.Config.maxConnectAttempts] was reached; the session has given up and will not retry. */
    public data class Failed(val reason: String) : SessionState

    /** [VehicleSession.stop] has completed (or the scope the session runs in was cancelled). Terminal. */
    public data object Stopped : SessionState
}

/**
 * Where the full scan is, next to the session's own [SessionState]. The scan runs inside the session, between two stretches of polling, and
 * only while the car is parked.
 */
public sealed interface ScanState {
    /** This session has no sweep plan, so it cannot scan. */
    public data object Unavailable : ScanState

    /** No scan has run in this session yet, or the last one was dismissed. */
    public data object Idle : ScanState

    /** A scan was asked for and starts as soon as the polling loop has handed the adapter over. */
    public data object Starting : ScanState

    /** The scan is running; [progress] says where, and whether it waits for a car that moves. */
    public data class Running(val progress: knightlink.core.scan.ScanProgress) : ScanState

    /**
     * The scan ended; the result says how (completed, stopped, cancelled, or the link was lost: [knightlink.core.scan.ScanResult.resumable]).
     *
     * @property restored true when this is the part-way result of a scan in an earlier session, remembered for the vehicle so that it can be resumed
     */
    public data class Finished(val result: knightlink.core.scan.ScanResult, val restored: Boolean = false) : ScanState

    /** The scan was not started or failed at once; [reason] is in words. */
    public data class Failed(val reason: String) : ScanState
}

/**
 * Where the body-unit check is, next to [SessionState] (see [VehicleSession.bodyScan]). Its parked stage runs before polling starts and takes up to
 * [VehicleSession.Config.bodyStageCap]; the user is told to keep the car parked meanwhile and when it is over.
 */
public sealed interface BodyScanState {
    /** Nothing running, nothing to report. */
    public data object Idle : BodyScanState

    /**
     * The parked stage is asking the units: [label] is the unit now, [done] of [total] identifiers of all units are done, and about [secondsLeft]
     * seconds remain (never more than what is left of the time cap). [auto]: the session started it by itself because the car stood still
     * (see [VehicleSession.Config.bodyAutoStageAfter]); it is shown low-key and never announced.
     */
    public data class Running(val label: String, val done: Int, val total: Int, val secondsLeft: Int, val auto: Boolean = false) : BodyScanState

    /** The parked stage finished every unit: [answered] data identifiers answered. The user may drive. [auto] as in [Running]. */
    public data class Complete(val answered: Int, val auto: Boolean = false) : BodyScanState

    /**
     * The parked stage ended before every unit was done; the rest is asked in the background. [ended] says why: the time cap
     * ([BodyOutcome.TIME_CAP], the user may drive), confirmed movement ([BodyOutcome.MOVING], at [speedKmh]), an unreadable speed
     * ([BodyOutcome.SPEED_UNKNOWN]), an adapter failure or a lost link ([BodyOutcome.FAILED]), or the user's skip ([BodyOutcome.SKIPPED]). Only the time cap and the skip are "you can drive".
     */
    public data class Partial(
        val answered: Int,
        val ended: BodyOutcome = BodyOutcome.TIME_CAP,
        val speedKmh: Int? = null,
        val auto: Boolean = false,
    ) : BodyScanState

    /**
     * There was no parked stage (the car [moving], or its speed cannot be read, [reason] in words); the units are asked in the background, a few requests at a time.
     * [retried]: the user asked for it again, and the speed still could not be read after several tries (about ten seconds).
     */
    public data class Skipped(val moving: Boolean, val reason: String, val retried: Boolean = false) : BodyScanState
}

/** Why a session cannot read the car in front of it, as a screen shows it. */
public sealed interface CannotReadReason {
    /** Nothing answered on any protocol the adapter tried, or no control unit answered the probe: usually the ignition is off, or the adapter is not in the car's diagnostic socket. */
    public data object NoResponse : CannotReadReason

    /**
     * The car answers, but over a protocol the app cannot read yet (29-bit CAN, or a legacy bus). The protocol is recorded in the session's log.
     *
     * @property protocolName what the adapter reported, in words (for example `ISO 15765-4 CAN (29 bit ID, 500 kbaud)`)
     */
    public data class UnsupportedProtocol(val protocolName: String) : CannotReadReason

    /** The bus is up and a unit answers the broadcast, but no engine control unit answers the app (a gateway that refuses with security access denied, or hides the units). */
    public data object GatewayBlocked : CannotReadReason
}

/** Where the reading of the fault codes and the readiness is, next to [SessionState]. */
public sealed interface FaultsState {
    /** Not read yet in this session. */
    public data object Idle : FaultsState

    /** The control units are being asked. */
    public data object Reading : FaultsState

    /** The last reading; [report] is read-only data and holds no VIN. */
    public data class Ready(val report: knightlink.core.scan.FaultReport) : FaultsState

    /** The reading could not be done (the link failed part-way); [reason] is in words. */
    public data class Failed(val reason: String) : FaultsState
}

/**
 * What the session knows about the car beyond the probe report: which protocol, whether a vehicle definition applies, the model the user said.
 * Never a full VIN.
 *
 * @property protocol the protocol the adapter is locked to; null before it is known
 * @property dpn what `ATDPN` answered
 * @property known true when a vehicle definition applies (the full feature set); false for a car the app knows only from the standard services
 * @property definitionId the definition's id when [known]
 * @property model the model name, from the definition or what the user picked (kept in the vehicle's memory)
 * @property negotiationMs how long finding the protocol took; 0 when it was not negotiated
 * @property compressionIgnition true for a diesel, false for a petrol car, null while unknown (the readiness answer says)
 */
public data class SessionFacts(
    val protocol: knightlink.core.vehicle.ObdProtocol? = null,
    val dpn: String? = null,
    val known: Boolean = true,
    val definitionId: String? = null,
    val model: String? = null,
    val negotiationMs: Long = 0,
    val compressionIgnition: Boolean? = null,
)

/**
 * Limits on how long a session waits for a car that does not answer; after them it ends in [SessionState.CannotReadVehicle].
 *
 * @property maxProbes how many empty rounds (a negotiation or a probe that found no control unit) are made
 * @property maxWait the longest the waiting takes, counted from the first round
 */
public data class ReadLimits(
    val maxProbes: Int = 3,
    val maxWait: Duration = 60.seconds,
) {
    init {
        require(maxProbes >= 1) { "maxProbes must be at least 1" }
        require(maxWait.isPositive()) { "maxWait must be positive" }
    }
}

/**
 * What the session works with once the car is identified: its signal definitions and which features it may use.
 *
 * @property known true when a vehicle definition applies. An unknown car gets the standard signals only, and none of the identifier sweeps,
 *   the body-unit check, `10 01`, passive capture or the features that belong to one car.
 */
public class ResolvedVehicle(
    public val catalog: knightlink.core.signals.SignalCatalog,
    public val known: Boolean,
    public val definitionId: String? = null,
    public val model: String? = null,
    public val candidateChecks: knightlink.core.scan.CandidateChecks? = null,
    public val seedScan: knightlink.core.scan.ScanResult? = null,
    public val scanPlan: knightlink.core.scan.DidSweepPlan? = null,
    /** How far up the learning ladder this car may go (see [LadderLevel]): a car with a definition all the way, any other to L4 (and L3 and L4 need the user's deep-scan switch). */
    public val allowedLevel: LadderLevel = if (known) LadderLevel.L5 else LadderLevel.L4,
)

/** Chooses the vehicle definition from the probe. Must be quick and must not throw for an unknown car: that is [ResolvedVehicle] with `known = false`. */
public fun interface VehicleResolver {
    public fun resolve(report: knightlink.core.vehicle.CapabilityReport): ResolvedVehicle
}
