package knightlink.core.sim

import knightlink.core.common.hexToBytes
import knightlink.core.common.isHexDigit
import knightlink.core.common.toHex
import knightlink.core.transport.ByteStream
import knightlink.core.transport.TransportException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A simulated ELM327 adapter plugged into a [VirtualVehicle]. It speaks the adapter's text protocol
 * over a [ByteStream], so the real driver and everything above it run unmodified against it.
 *
 * Models: echo, linefeed, space and header settings, header selection, receive filters (`ATCF`/`ATCM`/`ATCRA`), functional vs physical
 * addressing, ISO-TP segmentation of long replies, the response-count suffix, `NO DATA`, and
 * delivery in small chunks with a configurable delay.
 *
 * The response-count suffix is modelled as a real adapter behaves: the adapter stops after that many CAN frames, so with a count of 1 a
 * multi-frame reply is cut to its first frame and the adapter never sends flow control. A request for a reply longer than one frame
 * must therefore be sent without the suffix.
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
        /**
         * Extra time a diagnostic request without a response count takes: the adapter cannot know that no further ECU will answer, so
         * it listens until its timeout. A request with a count returns as soon as the replies are in.
         */
        val listenWithoutCount: Duration = Duration.ZERO,
        /** The clock that times `ATMA`: the frames printed are those the vehicle's broadcasts produced while monitoring lasted. */
        val timeSource: TimeSource = TimeSource.Monotonic,
        /** `ATMA` prints `BUFFER FULL` once and drops the rest after this many frames in one monitoring; null for no limit. */
        val monitorBufferFrames: Int? = null,
        /** Commands (upper case, no spaces, e.g. `ATCRA708`) this adapter answers with `?` from the start, as one that does not know them. */
        val unsupported: Set<String> = emptySet(),
        /**
         * The OBD protocol the simulated car speaks (the `ATSP` character, `6` for 11-bit 500 kbaud CAN). Null models no protocol at all: every
         * request reaches the car whatever `ATSP` said, as before. With a protocol, a request reaches the car only when the adapter is searching
         * by itself (`ATSP0`, which then finds it: `SEARCHING...`, and `ATDPN` says `A` and the digit) or has been told this very protocol;
         * any other explicit protocol answers `UNABLE TO CONNECT`, as does a car that is asleep. For `7` and `9` the replies carry 29-bit
         * headers, for `1` to `5` legacy ones.
         */
        val carProtocol: Char? = null,
        /** How long the adapter takes to find a protocol, or to give up on one, when [carProtocol] is modelled. */
        val searchDelay: Duration = Duration.ZERO,
    )

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val input = StringBuilder()

    private var echo = true
    private var linefeeds = true
    private var spaces = true
    private var headers = false
    private var header = VirtualVehicle.FUNCTIONAL_ADDRESS

    // Which CAN identifiers the adapter listens to: after a reset only 7E8..7EF (the OBD responses), as on a real ELM327 on an 11-bit bus.
    private var receiveFilter = DEFAULT_RECEIVE_FILTER
    private var receiveMask = DEFAULT_RECEIVE_MASK

    // Flow control: automatic (mode 0) answers the ECU it asked; in mode 1 the frames go to the identifier and carry the data set by the user.
    private var flowControlMode = 0
    private var flowControlHeader: Int? = null
    private var flowControlData: String? = null

    /**
     * A status word the adapter answers every diagnostic request with instead of asking the vehicle (`CAN ERROR`, `UNABLE TO CONNECT`),
     * as when the bus cannot be reached. Null for normal behaviour.
     */
    public var busFault: String? = null

    /** What `ATRV` answers: the battery voltage as the adapter reads it. */
    public var voltage: String = "14.1V"

    private var resetBeforeNextCommand = false

    // The protocol is chosen by the init script (ATSP6); until then, and after ATSP0, the adapter searches automatically (ATDPN says `A6`).
    private var automaticProtocol = true

    // With [Config.carProtocol]: the protocol the adapter was told, and the one its search found.
    private var selectedProtocol: Char? = null
    private var foundProtocol: Char? = null

    /** Commands (upper case, no spaces, e.g. `ATCSM1`) the adapter always answers with `?`, as one that does not know them. */
    public val unsupportedCommands: MutableSet<String> = HashSet(config.unsupported)

    /** Commands answered with `?` once, then accepted again. */
    public val rejectNext: MutableSet<String> = HashSet()

    /** Sends [text] as it is, without anyone having asked, as an adapter that is still saying something from an earlier command. */
    public fun sayUnasked(text: String) {
        text.encodeToByteArray().asList().chunked(config.chunkSize).forEach { channel.trySend(it.toByteArray()) }
    }

    // `ATMA` is running since this mark: frames are printed, all at once, when any character ends it.
    private var monitoringSince: TimeMark? = null

    /** How many times a character ended `ATMA`. For assertions in tests. */
    public var monitorStops: Int = 0
        private set

    /** Recent commands received (capped at [MAX_RECEIVED_LOG] entries), in order. For assertions in tests. */
    public val received: List<String> get() = log
    private val log = ArrayDeque<String>()

    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

    /** Set by [disconnect] and [close]: the link is gone and nothing can be sent over it any more. */
    private var linkEnded = false

    /**
     * Simulates the adapter being unplugged or going out of range: [incoming] ends (normally, or by failing
     * with [cause]) after delivering what was already sent, and every later [write] throws
     * [TransportException]. Does nothing if the link has already ended.
     */
    public fun disconnect(cause: Throwable? = null) {
        if (linkEnded) return
        linkEnded = true
        channel.close(cause)
    }

    override suspend fun write(bytes: ByteArray) {
        ensureLinked()
        for (byte in bytes) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (monitoringSince != null) {
                // Any character ends the monitoring, as on a real adapter; it is not a command.
                stopMonitoring()
                continue
            }
            if (c != '\r') {
                input.append(c)
                continue
            }
            val command = input.toString()
            input.clear()
            if (log.size >= MAX_RECEIVED_LOG) log.removeFirst()
            log.addLast(command)
            if (resetBeforeNextCommand) {
                resetBeforeNextCommand = false
                reset()
            }
            delay(config.responseDelay)
            val compact = command.filterNot { it.isWhitespace() }
            if (compact.uppercase() == "ATMA") {
                monitoringSince = config.timeSource.markNow()
                continue
            }
            if (config.listenWithoutCount.isPositive() && compact.length % 2 == 0 && compact.all { it.isHexDigit() }) delay(config.listenWithoutCount)
            if (config.carProtocol != null && config.searchDelay.isPositive() && compact.isNotEmpty() && compact.all { it.isHexDigit() } && !reachesCar()) {
                delay(config.searchDelay)
            }
            // The link may have dropped while the adapter was "thinking": the answer is then lost, as on a real link.
            ensureLinked()
            // Characters are echoed as they arrive, so the echo setting in force before the command applies.
            val echoed = echo
            val key = command.filterNot { it.isWhitespace() }.uppercase()
            send(command, echoed, if (key in unsupportedCommands || rejectNext.remove(key)) listOf("?") else execute(key))
        }
    }

    /**
     * The adapter loses power for a moment, as when the engine is cranked: every setting goes back to its default (echo on,
     * headers off), the selected header is forgotten, and, when [printBanner] is set, the start-up banner is sent without anyone
     * having asked for it. What the driver had been waiting for is lost.
     */
    public fun powerDip(printBanner: Boolean = true) {
        reset()
        if (printBanner && !linkEnded) send(command = "", echoed = false, lines = listOf(config.banner))
    }

    /** Like [powerDip] without a banner, and only when the next command arrives: that command is the first one the reset adapter sees. */
    public fun powerDipBeforeNextCommand() {
        resetBeforeNextCommand = true
    }

    override suspend fun close() {
        linkEnded = true
        channel.close()
    }

    private fun ensureLinked() {
        if (linkEnded) throw TransportException("The simulated adapter is disconnected")
    }

    /** Prints the frames the vehicle's broadcasts sent since `ATMA` and passing the receive filter, then `STOPPED` and the prompt. */
    private fun stopMonitoring() {
        val elapsedMs = monitoringSince!!.elapsedNow().inWholeMilliseconds
        monitoringSince = null
        monitorStops++
        val frames = ArrayList<Pair<Long, Pair<Int, ByteArray>>>()
        if (vehicle.awake) {
            for (broadcast in vehicle.broadcasts) {
                if ((broadcast.id xor receiveFilter) and receiveMask != 0) continue
                var n = 0
                while ((n + 1).toLong() * broadcast.periodMs <= elapsedMs) {
                    frames += (n + 1).toLong() * broadcast.periodMs to (broadcast.id to broadcast.data(n))
                    n++
                }
            }
        }
        frames.sortBy { it.first }
        val separator = if (spaces) " " else ""
        val lines = ArrayList<String>()
        val limit = config.monitorBufferFrames
        for ((index, frame) in frames.withIndex()) {
            if (limit != null && index == limit) {
                lines += "BUFFER FULL"
                break
            }
            val (id, data) = frame.second
            lines += id.toString(16).uppercase().padStart(3, '0') + separator + data.joinToString(separator) { byteArrayOf(it).toHex() }
        }
        lines += "STOPPED"
        send(command = "", echoed = false, lines = lines)
    }

    private fun send(command: String, echoed: Boolean, lines: List<String>) {
        val eol = if (linefeeds) "\r\n" else "\r"
        val text = buildString {
            if (echoed) append(command).append(eol)
            lines.forEach { append(it).append(eol) }
            append(eol).append('>')
        }
        text.encodeToByteArray().asList().chunked(config.chunkSize).forEach { channel.trySend(it.toByteArray()) }
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
            "RV" -> return listOf(voltage)
            "DPN" -> return listOf(
                when {
                    config.carProtocol == null -> if (automaticProtocol) "A6" else "6"
                    automaticProtocol -> "A" + (foundProtocol ?: '0')
                    else -> (selectedProtocol ?: '0').toString()
                },
            )
            "DP" -> return listOf("AUTO, ISO 15765-4 (CAN 11/500)")
            "E0" -> echo = false
            "E1" -> echo = true
            "L0" -> linefeeds = false
            "L1" -> linefeeds = true
            "S0" -> spaces = false
            "S1" -> spaces = true
            "H0" -> headers = false
            "H1" -> headers = true
            "AR" -> {
                receiveFilter = DEFAULT_RECEIVE_FILTER
                receiveMask = DEFAULT_RECEIVE_MASK
            }
            else -> {
                if (body.startsWith("CRA")) {
                    // One receive address; without an argument the automatic one again.
                    val argument = body.substring(3)
                    if (argument.isEmpty()) {
                        receiveFilter = DEFAULT_RECEIVE_FILTER
                        receiveMask = DEFAULT_RECEIVE_MASK
                    } else {
                        receiveFilter = hex3(argument) ?: return listOf("?")
                        receiveMask = EXACT_MASK
                    }
                } else if (body.startsWith("CF") && !body.startsWith("CFC")) {
                    receiveFilter = hex3(body.substring(2)) ?: return listOf("?")
                } else if (body.startsWith("CM")) {
                    receiveMask = hex3(body.substring(2)) ?: return listOf("?")
                } else if (body.startsWith("FCSH")) {
                    flowControlHeader = hex3(body.substring(4)) ?: return listOf("?")
                } else if (body.startsWith("FCSD")) {
                    val data = body.substring(4)
                    if (data.isEmpty() || data.length % 2 != 0 || !data.all { it.isHexDigit() }) return listOf("?")
                    flowControlData = data
                } else if (body.startsWith("FCSM")) {
                    flowControlMode = body.substring(4).toIntOrNull()?.takeIf { it in 0..2 } ?: return listOf("?")
                } else if ((body.startsWith("SP") || body.startsWith("TP")) && body.length == 3) {
                    automaticProtocol = body[2] == '0' || body[2] == 'A'
                    selectedProtocol = body[2].takeUnless { automaticProtocol }
                    if (automaticProtocol) foundProtocol = null
                } else if (body.startsWith("SH")) {
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
        busFault?.let { return listOf(it) }
        val search = ArrayList<String>()
        if (config.carProtocol != null) {
            if (!reachesCar()) return if (automaticProtocol) listOf("SEARCHING...", "UNABLE TO CONNECT") else listOf("UNABLE TO CONNECT")
            if (automaticProtocol && foundProtocol == null) {
                foundProtocol = config.carProtocol
                search += "SEARCHING..."
            }
        }

        // An ECU whose reply the adapter is not listening to is as good as silent.
        val replies = vehicle.targets(header)
            .filter { ecu -> (ecu.responseId xor receiveFilter) and receiveMask == 0 }
            .mapNotNull { ecu -> ecu.respond(request)?.let { Triple(ecu.responseId, it, ecu.needsCustomFlowControl) } }
        if (replies.isEmpty()) return search + "NO DATA"
        // A long reply goes on after its first frame only if the ECU gets a flow-control frame it understands: user-defined flow control
        // that is addressed to another identifier than the request leaves the ECU waiting for ever.
        val customWorks = flowControlMode == 1 && flowControlHeader == header && flowControlData?.startsWith("30") == true
        val flowControlWorks = flowControlMode != 1 || customWorks
        if (limit == null) {
            return search + replies.flatMap { (id, payload, needsCustom) ->
                val works = if (needsCustom) customWorks else flowControlWorks
                format(id, payload, firstFrameOnly = !works && payload.size > SINGLE_FRAME_MAX)
            }
        }

        // The count is in CAN frames: a long reply is cut to its first frame, and the replies after that are not sent at all.
        val lines = ArrayList<String>(search)
        var frames = 0
        for ((id, payload, _) in replies) {
            if (frames >= limit) break
            lines += format(id, payload, firstFrameOnly = payload.size > SINGLE_FRAME_MAX)
            frames++
        }
        return lines
    }

    /** Whether a diagnostic request gets to the car under the protocol model of [Config.carProtocol] (always, without one). */
    private fun reachesCar(): Boolean {
        val protocol = config.carProtocol ?: return true
        if (!vehicle.awake) return false
        return automaticProtocol || selectedProtocol == protocol
    }

    private fun format(id: Int, payload: ByteArray, firstFrameOnly: Boolean = false): List<String> {
        val separator = if (spaces) " " else ""
        fun bytes(data: ByteArray) = data.joinToString(separator) { byteArrayOf(it).toHex() }
        val low = (id and 0xFF).toString(16).uppercase().padStart(2, '0')
        val idText = when (config.carProtocol) {
            '7', '9' -> "18DAF1$low"
            '1', '2', '3', '4', '5' -> "486B$low"
            else -> id.toString(16).uppercase().padStart(3, '0')
        }

        if (headers) return segment(payload).let { if (firstFrameOnly) it.take(1) else it }.map { idText + separator + bytes(it) }
        if (payload.size <= SINGLE_FRAME_MAX) return listOf(bytes(payload))
        // Headers off, long reply: the ELM prints the length, then numbered rows.
        val rows = listOf(payload.copyOfRange(0, FIRST_FRAME_DATA)) +
            payload.copyOfRange(FIRST_FRAME_DATA, payload.size).asList().chunked(SINGLE_FRAME_MAX).map { it.toByteArray() }
        return listOf(payload.size.toString(16).uppercase().padStart(3, '0')) +
            rows.mapIndexed { i, row -> "${(i and 0x0F).toString(16).uppercase()}:$separator${bytes(row)}" }.let { if (firstFrameOnly) it.take(1) else it }
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
        receiveFilter = DEFAULT_RECEIVE_FILTER
        receiveMask = DEFAULT_RECEIVE_MASK
        flowControlMode = 0
        flowControlHeader = null
        flowControlData = null
        automaticProtocol = true
        selectedProtocol = null
        foundProtocol = null
    }

    /** An 11-bit identifier of exactly three hex digits, or null. */
    private fun hex3(text: String): Int? = if (text.length == 3 && text.all { it.isHexDigit() }) text.toInt(16) else null

    private companion object {
        val OK = listOf("OK")
        const val SINGLE_FRAME_MAX = 7
        const val FIRST_FRAME_DATA = 6
        const val DEFAULT_RECEIVE_FILTER = 0x7E8
        const val DEFAULT_RECEIVE_MASK = 0x7F8
        const val EXACT_MASK = 0x7FF
        const val MAX_RECEIVED_LOG = 50_000

        /** Settings the simulator accepts without modelling their effect. */
        val ACCEPTED_PREFIXES = listOf("SP", "TP", "AT", "ST", "CAF", "CFC", "AL", "M", "WS", "CSM")
    }
}
