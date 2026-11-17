package knightlink.platform.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.transport.TransportException
import knightlink.core.transport.TransportKind
import knightlink.core.transport.ble.BleSerialProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reaches an adapter over Bluetooth LE using Android's own GATT API. The adapter's identifiers all come from
 * [profile]; nothing here knows any particular adapter.
 *
 * One instance can be [open]ed repeatedly (a reconnect is simply another `open()`); a connection that is still
 * open when `open()` is called again is closed first, so a retry loop cannot leak GATT clients.
 *
 * @param deviceAddress the adapter's Bluetooth address as reported by [BleAdapterScanner].
 */
public class BleTransport(
    context: Context,
    private val deviceAddress: String,
    private val profile: BleSerialProfile,
    private val config: Config = Config(),
) : Transport {
    /** Timeouts and the MTU to ask for. Every Bluetooth operation is bounded by one of these. */
    public data class Config(
        /** How long to wait for the link to come up before giving up. */
        val connectTimeout: Duration = 15.seconds,
        /** How long to wait for the MTU exchange; the link carries on with the default MTU if it does not answer. */
        val mtuTimeout: Duration = 5.seconds,
        val discoveryTimeout: Duration = 10.seconds,
        /** How long enabling notifications may take. */
        val descriptorTimeout: Duration = 5.seconds,
        /** How long one chunk of a write may take, including time spent waiting for the stack to become ready. */
        val writeTimeout: Duration = 5.seconds,
        /** How long `close()` waits for the disconnect to be confirmed before it releases the connection anyway. */
        val disconnectGrace: Duration = 1500.milliseconds,
        /**
         * The MTU to request, or null to keep the default (writes then go out in chunks of at most 20 bytes, more than any
         * ELM327 command needs). Null by default: the path proven with the reference adapter never asks for a larger MTU, and
         * some cheap adapters misbehave when asked.
         */
        val requestedMtu: Int? = null,
        /**
         * Pause between the link coming up and the first GATT operation. Several adapters (and Android stacks) drop or time out
         * an operation started right after the connection; a short settle time avoids that.
         */
        val settleAfterConnect: Duration = 1.seconds,
    ) {
        init {
            require(requestedMtu == null || requestedMtu in MIN_MTU..MAX_MTU) { "requestedMtu must be in $MIN_MTU..$MAX_MTU, was $requestedMtu" }
            require(!settleAfterConnect.isNegative()) { "settleAfterConnect must not be negative" }
            require(listOf(connectTimeout, mtuTimeout, discoveryTimeout, descriptorTimeout, writeTimeout).all { it.isPositive() }) {
                "timeouts must be positive"
            }
            require(!disconnectGrace.isNegative()) { "disconnectGrace must not be negative" }
        }

        private companion object {
            const val MIN_MTU = 23
            const val MAX_MTU = 517
        }
    }

    // The application context: this object may outlive the screen that created it.
    private val appContext: Context = context.applicationContext
    private val openLock = Mutex()
    private var current: GattLink? = null

    override val kind: TransportKind = TransportKind.BLE

    /**
     * Connects, negotiates, discovers services, resolves the serial channel and turns notifications on.
     * Throws [TransportException] with a message fit to show to the user if any step fails; in every failure case the
     * Android GATT client has been released before this throws.
     */
    override suspend fun open(): ByteStream =
        openLock.withLock {
            current?.close()
            current = null

            val device = checkReadyAndFindDevice()
            val link = GattLink(appContext, device, profile, config)
            var succeeded = false
            try {
                link.connect()
                succeeded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                throw e
            } catch (e: Exception) {
                throw TransportException("Unexpected Bluetooth error: ${e.message ?: e::class.simpleName}", e)
            } finally {
                // Runs for cancellation too: a cancelled connection attempt must not leave a GATT client behind.
                if (!succeeded) link.abort()
            }
            current = link
            link
        }

    private fun checkReadyAndFindDevice(): BluetoothDevice {
        val missing = BlePermissions.requiredToConnect().filter { !BlePermissions.isGranted(appContext, it) }
        if (missing.isNotEmpty()) {
            throw TransportException("This app needs the Nearby devices permission to connect to the adapter. Allow it in the app's permission settings.")
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) throw TransportException("This phone has no Bluetooth adapter.")
        if (!adapter.isEnabled) throw TransportException("Bluetooth is turned off. Turn it on and try again.")
        // The check is case-sensitive about hex digits, and an invalid address would otherwise surface as an IllegalArgumentException.
        val address = deviceAddress.trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw TransportException("'$deviceAddress' is not a valid Bluetooth address.")
        return adapter.getRemoteDevice(address)
    }
}
