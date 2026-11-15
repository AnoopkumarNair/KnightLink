package knightlink.tools.archtest

/**
 * Finds vehicle identification numbers in data files (ADR 0002).
 *
 * In a session recording (`*.jsonl`) a VIN is not text: the `x` of a `tx`/`rx` event is the raw bytes as hex, and
 * what an adapter printed is itself hex digits, so a VIN is two levels down. [streams] therefore returns three views
 * of a recording: the decoded bytes of each direction, and the payload of every multi-frame reply read out of the
 * printed frames (CAN identifier and ISO-TP header dropped), where a VIN is plain text again.
 */
object VinScan {
    /** The simulator's made-up VIN. Allowed in `.json` fixtures, never in a recording, which must be scrubbed. */
    const val SIMULATOR_PLACEHOLDER = "SIMHARRIER2020001"

    private val vin = Regex("""\b[A-HJ-NPR-Z0-9]{17}\b""")

    /** What the recording scrubber leaves: a manufacturer code and fourteen zeros. Not a VIN, although it is shaped like one. */
    private val scrubbed = Regex("""[A-Z0-9]{3}0{14}""")

    private val direction = Regex(""""d"\s*:\s*"(tx|rx)"""")
    private val payload = Regex(""""x"\s*:\s*"([0-9A-Fa-f]*)"""")

    /** VIN-shaped tokens in [text], except scrubbed ones. */
    fun tokens(text: String): List<String> = vin.findAll(text).map { it.value }.filterNot { scrubbed.matches(it) }.toList()

    /** Offences in a `.json` file: VIN-shaped tokens other than the simulator's placeholder. */
    fun offencesInJson(text: String): List<String> = tokens(text).filter { it != SIMULATOR_PLACEHOLDER }

    /** Offences in a `.jsonl` recording: VIN-shaped tokens anywhere, and the placeholder too. */
    fun offencesInRecording(text: String): List<String> {
        val (tx, rx, payloads) = streams(text)
        // An adapter prints frames as hex with spaces off, e.g. 7E8 plus 7 bytes = 17 hex digits: shaped like a VIN, and not one.
        // So in the byte streams a token made only of hex digits is not an offence; the frame payloads view reads those frames properly.
        val byteStreamTokens = (tokens(tx) + tokens(rx)).filterNot { token -> token.all { it in HEX_DIGITS } }
        val found = tokens(text) + byteStreamTokens + tokens(payloads)
        val placeholder = listOf(text, tx, rx, payloads).any { SIMULATOR_PLACEHOLDER in it }
        return (found + listOfNotNull(SIMULATOR_PLACEHOLDER.takeIf { placeholder })).distinct()
    }

    /** The decoded `tx` bytes, the decoded `rx` bytes, and the payloads of the replies printed in the `rx` bytes, one per line. */
    fun streams(recording: String): List<String> {
        val tx = StringBuilder()
        val rx = StringBuilder()
        for (line in recording.lineSequence()) {
            val kind = direction.find(line)?.groupValues?.get(1) ?: continue
            val hex = payload.find(line)?.groupValues?.get(1) ?: continue
            if (hex.length % 2 != 0) continue
            (if (kind == "tx") tx else rx).append(decode(hex))
        }
        return listOf(tx.toString(), rx.toString(), framePayloads(rx.toString()).joinToString("\n"))
    }

    /** Hex digits to the characters they encode, one per byte (Latin-1), so that no byte is lost. */
    fun decode(hex: String): String = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")

    /**
     * Reads adapter lines that are CAN frames printed with headers on (three identifier digits, then data bytes, spaces
     * optional) and joins the data of each ISO-TP message, without its PCI bytes. A VIN reply is then `49 02 01` and
     * the 17 characters in a row. Lines that are not frames are ignored.
     */
    fun framePayloads(adapterText: String): List<String> {
        val messages = ArrayList<StringBuilder>()
        for (line in adapterText.split('\r', '\n')) {
            val compact = line.filterNot { it.isWhitespace() }
            if (compact.length < MIN_FRAME_DIGITS || (compact.length - ID_DIGITS) % 2 != 0 || !compact.all { it in HEX_DIGITS }) continue
            val data = compact.drop(ID_DIGITS).chunked(2).map { it.toInt(16) }
            val pci = data[0] ushr 4
            val skip = when (pci) {
                PCI_SINGLE, PCI_CONSECUTIVE -> 1
                PCI_FIRST -> 2
                else -> continue
            }
            // A consecutive frame continues the message before it; anything else starts a new one.
            if (pci != PCI_CONSECUTIVE || messages.isEmpty()) messages += StringBuilder()
            data.drop(skip).forEach { messages.last().append(it.toChar()) }
        }
        return messages.map { it.toString() }
    }

    private const val ID_DIGITS = 3
    private const val MIN_FRAME_DIGITS = 5
    private const val PCI_SINGLE = 0
    private const val PCI_FIRST = 1
    private const val PCI_CONSECUTIVE = 2
    private const val HEX_DIGITS = "0123456789abcdefABCDEF"
}
