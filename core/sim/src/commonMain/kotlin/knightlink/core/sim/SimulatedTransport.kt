package knightlink.core.sim

import knightlink.core.transport.Transport
import knightlink.core.transport.TransportException
import knightlink.core.transport.TransportKind

/**
 * A [Transport] whose far end is a [VirtualElm327] on a simulated vehicle, so session logic that opens,
 * loses and reopens links can run on a PC.
 *
 * Every [open] returns a new adapter, as plugging a real one back in would: its settings are back at their
 * defaults and its log is empty. Not thread-safe: use it from one dispatcher, like the other simulator classes.
 *
 * @param vehicle called on every open. Return the same vehicle each time to keep a scenario running across
 *   reconnects (the car does not reset when the adapter does), or a new one to start from scratch.
 * @param failFirstOpens how many initial calls to [open] fail, to exercise the caller's retry logic
 */
public class SimulatedTransport(
    override val kind: TransportKind = TransportKind.SIMULATED,
    private val vehicle: () -> VirtualVehicle,
    private val config: VirtualElm327.Config = VirtualElm327.Config(),
    private val failFirstOpens: Int = 0,
) : Transport {
    init {
        require(failFirstOpens >= 0) { "failFirstOpens must not be negative" }
    }

    /** The adapter returned by the latest successful [open]; null until one has succeeded. */
    public var current: VirtualElm327? = null
        private set

    /** How many times [open] has been called, failed calls included. */
    public var opens: Int = 0
        private set

    override suspend fun open(): VirtualElm327 {
        opens++
        if (opens <= failFirstOpens) throw TransportException("Simulated open failure $opens of $failFirstOpens")
        return VirtualElm327(vehicle(), config).also { current = it }
    }
}
