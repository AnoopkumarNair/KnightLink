package knightlink.core.transport

import kotlinx.coroutines.flow.Flow

/**
 * An open, bidirectional byte pipe to an adapter. Chunk boundaries in [incoming] carry no meaning:
 * BLE notifications, serial reads and TCP packets split data arbitrarily, so consumers must reframe.
 */
public interface ByteStream {
    /** Bytes received from the adapter. Completes when the link closes; fails if the link drops. */
    public val incoming: Flow<ByteArray>

    public suspend fun write(bytes: ByteArray)

    public suspend fun close()
}

/** A way of reaching an adapter. One implementation per link type (BLE, classic Bluetooth, TCP, simulator). */
public interface Transport {
    public val kind: TransportKind

    /** Opens the link. Throws [TransportException] if it cannot be established. */
    public suspend fun open(): ByteStream
}

public enum class TransportKind { BLE, BLUETOOTH_CLASSIC, TCP, SIMULATED }

public open class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
