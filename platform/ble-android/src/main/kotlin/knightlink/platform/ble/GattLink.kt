package knightlink.platform.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import knightlink.core.transport.ByteStream
import knightlink.core.transport.TransportException
import knightlink.core.transport.ble.BleSerialProfile
import knightlink.core.transport.ble.BluetoothUuid
import knightlink.core.transport.ble.DEFAULT_ATT_MTU
import knightlink.core.transport.ble.GattCharacteristic
import knightlink.core.transport.ble.GattProfileResolver
import knightlink.core.transport.ble.GattService
import knightlink.core.transport.ble.GattSubscription
import knightlink.core.transport.ble.GattWriteType
import knightlink.core.transport.ble.Resolution
import knightlink.core.transport.ble.chunkForWrite
import knightlink.core.transport.ble.payloadForMtu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * One GATT connection, from `connectGatt` to the release of the Android client. It is the [ByteStream] handed to the caller.
 *
 * Threading: Android delivers every [BluetoothGattCallback] on a binder thread. The callbacks here only do constant-time,
 * non-blocking work on thread-safe structures: complete a deferred in the [OperationSlot], push a copied byte array
 * into a [Channel], flip an atomic flag. All waiting, timeouts and sequencing happen in coroutines. There is
 * deliberately no Handler thread: moving callbacks to one would let the pre-Android-13 API overwrite a
 * characteristic's shared value array before it is copied.
 *
 * Release: the Android client is released ([release]) exactly once, on every path that ends the link. [finish] is the
 * single place that ends it: peer disconnect, error status, timeout, failed write, `close()` and `abort()` all go
 * through it.
 */
