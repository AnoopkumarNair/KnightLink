package knightlink.platform.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log

/**
 * One advertisement's manufacturer data for one company identifier. Deliberately without the sender's address: callers identify a sensor by
 * what it sends (ADR 0002), and an address that is never handed out cannot end up in a recording.
 *
 * @property payload the manufacturer data after the two bytes of the company identifier
 * @property rssi received signal strength in dBm
 * @property elapsedRealtimeMs when it was received, on the `SystemClock.elapsedRealtime` clock
 */
public class Advertisement(
    public val companyId: Int,
    public val payload: ByteArray,
    public val rssi: Int,
    public val elapsedRealtimeMs: Long,
)

/**
 * Listens, passively, to Bluetooth LE advertisements that carry manufacturer data of given company identifiers, for sensors that only
 * broadcast (tyre sensors). Separate from [BleAdapterScanner]: it never connects, never writes, never asks a device for anything (a passive
 * scan sends no scan requests), and uses a balanced scan mode with a hardware filter, so the radio does little while a session runs.
 *
 * Which identifiers to listen for is the caller's (from a definition file), never a constant here. Because a definition may give the two
 * bytes of a company identifier in the order they are sent rather than as the number Android reports, [identifiersFor] lists both readings.
 *
 * Not capturing is always an acceptable outcome: without the scan permission, with Bluetooth off or when the scan cannot start, [start]
 * returns false and nothing is delivered.
 */
public class BleAdvertisementListener(context: Context) {
    private val appContext: Context = context.applicationContext
    private val lock = Any()
    private var active: Active? = null

    private class Active(val scanner: android.bluetooth.le.BluetoothLeScanner, val callback: ScanCallback)

    /**
     * Starts listening for advertisements with manufacturer data filed under any of [companyIds] (each 0..0xFFFF) and hands each one to
     * [onAdvertisement], on a Bluetooth thread; it must not block. A running listener is stopped first.
     *
     * @return whether the scan was started; false (and nothing delivered) without permission, without Bluetooth, or when Android refuses
     */
    @SuppressLint("MissingPermission") // checked first; a permission withdrawn later surfaces as SecurityException, handled below
    public fun start(companyIds: Set<Int>, onAdvertisement: (Advertisement) -> Unit): Boolean {
        stop()
        val ids = companyIds.filter { it in 0..0xFFFF }.toSet()
        if (ids.isEmpty()) return false
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
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Sensor advertisement scan failed: ${ScanFailure.describe(errorCode)}")
                synchronized(lock) { active = null }
            }

            private fun handle(result: ScanResult) {
                val data = result.scanRecord?.manufacturerSpecificData ?: return
                val receivedMs = result.timestampNanos / NANOS_PER_MILLI
                for (i in 0 until data.size()) {
                    val id = data.keyAt(i)
                    if (id !in ids) continue
                    onAdvertisement(Advertisement(id, data.valueAt(i)?.copyOf() ?: ByteArray(0), result.rssi, receivedMs))
                }
            }
        }
        // One filter per identifier: Android ORs them. An empty data pattern matches any data under that identifier.
        val filters = ids.map { ScanFilter.Builder().setManufacturerData(it, ByteArray(0)).build() }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        return try {
            scanner.startScan(filters, settings, callback)
            synchronized(lock) { active = Active(scanner, callback) }
            Log.i(TAG, "Listening for sensor advertisements (${ids.size} company identifiers)")
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

    public companion object {
        private const val TAG = "BleAdvertisements"
        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * The company identifiers to listen for, given one written as four hex digits (as definition files do): the number as written, and
         * the same two bytes the other way round, in case the file gives them in the order they are sent. A tyre capture that hears nothing
         * because of a byte-order mistake would waste a drive; one extra identifier costs nothing.
         *
         * @throws IllegalArgumentException when [hex] is not four hex digits
         */
        public fun identifiersFor(hex: String): Set<Int> {
            require(hex.length == 4 && hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) { "a company identifier is four hex digits, not '$hex'" }
            val id = hex.toInt(16)
            val swapped = ((id and 0xFF) shl 8) or (id ushr 8)
            return linkedSetOf(id, swapped)
        }
    }
}
