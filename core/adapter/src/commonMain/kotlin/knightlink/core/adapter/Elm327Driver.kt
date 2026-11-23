package knightlink.core.adapter

import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticLink
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmLine
import knightlink.core.protocol.ElmLineParser
import knightlink.core.protocol.ElmResponse
import knightlink.core.protocol.ElmResponseDecoder
import knightlink.core.protocol.ElmStatus
import knightlink.core.protocol.ElmStreamFramer
import knightlink.core.transport.ByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

public open class AdapterException(message: String, cause: Throwable? = null) : Exception(message, cause)

public class AdapterTimeoutException(command: String, timeout: Duration) :
    AdapterException("No reply to '$command' within $timeout")

/**
 * The link to the adapter has ended: the stream completed or failed, or the driver was stopped. Every call
 * fails with this at once, instead of waiting out its timeout for a reply that can no longer arrive.
 *
 * @property reason what happened to the link, in words
 * @param cause the error that ended the stream, when it failed rather than completed
 */
public class AdapterDisconnectedException(public val reason: String, cause: Throwable? = null) :
    AdapterException("Adapter disconnected: $reason", cause)

/**
 * The adapter reset itself (typically a voltage dip while the engine was cranked) and, after the driver had initialised it
 * again, reset once more during the retried request. Whoever polls should treat the adapter as unreliable for now.
 */
public class AdapterResetException(public val hint: String) :
    AdapterException("The adapter reset itself again right after it was initialised again ($hint)")

/**
 * What the adapter said about itself during initialisation.
 *
 * @property banner the first reply line that mentions the adapter's chip (the answer to the reset command)
 * @property unsupportedCommands the script commands the adapter answered with `?`; they were skipped
 */
public data class AdapterIdentity(val banner: String?, val unsupportedCommands: List<String> = emptyList())

/** Counters of what the driver had to work around. Everything is counted from the creation of the driver. */
public data class DriverStats(
    /** Resets of the adapter noticed during requests (each followed by a re-initialisation). */
    val adapterResets: Int = 0,
    /** Initialisations after the first one, whatever the reason. */
    val reinits: Int = 0,
    val bufferFull: Int = 0,
    /** Replies that said the vehicle bus could not be reached. */
    val busFaults: Int = 0,
    /** Waits that were extended because the adapter was still searching for the protocol. */
    val searchingWaits: Int = 0,
    /** Commands sent a second time because the adapter had said STOPPED. */
    val resends: Int = 0,
    /** Requests whose first answer was "response pending" (7F xx 78) and which were kept waiting for the final answer. */
    val pendingWaits: Int = 0,
    /** Requests whose final answer never came within the pending limits (so the pending answer stands). */
    val pendingGaveUp: Int = 0,
)

/**
 * Talks to an ELM327-compatible adapter over any [ByteStream].
 *
 * The adapter is strictly half-duplex: one command, then wait for the `>` prompt. All access goes
 * through a single lock, so callers on different coroutines can never interleave commands.
 *
 * Cheap and clone adapters misbehave in ways the real chip does not, so the driver is tolerant on purpose (all of it
 * tunable through [Config], which the adapter definition fills in):
 *  - initialisation starts with a warm-up that flushes whatever the adapter is still saying, and an unknown `?` command is
 *    skipped unless it is essential;
 *  - the reader's partial line is dropped at the start of every exchange, so noise from a previous command cannot corrupt
 *    the next reply;
 *  - after a reset command the driver waits for the adapter to settle;
 *  - a command that is answered with `STOPPED` is sent once more;
 *  - while the adapter is searching for the vehicle's protocol the wait is longer;
 *  - an adapter that reset itself mid-session (echo is back, the banner appears, `LV RESET`, or replies without headers) is
 *    initialised again on the same link and the request is retried once.
 *
 * @param scope owns the reader coroutine; cancel it to stop the driver.
 * @param events receives what happened (init steps, resets, re-inits, bus faults, counters); never a VIN or an address.
 */