// The BLUETOOTH_CONNECT permission is verified by BleTransport.open() before a GattLink exists. If it is revoked later,
// the framework throws SecurityException; every framework call below that can do so is guarded (see guard and submit).
@SuppressLint("MissingPermission")
internal class GattLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val profile: BleSerialProfile,
    private val config: BleTransport.Config,
) : ByteStream {
    private class Route(
        val characteristic: BluetoothGattCharacteristic,
        val androidWriteType: Int,
        /** True for write with response: each chunk is complete only when `onCharacteristicWrite` says so. */
        val acknowledged: Boolean,
    )

    private sealed interface Outcome {
        data class Done(
            val status: Int,
            val value: Int,
        ) : Outcome

        data object TimedOut : Outcome

        data object NotStarted : Outcome
    }

    private val slot = OperationSlot()
    private val writeLock = Mutex()
    private val retrier = WriteRetrier(maxRejections = 1)
    private val received = Channel<ByteArray>(RECEIVE_BUFFER_CHUNKS)
    private val gattRef = AtomicReference<BluetoothGatt?>(null)
    private val released = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private val connected = CompletableDeferred<Unit>()
    private val disconnected = CompletableDeferred<Unit>()

    @Volatile private var ready = false

    @Volatile private var closing = false

    @Volatile private var failure = TransportException("The Bluetooth link is closed.")

    @Volatile private var payloadBytes = payloadForMtu(DEFAULT_ATT_MTU)

    @Volatile private var route: Route? = null

    @Volatile private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * Notifications as they arrived, in order. Backed by a channel, so it has a single consumer: collect it once.
     * Completes normally when the peer disconnects cleanly or the link is closed; fails with [TransportException] on an error.
     */
    override val incoming: Flow<ByteArray> = received.receiveAsFlow()

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                gattRef.compareAndSet(null, gatt)
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> {
                        disconnected.complete(Unit)
                        val what = if (ready) "The Bluetooth connection to the adapter was lost" else "Could not connect to the adapter"
                        finish(TransportException("$what: ${GattStatus.describe(status)}"))
                    }
                    newState == BluetoothProfile.STATE_CONNECTED -> connected.complete(Unit)
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        disconnected.complete(Unit)
                        // A disconnect with a success status is a clean one (we asked for it, or the peer closed politely).
                        finish(null)
                    }
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                slot.complete(OpKind.MTU, status, mtu)
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                slot.complete(OpKind.DISCOVER, status)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                slot.complete(OpKind.DESCRIPTOR, status)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                slot.complete(OpKind.WRITE, status)
            }

            // Android 13+ delivers the value as an argument; this is the only overload it calls.
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                deliver(characteristic, value)
            }

            // Before Android 13 the value lives in the characteristic and the next notification overwrites it, so it
            // has to be copied right here on the callback thread.
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            @Deprecated("Called on Android 12 and older only; the overload with a value parameter replaces it.")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                deliver(characteristic, characteristic.value)
            }
        }

    /** Brings the link up to the point where bytes can flow. Throws [TransportException]; the caller must [abort] on any failure. */
    suspend fun connect() {
        val gatt = startConnection()
        awaitConnected()
        delay(config.settleAfterConnect)
        ensureOpen()
        payloadBytes = negotiatePayloadSize(gatt)
        val found = resolveSerialChannel(gatt)
        enableNotifications(gatt, found.first, found.second)
        ready = true
    }

    private fun startConnection(): BluetoothGatt {
        val gatt =
            guard { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }
                ?: throw TransportException("Android could not start a Bluetooth connection. Try turning Bluetooth off and on.")
        gattRef.compareAndSet(null, gatt)
        // If the link already failed while connectGatt was still returning, there was nothing to release yet; do it now.
        if (released.get()) guard { gatt.close() }
        return gatt
    }

    private suspend fun awaitConnected() {
        withTimeoutOrNull(config.connectTimeout) { connected.await() }
            ?: throw TransportException(
                "Timed out after ${config.connectTimeout} waiting for the adapter. " +
                    "Check that it is plugged in, in range, and not connected to another phone.",
            )
    }

    /** Asks for a bigger MTU; whatever comes back (or the default if nothing does) decides how large each write may be. */
    private suspend fun negotiatePayloadSize(gatt: BluetoothGatt): Int {
        val mtu = config.requestedMtu ?: return payloadForMtu(DEFAULT_ATT_MTU)
        val outcome = runOperation(OpKind.MTU, config.mtuTimeout) { gatt.requestMtu(mtu) }
        val granted = (outcome as? Outcome.Done)?.takeIf { it.status == BluetoothGatt.GATT_SUCCESS && it.value >= DEFAULT_ATT_MTU }?.value
        return payloadForMtu(granted ?: DEFAULT_ATT_MTU)
    }

    private suspend fun resolveSerialChannel(gatt: BluetoothGatt): Pair<BluetoothGattCharacteristic, GattSubscription> {
        val discovery = runOperation(OpKind.DISCOVER, config.discoveryTimeout) { gatt.discoverServices() }
        requireDone(discovery, "listing the adapter's services")

        val androidServices = gatt.services.toList()
        val mapped = androidServices.map { service -> GattService(service.uuid.toString(), service.characteristics.map(::describe)) }
        val found =
            when (val resolution = GattProfileResolver.resolve(mapped, profile)) {
                is Resolution.Found -> resolution
                is Resolution.NotFound -> throw TransportException(resolution.reason)
            }
        // The resolver hands back the very instances it was given, so identity finds the matching Android objects.
        val service = androidServices[mapped.indexOfFirst { it === found.service }]
        val write = service.characteristics[found.service.characteristics.indexOfFirst { it === found.write }]
        val notify = service.characteristics[found.service.characteristics.indexOfFirst { it === found.notify }]

        route =
            when (found.writeType) {
                GattWriteType.WITHOUT_RESPONSE -> Route(write, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, acknowledged = false)
                GattWriteType.WITH_RESPONSE -> Route(write, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, acknowledged = true)
            }
        notifyCharacteristic = notify
        return notify to checkNotNull(found.notify.subscription) { "the resolver returned a characteristic that cannot be subscribed to" }
    }

    private fun describe(characteristic: BluetoothGattCharacteristic): GattCharacteristic {
        val properties = characteristic.properties
        return GattCharacteristic(
            uuid = characteristic.uuid.toString(),
            canWrite = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
            canWriteWithoutResponse = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
            canNotify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
            canIndicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
        )
    }

    private suspend fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        subscription: GattSubscription,
    ) {
        if (!guard { gatt.setCharacteristicNotification(characteristic, true) }) {
            throw TransportException("Android could not enable notifications for the adapter's data channel.")
        }
        // Setting the flag above only tells Android to deliver notifications; the peripheral sends them once its
        // client characteristic configuration descriptor has been written.
        val descriptor =
            characteristic.getDescriptor(UUID.fromString(BluetoothUuid.fromShort(CLIENT_CONFIG_DESCRIPTOR)))
                ?: throw TransportException("The adapter's data channel has no switch for notifications, so it cannot be used.")
        val outcome = runOperation(OpKind.DESCRIPTOR, config.descriptorTimeout) { writeDescriptor(gatt, descriptor, subscription) }
        requireDone(outcome, "turning on notifications")
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        subscription: GattSubscription,
    ): Boolean {
        val value = if (subscription == GattSubscription.NOTIFY) byteArrayOf(1, 0) else byteArrayOf(2, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    /**
     * Starts one callback-completed operation and waits for its callback. The ticket is claimed before the operation is
     * started, because the callback can arrive before the start call has returned. A link failure throws; a timeout, or
     * the stack refusing to start the operation, is reported to the caller to decide on.
     */
    private suspend fun runOperation(
        kind: OpKind,
        timeout: Duration,
        start: () -> Boolean,
    ): Outcome {
        val ticket = slot.begin(kind)
        try {
            if (!guard(start)) return Outcome.NotStarted
            val result = withTimeoutOrNull(timeout) { ticket.await() } ?: return Outcome.TimedOut
            return Outcome.Done(result.status, result.value)
        } finally {
            slot.end(ticket)
        }
    }

    private fun requireDone(outcome: Outcome, what: String) {
        when (outcome) {
            is Outcome.Done -> {
                val status = outcome.status
                if (status != BluetoothGatt.GATT_SUCCESS) throw TransportException("Problem $what: ${GattStatus.describe(status)}")
            }
            Outcome.TimedOut -> throw TransportException("The adapter did not answer in time while $what.")
            Outcome.NotStarted -> throw TransportException("Android refused to start $what. Try turning Bluetooth off and on.")
        }
    }

    /** One write is serialised against all others; its chunks go out in order, one GATT operation in flight at a time. */
    override suspend fun write(bytes: ByteArray) {
        val chunks = chunkForWrite(bytes, payloadBytes)
        if (chunks.isEmpty()) return
        writeLock.withLock {
            ensureOpen()
            val gatt = checkNotNull(gattRef.get()) { "write before connect" }
            val route = checkNotNull(route) { "write before the serial channel was resolved" }
            var handedOver = false
            try {
                for (chunk in chunks) {
                    ensureOpen()
                    withTimeoutOrNull(config.writeTimeout) {
                        retrier.run { attempt(gatt, route, chunk) { handedOver = true } }
                    } ?: throw TransportException("Writing to the adapter timed out after ${config.writeTimeout}; the link is assumed lost.")
                }
            } catch (e: TransportException) {
                // The Android status code is in the message; the queue state says whether something else was in flight (a leaked operation).
                Log.w(
                    TAG,
                    "Write failed: ${e.message}; operation slot ${slot.describeState()}, ${chunks.size} chunk(s) of ${bytes.size} bytes, handed over: $handedOver",
                )
                // A failed or timed-out write leaves the stack's single operation slot in an unknown state and a
                // command half-sent, so the link is abandoned; the session above reconnects.
                finish(e)
                throw e
            } catch (e: CancellationException) {
                // The same holds if the writer is cancelled after bytes were handed over (or are awaiting their acknowledgement).
                if (handedOver) finish(TransportException("Writing to the adapter was interrupted, so the link was abandoned."))
                throw e
            }
        }
    }

    private suspend fun attempt(
        gatt: BluetoothGatt,
        route: Route,
        chunk: ByteArray,
        onHandedOver: () -> Unit,
    ): WriteAttempt {
        if (!route.acknowledged) {
            // Write without response: no callback to wait for. A busy stack is the back-pressure signal.
            return submit(gatt, route, chunk).also { if (it == WriteAttempt.Accepted) onHandedOver() }
        }
        val ticket = slot.begin(OpKind.WRITE)
        try {
            val submitted = submit(gatt, route, chunk)
            if (submitted != WriteAttempt.Accepted) return submitted
            onHandedOver()
            val result = ticket.await()
            return if (result.status == BluetoothGatt.GATT_SUCCESS) {
                WriteAttempt.Accepted
            } else {
                WriteAttempt.Rejected(GattStatus.describe(result.status))
            }
        } finally {
            slot.end(ticket)
        }
    }

    @Suppress("DEPRECATION")
    private fun submit(
        gatt: BluetoothGatt,
        route: Route,
        chunk: ByteArray,
    ): WriteAttempt =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                when (val code = gatt.writeCharacteristic(route.characteristic, chunk, route.androidWriteType)) {
                    BluetoothStatusCodes.SUCCESS -> WriteAttempt.Accepted
                    BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> WriteAttempt.Busy
                    else -> WriteAttempt.Rejected("Android error code $code")
                }
            } else {
                // The old API reports only true or false; false most often means "busy", and a stack that is really
                // broken ends in the retry limit or the write timeout.
                route.characteristic.writeType = route.androidWriteType
                route.characteristic.value = chunk
                if (gatt.writeCharacteristic(route.characteristic)) WriteAttempt.Accepted else WriteAttempt.Busy
            }
        } catch (e: SecurityException) {
            WriteAttempt.Rejected("the Bluetooth permission was withdrawn")
        } catch (e: RuntimeException) {
            // For example DeadObjectException when the Bluetooth service restarted.
            WriteAttempt.Rejected(e.message ?: e::class.simpleName ?: "unknown error")
        }

    private fun ensureOpen() {
        if (closing || ended.get()) throw if (ended.get()) failure else TransportException("The Bluetooth link is closed.")
    }

    /**
     * Disconnects politely: asks Android to disconnect, gives the peripheral a moment to confirm, then releases the
     * client regardless. Idempotent, and safe to call after the link was lost.
     */
    override suspend fun close() {
        closing = true
        // Teardown must not be cut short by the caller's own cancellation, or the client would leak.
        withContext(NonCancellable) {
            val gatt = gattRef.get()
            if (!ended.get() && gatt != null) {
                try {
                    gatt.disconnect()
                } catch (e: Exception) {
                    // The release below covers a stack that will not cooperate.
                }
                withTimeoutOrNull(config.disconnectGrace) { disconnected.await() }
            }
            finish(null)
        }
    }

    /** Abandons a connection attempt: fails whatever waits on it and releases the client immediately. Never blocks. */
    fun abort() {
        finish(TransportException("The connection attempt was abandoned."))
    }

    private fun deliver(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
    ) {
        val expected = notifyCharacteristic
        if (value == null || value.isEmpty() || expected == null) return
        if (characteristic.uuid != expected.uuid || characteristic.instanceId != expected.instanceId) return
        val sent = received.trySend(value.copyOf())
        if (sent.isFailure && !sent.isClosed) {
            // Dropping bytes would corrupt the framing of every later reply, so an overflowing consumer ends the link instead.
            finish(TransportException("Data from the adapter arrived faster than it was read."))
        }
    }

    /**
     * Ends the link, once. [cause] null means a clean end. Callable from any thread and never blocks: it completes the
     * incoming channel, fails any operation in flight, and releases the Android client.
     */
    private fun finish(cause: TransportException?) {
        if (!ended.compareAndSet(false, true)) return
        val error = cause ?: if (ready) null else TransportException("The adapter closed the connection before it was ready.")
        failure = error ?: TransportException("The Bluetooth link is closed.")
        if (error != null) connected.completeExceptionally(error)
        received.close(error)
        slot.fail(failure)
        disconnected.complete(Unit)
        release()
    }

    /** Disconnects and closes the Android client. The first call does it; later calls do nothing. */
    private fun release() {
        if (!released.compareAndSet(false, true)) return
        val gatt = gattRef.get() ?: return
        try {
            gatt.disconnect()
        } catch (e: Exception) {
            // Already disconnected, or Bluetooth is gone; close() below is what frees the client slot.
        }
        try {
            gatt.close()
        } catch (e: Exception) {
            // Nothing more can be done for a client that cannot be closed.
        }
    }

    /** Runs a framework call, turning a revoked permission into a [TransportException]. */
    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: SecurityException) {
            throw TransportException("The Bluetooth permission was withdrawn.", e)
        }

    private companion object {
        const val TAG = "GattLink"

        /** The 16-bit UUID of the client characteristic configuration descriptor (Bluetooth assigned number). */
        const val CLIENT_CONFIG_DESCRIPTOR = 0x2902

        /** Notification chunks buffered for the reader; a few kilobytes of ELM traffic at most, so this is generous. */
        const val RECEIVE_BUFFER_CHUNKS = 1024
    }
}
