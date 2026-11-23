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
 * - Also allowed, narrowly: UDS 10 with sub-function 01 (the default session) and only when the request is addressed to a body unit (a
 *   request identifier from 700 to 7DE or 7F0 to 7FF, outside the engine-bay addresses; see [isBodyUnit]). Not "nothing": it makes the unit
 *   start its default session afresh, which ends an extended or programming session another tool may have opened on it. A unit is in the
 *   default session at power-up, so there it only confirms it; some units answer identifiers only after one, as the commercial scanners send one.
 * - Every diagnostic request goes to a request identifier from 700 to 7FF ([requestIdViolation]): never to the low identifiers that carry the
 *   car's own broadcast, whatever a data file says.
 * - Refused, among others: 04 (clear codes), 08 (control of on-board systems), UDS 10 with any other sub-function (02 programming,
 *   03 extended) or to an engine-bay unit, 11 (ECU reset), 14 (clear DTC information), 27 (security access), 28 (communication control),
 *   2E (write data), 2F (I/O control), 31 (routine control), 34–37 (download/upload), 3D (write memory), 3E (tester present),
 *   85 (DTC setting control).
 *
 * Adapter commands (`AT…`): an allowlist of commands that only affect the adapter's volatile state or read it. That includes the
 * adapter-local receive and flow-control settings that a read of an ECU with a non-OBD address needs: `ATCF`/`ATCM`/`ATCRA` (which CAN
 * identifiers the adapter listens to; `ATCRA` with one identifier of three or eight hex digits, or none to reset it), `ATFCSH` (the identifier its flow-control frames go to), `ATFCSD` (their data, only
 * ClearToSend: `30…`) and `ATFCSM` (flow-control mode 0 or 1). None of them puts a diagnostic message on the bus. Refused,
 * among others: `ATPP…` (writes the adapter's EEPROM), `AT@3` (writes its identifier), `ATCV` (writes its calibration),
 * `ATWM`/`ATSW` (make the adapter send wake-up messages on the bus by itself), `ATBI` (skips the bus initialisation checks),
 * `ATMA`/`ATMR`/`ATMT` (monitor mode floods the link), `ATSI`/`ATFI` (K-line slow/fast init), and a bare carriage return
 * (repeats the previous command, which on a fresh link another app chose).
 *
 * Passive capture ([passiveViolation]) is a second, narrower gate for the one path that listens to the bus broadcast without asking
 * anything. It adds `ATMA` and `ATCSM1`, which this gate still refuses, and allows nothing else but the receive-side settings it lists.
 * `ATMA` (monitor all) together with silent monitoring (`ATCSM1`) is receive-only: the adapter's CAN controller listens, does not
 * acknowledge frames and does not send error frames, so nothing goes onto the bus until a character from the phone stops the
 * monitoring. That character is [MONITOR_STOP]; it is not a command and only [Elm327Driver.monitor] writes it.
 */
public object ReadOnlyPolicy {
    /** The single character that ends `ATMA`. Any character does; a space is used because a carriage return at a prompt repeats the last command. */
    public const val MONITOR_STOP: String = " "

    /** The only adapter commands (without `AT`, upper case) the passive capture path may send, besides `CF`/`CM` with a three-digit identifier. */
    private val passiveExact = setOf("CSM1", "CAF0", "CAF1", "H1", "S0", "AR", "DPN")

    /**
     * Why [command] may not be sent on the passive capture path, or null when it may. Much narrower than [violation]: only adapter
     * commands that set how the adapter listens (`ATCSM1`, `ATCAF0`, `ATCAF1`, `ATH1`, `ATS0`, `ATAR`, `ATCF hhh`, `ATCM hhh`) and
     * `ATDPN`, which asks which protocol is in use (monitoring is only started when it is fixed, because under automatic protocol search
     * `ATMA` makes the adapter send requests of its own). No diagnostic request, no other adapter command, and not `ATMA` itself: that
     * one is gated by [monitorViolation] and only [Elm327Driver.monitor] sends it.
     */
    public fun monitorViolation(command: String): String? {
        val compact = command.filterNot { it.isWhitespace() }.uppercase()
        return if (compact == "ATMA") null else "only ATMA is a monitor command: $compact"
    }

    public fun passiveViolation(command: String): String? {
        val compact = command.filterNot { it.isWhitespace() }.uppercase()
        if (!compact.startsWith("AT")) return "only adapter commands are allowed while capturing passively: $compact"
        val rest = compact.removePrefix("AT")
        if (rest in passiveExact) return null
        if ((rest.startsWith("CF") || rest.startsWith("CM")) && rest.length == 5 && rest.drop(2).all { it in '0'..'9' || it in 'A'..'F' }) return null
        return "adapter command $compact is not allowed while capturing passively"
    }

    private val readServices = setOf(0x01, 0x02, 0x03, 0x06, 0x07, 0x09, 0x0A, 0x22)
    private const val UDS_READ_DTC_INFORMATION = 0x19
    private const val UDS_SESSION_CONTROL = 0x10

    /** The request identifiers of the engine-bay units (7E0-7E7), the functional address (7DF) and the response identifiers (7E8-7EF): not body units. */
    private val obdIds = 0x7DF..0x7EF

    /** The request identifiers a diagnostic request may go to: the 11-bit diagnostic block, 700-7FF. */
    private val diagnosticIds = 0x700..0x7FF

    /** Why a diagnostic request may not be sent to [requestId], or null when it may. */
    public fun requestIdViolation(requestId: Int): String? =
        if (requestId in diagnosticIds) null else "request identifier ${requestId.toString(16).uppercase()} is outside the diagnostic block 700-7FF"

    /**
     * Whether [requestId] is a body unit: in the diagnostic block 700-7FF but not an OBD identifier (7DF-7EF). Only to those may the default
     * session be requested.
     */
    public fun isBodyUnit(requestId: Int): Boolean = requestId in diagnosticIds && requestId !in obdIds

    // ISO 14229-1 ReadDTCInformation report sub-functions; every one of them only reports.
    private val readDtcSubFunctions = (0x01..0x15).toSet() + setOf(0x16, 0x17, 0x18, 0x19, 0x1A, 0x42, 0x55, 0x56)

    // Adapter commands by prefix (upper case, no spaces). Each only sets the adapter's own volatile state or reads it.
    private val atAllowed = listOf(
        "Z", "D", "WS", "I", "@1", "@2", "RV", "DP", "DPN", "IGN",
        "E0", "E1", "L0", "L1", "S0", "S1", "H0", "H1", "M0", "M1", "AL", "NL",
        "SP", "TP", "AT0", "AT1", "AT2", "ST", "SH", "CRA", "AR", "CAF0", "CAF1", "CFC0", "CFC1", "CEA", "CP", "R0", "R1",
        "D0", "D1", "V0", "V1",
        "CF", "CM", "FCSH", "FCSD", "FCSM",
    )

    /**
     * Why [command] may not be sent, or null when it may. [command] is the text without the trailing carriage return.
     *
     * @param requestId the request identifier a diagnostic request is sent to, when known (the driver's request path knows it; a raw
     *   command does not). Only `10 01` to a body unit needs it: without it service 10 is refused.
     */
    public fun violation(command: String, requestId: Int? = null): String? {
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
        if (service == UDS_SESSION_CONTROL) {
            return if (hex == "1001" && requestId != null && isBodyUnit(requestId)) {
                null
            } else {
                "only the default session (10 01) of a body unit may be requested, nothing else of service 10: $compact"
            }
        }
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
        if (!argument.all { it in '0'..'9' || it in 'A'..'F' }) return "adapter command AT$rest is not on the read-only allowlist"
        // The receive and flow-control settings take a CAN identifier (3 hex digits, or 8 for 29-bit) or fixed data, nothing else.
        val shape = when (match) {
            "CF", "CM", "FCSH" -> argument.length == 3 || argument.length == 8
            // ClearToSend only (30 = continue, then block size and separation time): never wait or abort, never a stranger's byte.
            "FCSD" -> argument.length in 2..10 && argument.length % 2 == 0 && argument.startsWith("30")
            "FCSM" -> argument == "0" || argument == "1"
            // One receive address, or none to go back to the automatic one.
            "CRA" -> argument.isEmpty() || argument.length == 3 || argument.length == 8
            else -> true
        }
        return if (shape) null else "adapter command AT$rest has an argument that is not allowed for $match"
    }
}

/** Thrown, before anything is written, for a command [ReadOnlyPolicy] refuses. Never retried. */
public class ReadOnlyViolationException(message: String) : AdapterException(message)
