package knightlink.core.transport.ble

/**
 * UUID handling for GATT. Bluetooth lets a 16-bit (or 32-bit) assigned number stand in for a full UUID built on the
 * Bluetooth base UUID, and platform APIs print the same UUID in either form and in either letter case. Comparing
 * the raw strings would therefore miss matches; everything here compares the normalised form instead.
 */
public object BluetoothUuid {
    // Only the tail of the base UUID is stored: a short form is the first block, the rest is always this.
    private const val BASE_TAIL = "-0000-1000-8000-00805f9b34fb"
    private const val SHORT_16_LENGTH = 4
    private const val SHORT_32_LENGTH = 8
    private const val MAX_16_BIT = 0xFFFF

    /**
     * Returns [uuid] lower-cased, without braces or a `0x` prefix, with 16-bit and 32-bit forms expanded to the
     * full 128-bit form. Input that is not a recognisable short form is only lower-cased, so it never matches
     * anything by accident.
     */
    public fun normalize(uuid: String): String {
        val text = uuid.trim().removePrefix("{").removeSuffix("}").lowercase().removePrefix("0x")
        val isShortHex = (text.length == SHORT_16_LENGTH || text.length == SHORT_32_LENGTH) && text.all { it in '0'..'9' || it in 'a'..'f' }
        return when {
            !isShortHex -> text
            text.length == SHORT_16_LENGTH -> "0000$text$BASE_TAIL"
            else -> "$text$BASE_TAIL"
        }
    }

    /** True when [a] and [b] denote the same UUID, whatever their case or short/long form. */
    public fun matches(
        a: String,
        b: String,
    ): Boolean = normalize(a) == normalize(b)

    /** The full UUID for the 16-bit assigned number [value] (for example the client characteristic configuration descriptor). */
    public fun fromShort(value: Int): String {
        require(value in 0..MAX_16_BIT) { "A 16-bit UUID must be in 0..0xFFFF, was $value" }
        return normalize(value.toString(16).padStart(SHORT_16_LENGTH, '0'))
    }
}
