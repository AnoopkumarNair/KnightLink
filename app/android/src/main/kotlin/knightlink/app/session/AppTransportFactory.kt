package knightlink.app.session

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.toSerialProfile
import knightlink.core.transport.Transport
import knightlink.core.transport.ble.BleSerialProfile

/**
 * The factory the app uses: the demo is delegated to [demo], a real adapter becomes a Bluetooth transport.
 *
 * The Bluetooth profile (service and characteristic ids, names) is read from the adapter definition when a transport is
 * created, so nothing about an adapter model is known here. Constructing the Android transport is behind [ble] so that
 * this selection logic runs on the JVM.
 *
 * @param adapter the adapter definition the app is configured for; may throw (a missing or invalid file), which the
 *   session shows as a failed connection
 * @param ble builds the Bluetooth transport for the adapter at an address
 */
class AppTransportFactory(
    private val demo: TransportFactory,
    private val adapter: () -> AdapterDefinition,
    private val ble: (address: String, profile: BleSerialProfile) -> Transport,
) : TransportFactory {
    override fun create(target: ConnectionTarget): Transport = when (target) {
        is ConnectionTarget.Demo -> demo.create(target)
        is ConnectionTarget.Ble -> ble(target.address, adapter().requireBle().toSerialProfile())
    }
}

/** The Bluetooth profile of this adapter definition. @throws UnsupportedOperationException if it has none. */
fun AdapterDefinition.requireBle(): AdapterDefinition.BleProfile =
    ble ?: throw UnsupportedOperationException("The adapter definition '$id' has no Bluetooth profile, so it cannot be reached over Bluetooth")
