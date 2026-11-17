package knightlink.core.transport.ble

/** The ATT MTU every BLE link starts with; the usable payload is [ATT_HEADER_BYTES] less. */
public const val DEFAULT_ATT_MTU: Int = 23

/** Opcode plus handle: bytes of every ATT write that are not payload. */
public const val ATT_HEADER_BYTES: Int = 3

/** The longest value a GATT attribute can hold, whatever MTU was negotiated. */
public const val MAX_ATT_VALUE_BYTES: Int = 512

/** Payload bytes that fit in one write on a link with the given [mtu] (20 for the default 23). */
public fun payloadForMtu(mtu: Int): Int {
    require(mtu >= DEFAULT_ATT_MTU) { "An ATT MTU is at least $DEFAULT_ATT_MTU, was $mtu" }
    return minOf(mtu - ATT_HEADER_BYTES, MAX_ATT_VALUE_BYTES)
}

/**
 * Splits [bytes] into consecutive pieces of at most [maxPayload] bytes, in order, each a fresh copy. Empty input
 * gives no pieces (nothing to send). [maxPayload] defaults to what the default MTU allows.
 */
public fun chunkForWrite(
    bytes: ByteArray,
    maxPayload: Int = DEFAULT_ATT_MTU - ATT_HEADER_BYTES,
): List<ByteArray> {
    require(maxPayload in 1..MAX_ATT_VALUE_BYTES) { "maxPayload must be in 1..$MAX_ATT_VALUE_BYTES, was $maxPayload" }
    return (bytes.indices step maxPayload).map { start -> bytes.copyOfRange(start, minOf(start + maxPayload, bytes.size)) }
}
