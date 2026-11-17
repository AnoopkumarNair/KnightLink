package knightlink.core.adapter

import knightlink.core.common.hexToBytes
import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmStatus
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.transport.ByteStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A fake adapter: replies from a table, delivered in small chunks like BLE notifications. */
private class ScriptedAdapter(
    private val replies: Map<String, String?>,
    private val echo: Boolean = false,
    private val chunkSize: Int = 5,
    /** Ends the stream right after the first reply, like an adapter that drops the link as it answers. */
    private val endAfterReply: Boolean = false,
) : ByteStream {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    val written = ArrayList<String>()

    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        val command = bytes.decodeToString().trimEnd('\r')
        written += command
        if (command in replies && replies[command] == null) return // adapter stays silent
        val body = replies[command] ?: "?"
        val full = (if (echo) "$command\r" else "") + "$body\r\r>"
        full.encodeToByteArray().asList().chunked(chunkSize).forEach { channel.send(it.toByteArray()) }
        if (endAfterReply) channel.close()
    }

    override suspend fun close() {
        channel.close()
    }

    /** Ends [incoming] the way a dropped link does: normally, or with [cause]. */
    fun endStream(cause: Throwable? = null) {
        channel.close(cause)
    }
}

class Elm327DriverTest {
    private val srsInit = listOf("ATD", "ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP6", "ATAT1", "ATST32")
    private val initReplies = srsInit.associateWith<String, String?> { if (it == "ATZ") "\r\rELM327 v2.3" else "OK" }

    @Test
    fun runsInitScriptAndReportsBanner() = runTest {
        val adapter = ScriptedAdapter(initReplies, echo = true)
        val driver = Elm327Driver(adapter, backgroundScope)
        assertEquals(AdapterIdentity("ELM327 v2.3"), driver.initialize(srsInit))
        // The warm-up: the echo switched off twice before the script (never a bare carriage return, which repeats the last command).
        assertEquals(listOf("ATE0", "ATE0") + srsInit, adapter.written)
    }

    @Test
    fun initFailsWhenAdapterRejectsAnEssentialCommand() = runTest {
        val driver = Elm327Driver(ScriptedAdapter(initReplies - "ATH1"), backgroundScope)
        assertTrue(assertFailsWith<AdapterException> { driver.initialize(srsInit) }.message!!.contains("ATH1"))
    }

    @Test
    fun setsHeaderOnlyWhenTheTargetEcuChanges() = runTest {
        val adapter = ScriptedAdapter(
            mapOf(
                "ATSH7E0" to "OK",
                "ATSH7E4" to "OK",
                "010C1" to "7E804410C0C7C",
                "01051" to "7E80341056B",
                "019B1" to "7EC06419B0E833EF4",
            ),
        )
        val driver = Elm327Driver(adapter, backgroundScope)
        driver.request(0x7E0, "010C".hexToBytes(), expectedResponses = 1)
        driver.request(0x7E0, "0105".hexToBytes(), expectedResponses = 1)
        val def = driver.request(0x7E4, "019B".hexToBytes(), expectedResponses = 1)
        assertEquals(listOf("ATSH7E0", "010C1", "01051", "ATSH7E4", "019B1"), adapter.written)
        assertEquals(0x7EC, def.messages.single().sourceId)
    }

    @Test
    fun timesOutWhenAdapterIsSilentThenRecovers() = runTest {
        val adapter = ScriptedAdapter(mapOf("ATSH7E0" to "OK", "0105" to null, "010D" to "7E803410D28"))
        val driver = Elm327Driver(adapter, backgroundScope)
        assertFailsWith<AdapterTimeoutException> { driver.request(0x7E0, "0105".hexToBytes()) }
        assertIs<DiagnosticResponse.Positive>(driver.request(0x7E0, "010D".hexToBytes()).messages.single())
    }

    @Test
    fun noDataIsAStatusNotAnError() = runTest {
        val driver = Elm327Driver(ScriptedAdapter(mapOf("ATSH7E1" to "OK", "0105" to "NO DATA")), backgroundScope)
        val response = driver.request(0x7E1, "0105".hexToBytes())
        assertEquals(listOf(ElmStatus.NO_DATA), response.statuses)
        assertTrue(response.messages.isEmpty())
    }

    /** Raw adapter bytes in, engineering values out: the full path the app relies on. */
    @Test
    fun endToEndDpfReadFromSrsCapture() = runTest {
        val catalog = SignalCatalog.of(
            listOf(
                DefinitionJson.parse(
                    """
                    {"schemaVersion":1,"id":"car","kind":"vehicle","title":"Car",
                     "ecus":[{"id":"ecm","name":"ECM","requestId":"7E0","responseId":"7E8"}],
                     "parameters":[{"service":"01","pid":"8B","ecu":"ecm","provenance":"verified","signals":[
                       {"id":"dpf.regen_status","formula":"B","unit":"enum"},
                       {"id":"dpf.soot_pct","formula":"C/2.55","unit":"percent"}]}]}
                    """,
                ),
            ),
        )
        val adapter = ScriptedAdapter(
            mapOf("ATSH7E0" to "OK", "018B1" to "7E81009418B7300E300\r7E821EF00B30000"),
            chunkSize = 3,
        )
        val driver = Elm327Driver(adapter, backgroundScope)
        val ecm = catalog.ecus.single()

        val message = assertIs<DiagnosticResponse.Positive>(
            driver.request(ecm.requestCanId, "018B".hexToBytes(), expectedResponses = 1).messages.single(),
        )
        val pid = message.data.copyOfRange(0, 1).toHex()
        val values = catalog.find(message.service, pid, catalog.ecuByResponseId(message.sourceId)?.id)!!
            .decode(message.data.copyOfRange(1, message.data.size))
            .associate { it.id to it.value }

        assertEquals(0.0, values["dpf.regen_status"])
        assertEquals(89.02, values.getValue("dpf.soot_pct"), 0.005)
    }
}

