package knightlink.core.runtime

import knightlink.core.adapter.AdapterDisconnectedException
import knightlink.core.adapter.AdapterException
import knightlink.core.adapter.Elm327Driver
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import knightlink.core.vehicle.ObdProtocol
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What [ProtocolNegotiator] tried. [outcome] is in words; no VIN is ever in it. */
public data class NegotiationAttempt(val step: String, val outcome: String, val millis: Long)

/** How a negotiation ended. */
public sealed interface Negotiation {
    public val attempts: List<NegotiationAttempt>
    public val elapsedMs: Long

    /**
     * A protocol answered `0100`.
     *
     * @property protocol the protocol found; null when the adapter could not name it and the replies' headers do not say (a legacy bus)
     * @property dpn the adapter's `ATDPN` answer, e.g. `A6`; null when it gave none
     * @property viaAuto whether the adapter's own search found it (`ATSP0`)
     */
    public data class Found(
        val protocol: ObdProtocol?,
        val dpn: String?,
        val viaAuto: Boolean,
        override val attempts: List<NegotiationAttempt>,
        override val elapsedMs: Long,
        /** A unit refused `0100` (a negative response) instead of answering it: the bus is alive and the protocol right, but something in the car keeps the app out. */
        val refused: Boolean = false,
    ) : Negotiation {
        /** Whether the app's request path can read a car over it. */
        val readable: Boolean get() = protocol?.isReadable == true
    }

    /**
     * Nothing answered on any protocol that was tried.
     *
     * @property busFault the adapter said it could not reach the vehicle bus (`UNABLE TO CONNECT`, `CAN ERROR`) at least once, as opposed to
     *   plain silence
     * @property dpn the adapter's `ATDPN` answer after the last try, when it gave one
     */
    public data class NotFound(
        val busFault: Boolean,
        val dpn: String?,
        override val attempts: List<NegotiationAttempt>,
        override val elapsedMs: Long,
    ) : Negotiation
}

/**
 * Finds out which protocol the car speaks, the way a scan tool does, before the capability probe runs and without a definition of the car.
 *
 * 1. The protocol that is set already (the adapter definition's, or the one remembered for this adapter) is tried first: `0100` answered
 *    means nothing else is needed (an 11-bit 500 kbaud car, the common case, costs one request).
 * 2. Otherwise the adapter searches by itself: `ATSP0`, a long timeout (`ATSTFF`), `0100` (the adapter's `SEARCHING...` and `BUS INIT` are
 *    waited through), then `ATDPN` says what it found, and the protocol is locked with `ATSP<n>`.
 * 3. When the search finds nothing, the protocols are tried one by one in [ObdProtocol.EXPLICIT_ORDER], each with `0100`, for at most
 *    [Config.explicitBudget] in all.
 *
 * `0100` is sent as a broadcast request here, once per try: it is the one request every OBD adapter sends to find a car, and it is a read of the
 * list of supported data, nothing more. Everything else the session sends goes to one control unit's physical address. Every command passes the
 * adapter driver's read-only gate.
 *
 * @param timerRestore commands that put the adapter's timeout back as the probe wants it, sent when a try has lengthened it
 */
