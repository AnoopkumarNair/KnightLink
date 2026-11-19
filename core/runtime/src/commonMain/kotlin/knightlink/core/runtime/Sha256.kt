package knightlink.core.runtime

import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.sqrt

/** SHA-256 for the vehicle key (common code has no digest of its own). Not for secrets: it only keeps a VIN out of storage. */
internal object Sha256 {
    private val PRIMES: List<Int> = generateSequence(2) { it + 1 }.filter { n -> (2 until n).none { n % it == 0 } }.take(64).toList()

    /** The first 32 bits of the fractional part of a root: how the standard defines its constants. */
    private fun fraction(x: Double): Int = ((x - floor(x)) * 4294967296.0).toLong().toInt()

    private val K: IntArray = IntArray(64) { fraction(cbrt(PRIMES[it].toDouble())) }
    private val H0: IntArray = IntArray(8) { fraction(sqrt(PRIMES[it].toDouble())) }

    fun hex(text: String): String {
        val message = text.encodeToByteArray()
        val padded = message.copyOf(((message.size + 8) / 64 + 1) * 64)
        padded[message.size] = 0x80.toByte()
        val bits = message.size.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
        val h = H0.copyOf()
        val w = IntArray(64)
        for (block in padded.indices step 64) {
            for (i in 0 until 16) {
                w[i] = ((padded[block + 4 * i].toInt() and 0xFF) shl 24) or ((padded[block + 4 * i + 1].toInt() and 0xFF) shl 16) or
                    ((padded[block + 4 * i + 2].toInt() and 0xFF) shl 8) or (padded[block + 4 * i + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotr(7) xor w[i - 15].rotr(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotr(17) xor w[i - 2].rotr(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]
            for (i in 0 until 64) {
                val t1 = hh + (e.rotr(6) xor e.rotr(11) xor e.rotr(25)) + ((e and f) xor (e.inv() and g)) + K[i] + w[i]
                val t2 = (a.rotr(2) xor a.rotr(13) xor a.rotr(22)) + ((a and b) xor (a and c) xor (b and c))
                hh = g
                g = f
                f = e
                e = d + t1
                d = c
                c = b
                b = a
                a = t1 + t2
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
        }
        return h.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
    }

    private fun Int.rotr(n: Int): Int = (this ushr n) or (this shl (32 - n))
}
