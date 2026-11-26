package knightlink.app.session

import knightlink.core.transport.Transport

/** What a session connects to. */
sealed interface ConnectionTarget {
    /** The simulated vehicle of [scenarioId], run through the complete real stack on the phone. */
    data class Demo(val scenarioId: String) : ConnectionTarget

    /** A real adapter at the BLE [address]. */
    data class Ble(val address: String) : ConnectionTarget

    /** A real Classic Bluetooth (SPP / RFCOMM) adapter at [address]. */
    data class Spp(val address: String) : ConnectionTarget
}

/**
 * The one seam between the app and the link types: the session host asks this for a [Transport] and knows nothing else
 * about how bytes reach an adapter. A later task adds the Bluetooth transport by providing a factory that handles
 * [ConnectionTarget.Ble] (and delegates [ConnectionTarget.Demo] to [DemoTransportFactory]).
 */
fun interface TransportFactory {
    /**
     * @throws UnsupportedOperationException if this build cannot connect to [target].
     * @throws IllegalArgumentException if [target] names something that does not exist (an unknown scenario).
     */
    fun create(target: ConnectionTarget): Transport
}
