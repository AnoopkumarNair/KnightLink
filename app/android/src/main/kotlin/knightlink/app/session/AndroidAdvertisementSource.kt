package knightlink.app.session

import android.content.Context
import knightlink.platform.ble.AdvertisementMatch
import knightlink.platform.ble.BleAdvertisementListener

/** Tyre sensor advertisements from the phone's Bluetooth, through the passive [BleAdvertisementListener]. */
class AndroidAdvertisementSource(context: Context) : AdvertisementSource {
    private val listener = BleAdvertisementListener(context)

    override fun start(matches: List<AdvertisementMatch>, onAdvertisement: (serviceData: Boolean, key: Int, data: ByteArray, rssi: Int) -> Unit): Boolean =
        listener.start(matches) { onAdvertisement(it.serviceData, it.key, it.data, it.rssi) }

    override fun stop() = listener.stop()
}
