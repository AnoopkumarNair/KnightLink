package knightlink.core.vehicle

/**
 * The OBD protocols an ELM327-style adapter reports with `ATDPN` (the digit, with an `A` in front when the adapter chose it by itself).
 *
 * The app can read a car over [CAN_11_500] and [CAN_11_250] only: its request path addresses 11-bit identifiers. The others are detected,
 * recorded and named, and the session ends in "cannot read this car yet" instead of guessing.
 */
public enum class ObdProtocol(
    /** The character `ATSP` and `ATDPN` use. */
    public val code: Char,
    /** Plain name, as the adapter's `ATDP` prints it, without the "auto" prefix. */
    public val title: String,
    /** Whether the bus is CAN (the legacy protocols 1 to 5 are not). */
    public val isCan: Boolean,
    /** Whether the CAN identifiers have 11 bits; false for 29-bit CAN and for the legacy protocols. */
    public val has11BitIds: Boolean,
) {
    J1850_PWM('1', "SAE J1850 PWM (41.6 kbaud)", isCan = false, has11BitIds = false),
    J1850_VPW('2', "SAE J1850 VPW (10.4 kbaud)", isCan = false, has11BitIds = false),
    ISO_9141_2('3', "ISO 9141-2 (5 baud init)", isCan = false, has11BitIds = false),
    KWP_SLOW('4', "ISO 14230-4 KWP (5 baud init)", isCan = false, has11BitIds = false),
    KWP_FAST('5', "ISO 14230-4 KWP (fast init)", isCan = false, has11BitIds = false),
    CAN_11_500('6', "ISO 15765-4 CAN (11 bit ID, 500 kbaud)", isCan = true, has11BitIds = true),
    CAN_29_500('7', "ISO 15765-4 CAN (29 bit ID, 500 kbaud)", isCan = true, has11BitIds = false),
    CAN_11_250('8', "ISO 15765-4 CAN (11 bit ID, 250 kbaud)", isCan = true, has11BitIds = true),
    CAN_29_250('9', "ISO 15765-4 CAN (29 bit ID, 250 kbaud)", isCan = true, has11BitIds = false),
    J1939('A', "SAE J1939 CAN (29 bit ID, 250 kbaud)", isCan = true, has11BitIds = false),
    USER1_CAN('B', "USER1 CAN (11 bit ID, 125 kbaud)", isCan = true, has11BitIds = true),
    USER2_CAN('C', "USER2 CAN (11 bit ID, 50 kbaud)", isCan = true, has11BitIds = true),
    ;

    /** Whether the app's request path can talk over this protocol (11-bit and 29-bit ISO 15765-4 CAN). */
    public val isReadable: Boolean get() = this == CAN_11_500 || this == CAN_11_250 || this == CAN_29_500 || this == CAN_29_250

    /** The CAN addressing scheme for this protocol. */
    public val addressing: knightlink.core.protocol.BusAddressing
        get() = if (has11BitIds) knightlink.core.protocol.BusAddressing.Can11Bit else knightlink.core.protocol.BusAddressing.Can29Bit

    /** The command that selects this protocol and nothing else. */
    public val selectCommand: String get() = "ATSP$code"

    public companion object {
        /** The order explicit protocols are tried in when the adapter's own search finds nothing: 11-bit CAN first, then 29-bit, then the legacy ones. */
        public val EXPLICIT_ORDER: List<ObdProtocol> = listOf(
            CAN_11_500,
            CAN_11_250,
            CAN_29_500,
            CAN_29_250,
            KWP_FAST,
            KWP_SLOW,
            ISO_9141_2,
            J1850_VPW,
            J1850_PWM,
        )

        /** The protocol an `ATDPN` reply names (`A6` for "chosen automatically: 6", or just `6`), or null when it names none (`0`, `?`, text). */
        public fun fromDpn(reply: String?): ObdProtocol? {
            val text = reply?.filterNot { it.isWhitespace() }?.uppercase().orEmpty()
            // "A" alone is J1939, but "A6" is automatic 6; a bare "A" followed by nothing is the protocol itself.
            val digit = if (text.length >= 2 && text[0] == 'A') text[1] else text.firstOrNull()
            return entries.firstOrNull { it.code == digit }.takeIf { text.length in 1..2 }
        }

        /** The protocol with this code character, or null. */
        public fun fromCode(code: Char?): ObdProtocol? = entries.firstOrNull { it.code == code?.uppercaseChar() }
    }
}
