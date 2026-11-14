package knightlink.core.sim

import knightlink.core.common.hexToBytes
import knightlink.core.common.toHex

/**
 * A simulated ECU. It stores raw reply data per request, exactly as bytes on the wire, so the
 * simulator shares no decoding logic with the code under test.
 *
 * Not thread-safe: use it from one dispatcher.
 */
public class VirtualEcu(public val requestId: Int, public val responseId: Int) {
    private val replies = HashMap<String, ByteArray>()

    /**
     * @param request service + PID/DID as hex, e.g. `010C` or `22F190`
     * @param data the bytes that follow the echoed request in a positive reply, as hex
     */
    public fun set(request: String, data: String) {
        replies[request.filterNot { it.isWhitespace() }.uppercase()] = data.hexToBytes()
    }

    public fun remove(request: String) {
        replies.remove(request.filterNot { it.isWhitespace() }.uppercase())
    }

    /** @return the reply payload (before ISO-TP framing), or null when this ECU stays silent. */
    public fun respond(request: ByteArray): ByteArray? {
        if (request.isEmpty()) return null
        val service = request[0].toInt() and 0xFF
        val positive = byteArrayOf((service + POSITIVE_OFFSET).toByte()) + request.copyOfRange(1, request.size)
        replies[request.toHex()]?.let { return positive + it }

        if (service == SERVICE_LIVE_DATA && request.size == 2) {
            val pid = request[1].toInt() and 0xFF
            if (pid % SUPPORT_RANGE == 0) return supportBitmap(pid)?.let { positive + it }
        }
        return when (service) {
            // Emission-related services: an ECU that lacks the PID simply does not answer.
            SERVICE_LIVE_DATA, 0x02, 0x09 -> null
            0x22 -> byteArrayOf(NEGATIVE, service.toByte(), NRC_REQUEST_OUT_OF_RANGE)
            else -> byteArrayOf(NEGATIVE, service.toByte(), NRC_SERVICE_NOT_SUPPORTED)
        }
    }

    /** Service 01 "supported PIDs" bitmap for the range starting at [base], derived from the stored replies. */
    private fun supportBitmap(base: Int): ByteArray? {
        val pids = replies.keys
            .filter { it.length == 4 && it.startsWith("01") }
            .map { it.substring(2).toInt(16) }
        var bits = 0L
        for (offset in 1..SUPPORT_RANGE) {
            val pid = base + offset
            val supported = if (offset == SUPPORT_RANGE) pids.any { it > pid } else pid in pids
            if (supported) bits = bits or (1L shl (SUPPORT_RANGE - offset))
        }
        if (bits == 0L) return null
        return ByteArray(4) { i -> (bits shr (8 * (3 - i))).toByte() }
    }

    private companion object {
        const val POSITIVE_OFFSET = 0x40
        const val SERVICE_LIVE_DATA = 0x01
        const val SUPPORT_RANGE = 0x20
        const val NEGATIVE = 0x7F.toByte()
        const val NRC_SERVICE_NOT_SUPPORTED = 0x11.toByte()
        const val NRC_REQUEST_OUT_OF_RANGE = 0x31.toByte()
    }
}

/** A simulated vehicle: a set of ECUs on one 11-bit CAN bus. */
public class VirtualVehicle(public val ecus: List<VirtualEcu>) {
    public fun ecu(requestId: Int): VirtualEcu =
        ecus.firstOrNull { it.requestId == requestId }
            ?: throw IllegalArgumentException("No simulated ECU at ${requestId.toString(16).uppercase()}")

    /** ECUs addressed by [header]: all of them for the functional address, otherwise the matching one. */
    internal fun targets(header: Int): List<VirtualEcu> =
        if (header == FUNCTIONAL_ADDRESS) ecus else ecus.filter { it.requestId == header }

    public companion object {
        public const val FUNCTIONAL_ADDRESS: Int = 0x7DF
    }
}