/** What the driver does when the link under it ends: callers must learn at once, not after a timeout. */
@OptIn(ExperimentalCoroutinesApi::class)
class Elm327DriverDisconnectTest {
    private val silent = mapOf<String, String?>("0105" to null, "01051" to null)

    @Test
    fun aCommandWaitingForAReplyFailsAtOnceWhenTheStreamCompletes() = runTest {
        val adapter = ScriptedAdapter(mapOf("ATSH7E0" to "OK") + silent)
        val driver = Elm327Driver(adapter, backgroundScope)

        val pending = async { runCatching { driver.request(0x7E0, "0105".hexToBytes(), expectedResponses = 1) } }
        runCurrent()
        adapter.endStream()

        val error = assertIs<AdapterDisconnectedException>(pending.await().exceptionOrNull())
        // The default timeout is 3 s; failing "at once" means no virtual time passed at all.
        assertEquals(0L, currentTime)
    }

    @Test
    fun aStreamFailureFailsTheWaitingCommandAndCarriesTheCause() = runTest {
        val adapter = ScriptedAdapter(silent)
        val driver = Elm327Driver(adapter, backgroundScope)
        val reset = IllegalStateException("link reset by peer")

        val pending = async { runCatching { driver.command("0105") } }
        runCurrent()
        adapter.endStream(reset)

        val error = assertIs<AdapterDisconnectedException>(pending.await().exceptionOrNull())
        assertTrue(error.message!!.contains("link reset by peer"), error.message)
        assertEquals("link reset by peer", error.cause?.message)
        assertEquals(0L, currentTime)
    }

    @Test
    fun aCleanEndIsReportedAsTheAdapterClosingTheConnection() = runTest {
        val adapter = ScriptedAdapter(silent)
        val driver = Elm327Driver(adapter, backgroundScope)
        val pending = async { runCatching { driver.command("0105") } }
        runCurrent()
        adapter.endStream()
        val error = assertIs<AdapterDisconnectedException>(pending.await().exceptionOrNull())
        assertTrue(error.message!!.contains("closed"), error.message)
        assertEquals(null, error.cause)
    }

    @Test
    fun everyLaterCallFailsAtOnceAndNothingMoreIsWritten() = runTest {
        val adapter = ScriptedAdapter(mapOf("ATZ" to "ELM327 v2.3"))
        val driver = Elm327Driver(adapter, backgroundScope)
        adapter.endStream()
        runCurrent()

        assertFailsWith<AdapterDisconnectedException> { driver.command("ATZ") }
        assertFailsWith<AdapterDisconnectedException> { driver.request(0x7E0, "0105".hexToBytes(), expectedResponses = 1) }
        assertFailsWith<AdapterDisconnectedException> { driver.initialize(listOf("ATZ")) }
        assertEquals(emptyList(), adapter.written)
        assertEquals(0L, currentTime)
    }

    @Test
    fun callsQueuedBehindTheFailingOneFailToo() = runTest {
        val adapter = ScriptedAdapter(silent)
        val driver = Elm327Driver(adapter, backgroundScope)
        val first = async { runCatching { driver.command("0105") } }
        val second = async { runCatching { driver.command("0105") } }
        runCurrent()
        adapter.endStream()
        assertIs<AdapterDisconnectedException>(first.await().exceptionOrNull())
        assertIs<AdapterDisconnectedException>(second.await().exceptionOrNull())
        assertEquals(listOf("0105"), adapter.written)
        assertEquals(0L, currentTime)
    }

    @Test
    fun aReplyThatArrivedBeforeTheStreamEndedIsStillDelivered() = runTest {
        val adapter = ScriptedAdapter(mapOf("ATI" to "ELM327 v2.3"), endAfterReply = true)
        val driver = Elm327Driver(adapter, backgroundScope)
        assertEquals(listOf("ELM327 v2.3"), driver.command("ATI"))
        assertFailsWith<AdapterDisconnectedException> { driver.command("ATI") }
    }

    @Test
    fun stoppingTheDriverFailsCallsInsteadOfLeavingThemToTimeOut() = runTest {
        val adapter = ScriptedAdapter(silent)
        val driver = Elm327Driver(adapter, backgroundScope)
        val pending = async { runCatching { driver.command("0105") } }
        runCurrent()
        driver.stop()
        assertIs<AdapterDisconnectedException>(pending.await().exceptionOrNull())
        assertFailsWith<AdapterDisconnectedException> { driver.command("ATI") }
        assertEquals(0L, currentTime)
    }

    @Test
    fun aTimeoutIsStillATimeoutWhileTheStreamIsAlive() = runTest {
        val driver = Elm327Driver(ScriptedAdapter(silent), backgroundScope)
        assertFailsWith<AdapterTimeoutException> { driver.command("0105") }
        assertEquals(3_000L, currentTime)
    }
}
