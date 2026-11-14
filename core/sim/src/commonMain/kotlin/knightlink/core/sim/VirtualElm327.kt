package knightlink.core.sim

import knightlink.core.common.hexToBytes
import knightlink.core.common.isHexDigit
import knightlink.core.common.toHex
import knightlink.core.transport.ByteStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.time.Duration

/**
 * A simulated ELM327 adapter plugged into a [VirtualVehicle]. It speaks the adapter's text protocol
 * over a [ByteStream], so the real driver and everything above it run unmodified against it.
 *
 * Models: echo, linefeed, space and header settings, header selection, functional vs physical
 * addressing, ISO-TP segmentation of long replies, the response-count suffix, `NO DATA`, and
 * delivery in small chunks with a configurable delay.
 */
public class VirtualElm327(
    private val vehicle: VirtualVehicle,
    private val config: Config = Config(),
) : ByteStream {
    public data class Config(
        val banner: String = "ELM327 v2.3",
        /** Bytes per delivered chunk; 20 matches a default BLE notification. */
        val chunkSize: Int = 20,
        /** Time the adapter takes to answer one command. */
        val responseDelay: Duration = Duration.ZERO,
    )

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val input = StringBuilder()

    private var echo = true
    private var linefeeds = true
    private var spaces = true
    private var headers = false
    private var header = VirtualVehicle.FUNCTIONAL_ADDRESS

    /** Every command received, in order. For assertions in tests. */
    public val received: List<String> get() = log
    private val log = ArrayList<String>()

    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        for (byte in bytes) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c != '\r') {
                input.append(c)
                continue
            }
            val command = input.toString()
            input.clear()
            log += command
            delay(config.responseDelay)
            // Characters are echoed as they arrive, so the echo setting in force before the command applies.
            val echoed = echo
            send(command, echoed, execute(command.filterNot { it.isWhitespace() }.uppercase()))
        }
    }

    override suspend fun close() {
        channel.close()
    }

    private suspend fun send(command: String, echoed: Boolean, lines: List<String>) {
        val eol = if (linefeeds) "\r\n" else "\r"
        val text = buildString {
            if (echoed) append(command).append(eol)
            lines.forEach { append(it).append(eol) }
            append(eol).append('>')
        }
        text.encodeToByteArray().asList().chunked(config.chunkSize).forEach { channel.send(it.toByteArray()) }
    }

    private fun execute(command: String): List<String> = when {
        command.isEmpty() -> listOf("?")
        command.startsWith("AT") -> atCommand(command.substring(2))
        command.all { it.isHexDigit() } -> diagnosticRequest(command)
        else -> listOf("?")
    }

    private fun atCommand(body: String): List<String> {
        when (body) {
            "Z" -> {
                reset()
                return listOf(config.banner)
            }
            "D" -> {
                reset()
                return OK
            }
            "I" -> return listOf(config.banner)
            "@1" -> return listOf("OBDII to RS232 Interpreter")
            "RV" -> return listOf("14.1V")
            "DPN" -> return listOf("A6")
            "DP" -> return listOf("AUTO, ISO 15765-4 (CAN 11/500)")
            "E0" -> echo = false
            "E1" -> echo = true
            "L0" -> linefeeds = false
            "L1" -> linefeeds = true
            "S0" -> spaces = false
            "S1" -> spaces = true
            "H0" -> headers = false
            "H1" -> headers = true
            else -> {
                if (body.startsWith("SH")) {
                    val id = body.substring(2)
                    if (id.length != 3 || !id.all { it.isHexDigit() }) return listOf("?")
                    header = id.toInt(16)
                } else if (ACCEPTED_PREFIXES.none { body.startsWith(it) }) {
                    return listOf("?")
                }
            }
        }
        return OK
    }

    private fun diagnosticRequest(command: String): List<String> {
        // An odd number of digits means the last one is the "stop after N replies" suffix.
        val limit = if (command.length % 2 == 1) command.last().digitToInt(16) else null
        val request = (if (limit != null) command.dropLast(1) else command).hexToBytes()
        if (request.isEmpty()) return listOf("?")

        var replies = vehicle.targets(header).mapNotNull { ecu -> ecu.respond(request)?.let { ecu.responseId to it } }
        if (limit != null) replies = replies.take(limit)
        if (replies.isEmpty()) return listOf("NO DATA")
        return replies.flatMap { (id, payload) -> format(id, payload) }
    }

    private fun format(id: Int, payload: ByteArray): List<String> {
        val separator = if (spaces) " " else ""
        fun bytes(data: ByteArray) = data.joinToString(separator) { byteArrayOf(it).toHex() }
        val idText = id.toString(16).uppercase().padStart(3, '0')

        if (headers) return segment(payload).map { idText + separator + bytes(it) }
        if (payload.size <= SINGLE_FRAME_MAX) return listOf(bytes(payload))
        // Headers off, long reply: the ELM prints the length, then numbered rows.
        val rows = listOf(payload.copyOfRange(0, FIRST_FRAME_DATA)) +
            payload.copyOfRange(FIRST_FRAME_DATA, payload.size).asList().chunked(SINGLE_FRAME_MAX).map { it.toByteArray() }
        return listOf(payload.size.toString(16).uppercase().padStart(3, '0')) +
            rows.mapIndexed { i, row -> "${(i and 0x0F).toString(16).uppercase()}:$separator${bytes(row)}" }
    }

    /** ISO 15765-2 segmentation into CAN frame payloads. */
    private fun segment(payload: ByteArray): List<ByteArray> {
        if (payload.size <= SINGLE_FRAME_MAX) return listOf(byteArrayOf(payload.size.toByte()) + payload)
        val frames = ArrayList<ByteArray>()
        frames += byteArrayOf((0x10 or (payload.size shr 8)).toByte(), payload.size.toByte()) +
            payload.copyOfRange(0, FIRST_FRAME_DATA)
        var offset = FIRST_FRAME_DATA
        var sequence = 1
        while (offset < payload.size) {
            val end = minOf(payload.size, offset + SINGLE_FRAME_MAX)
            frames += byteArrayOf((0x20 or sequence).toByte()) + payload.copyOfRange(offset, end)
            offset = end
            sequence = (sequence + 1) and 0x0F
        }
        return frames
    }

    private fun reset() {
        echo = true
        linefeeds = true
        spaces = true
        headers = false
        header = VirtualVehicle.FUNCTIONAL_ADDRESS
    }

    private companion object {
        val OK = listOf("OK")
        const val SINGLE_FRAME_MAX = 7
        const val FIRST_FRAME_DATA = 6

        /** Settings the simulator accepts without modelling their effect. */
        val ACCEPTED_PREFIXES = listOf("SP", "TP", "AT", "ST", "CAF", "CFC", "AL", "M", "WS")
    }
}
