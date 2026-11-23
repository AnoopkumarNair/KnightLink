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

    /** False simulates an ECU that does not understand a service 01 request for several PIDs: it stays silent on one, as some older ECUs do. */
    public var multiPid: Boolean = true

    /** True simulates an ECU that has stopped answering (it crashed, or lost power): it stays silent on everything. */
    public var silent: Boolean = false

    /** False simulates an ECU that stays silent on a data identifier it does not have, instead of refusing it with "request out of range". */
    public var refusesUnknownDids: Boolean = true

    /**
     * True for a body unit: the adapter's automatic flow control does not reach it, so a reply longer than one frame goes on only after the
     * phone has set `ATFCSH<request id>`, `ATFCSD30...` and `ATFCSM1`. Its replies are heard only when the receive filter passes [responseId] (the
     * ordinary 7E8-7EF filter does not pass 700-7DF).
     */
    public var needsCustomFlowControl: Boolean = false

    /**
     * True simulates a unit that refuses every service 22 ("service not supported in the active session", 7F) until it has been sent `10 01` (set
     * the reply of `1001` too), as some body units do after another tool left them in another session.
     */
    public var needsDefaultSession: Boolean = false
    private var sessionRequested = false

    private val refusals = HashMap<String, Int>()
    private val rawReplies = HashMap<String, ByteArray>()

    /**
     * @param request service + PID/DID as hex, e.g. `010C` or `22F190`
     * @param data the bytes that follow the echoed request in a positive reply, as hex
     */
    public fun set(request: String, data: String) {
        replies[request.filterNot { it.isWhitespace() }.uppercase()] = data.hexToBytes()
    }

    /** Makes [request] answer with exactly [payload] (hex, the whole message including the service byte), even one that does not match the request. */
    public fun setRaw(request: String, payload: String) {
        rawReplies[request.filterNot { it.isWhitespace() }.uppercase()] = payload.hexToBytes()
    }

    public fun remove(request: String) {
        replies.remove(request.filterNot { it.isWhitespace() }.uppercase())
        refusals.remove(request.filterNot { it.isWhitespace() }.uppercase())
    }

    /** Makes [request] (e.g. `221E12`) answer with the negative response [code] (`7F <service> <code>`), whatever it held before. */
    public fun refuse(request: String, code: Int) {
        refusals[request.filterNot { it.isWhitespace() }.uppercase()] = code
    }

    /** @return the reply payload (before ISO-TP framing), or null when this ECU stays silent. */
    public fun respond(request: ByteArray): ByteArray? {
        if (request.isEmpty() || silent) return null
        val service = request[0].toInt() and 0xFF
        val positive = byteArrayOf((service + POSITIVE_OFFSET).toByte()) + request.copyOfRange(1, request.size)
        if (request.toHex() == "1001" && replies.containsKey("1001")) sessionRequested = true
        if (service == 0x22 && needsDefaultSession && !sessionRequested) return byteArrayOf(NEGATIVE, service.toByte(), NRC_NOT_IN_SESSION)
        rawReplies[request.toHex()]?.let { return it }
        refusals[request.toHex()]?.let { return byteArrayOf(NEGATIVE, service.toByte(), it.toByte()) }
        replies[request.toHex()]?.let { return positive + it }

        // Several PIDs in one request: the ECU answers the ones it supports, in the order asked, and stays silent when it supports none.
        if (service == SERVICE_LIVE_DATA && request.size > 2) return if (!multiPid) null else answerMultiPid(request)?.let { byteArrayOf(positive[0]) + it }

        if ((service == SERVICE_LIVE_DATA || service == SERVICE_VEHICLE_INFO) && request.size == 2) {
            val pid = request[1].toInt() and 0xFF
            if (pid % SUPPORT_RANGE == 0) return supportBitmap(service, pid)?.let { positive + it }
        }
        return when (service) {
            // Emission-related services: an ECU that lacks the PID simply does not answer.
            SERVICE_LIVE_DATA, 0x02, SERVICE_VEHICLE_INFO, 0x03, 0x06, 0x07, 0x0A -> null
            0x22 -> if (refusesUnknownDids) byteArrayOf(NEGATIVE, service.toByte(), NRC_REQUEST_OUT_OF_RANGE) else null
            else -> byteArrayOf(NEGATIVE, service.toByte(), NRC_SERVICE_NOT_SUPPORTED)
        }
    }

    /** `<pid> <data> <pid> <data> ...` for the PIDs of a service 01 request that this ECU supports; null when it supports none. */
    private fun answerMultiPid(request: ByteArray): ByteArray? {
        var reply = ByteArray(0)
        for (pidByte in request.copyOfRange(1, request.size)) {
            val pid = pidByte.toInt() and 0xFF
            val data = replies[byteArrayOf(SERVICE_LIVE_DATA.toByte(), pidByte).toHex()]
                ?: if (pid % SUPPORT_RANGE == 0) supportBitmap(SERVICE_LIVE_DATA, pid) else null
            if (data != null) reply += byteArrayOf(pidByte) + data
        }
        return reply.takeIf { it.isNotEmpty() }
    }

    /**
     * "Supported PIDs" bitmap of [service] (01 or 09, which share one layout) for the range starting at [base],
     * derived from the stored replies so the two can never disagree.
     */
    private fun supportBitmap(service: Int, base: Int): ByteArray? {
        val prefix = byteArrayOf(service.toByte()).toHex()
        val pids = replies.keys
            .filter { it.length == 4 && it.startsWith(prefix) }
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
        const val SERVICE_VEHICLE_INFO = 0x09
        const val SUPPORT_RANGE = 0x20
        const val NEGATIVE = 0x7F.toByte()
        const val NRC_SERVICE_NOT_SUPPORTED = 0x11.toByte()
        const val NRC_REQUEST_OUT_OF_RANGE = 0x31.toByte()
        const val NRC_NOT_IN_SESSION = 0x7F.toByte()
    }
}

