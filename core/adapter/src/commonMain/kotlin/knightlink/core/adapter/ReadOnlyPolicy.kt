package knightlink.core.adapter

/**
 * The one gate every command passes before it is written to the adapter (see [Elm327Driver]). KnightLink is read-only
 * towards the vehicle (ADR 0005): this allows only diagnostic requests that read, and only adapter commands that change the
 * adapter's own volatile settings or ask it something. Anything else is refused before a byte leaves the phone, whoever asks.
 *
 * Diagnostic requests (hex): the first byte is the service.
 * - Allowed: OBD services 01 (live data), 02 (freeze frame), 03 (stored codes), 06 (test results), 07 (pending codes),
 *   09 (vehicle information), 0A (permanent codes); UDS 22 (read data by identifier) and 19 (read DTC information) with a
 *   reporting sub-function only.
 * - Refused, among others: 04 (clear codes), 08 (control of on-board systems), UDS 10 (session control), 11 (ECU reset),
 *   14 (clear DTC information), 27 (security access), 28 (communication control), 2E (write data), 2F (I/O control),
 *   31 (routine control), 34–37 (download/upload), 3D (write memory), 85 (DTC setting control).
 *
 * Adapter commands (`AT…`): an allowlist of commands that only affect the adapter's volatile state or read it. Refused,
 * among others: `ATPP…` (writes the adapter's EEPROM), `AT@3` (writes its identifier), `ATCV` (writes its calibration),
 * `ATWM`/`ATSW` (make the adapter send wake-up messages on the bus by itself), `ATBI` (skips the bus initialisation checks),
 * `ATMA`/`ATMR`/`ATMT` (monitor mode floods the link), `ATSI`/`ATFI` (K-line slow/fast init), and a bare carriage return
 * (repeats the previous command, which on a fresh link another app chose).
 */
public object ReadOnlyPolicy {
    private val readServices = setOf(0x01, 0x02, 0x03, 0x06, 0x07, 0x09, 0x0A, 0x22)
    private const val UDS_READ_DTC_INFORMATION = 0x19

    // ISO 14229-1 ReadDTCInformation report sub-functions; every one of them only reports.
    private val readDtcSubFunctions = (0x01..0x15).toSet() + setOf(0x16, 0x17, 0x18, 0x19, 0x1A, 0x42, 0x55, 0x56)

    // Adapter commands by prefix (upper case, no spaces). Each only sets the adapter's own volatile state or reads it.
    private val atAllowed = listOf(
        "Z", "D", "WS", "I", "@1", "@2", "RV", "DP", "DPN", "IGN",
        "E0", "E1", "L0", "L1", "S0", "S1", "H0", "H1", "M0", "M1", "AL", "NL",
        "SP", "TP", "AT0", "AT1", "AT2", "ST", "SH", "CRA", "AR", "CAF0", "CAF1", "CFC0", "CFC1", "CEA", "CP", "R0", "R1",
        "D0", "D1", "V0", "V1",
    )

    /** Why [command] may not be sent, or null when it may. [command] is the text without the trailing carriage return. */
    public fun violation(command: String): String? {
        val compact = command.filterNot { it.isWhitespace() }.uppercase()
        if (compact.isEmpty()) return "an empty command makes the adapter repeat its previous one"
        if (compact.startsWith("AT")) return adapterCommandViolation(compact.removePrefix("AT"))
        if (compact.startsWith("ST") || compact.startsWith("VT")) return "chipset-specific commands are not allowed yet: $compact"
        if (!compact.all { it in '0'..'9' || it in 'A'..'F' }) return "not a diagnostic request: $compact"
        // An odd length is a request followed by the one-digit expected-response count.
        val hex = if (compact.length % 2 == 1) compact.dropLast(1) else compact
        if (hex.length < 2) return "no service byte: $compact"
        val service = hex.substring(0, 2).toInt(16)
        if (service in readServices) return null
        if (service == UDS_READ_DTC_INFORMATION) {
            if (hex.length < 4) return "ReadDTCInformation without a sub-function: $compact"
            val sub = hex.substring(2, 4).toInt(16) and 0x7F
            return if (sub in readDtcSubFunctions) null else "ReadDTCInformation sub-function ${hex.substring(2, 4)} is not a report: $compact"
        }
        return "service ${hex.substring(0, 2)} can change the vehicle's state; KnightLink only reads: $compact"
    }

    private fun adapterCommandViolation(rest: String): String? {
        if (rest.isEmpty()) return null // a bare "AT" only answers OK
        // Longest-prefix match on the allowlist, then the argument must look like an argument (hex digits) and nothing else.
        val match = atAllowed.filter { rest.startsWith(it) }.maxByOrNull { it.length }
            ?: return "adapter command AT$rest is not on the read-only allowlist"
        val argument = rest.removePrefix(match)
        return if (argument.all { it in '0'..'9' || it in 'A'..'F' }) null else "adapter command AT$rest is not on the read-only allowlist"
    }
}

/** Thrown, before anything is written, for a command [ReadOnlyPolicy] refuses. Never retried. */
public class ReadOnlyViolationException(message: String) : AdapterException(message)
