package knightlink.core.vehicle

import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import kotlin.time.Duration

// Helpers shared by the probe and the report types. Everything here is internal: the public surface is
// the probe, the report and the coverage comparison.

/** SAE J1979 / ISO 15031-5: each "supported PIDs" request covers this many PIDs. */
internal const val PIDS_PER_RANGE = 0x20

internal const val SERVICE_LIVE_DATA = 0x01
internal const val SERVICE_VEHICLE_INFO = 0x09

internal fun Int.toPidHex(): String = (this and 0xFF).toString(16).uppercase().padStart(2, '0')

internal fun Int.toCanIdHex(): String = toString(16).uppercase().padStart(3, '0')

/**
 * One decoded "supported PIDs" reply.
 *
 * @property pids the PIDs reported as supported, never including the range PIDs (00, 20, 40, ...) themselves.
 * @property hasNext true when the last bit says the ECU also answers the next range request.
 */
internal class SupportRange(val pids: Set<Int>, val hasNext: Boolean)

/**
 * Decodes the 4-byte bitmap of the range starting at [base]. The most significant bit of the first byte is
 * PID `base + 1`; the least significant bit of the last byte is the "next range" flag.
 */
internal fun decodeSupportRange(base: Int, bitmap: ByteArray): SupportRange {
    require(bitmap.size >= BITMAP_BYTES) { "A support bitmap has $BITMAP_BYTES bytes, got ${bitmap.size}" }
    val pids = LinkedHashSet<Int>()
    for (offset in 1 until PIDS_PER_RANGE) {
        if (bitmap.bit(offset)) pids += base + offset
    }
    return SupportRange(pids, hasNext = bitmap.bit(PIDS_PER_RANGE))
}

private const val BITMAP_BYTES = 4

/** Bit for the PID at 1-based [offset] within a range. */
private fun ByteArray.bit(offset: Int): Boolean {
    val index = offset - 1
    return (this[index / 8].toInt() shr (7 - index % 8)) and 1 == 1
}

/**
 * The positive reply to `service pid`, or null when the response holds none (silence, `NO DATA`, a negative
 * reply, or only answers to something else). With [from] set, replies from other ECUs are ignored.
 */
internal fun ElmResponse.positiveReply(service: Int, pid: Int, from: Int? = null): DiagnosticResponse.Positive? =
    messages.filterIsInstance<DiagnosticResponse.Positive>().firstOrNull { reply ->
        reply.service == service &&
            reply.data.isNotEmpty() &&
            (reply.data[0].toInt() and 0xFF) == pid &&
            (from == null || reply.sourceId == from)
    }

/** The 4 bitmap bytes of a support reply (the data after the PID byte), or null when the reply is too short. */
internal fun DiagnosticResponse.Positive.bitmap(): ByteArray? = if (data.size >= 1 + 4) data.copyOfRange(1, 5) else null

/**
 * Printable ASCII of [bytes] from [from] on, trimmed. Padding NULs and any other unprintable byte are dropped,
 * so a NUL-padded field comes out clean. Null when nothing is left.
 */
internal fun printableText(bytes: ByteArray, from: Int, to: Int = bytes.size): String? {
    val text = buildString {
        for (i in from until minOf(to, bytes.size)) {
            val code = bytes[i].toInt() and 0xFF
            if (code in PRINTABLE) append(code.toChar())
        }
    }.trim()
    return text.ifEmpty { null }
}

private val PRINTABLE = 0x20..0x7E

/** Median of [samples]; the mean of the two middle values when the count is even. */
internal fun median(samples: List<Duration>): Duration {
    require(samples.isNotEmpty()) { "No samples" }
    val sorted = samples.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}
