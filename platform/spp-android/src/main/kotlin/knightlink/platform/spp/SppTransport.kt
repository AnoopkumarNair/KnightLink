package knightlink.platform.spp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.transport.TransportException
import knightlink.core.transport.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Classic Bluetooth (SPP / RFCOMM) [Transport] for ELM327 v2.1 / v1.5 OBD-II adapters.
 * Tries standard authenticated RFCOMM first and falls back to insecure RFCOMM for budget clones
 * that do not complete SSP pairing cleanly.
 */
public class SppTransport(
    private val context: Context,
    private val address: String,
) : Transport {
    override val kind: TransportKind = TransportKind.BLUETOOTH_CLASSIC

    @SuppressLint("MissingPermission")
    override suspend fun open(): ByteStream = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: throw TransportException("Bluetooth is unavailable on this device")
        val adapter = manager.adapter ?: throw TransportException("No Bluetooth adapter present")
        if (!adapter.isEnabled) throw TransportException("Bluetooth is turned off")

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw TransportException("Invalid Bluetooth address '$address'", e)
        }

        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
        } catch (_: SecurityException) {
            // Discovery cancellation is best-effort; connect still proceeds if BLUETOOTH_CONNECT is granted.
        }

        val socket = connectSocket(device)
        SppByteStream(socket)
    }

    @SuppressLint("MissingPermission")
    private fun connectSocket(device: BluetoothDevice): BluetoothSocket {
        var primaryError: Exception? = null
        try {
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
                return socket
            } catch (e: IOException) {
                primaryError = e
                runCatching { socket.close() }
            }
        } catch (e: SecurityException) {
            throw TransportException("Bluetooth connect permission is required for Classic SPP", e)
        } catch (e: IOException) {
            primaryError = e
        }

        return try {
            val insecure = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            try {
                insecure.connect()
                insecure
            } catch (e: IOException) {
                runCatching { insecure.close() }
                throw TransportException("Classic SPP connection failed: ${e.message ?: primaryError?.message}", e)
            }
        } catch (e: SecurityException) {
            throw TransportException("Bluetooth connect permission is required for Classic SPP", e)
        } catch (e: IOException) {
            throw TransportException("Classic SPP connection failed: ${e.message ?: primaryError?.message}", e)
        }
    }

    private class SppByteStream(private val socket: BluetoothSocket) : ByteStream {
        private val input = socket.inputStream
        private val output = socket.outputStream

        @Volatile
        private var closed = false

        override val incoming: Flow<ByteArray> = flow {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            try {
                while (currentCoroutineContext().isActive && !closed) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) emit(buffer.copyOf(count))
                }
            } catch (e: IOException) {
                if (!closed) throw TransportException("SPP link dropped: ${e.message}", e)
            } finally {
                closeQuietly()
            }
        }.flowOn(Dispatchers.IO)

        override suspend fun write(bytes: ByteArray) {
            if (closed) throw TransportException("SPP stream is closed")
            withContext(Dispatchers.IO) {
                try {
                    output.write(bytes)
                    output.flush()
                } catch (e: IOException) {
                    throw TransportException("SPP write failed: ${e.message}", e)
                }
            }
        }

        override suspend fun close() {
            closed = true
            withContext(Dispatchers.IO) { closeQuietly() }
        }

        private fun closeQuietly() {
            closed = true
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { socket.close() }
        }
    }

    public companion object {
        /** Standard Serial Port Profile (SPP) UUID. */
        public val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val READ_BUFFER_BYTES = 1024
    }
}

/** A paired Classic Bluetooth device that may be an SPP OBD-II adapter. */
public data class BondedSppDevice(val name: String, val address: String)

/** Lists paired Classic Bluetooth devices so the user can pick an ELM327 v2.1 SPP adapter without BLE advertisements. */
public object SppAdapterScanner {
    @SuppressLint("MissingPermission")
    public fun bondedDevices(context: Context): List<BondedSppDevice> {
        val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val bonded = try {
            adapter.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            return emptyList()
        }
        return bonded
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
            .map { BondedSppDevice(name = it.name?.takeIf(String::isNotBlank) ?: it.address, address = it.address) }
            .sortedBy { it.name.lowercase() }
    }
}
