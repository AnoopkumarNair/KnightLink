package knightlink.platform.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log

/**
 * Which advertisements to listen for: data filed under [key] (a company id for manufacturer data, a 16-bit service UUID for service data)
 * that starts with [prefix]. Plain values from a definition file, never constants here.
 */
public class AdvertisementMatch(
    public val serviceData: Boolean,
    public val key: Int,
    public val prefix: ByteArray,
) {
    init {
        require(key in 0..0xFFFF) { "a company id or 16-bit service UUID, not $key" }
    }

    /** Whether [data] starts with [prefix]. */
    public fun accepts(data: ByteArray): Boolean = data.size >= prefix.size && prefix.indices.all { data[it] == prefix[it] }

    /** The full 128-bit form of a 16-bit service UUID, on the Bluetooth base UUID. */
    public fun serviceUuid(): String = "0000" + key.toString(16).uppercase().padStart(4, '0') + "-0000-1000-8000-00805F9B34FB"
}

/**
 * One advertisement's data for one [AdvertisementMatch]. Deliberately without the sender's address: callers identify a sensor by what it
 * sends (ADR 0002), and an address that is never handed out cannot end up in a recording.
 *
 * @property data the manufacturer data after the company id, or the service data after the UUID
 * @property rssi received signal strength in dBm
 * @property elapsedRealtimeMs when it was received, on the `SystemClock.elapsedRealtime` clock
 */
public class Advertisement(
    public val serviceData: Boolean,
    public val key: Int,
    public val data: ByteArray,
    public val rssi: Int,
    public val elapsedRealtimeMs: Long,
)

/**
 * Listens, passively, to Bluetooth LE advertisements of sensors that only broadcast (tyre sensors). Separate from [BleAdapterScanner]: it
 * never connects, never writes, never asks a device for anything (a passive scan sends no scan requests), and uses a balanced scan mode with
 * a hardware filter per [AdvertisementMatch] (place and prefix of the data), so nothing else is delivered and the radio does little while a
 * session runs.
 *
 * Not capturing is always an acceptable outcome: without the scan permission, with Bluetooth off or when the scan cannot start, [start]
 * returns false and nothing is delivered.
 */
public class BleAdvertisementListener(context: Context) {
    private val appContext: Context = context.applicationContext
    private val lock = Any()
    private var active: Active? = null

    private class Active(val scanner: BluetoothLeScanner, val callback: ScanCallback)

    /**
     * Starts listening for advertisements that fit any of [matches] and hands each one to [onAdvertisement], on a Bluetooth thread; it must
     * not block. A running listener is stopped first.
     *
     * @return whether the scan was started; false (and nothing delivered) without permission, without Bluetooth, or when Android refuses
     */
    @SuppressLint("MissingPermission") // checked first; a permission withdrawn later surfaces as SecurityException, handled below
    public fun start(matches: List<AdvertisementMatch>, onAdvertisement: (Advertisement) -> Unit): Boolean {
        stop()
        if (matches.isEmpty()) return false
        if (BlePermissions.requiredToScan().any { !BlePermissions.isGranted(appContext, it) }) {
            Log.i(TAG, "Not listening for sensor advertisements: no permission to scan")
            return false
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            Log.i(TAG, "Not listening for sensor advertisements: Bluetooth is off or missing")
            return false
        }
        val uuids = matches.filter { it.serviceData }.associateWith { ParcelUuid.fromString(it.serviceUuid()) }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Sensor advertisement scan failed: ${ScanFailure.describe(errorCode)}")
                synchronized(lock) { active = null }
            }

            private fun handle(result: ScanResult) {
                val record = result.scanRecord ?: return
                val receivedMs = result.timestampNanos / NANOS_PER_MILLI
                for (match in matches) {
                    val data = if (match.serviceData) record.getServiceData(uuids.getValue(match)) else record.getManufacturerSpecificData(match.key)
                    if (data == null || !match.accepts(data)) continue
                    onAdvertisement(Advertisement(match.serviceData, match.key, data.copyOf(), result.rssi, receivedMs))
                }
            }
        }
        // One filter per match: Android ORs them. The prefix is matched in full (mask of all ones).
        val filters = matches.map { match ->
            val mask = ByteArray(match.prefix.size) { 0xFF.toByte() }
            val builder = ScanFilter.Builder()
            if (match.serviceData) {
                builder.setServiceData(uuids.getValue(match), match.prefix, mask)
            } else {
                builder.setManufacturerData(match.key, match.prefix, mask)
            }
            builder.build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        return try {
            scanner.startScan(filters, settings, callback)
            synchronized(lock) { active = Active(scanner, callback) }
            Log.i(TAG, "Listening for sensor advertisements (${matches.size} filters)")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Not listening for sensor advertisements: permission withdrawn", e)
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Not listening for sensor advertisements: Bluetooth turned off", e)
            false
        }
    }

    /** Stops listening. Safe to call more than once and without [start]. */
    @SuppressLint("MissingPermission")
    public fun stop() {
        val running = synchronized(lock) { active.also { active = null } } ?: return
        try {
            running.scanner.stopScan(running.callback)
        } catch (e: Exception) {
            // Bluetooth off or the permission withdrawn: the scan is gone either way.
        }
    }

    private companion object {
        const val TAG = "BleAdvertisements"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
