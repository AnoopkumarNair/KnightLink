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
     * Live data is being polled.
     *
     * @property report what the probe found on this vehicle. Contains the full VIN: never log or export it unredacted
     *   (see [CapabilityReport.redacted]).
     * @property coverage how the vehicle definition compares with [report]; only its confirmed parameters are polled
     */
    public data class Polling(val report: CapabilityReport, val coverage: DefinitionCoverage) : SessionState

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
