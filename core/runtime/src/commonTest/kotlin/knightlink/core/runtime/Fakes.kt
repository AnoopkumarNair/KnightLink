package knightlink.core.runtime

import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.transport.TransportKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A scripted adapter: [respond] maps a command to the text the adapter answers with, or null to stay silent.
 * Counts how often it was closed, so tests can assert a stream is closed exactly once.
 */
internal class FakeStream(private val respond: (String) -> String?) : ByteStream {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    val written = ArrayList<String>()
    var closes = 0
        private set

    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        val command = bytes.decodeToString().trimEnd('\r')
        written += command
        val body = respond(command) ?: return
        channel.send("$body\r\r>".encodeToByteArray())
    }

    override suspend fun close() {
        closes++
        channel.close()
    }

    /** Ends [incoming] the way a dropped link does. */
    fun end(cause: Throwable? = null) {
        channel.close(cause)
    }
}

/** A transport whose every open is decided by [opener], which gets the 1-based number of the call. */
internal class FakeTransport(private val opener: suspend (opens: Int) -> ByteStream) : Transport {
    override val kind: TransportKind = TransportKind.SIMULATED
    var opens = 0
        private set

    override suspend fun open(): ByteStream {
        opens++
        return opener(opens)
    }
}

/** An adapter that accepts every setting but whose car does not answer, which is what a car with the ignition off looks like. */
internal fun silentCar(command: String): String? = when {
    command == "ATZ" || command == "ATI" -> "ELM327 v2.3"
    command == "AT@1" -> "OBDII to RS232 Interpreter"
    command == "ATRV" -> "12.1V"
    command == "ATDPN" -> "A6"
    command.startsWith("AT") -> "OK"
    else -> "NO DATA"
}

/** An adapter that rejects everything it is sent. */
internal fun rejectingAdapter(@Suppress("UNUSED_PARAMETER") command: String): String? = "?"

/** An adapter that never answers. */
internal fun deadAdapter(@Suppress("UNUSED_PARAMETER") command: String): String? = null

internal fun testCatalog(): SignalCatalog = SignalCatalog.of(
    listOf(
        DefinitionJson.parse(
            """
            {"schemaVersion":1,"id":"car","kind":"vehicle","title":"Car",
             "ecus":[
               {"id":"ecm","name":"ECM","requestId":"7E0","responseId":"7E8"},
               {"id":"tcm","name":"TCM","requestId":"7E1","responseId":"7E9"},
               {"id":"rdcm","name":"RDCM","requestId":"7E4","responseId":"7EC"}],
             "parameters":[
               {"service":"01","pid":"04","provenance":"standard","signals":[{"id":"engine.load_pct","formula":"A/2.55","unit":"percent"}]},
               {"service":"01","pid":"05","provenance":"standard","signals":[{"id":"engine.coolant_temp","formula":"A-40","unit":"degC"}]},
               {"service":"01","pid":"0C","provenance":"standard","signals":[{"id":"engine.rpm","formula":"(256*A+B)/4","unit":"rpm"}]},
               {"service":"01","pid":"8B","ecu":"ecm","provenance":"verified","signals":[{"id":"dpf.soot_pct","formula":"C/2.55","unit":"percent"}]},
               {"service":"01","pid":"9B","ecu":"rdcm","provenance":"verified","signals":[{"id":"def.level_pct","formula":"D/2.55","unit":"percent"}]}]}
            """,
        ),
    ),
)

/** A state change with the virtual time (ms) at which it happened. */
internal data class Timed(val atMs: Long, val state: SessionState)

/**
 * Records every state of [session], in order and with its virtual time. Collects on an unconfined dispatcher so that
 * states set back to back, with no suspension between them, are not conflated by the StateFlow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.recordStates(session: VehicleSession): List<Timed> {
    val log = ArrayList<Timed>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { log += Timed(currentTime, it) } }
    return log
}

/**
 * Lets virtual time run for [duration]. Not advanceUntilIdle(): that returns as soon as only background work is left,
 * and the sessions in these tests live in backgroundScope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle(duration: Duration = 1.minutes) {
    testScheduler.advanceTimeBy(duration)
    testScheduler.runCurrent()
}