public class ProtocolNegotiator(
    private val driver: Elm327Driver,
    private val events: SessionEventSink = SessionEventSink.None,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val config: Config = Config(),
    private val timerRestore: List<String> = emptyList(),
) {
    public data class Config(
        /** How long the first try, with the protocol that is set already, may take. */
        val preferredTimeout: Duration = 4.seconds,
        /** How long `0100` may take under the adapter's own search; the driver waits for `SEARCHING...` on top of this. */
        val autoTimeout: Duration = 15.seconds,
        /** How long `0100` may take with one explicit protocol (a slow-initialising legacy bus needs several seconds). */
        val explicitTimeout: Duration = 6.seconds,
        /** The most time the explicit tries may take in all. */
        val explicitBudget: Duration = 30.seconds,
        /** The adapter's long timeout during the search; `ATST FF` is about one second. Null leaves it alone. */
        val searchTimer: String? = "ATSTFF",
        /** Whether to try the explicit protocols when the search found nothing. */
        val tryExplicit: Boolean = true,
    )

    private enum class Reply { ANSWER, REFUSED, BUS_FAULT, NO_DATA, OTHER }

    private val attempts = ArrayList<NegotiationAttempt>()
    private var busFault = false
    private var timerLengthened = false

    /**
     * @param preferred the protocol to try first, or null to go straight to the search
     * @param quick only [preferred] and, without one, the search: no explicit tries (for a car that may simply be switched off)
     * @throws AdapterException when the adapter rejects `ATSP` (an adapter that cannot select a protocol cannot be used)
     */
    public suspend fun negotiate(preferred: ObdProtocol?, quick: Boolean = false): Negotiation {
        attempts.clear()
        busFault = false
        timerLengthened = false
        val start = timeSource.markNow()
        try {
            if (preferred != null) {
                select(preferred)
                if (broadcast("preferred ${preferred.code}", config.preferredTimeout).isAlive()) return found(preferred, viaAuto = false, start)
            }
            if (!quick || preferred == null) {
                search(start)?.let { return it }
            }
            if (!quick && config.tryExplicit) {
                val budget = timeSource.markNow()
                for (protocol in ObdProtocol.EXPLICIT_ORDER) {
                    if (protocol == preferred) continue
                    // What is left of the budget bounds the try itself, so the loop cannot overrun it by a whole timeout.
                    val remaining = config.explicitBudget - budget.elapsedNow()
                    if (remaining < MIN_TRY) {
                        attempts += NegotiationAttempt("explicit ${protocol.code}", "skipped: the ${config.explicitBudget} budget is used", 0)
                        break
                    }
                    select(protocol)
                    if (broadcast("explicit ${protocol.code}", minOf(config.explicitTimeout, remaining)).isAlive()) {
                        return found(protocol, viaAuto = false, start)
                    }
                }
            }
            return Negotiation.NotFound(busFault, dpn(), attempts.toList(), start.elapsedNow().inWholeMilliseconds).also {
                log("nothing answered: ${summary(it)}")
            }
        } finally {
            restoreTimer()
        }
    }

    // The adapter's own search: ATSP0, then the first 0100, then ATDPN, then lock what it found.
    private suspend fun search(start: TimeMark): Negotiation? {
        adapterCommand("ATSP0")
        config.searchTimer?.let {
            try {
                driver.command(it, config.preferredTimeout)
                timerLengthened = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdapterException) {
                if (e is AdapterDisconnectedException) throw e
                // A timer the adapter will not take only makes the search shorter.
            }
        }
        val reply = broadcast("auto", config.autoTimeout)
        if (!reply.isAlive()) return null
        val dpn = dpn()
        val protocol = ObdProtocol.fromDpn(dpn) ?: guessFromHeaders()
        if (protocol != null && protocol.isReadable) {
            // Locked: the adapter keeps this protocol, and a re-initialisation runs the script with it (the session updates the script).
            adapterCommand(protocol.selectCommand)
        }
        return Negotiation.Found(protocol, dpn, viaAuto = true, attempts.toList(), start.elapsedNow().inWholeMilliseconds, refused = reply == Reply.REFUSED)
            .also { log("found by the adapter's search: ${summary(it)}") }
    }

    private var lastAnswerLines: List<String> = emptyList()
    private var lastReply: Reply = Reply.OTHER

    /** When the adapter cannot say (`ATDPN` answered `?`), the width of the reply's headers does: three digits is 11-bit CAN. */
    private fun guessFromHeaders(): ObdProtocol? {
        val line = lastAnswerLines.firstOrNull { it.filterNot(Char::isWhitespace).uppercase().contains("4100") } ?: return null
        val first = line.trim().split(' ').firstOrNull().orEmpty()
        return when {
            first.length == 3 -> ObdProtocol.CAN_11_500
            first.length == 8 -> ObdProtocol.CAN_29_500
            else -> null
        }
    }

    private fun Reply.isAlive(): Boolean = this == Reply.ANSWER || this == Reply.REFUSED

    private suspend fun found(protocol: ObdProtocol, viaAuto: Boolean, start: TimeMark): Negotiation {
        val dpn = dpn()
        // The adapter may disagree with what was selected (a protocol it could not start falls back to its search): believe it.
        val actual = ObdProtocol.fromDpn(dpn) ?: protocol
        // ATTP only tried it; ATSP locks it.
        if (actual.isReadable) adapterCommand(actual.selectCommand)
        return Negotiation.Found(actual, dpn, viaAuto, attempts.toList(), start.elapsedNow().inWholeMilliseconds, refused = lastReply == Reply.REFUSED).also {
            log("found: ${summary(it)}")
        }
    }

    /**
     * Tries [protocol] with `ATTP` (a trial that is not kept: the adapter's stored protocol and the init script stay as they were). An adapter that
     * does not know `ATTP` is given `ATSP` instead; `ATSP` is otherwise used only to lock a protocol that answered.
     */
    private suspend fun select(protocol: ObdProtocol) {
        trying = protocol
        val lines = driver.command("ATTP${protocol.code}")
        if (lines.any { it.trim() == "?" }) adapterCommand(protocol.selectCommand)
    }

    private var trying: ObdProtocol? = null

    /** `ATSP..`: essential, so an adapter that answers `?` cannot be used for this. */
    private suspend fun adapterCommand(command: String) {
        val lines = driver.command(command)
        if (lines.any { it.trim() == "?" }) throw AdapterException("Adapter rejected '$command'")
    }

    private suspend fun dpn(): String? = try {
        driver.command("ATDPN").map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "?" }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdapterDisconnectedException) {
        throw e
    } catch (_: AdapterException) {
        null
    }

    /** One `0100`, classified. A failed command (no prompt in time) is "no answer". */
    private suspend fun broadcast(step: String, timeout: Duration): Reply {
        val mark = timeSource.markNow()
        val setHeader = step != "auto" && trying?.isReadable == true
        var lines = try {
            askFunctional(timeout, setHeader)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdapterDisconnectedException) {
            throw e
        } catch (e: AdapterException) {
            attempts += NegotiationAttempt(step, "no reply to 0100: ${e.message}", mark.elapsedNow().inWholeMilliseconds)
            return Reply.OTHER
        }
        if (looksReset(lines)) {
            // The adapter restarted (low voltage, a banner): its settings are gone. Initialise it again, select the protocol again, ask once more.
            attempts += NegotiationAttempt(step, "the adapter reset itself (LV RESET or banner): initialised again", mark.elapsedNow().inWholeMilliseconds)
            log("$step: the adapter reset itself; initialising it again")
            driver.reinitialize("the adapter reset during the protocol search")
            if (step == "auto") adapterCommand("ATSP0") else trying?.let { select(it) }
            lines = try {
                askFunctional(timeout, setHeader)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdapterDisconnectedException) {
                throw e
            } catch (e: AdapterException) {
                attempts += NegotiationAttempt(step, "no reply to 0100 after the reset: ${e.message}", mark.elapsedNow().inWholeMilliseconds)
                return Reply.OTHER
            }
        }
        lastAnswerLines = lines
        val reply = classify(lines)
        lastReply = reply
        if (reply == Reply.BUS_FAULT) busFault = true
        val text = when (reply) {
            Reply.ANSWER -> "answered"
            Reply.REFUSED -> "refused with a negative response"
            Reply.BUS_FAULT -> "the adapter could not connect"
            Reply.NO_DATA -> "NO DATA"
            Reply.OTHER -> "unclear: ${lines.firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_LOGGED).orEmpty()}"
        }
        val millis = mark.elapsedNow().inWholeMilliseconds
        attempts += NegotiationAttempt(step, text, millis)
        log("$step: 0100 $text in $millis ms")
        return reply
    }

    /** `0100` to the functional address: the header is set first, whatever an earlier session left (or a reset cleared) in the adapter. */
    private suspend fun askFunctional(timeout: Duration, setHeader: Boolean): List<String> {
        // On CAN protocols (11-bit or 29-bit) set the functional header explicitly; on K-line/J1850 or auto-search leave it alone.
        val protocol = trying
        if (setHeader && protocol != null && protocol.isCan) {
            for (cmd in protocol.addressing.selectHeaderCommands(protocol.addressing.functionalRequestId)) {
                driver.command(cmd)
            }
        }
        return driver.command("0100", timeout)
    }

    private fun looksReset(lines: List<String>): Boolean = lines.any {
        val compact = it.filterNot(Char::isWhitespace).uppercase()
        compact.startsWith("LVRESET") || compact.startsWith("ELM327V") || compact.startsWith("ELM327")
    }

    private fun classify(lines: List<String>): Reply {
        val compact = lines.map { it.filterNot(Char::isWhitespace).uppercase() }
        // A reply is the 41 00 of a unit: hex only (with a header in front), whatever the header's width.
        if (compact.any { line -> line.length >= 6 && line.all { it in '0'..'9' || it in 'A'..'F' } && line.contains("4100") }) return Reply.ANSWER
        // 7F 01 <code>: a unit is there and says no.
        if (compact.any { line -> line.length >= 6 && line.all { it in '0'..'9' || it in 'A'..'F' } && line.contains("7F01") }) return Reply.REFUSED
        if (compact.any {
                it.startsWith("UNABLETOCONNECT") ||
                    it.startsWith("CANERROR") ||
                    it.startsWith("BUSERROR") ||
                    it.contains("BUSINIT:ERR") ||
                    it.contains("BUSINIT:...ERR") ||
                    it.startsWith("BUSBUSY") ||
                    it.startsWith("FBERROR") ||
                    it.startsWith("DATAERROR") ||
                    it.startsWith("ERR")
            }
        ) {
            return Reply.BUS_FAULT
        }
        if (compact.any { it.startsWith("NODATA") }) return Reply.NO_DATA
        return Reply.OTHER
    }

    private suspend fun restoreTimer() {
        if (!timerLengthened) return
        for (command in timerRestore) {
            try {
                driver.command(command)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The probe sets its timing again; a failure here only costs that.
            }
        }
        timerLengthened = false
    }

    private fun summary(result: Negotiation): String {
        val tried = result.attempts.joinToString("; ") { "${it.step}: ${it.outcome} (${it.millis} ms)" }
        return when (result) {
            is Negotiation.Found -> {
                val name = result.protocol?.title ?: "an unnamed protocol"
                "$name (ATDPN ${result.dpn ?: "unanswered"}) after ${result.elapsedMs} ms; $tried"
            }
            is Negotiation.NotFound -> "after ${result.elapsedMs} ms, ATDPN ${result.dpn ?: "unanswered"}; $tried"
        }
    }

    private suspend fun log(detail: String) {
        try {
            events.emit(SessionEvents.PROTOCOL, detail)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A broken log must not stop the negotiation.
        }
    }

    private companion object {
        const val MAX_LOGGED = 40
        val MIN_TRY = 1.seconds
    }
}