public class Elm327Driver(
    private val stream: ByteStream,
    scope: CoroutineScope,
    private val extendedIds: Boolean = false,
    private val config: Config = Config(),
    private val events: SessionEventSink = SessionEventSink.None,
) : DiagnosticLink {
    /**
     * How the driver treats the adapter. The defaults suit a generic ELM327; an adapter definition overrides them.
     *
     * @property commandTimeout how long one command may take
     * @property resetTimeout how long the reset command may take (the adapter prints its banner after a pause)
     * @property searchingTimeout how long one exchange may take once the adapter has said it is searching for the protocol
     * @property warmUp whether initialisation starts with two echo-off commands whose replies are discarded
     * @property warmUpDelay the pause between the warm-up commands
     * @property postResetDelay the pause after the reset command's reply, before the next command
     * @property stoppedRetryDelay the pause before a command that was answered with STOPPED is sent again
     * @property essentialCommands command prefixes (upper case, no spaces) whose rejection with `?` fails initialisation; every
     *   other rejected command is recorded as unsupported and skipped
     * @property detectResets whether replies are checked for signs that the adapter reset itself
     * @property minRequestInterval the shortest time between the starts of two diagnostic requests: a hard ceiling on the load
     *   KnightLink puts on the vehicle bus (20 ms = at most 50 requests a second), whatever the scheduler asks for. One request
     *   is in flight at a time anyway, so a real ELM327 over Bluetooth stays well below it.
     * @property slowReply a reply that took longer than this is written to the log (with the request, never a reply's content)
     * @property pendingTimeout how long, in all, a request whose answer is "response pending" (negative response code 0x78) waits for the final
     *   answer, which comes without a new request
     * @property pendingRounds how many further replies one request reads while its answer stays "response pending"
     * @property timeSource the clock of [minRequestInterval]
     */
    public data class Config(
        val commandTimeout: Duration = 3.seconds,
        val resetTimeout: Duration = 6.seconds,
        val searchingTimeout: Duration = 8.seconds,
        val warmUp: Boolean = true,
        val warmUpDelay: Duration = 300.milliseconds,
        val postResetDelay: Duration = 500.milliseconds,
        val stoppedRetryDelay: Duration = 200.milliseconds,
        val essentialCommands: List<String> = listOf("ATE0", "ATH1", "ATSP"),
        val detectResets: Boolean = true,
        val minRequestInterval: Duration = DEFAULT_MIN_REQUEST_INTERVAL,
        val slowReply: Duration = 2.seconds,
        val pendingTimeout: Duration = 5.seconds,
        val pendingRounds: Int = 3,
        val timeSource: TimeSource = TimeSource.Monotonic,
    ) {
        init {
            require(commandTimeout > Duration.ZERO) { "commandTimeout must be positive" }
            require(resetTimeout > Duration.ZERO) { "resetTimeout must be positive" }
            require(searchingTimeout > Duration.ZERO) { "searchingTimeout must be positive" }
            require(pendingTimeout > Duration.ZERO) { "pendingTimeout must be positive" }
            require(pendingRounds >= 0) { "pendingRounds must not be negative" }
            require(minRequestInterval >= MIN_REQUEST_INTERVAL_FLOOR) { "minRequestInterval must be at least $MIN_REQUEST_INTERVAL_FLOOR" }
        }

        public companion object {
            public val DEFAULT_MIN_REQUEST_INTERVAL: Duration = 20.milliseconds

            /** No configuration may lift the ceiling above 100 requests a second. */
            public val MIN_REQUEST_INTERVAL_FLOOR: Duration = 10.milliseconds
        }
    }

    private var lastRequestStart: TimeMark? = null
    private var slowReplies = 0

    private val framer = ElmStreamFramer()
    private val framerLock = Mutex()
    private val decoder = ElmResponseDecoder(extendedIds)
    private val parser = ElmLineParser(extendedIds)
    private val responses = Channel<List<String>>(Channel.UNLIMITED)
    private val lock = Mutex()
    private var currentHeader: Int? = null

    // What the init script switched on, so that a reply can be recognised as coming from an adapter that has forgotten it.
    private var activeScript: List<String>? = null
    private var echoIsOff = false
    private var headersAreOn = false
    private var initialised = false
    private val unsupported = LinkedHashSet<String>()

    /** An adapter banner or `LV RESET` arrived between two exchanges, which only a reset explains. Cleared by every initialisation. */
    private var unsolicitedReset = false

    @Volatile
    private var counters = DriverStats()

    /** Set once, when the stream ends for any reason. Read under [lock], written by the reader or its completion handler. */
    @Volatile
    private var disconnection: AdapterDisconnectedException? = null

    private val reader: Job = scope.launch {
        try {
            stream.incoming.collect { chunk ->
                // Feeding and queueing under one lock: an exchange that clears the framer and the queue sees neither half of a reply.
                framerLock.withLock { framer.feed(chunk).forEach { responses.send(it) } }
            }
            disconnect(AdapterDisconnectedException("the adapter closed the connection"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not rethrown: an uncaught exception here would cancel the caller's whole scope, and the session
            // layer needs to survive a dropped link to reconnect.
            disconnect(AdapterDisconnectedException("the connection failed: ${e.message ?: e::class.simpleName}", e))
        }
    }

    init {
        // Also covers cancellation of the scope (stop(), or the session tearing the connection down): without it, a
        // call still waiting for a reply would run into its timeout. A no-op when the reader already reported its end.
        reader.invokeOnCompletion { disconnect(AdapterDisconnectedException("the driver was stopped")) }
    }

    /** The commands of the init scripts that the adapter rejected as unknown and that were therefore skipped, in the order met. */
    public val unsupportedCommands: List<String> get() = unsupported.toList()

    /** What the driver has had to work around so far. */
    public val stats: DriverStats get() = counters

    override suspend fun command(text: String): List<String> = command(text, config.commandTimeout)

    /** Sends one command and returns the reply lines, without the echo. */
    public suspend fun command(text: String, timeout: Duration): List<String> =
        lock.withLock {
            val reply = exchange(text, timeout)
            // A raw command that moves the header (or resets the adapter) makes the remembered header wrong: the next request sets its own.
            val compact = text.compact()
            if (compact.startsWith("ATSH") || compact == "ATD" || compact.startsWith("ATZ") || compact == "ATWS") currentHeader = null
            reply.lines
        }

    /**
     * Runs the adapter's initialisation script (from its definition file), after the warm-up if [Config.warmUp] is set. The
     * script is remembered: [reinitialize] and the automatic re-initialisation after a reset run it again.
     *
     * A command the adapter answers with `?` is skipped and listed in [unsupportedCommands] unless it is one of
     * [Config.essentialCommands].
     *
     * @throws AdapterException if the adapter rejects an essential command or does not answer.
     */
    public suspend fun initialize(script: List<String>): AdapterIdentity = lock.withLock {
        note(SessionEvents.INIT_START, "${script.size} commands")
        runInit(script)
    }

    /**
     * Initialises the adapter again with the script given to [initialize] (or [applyScript] after it), on the same link.
     * Forgets the selected header. [reason] goes into the log.
     *
     * @throws IllegalStateException if the driver was never initialised.
     */
    public suspend fun reinitialize(reason: String): AdapterIdentity = lock.withLock { reinitLocked(reason) }

    /**
     * Sends [send] with the tolerance rules of [initialize] and makes [script] the script that a re-initialisation runs from
     * now on. The session uses it to move from the probing configuration to the polling one without a reset.
     */
    public suspend fun applyScript(script: List<String>, send: List<String>): Unit = lock.withLock {
        for (step in send) runStep(step)
        activeScript = script
        expect(script)
    }

    /**
     * Sends a diagnostic request to one ECU by its physical request identifier.
     *
     * If the reply shows that the adapter reset itself, the adapter is initialised again and the request is sent once more.
     *
     * @param expectedResponses when set, tells the adapter to stop waiting after that many replies,
     *   which removes the adapter's timeout from every poll.
     * @throws AdapterResetException when the adapter resets again right after being initialised again.
     */
    public suspend fun request(
        requestId: Int,
        payload: ByteArray,
        expectedResponses: Int?,
        timeout: Duration,
    ): ElmResponse = lock.withLock {
        ReadOnlyPolicy.requestIdViolation(requestId)?.let { reason ->
            note(SessionEvents.INIT_STEP, "refused (read-only): $reason")
            throw ReadOnlyViolationException("Refused to send a request: $reason")
        }
        require(!extendedIds) { "29-bit header selection is not implemented yet" }
        require(expectedResponses == null || expectedResponses in 1..15) { "expectedResponses must be 1..15" }
        val first = attempt(requestId, payload, expectedResponses, timeout)
        if (first.response != null) return@withLock first.response
        val hint = checkNotNull(first.resetHint)
        counters = counters.copy(adapterResets = counters.adapterResets + 1)
        note(SessionEvents.ADAPTER_RESET, hint)
        reinitLocked("the adapter reset itself: $hint")
        val second = attempt(requestId, payload, expectedResponses, timeout)
        second.response ?: throw AdapterResetException(checkNotNull(second.resetHint))
    }

    override suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int?): ElmResponse =
        request(requestId, payload, expectedResponses, config.commandTimeout)

    /**
     * Sends one adapter command on the passive capture path, which [ReadOnlyPolicy.passiveViolation] gates (much narrower than for
     * [command]). Used to set up and to restore the adapter around [monitor]. A reply of `STOPPED` (the adapter was still waiting for the
     * car when the command's first byte interrupted it) earns one resend, as for every command.
     */
    public suspend fun passiveCommand(text: String): List<String> =
        lock.withLock { exchange(text, config.commandTimeout, passive = true).lines }

    /**
     * Listens to the bus without sending anything: `ATMA`, then, after [listenFor] (or when [until] completes, or when the adapter ends the
     * monitoring itself), the stop character, then the wait for the prompt. Returns every line the adapter printed meanwhile, frames and
     * status words alike, for [knightlink.core.runtime.PassiveCapture] to read. The adapter must have been set up with `ATCSM1` first.
     *
     * Cancelling stops the monitoring too (the adapter is not left streaming), as far as the link still works. Every wait has a limit: the
     * stop character is sent twice, [stopTimeout] each, before giving up.
     *
     * @throws AdapterTimeoutException when the adapter does not come back to its prompt after the stop character
     * @throws AdapterDisconnectedException when the link ends
     */
    public suspend fun monitor(listenFor: Duration, stopTimeout: Duration, until: Deferred<*>? = null): List<String> = lock.withLock {
        ReadOnlyPolicy.monitorViolation(MONITOR_COMMAND)?.let { reason -> throw ReadOnlyViolationException("Refused to send a command: $reason") }
        disconnection?.let { throw it }
        discardStale()
        // The adapter forgets nothing it was told, but the next request must not rely on the header it had before monitoring.
        currentHeader = null
        stream.write((MONITOR_COMMAND + "\r").encodeToByteArray())
        val early = try {
            withTimeoutOrNull(listenFor) {
                select<ChannelResult<List<String>>?> {
                    responses.onReceiveCatching { it }
                    until?.onAwait { null }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                try {
                    haltMonitoring(stopTimeout)
                } catch (_: Exception) {
                    // The link may be gone; there is nothing left to stop.
                }
            }
            throw e
        }
        // The adapter ended the monitoring by itself (an error status, say): its prompt is already here and nothing needs stopping.
        if (early != null) return@withLock early.getOrNull() ?: throw checkNotNull(disconnection) { "responses closed without a reason" }
        haltMonitoring(stopTimeout)
    }

    private suspend fun haltMonitoring(stopTimeout: Duration): List<String> {
        repeat(STOP_ATTEMPTS) {
            stream.write(ReadOnlyPolicy.MONITOR_STOP.encodeToByteArray())
            val result = withTimeoutOrNull(stopTimeout) { responses.receiveCatching() }
            if (result != null) return result.getOrNull() ?: throw checkNotNull(disconnection) { "responses closed without a reason" }
        }
        throw AdapterTimeoutException(MONITOR_COMMAND, stopTimeout * STOP_ATTEMPTS)
    }

    public fun stop() {
        reader.cancel()
    }

    private fun disconnect(reason: AdapterDisconnectedException) {
        if (disconnection != null) return
        // The reason is published before the channel is closed, so a receiver that sees the close also sees the reason.
        disconnection = reason
        responses.close()
    }

    // Initialisation -----------------------------------------------------------------------------------------

    private suspend fun reinitLocked(reason: String): AdapterIdentity {
        checkNotNull(activeScript) { "The adapter was never initialised" }
        counters = counters.copy(reinits = counters.reinits + 1)
        note(SessionEvents.REINIT, reason)
        return runInit(activeScript!!)
    }

    private suspend fun runInit(script: List<String>): AdapterIdentity {
        currentHeader = null
        if (config.warmUp) warmUp()
        var banner: String? = null
        val skipped = ArrayList<String>()
        var echoWasSwitchedOff = false
        var echoPersists = false
        for (step in script) {
            val outcome = runStep(step)
            if (outcome.unsupported) skipped += step
            outcome.banner?.let { banner = it }
            if (step.compact() == "ATE0") {
                echoWasSwitchedOff = true
            } else if (echoWasSwitchedOff && outcome.echoed) {
                echoPersists = true
            }
        }
        activeScript = script
        expect(script)
        // An adapter that says OK to ATE0 and echoes anyway (some clones) must not be taken for a reset on every command.
        if (echoPersists) echoIsOff = false
        initialised = true
        // Whatever the adapter said while it was being set up (a late banner) is not a sign of a reset during polling.
        unsolicitedReset = false
        note(SessionEvents.INIT_END, if (skipped.isEmpty()) "ok" else "ok, unsupported: ${skipped.joinToString(" ")}")
        return AdapterIdentity(banner, unsupported.toList())
    }

    /**
     * The echo switched off twice. The replies are worthless; the point is to drain and to quiet the adapter.
     *
     * Deliberately no bare carriage return: an ELM327 answers one by repeating its previous command, and on a fresh link that
     * command was sent by whoever used the adapter last (another app), so it could be anything, including a write. KnightLink
     * is read-only towards the vehicle and must never replay a command it did not choose.
     */
    private suspend fun warmUp() {
        val steps = listOf("ATE0", "ATE0")
        for ((index, step) in steps.withIndex()) {
            if (index > 0) delay(config.warmUpDelay)
            try {
                exchange(step, minOf(config.commandTimeout, WARM_UP_TIMEOUT))
            } catch (_: AdapterTimeoutException) {
                // An adapter that is still starting up may not answer yet; the script that follows will tell.
            }
        }
        note(SessionEvents.INIT_STEP, "warm-up done")
    }

    private class StepOutcome(val banner: String?, val unsupported: Boolean, val echoed: Boolean)

    private suspend fun runStep(step: String): StepOutcome {
        val isReset = step.compact() == "ATZ"
        val reply = exchange(step, if (isReset) config.resetTimeout else config.commandTimeout)
        val rejected = reply.lines.any { it.trim() == "?" }
        if (rejected && isEssential(step)) {
            note(SessionEvents.INIT_STEP, "$step -> rejected, essential")
            throw AdapterException("Adapter rejected '$step'")
        }
        if (rejected) {
            unsupported += step
            note(SessionEvents.INIT_STEP, "$step -> not supported, skipped")
        } else {
            note(SessionEvents.INIT_STEP, "$step -> ${reply.lines.firstOrNull()?.take(MAX_LOGGED_REPLY).orEmpty()}")
        }
        // The adapter prints its banner after a reset and may not be ready for the next command at once.
        if (isReset) delay(config.postResetDelay)
        return StepOutcome(reply.lines.firstOrNull { it.contains("ELM", ignoreCase = true) }, rejected, reply.echoed)
    }

    private fun isEssential(step: String): Boolean {
        val key = step.compact()
        return config.essentialCommands.any { key.startsWith(it.compact()) }
    }

    /** Remembers what the script set up, so a later reply can be told apart from one by an adapter that lost those settings. */
    private fun expect(script: List<String>) {
        var echoOff = false
        var headersOn = false
        for (step in script) {
            when (step.compact()) {
                "ATZ", "ATD" -> {
                    echoOff = false
                    headersOn = false
                }
                "ATE0" -> echoOff = true
                "ATE1" -> echoOff = false
                "ATH1" -> headersOn = true
                "ATH0" -> headersOn = false
            }
        }
        echoIsOff = echoOff
        headersAreOn = headersOn
    }

    // Requests -----------------------------------------------------------------------------------------------

    private class Attempt(val response: ElmResponse?, val resetHint: String?)

    private suspend fun attempt(requestId: Int, payload: ByteArray, expectedResponses: Int?, timeout: Duration): Attempt {
        // A session request always gets its header sent right before it, whatever was cached: it must reach exactly this unit.
        if (currentHeader != requestId || payload.firstOrNull() == SESSION_CONTROL_SERVICE) {
            val header = requestId.toString(16).uppercase().padStart(3, '0')
            val reply = exchange("ATSH$header", timeout)
            resetHint(reply, diagnostic = false)?.let { return Attempt(null, it) }
            if (reply.lines.none { it.compact() == "OK" }) throw AdapterException("Adapter refused header $header: ${reply.lines}")
            currentHeader = requestId
        }
        val suffix = expectedResponses?.toString(16)?.uppercase().orEmpty()
        lastRequestStart?.let { start ->
            val wait = config.minRequestInterval - start.elapsedNow()
            if (wait.isPositive()) delay(wait)
        }
        lastRequestStart = config.timeSource.markNow()
        val reply = exchange(payload.toHex() + suffix, timeout, requestId = requestId)
        resetHint(reply, diagnostic = true)?.let { return Attempt(null, it) }
        val response = settlePending(withoutSupersededPending(decoder.decode(reply.lines)), requestId)
        countStatuses(response, requestId)
        return Attempt(response, null)
    }

    private fun isPending(message: DiagnosticResponse): Boolean = message is DiagnosticResponse.Negative && message.code == PENDING_CODE

    /** An ECU that sent "response pending" and then its final answer in the same read gives only the final answer. */
    private fun withoutSupersededPending(response: ElmResponse): ElmResponse {
        val finalSources = response.messages.filterNot(::isPending).map { it.sourceId }.toSet()
        if (response.messages.none { isPending(it) && it.sourceId in finalSources }) return response
        return ElmResponse(
            response.messages.filterNot { isPending(it) && it.sourceId in finalSources },
            response.statuses,
            response.protocolErrors,
            response.text,
            response.respondingIds,
        )
    }

    /**
     * An answer that is only "response pending" (the ECU is busy and will answer by itself) is not an answer: the same request's further
     * replies are read, without sending it again (a resend would restart the ECU's work), until the final one or [Config.pendingTimeout]
     * or [Config.pendingRounds] more replies. When nothing final arrives the pending answer is returned as it was.
     */
    private suspend fun settlePending(first: ElmResponse, requestId: Int): ElmResponse {
        if (config.pendingRounds == 0 || first.messages.isEmpty() || !first.messages.all(::isPending)) return first
        counters = counters.copy(pendingWaits = counters.pendingWaits + 1)
        val id = requestId.toString(16).uppercase()
        var rounds = 0
        val settled = withTimeoutOrNull(config.pendingTimeout) {
            while (rounds < config.pendingRounds) {
                rounds++
                val next = responses.receiveCatching().getOrNull() ?: return@withTimeoutOrNull null
                val decoded = withoutSupersededPending(decoder.decode(next))
                if (decoded.messages.isNotEmpty() && !decoded.messages.all(::isPending)) return@withTimeoutOrNull decoded
            }
            null
        }
        if (settled != null) {
            if (shouldLog(counters.pendingWaits)) note(SessionEvents.RESPONSE_PENDING, "$id answered after response pending ($rounds extra replies)")
            return settled
        }
        counters = counters.copy(pendingGaveUp = counters.pendingGaveUp + 1)
        note(SessionEvents.RESPONSE_PENDING, "$id still pending after $rounds extra replies; giving up on the final answer")
        return first
    }

    /** A sign in [reply] that the adapter lost its settings since the init script ran, in words; null when there is none. */
    private fun resetHint(reply: Reply, diagnostic: Boolean): String? {
        if (!config.detectResets || !initialised) return null
        val unsolicited = unsolicitedReset
        unsolicitedReset = false
        if (unsolicited) return "the adapter printed its start-up banner between two commands"
        if (reply.echoed && echoIsOff) return "the command was echoed although echo is off"
        if (reply.lines.any { it.contains(BANNER, ignoreCase = true) }) return "the reply holds the adapter's start-up banner"
        if (reply.lines.any { isLowVoltageReset(it) }) return "the adapter reported LV RESET"
        if (diagnostic && headersAreOn && reply.lines.any(::isHeaderlessData)) return "the reply has no CAN headers although headers are on"
        return null
    }

    private fun isLowVoltageReset(line: String): Boolean = (parser.parse(line) as? ElmLine.Status)?.status == ElmStatus.LV_RESET

    /** Hex digits only, and not a frame (which needs headers): what an adapter with headers off prints for a reply. */
    private fun isHeaderlessData(line: String): Boolean {
        if (parser.parse(line) !is ElmLine.Text) return false
        val compact = line.compact()
        return compact.length >= 2 && compact.all { it in '0'..'9' || it in 'A'..'F' }
    }

    private suspend fun countStatuses(response: ElmResponse, requestId: Int) {
        val id = requestId.toString(16).uppercase()
        response.busFault?.let { fault ->
            val count = counters.busFaults + 1
            counters = counters.copy(busFaults = count)
            // Every one is counted, but a car with a dead bus would otherwise fill the log with one line per poll.
            if (shouldLog(count)) note(SessionEvents.BUS_FAULT, "${fault.name} answering $id (count $count)")
        }
        if (ElmStatus.BUFFER_FULL in response.statuses) {
            val count = counters.bufferFull + 1
            counters = counters.copy(bufferFull = count)
            if (shouldLog(count)) note(SessionEvents.BUFFER_FULL, "adapter buffer full (count $count)")
        }
    }

    private fun shouldLog(count: Int): Boolean = count <= LOG_FIRST || count % LOG_EVERY == 0

    // One exchange -------------------------------------------------------------------------------------------

    private class Reply(val lines: List<String>, val echoed: Boolean)

    /** One command and its reply, with the one resend that STOPPED earns. */
    private suspend fun exchange(text: String, timeout: Duration, passive: Boolean = false, requestId: Int? = null): Reply {
        val first = exchangeOnce(text, timeout, passive, requestId)
        if (first.lines.none(::isStopped)) return first
        counters = counters.copy(resends = counters.resends + 1)
        note(SessionEvents.RESEND, "'${text.trim()}' was answered with STOPPED; sending it again")
        delay(config.stoppedRetryDelay)
        return exchangeOnce(text, timeout, passive, requestId)
    }

    private fun isStopped(line: String): Boolean = (parser.parse(line) as? ElmLine.Status)?.status == ElmStatus.STOPPED

    private suspend fun exchangeOnce(text: String, timeout: Duration, passive: Boolean = false, requestId: Int? = null): Reply {
        // The writes to the adapter are here and in [monitor]'s stop, so this is where the read-only guarantee is enforced.
        checkAllowed(text, passive, requestId)
        disconnection?.let { throw it }
        discardStale()
        val asked = config.timeSource.markNow()
        stream.write((text + "\r").encodeToByteArray())
        val lines = awaitReply(text, timeout)
        val took = asked.elapsedNow()
        if (took >= config.slowReply && !text.compact().startsWith("AT")) {
            val count = ++slowReplies
            if (shouldLog(count)) note(SessionEvents.SLOW_REPLY, "'${text.trim()}' took ${took.inWholeMilliseconds} ms (count $count)")
        }
        val sent = text.compact()
        val echoed = sent.isNotEmpty() && lines.any { it.compact() == sent }
        return Reply(lines.filterNot { it.compact() == sent }, echoed)
    }

    private suspend fun checkAllowed(text: String, passive: Boolean, requestId: Int?) {
        (if (passive) ReadOnlyPolicy.passiveViolation(text) else ReadOnlyPolicy.violation(text, requestId))?.let { reason ->
            note(SessionEvents.INIT_STEP, "refused (read-only): $reason")
            throw ReadOnlyViolationException("Refused to send a command: $reason")
        }
    }

    private suspend fun discardStale() {
        framerLock.withLock {
            // A partial line belongs to a command that was given up on; anything queued is a reply nobody is waiting for.
            framer.reset()
            while (true) {
                val stale = responses.tryReceive().getOrNull() ?: break
                if (stale.any { it.contains(BANNER, ignoreCase = true) || isLowVoltageReset(it) }) unsolicitedReset = true
            }
        }
    }

    private suspend fun awaitReply(text: String, timeout: Duration): List<String> {
        var waited = timeout
        var result = withTimeoutOrNull(timeout) { responses.receiveCatching() }
        if (result == null && config.searchingTimeout > timeout && isSearching()) {
            // The adapter is trying protocols one after the other, which takes seconds; the shorter wait would give up on a working car.
            val extra = config.searchingTimeout - timeout
            counters = counters.copy(searchingWaits = counters.searchingWaits + 1)
            note(SessionEvents.SEARCHING, "the adapter is searching for the protocol; waiting up to ${config.searchingTimeout} for '${text.trim()}'")
            waited = config.searchingTimeout
            result = withTimeoutOrNull(extra) { responses.receiveCatching() }
        }
        // receiveCatching, not receive: a closed channel is the stream ending, which is not an ordinary failure to wait out.
        val received = result ?: throw AdapterTimeoutException(text, waited)
        return received.getOrNull() ?: throw checkNotNull(disconnection) { "responses closed without a reason" }
    }

    private suspend fun isSearching(): Boolean = framerLock.withLock { framer.pending().contains(SEARCHING_WORD, ignoreCase = true) }

    private suspend fun note(name: String, detail: String) {
        try {
            events.emit(name, detail)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A broken log must not break the conversation with the adapter.
        }
    }

    private fun String.compact(): String = filterNot { it.isWhitespace() }.uppercase()

    private companion object {
        const val MONITOR_COMMAND = "ATMA"
        const val STOP_ATTEMPTS = 2
        const val PENDING_CODE = 0x78
        const val SESSION_CONTROL_SERVICE: Byte = 0x10
        const val BANNER = "ELM327"
        const val SEARCHING_WORD = "SEARCHING"
        const val MAX_LOGGED_REPLY = 40
        const val LOG_FIRST = 3
        const val LOG_EVERY = 25
        val WARM_UP_TIMEOUT: Duration = 1500.milliseconds
    }
}