/**
 * A frame the car sends on its own, over and over, whether anyone asks or not (gear, torque, odometer ...).
 *
 * @property id the 11-bit CAN identifier
 * @property periodMs milliseconds between two frames
 * @property data the data bytes (0..8) of the n-th frame, counting from 0; may change over time
 */
public class VirtualBroadcast(public val id: Int, public val periodMs: Int, public val data: (Int) -> ByteArray) {
    init {
        require(id in 0..0x7FF) { "an 11-bit identifier is 0..7FF" }
        require(periodMs > 0) { "periodMs must be positive" }
    }
}

/** A simulated vehicle: a set of ECUs on one 11-bit CAN bus. */
public class VirtualVehicle(public val ecus: List<VirtualEcu>) {
    /** The frames the car broadcasts by itself; heard by an adapter that monitors the bus ([VirtualElm327] `ATMA`), never by a request. */
    public val broadcasts: MutableList<VirtualBroadcast> = ArrayList()

    /** Adds a broadcast: [id] every [periodMs] ms with the bytes [data] gives for the n-th frame. */
    public fun broadcast(id: Int, periodMs: Int, data: (Int) -> ByteArray) {
        broadcasts += VirtualBroadcast(id, periodMs, data)
    }

    /** False simulates a car with the ignition off: the adapter works, but no ECU answers anything. */
    public var awake: Boolean = true

    public fun ecu(requestId: Int): VirtualEcu =
        ecus.firstOrNull { it.requestId == requestId }
            ?: throw IllegalArgumentException("No simulated ECU at ${requestId.toString(16).uppercase()}")

    /** ECUs addressed by [header]: all of them for the functional address, otherwise the matching one. */
    internal fun targets(header: Int): List<VirtualEcu> =
        if (!awake) {
            emptyList()
        } else if (header == FUNCTIONAL_ADDRESS) {
            ecus
        } else {
            ecus.filter { it.requestId == header }
        }

    public companion object {
        public const val FUNCTIONAL_ADDRESS: Int = 0x7DF
    }
}
