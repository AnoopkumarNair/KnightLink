package knightlink.core.common

/** Parses a hex string (whitespace allowed) into bytes. Throws [IllegalArgumentException] on bad input. */
public fun String.hexToBytes(): ByteArray {
    val clean = filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0) { "Hex string has an odd number of digits: '$this'" }
    return ByteArray(clean.length / 2) { i ->
        val hi = clean[2 * i].digitToIntOrNull(16)
        val lo = clean[2 * i + 1].digitToIntOrNull(16)
        require(hi != null && lo != null) { "Not a hex string: '$this'" }
        ((hi shl 4) or lo).toByte()
    }
}

/** Upper-case hex without separators. */
public fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) {
        val v = b.toInt() and 0xFF
        append(HEX_DIGITS[v ushr 4])
        append(HEX_DIGITS[v and 0x0F])
    }
}

public fun Char.isHexDigit(): Boolean = digitToIntOrNull(16) != null

private const val HEX_DIGITS = "0123456789ABCDEF"
