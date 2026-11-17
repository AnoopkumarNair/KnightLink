package knightlink.platform.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.toSerialProfile
import knightlink.core.transport.TransportException
import knightlink.core.transport.ble.BleSerialProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** An adapter seen while scanning. [name] is null until an advertisement that carries it has been received. */
public data class DiscoveredAdapter(
    val address: String,
    val name: String?,
    val rssi: Int,
)

/** Finds nearby adapters over Bluetooth LE. */
public class BleAdapterScanner(
    context: Context,
) {
    private val appContext: Context = context.applicationContext

    /** Scans for the adapter described by an adapter definition's BLE profile, using the names the definition lists. */
    public fun scan(profile: AdapterDefinition.BleProfile): Flow<DiscoveredAdapter> = scan(profile.toSerialProfile(), profile.names)

    /**
     * Scans until the collector is cancelled; the scan starts on collection and is stopped on cancellation or failure.
     * Emits one [DiscoveredAdapter] per address, and again when its signal strength changes noticeably or its name is
     * learned. An advertisement counts when it carries the profile's service UUID or its name is in [names]; many
     * adapters advertise only a name, so no scan filter is used that could hide those.
     *
     * Fails with [TransportException] if the scan permission is missing, Bluetooth is off, or the scan cannot start.
     */
    public fun scan(
        profile: BleSerialProfile,
        names: List<String>,
    ): Flow<DiscoveredAdapter> =
        flow {
            val missing = BlePermissions.requiredToScan().filter { !BlePermissions.isGranted(appContext, it) }
            if (missing.isNotEmpty()) {
                throw TransportException("This app needs permission to scan for Bluetooth devices. Allow it in the app's permission settings.")
            }
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null) throw TransportException("This phone has no Bluetooth adapter.")
            // The scanner object is null while Bluetooth is off, as well as when it has just been switched off.
            val scanner = adapter.takeIf { it.isEnabled }?.bluetoothLeScanner ?: throw TransportException("Bluetooth is turned off. Turn it on and try again.")
            emitAll(results(scanner, AdapterScanMatcher(profile, names)))
        }

    // The permission is verified in scan() before this runs, and a revocation while scanning surfaces as the SecurityException handled here.
    @SuppressLint("MissingPermission")
    private fun results(
        scanner: BluetoothLeScanner,
        matcher: AdapterScanMatcher,
    ): Flow<DiscoveredAdapter> =
        callbackFlow {
            val deduplicator = ScanDeduplicator()
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) = handle(result)

                    override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

                    override fun onScanFailed(errorCode: Int) {
                        close(TransportException(ScanFailure.describe(errorCode)))
                    }

                    private fun handle(result: ScanResult) {
                        // The advertised name, not BluetoothDevice.name: the latter needs the connect permission on Android 12+.
                        val record = result.scanRecord
                        val name = record?.deviceName
                        val services = record?.serviceUuids.orEmpty().map { it.uuid.toString() }
                        if (!matcher.matches(name, services)) return
                        val found = deduplicator.accept(result.device.address, name, result.rssi) ?: return
                        // Results are few after de-duplication; if the collector is that far behind, dropping one is harmless
                        // because the adapter advertises again within moments.
                        trySend(found)
                    }
                }
            // No filters (see scan()); low latency because the user is waiting on this screen.
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try {
                scanner.startScan(null, settings, callback)
            } catch (e: SecurityException) {
                throw TransportException("Bluetooth scan permission was withdrawn.", e)
            } catch (e: IllegalStateException) {
                throw TransportException("Bluetooth was turned off while starting the scan.", e)
            }
            awaitClose {
                try {
                    scanner.stopScan(callback)
                } catch (e: Exception) {
                    // Bluetooth may have been switched off or the permission withdrawn; the scan is gone either way.
                }
            }
        }
}
