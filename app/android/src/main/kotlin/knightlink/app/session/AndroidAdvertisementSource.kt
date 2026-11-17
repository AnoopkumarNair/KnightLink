package knightlink.app.session

import android.content.Context
import knightlink.platform.ble.BleAdvertisementListener

/** Tyre sensor advertisements from the phone's Bluetooth, through the passive [BleAdvertisementListener]. */
class AndroidAdvertisementSource(context: Context) : AdvertisementSource {
    private val listener = BleAdvertisementListener(context)

    override fun start(companyIdsHex: Set<String>, onAdvertisement: (companyId: Int, payload: ByteArray, rssi: Int) -> Unit): Boolean {
        val ids = try {
            companyIdsHex.flatMap { BleAdvertisementListener.identifiersFor(it) }.toSet()
        } catch (e: IllegalArgumentException) {
            return false
        }
        return listener.start(ids) { onAdvertisement(it.companyId, it.payload, it.rssi) }
    }

    override fun stop() = listener.stop()
}
