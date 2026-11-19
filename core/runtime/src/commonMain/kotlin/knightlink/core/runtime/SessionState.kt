package knightlink.core.runtime

import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.ProbeStage
import kotlin.time.Duration

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
