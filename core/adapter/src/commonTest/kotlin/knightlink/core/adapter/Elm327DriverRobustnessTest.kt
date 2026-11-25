package knightlink.core.adapter

import knightlink.core.common.SessionEvent
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.hexToBytes
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What the driver does to survive cheap and badly behaved adapters: warm-up, flushing, tolerance, retries, waiting, resets. */
@OptIn(ExperimentalCoroutinesApi::class)
class Elm327DriverRobustnessTest {
    private val script = listOf("ATZ", "ATE0", "ATL0", "ATH1", "ATSP6")

    private class Collected : SessionEventSink {
        val events = ArrayList<SessionEvent>()

        override suspend fun emit(event: SessionEvent) {
            events += event
        }

        fun of(name: String): List<String> = events.filter { it.name == name }.map { it.detail }
    }

    /**
     * A small adapter model: it keeps the settings the script gave it, forgets them on [dip], and answers an ELM327's way
     * (echo when echo is on, headers when headers are on). [glitch] can replace the reply to the next diagnostic request.
     */
    private class Model(private val now: () -> Long) {
        var echoOn = true
        var headersOn = false
        var glitch: ((echoOn: Boolean) -> String?)? = null
        var persistentGlitch = false

        /** The first this many commands get no answer at all. */
        var silentFirst = 0
        lateinit var adapter: FakeAdapter

        fun dip() {
            echoOn = true
            headersOn = false
        }

        fun build(): FakeAdapter = FakeAdapter(now) { command ->
            if (written.size <= silentFirst) return@FakeAdapter
            val key = command.filterNot { it.isWhitespace() }.uppercase()
            val echo = if (echoOn) listOf(command) else emptyList()
            when {
                key == "ATZ" -> {
                    dip()
                    reply("ELM327 v2.3")
                }
                key == "ATE0" -> {
                    reply(*(echo + "OK").toTypedArray())
                    echoOn = false
                }
                key == "ATH1" -> {
                    headersOn = true
                    reply(*(echo + "OK").toTypedArray())
                }
                key.startsWith("AT") -> reply(*(echo + "OK").toTypedArray())
                key.isEmpty() -> reply("?")
                else -> {
                    val replacement = glitch?.invoke(echoOn)
                    if (replacement != null) {
                        if (!persistentGlitch) glitch = null
                        emit(replacement)
                    } else {
                        reply(*(echo + if (headersOn) "7E804410C0C7C" else "41 0C 0C 7C").toTypedArray())
                    }
                }
            }
        }.also { adapter = it }
    }

    private fun TestScope.driverWith(
        model: Model,
        config: Elm327Driver.Config = Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO),
        events: SessionEventSink = SessionEventSink.None,
    ): Elm327Driver = Elm327Driver(model.build(), backgroundScope, config = config, events = events)

    private val rpmRequest = "010C".hexToBytes()

    // Warm-up --------------------------------------------------------------------------------------------------

    @Test
    fun initialisationStartsWithTwoEchoOffCommandsThreeHundredMillisecondsApart() = runTest {
        val model = Model { currentTime }
        val driver = driverWith(model, Elm327Driver.Config(postResetDelay = Duration.ZERO))
        driver.initialize(script)

        assertEquals(listOf("ATE0", "ATE0") + script, model.adapter.written)
        assertEquals(listOf(0L, 300L), model.adapter.writtenAt.take(2))
    }

    @Test
    fun theWarmUpCanBeSwitchedOff() = runTest {
        val model = Model { currentTime }
        driverWith(model, Elm327Driver.Config(warmUp = false)).initialize(script)
        assertEquals(script, model.adapter.written)
    }

    @Test
    fun warmUpTimeoutsAreIgnoredAndTheScriptThatFollowsRuns() = runTest {
        // Silent for the first two commands (the warm-up), like an adapter that is still starting up.
        val model = Model { currentTime }
        model.silentFirst = 2
        val driver = driverWith(model, Elm327Driver.Config(postResetDelay = Duration.ZERO))

        driver.initialize(script)

        assertEquals(listOf("ATE0", "ATE0") + script, model.adapter.written)
        assertTrue(currentTime >= 2 * 1_500, "each silent warm-up command was waited for, up to its own short timeout: $currentTime")
    }

    // Flushing -------------------------------------------------------------------------------------------------

    @Test
    fun aPartialLineLeftByEarlierNoiseDoesNotCorruptTheNextReply() = runTest {
        val model = Model { currentTime }
        model.echoOn = false
        model.headersOn = true
        val driver = driverWith(model)
        driver.initialize(script)
        // Leftovers of a reply that was never finished: they would be glued to the front of the next reply's first line.
        model.adapter.emit("41 0")
        testScheduler.runCurrent()

        val response = driver.request(0x7E0, rpmRequest, expectedResponses = 1)

        assertIs<DiagnosticResponse.Positive>(response.messages.single())
        assertTrue(response.text.isEmpty(), response.text.toString())
    }

    // Reset command --------------------------------------------------------------------------------------------

    @Test
    fun theDriverWaitsAfterTheResetCommandBeforeTheNextOne() = runTest {
        val model = Model { currentTime }
        driverWith(model, Elm327Driver.Config(warmUp = false, postResetDelay = 500.milliseconds)).initialize(script)
        val at = model.adapter.writtenAt
        assertEquals(500L, at[1] - at[0], "ATE0 follows ATZ after the settle pause")
        assertEquals(0L, at[2] - at[1])
    }

    // STOPPED --------------------------------------------------------------------------------------------------

    @Test
    fun aCommandAnsweredWithStoppedIsSentOnceMoreAfterAShortPause() = runTest {
        var calls = 0
        val adapter = FakeAdapter({ currentTime }) { command ->
            when (command) {
                "ATSH7E0" -> reply("OK")
                else -> if (++calls == 1) reply("STOPPED") else reply("7E804410C0C7C")
            }
        }
        val driver = Elm327Driver(adapter, backgroundScope)

        val response = driver.request(0x7E0, rpmRequest, expectedResponses = 1)

        assertIs<DiagnosticResponse.Positive>(response.messages.single())
        assertEquals(listOf("ATSH7E0", "010C1", "010C1"), adapter.written)
        assertEquals(200L, adapter.writtenAt[2] - adapter.writtenAt[1])
        assertEquals(1, driver.stats.resends)
    }

    @Test
    fun aCommandThatIsStoppedAgainIsNotSentAThirdTime() = runTest {
        val adapter = FakeAdapter({ currentTime }) { command -> if (command == "ATSH7E0") reply("OK") else reply("STOPPED") }
        val driver = Elm327Driver(adapter, backgroundScope)

        val response = driver.request(0x7E0, rpmRequest, expectedResponses = 1)

        assertEquals(listOf(ElmStatus.STOPPED), response.statuses)
        assertEquals(3, adapter.written.size)
    }

    // Unsupported commands -------------------------------------------------------------------------------------

    @Test
    fun anUnknownNonEssentialCommandIsSkippedAndReported() = runTest {
        val events = Collected()
        val adapter = FakeAdapter({ currentTime }) { command -> if (command in setOf("ATL0", "ATAT1")) reply("?") else reply("OK") }
        val driver = Elm327Driver(adapter, backgroundScope, config = Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO), events = events)

        val identity = driver.initialize(listOf("ATE0", "ATL0", "ATH1", "ATSP6", "ATAT1"))

        assertEquals(listOf("ATL0", "ATAT1"), identity.unsupportedCommands)
        assertEquals(listOf("ATL0", "ATAT1"), driver.unsupportedCommands)
        // The script carried on after the first rejection.
        assertEquals(listOf("ATE0", "ATL0", "ATH1", "ATSP6", "ATAT1"), adapter.written)
        assertEquals(listOf("ok, unsupported: ATL0 ATAT1"), events.of(SessionEvents.INIT_END))
    }

    @Test
    fun anUnknownEssentialCommandFailsInitialisation() = runTest {
        for (essential in listOf("ATE0", "ATH1", "ATSP6")) {
            val adapter = FakeAdapter({ currentTime }) { command -> if (command == essential) reply("?") else reply("OK") }
            val driver = Elm327Driver(adapter, backgroundScope, config = Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO))
            val error = assertFailsWith<AdapterException> { driver.initialize(listOf("ATE0", "ATH1", "ATSP6")) }
            assertTrue(error.message!!.contains(essential), error.message)
        }
    }

    @Test
    fun whichCommandsAreEssentialIsConfigurable() = runTest {
        val adapter = FakeAdapter({ currentTime }) { command -> if (command == "ATL0") reply("?") else reply("OK") }
        val config = Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO, essentialCommands = listOf("ATL"))
        assertFailsWith<AdapterException> { Elm327Driver(adapter, backgroundScope, config = config).initialize(listOf("ATE0", "ATL0")) }
    }

    // SEARCHING ------------------------------------------------------------------------------------------------

    @Test
    fun theWaitIsLongerWhileTheAdapterIsSearching() = runTest {
        val events = Collected()
        val adapter = FakeAdapter({ currentTime }) { command ->
            when (command) {
                "ATSH7E0" -> reply("OK")
                else -> {
                    emit("SEARCHING...\r")
                    later(backgroundScope, 6_000) { emit("7E804410C0C7C\r\r>") }
                }
            }
        }
        val driver = Elm327Driver(adapter, backgroundScope, events = events)

        val response = driver.request(0x7E0, rpmRequest, expectedResponses = 1, timeout = 3.seconds)

        assertIs<DiagnosticResponse.Positive>(response.messages.single())
        assertEquals(6_000L, currentTime)
        assertEquals(1, driver.stats.searchingWaits)
        assertEquals(1, events.of(SessionEvents.SEARCHING).size)
    }

    @Test
    fun theLongerWaitHasALimitToo() = runTest {
        val adapter = FakeAdapter({ currentTime }) { command ->
            if (command == "ATSH7E0") {
                reply("OK")
            } else {
                emit("SEARCHING...\r")
                later(backgroundScope, 20_000) { emit("7E804410C0C7C\r\r>") }
            }
        }
        val driver = Elm327Driver(adapter, backgroundScope)

        assertFailsWith<AdapterTimeoutException> { driver.request(0x7E0, rpmRequest, expectedResponses = 1, timeout = 3.seconds) }

        assertEquals(8_000L, currentTime, "searching earns a wait of 8 s in all")
    }

    @Test
    fun withoutSearchingTheOrdinaryTimeoutApplies() = runTest {
        val adapter = FakeAdapter({ currentTime }) { command -> if (command == "ATSH7E0") reply("OK") }
        val driver = Elm327Driver(adapter, backgroundScope)

        assertFailsWith<AdapterTimeoutException> { driver.request(0x7E0, rpmRequest, expectedResponses = 1, timeout = 3.seconds) }

        assertEquals(3_000L, currentTime)
    }

    // Bus and buffer -------------------------------------------------------------------------------------------

    @Test
    fun busFaultsAreTypedCountedAndLogged() = runTest {
        for ((word, status) in listOf(
            "CAN ERROR" to ElmStatus.CAN_ERROR,
            "UNABLE TO CONNECT" to ElmStatus.UNABLE_TO_CONNECT,
            "BUS INIT: ...ERROR" to ElmStatus.BUS_ERROR,
            "BUS ERROR" to ElmStatus.BUS_ERROR,
        )) {
            val events = Collected()
            val adapter = FakeAdapter({ currentTime }) { command -> if (command == "ATSH7E0") reply("OK") else reply(word) }
            val driver = Elm327Driver(adapter, backgroundScope, events = events)

            val response = driver.request(0x7E0, rpmRequest, expectedResponses = 1)

            assertEquals(status, response.busFault, word)
            assertEquals(1, driver.stats.busFaults)
            assertEquals(1, events.of(SessionEvents.BUS_FAULT).size)
        }
    }

    @Test
    fun anOrdinaryNoDataIsNotABusFault() = runTest {
        val adapter = FakeAdapter({ currentTime }) { command -> if (command == "ATSH7E0") reply("OK") else reply("NO DATA") }
        val driver = Elm327Driver(adapter, backgroundScope)
        val response = driver.request(0x7E0, rpmRequest, expectedResponses = 1)
        assertEquals(null, response.busFault)
        assertEquals(0, driver.stats.busFaults)
    }

    @Test
    fun aBusFaultOnEveryPollIsCountedEachTimeButLoggedSparingly() = runTest {
        val events = Collected()
        val adapter = FakeAdapter({ currentTime }) { command -> if (command == "ATSH7E0") reply("OK") else reply("CAN ERROR") }
        val driver = Elm327Driver(adapter, backgroundScope, events = events)
        repeat(100) { driver.request(0x7E0, rpmRequest, expectedResponses = 1) }
        assertEquals(100, driver.stats.busFaults)
        assertTrue(events.of(SessionEvents.BUS_FAULT).size < 12, "logged ${events.of(SessionEvents.BUS_FAULT).size} times")
    }

    @Test
    fun bufferFullIsCountedAndLogged() = runTest {
        val events = Collected()
        val adapter = FakeAdapter({ currentTime }) { command -> if (command == "ATSH7E0") reply("OK") else reply("BUFFER FULL") }
        val driver = Elm327Driver(adapter, backgroundScope, events = events)
        driver.request(0x7E0, rpmRequest, expectedResponses = 1)
        driver.request(0x7E0, rpmRequest, expectedResponses = 1)
        assertEquals(2, driver.stats.bufferFull)
        assertEquals(2, events.of(SessionEvents.BUFFER_FULL).size)
    }

    // Resets ---------------------------------------------------------------------------------------------------

    private suspend fun initialisedModel(scope: TestScope, events: SessionEventSink = SessionEventSink.None): Pair<Model, Elm327Driver> {
        val model = Model { scope.currentTime }
        val driver = scope.driverWith(model, events = events)
        driver.initialize(script)
        return model to driver
    }

    @Test
    fun aRequestAnswerWithTheCommandEchoedWhileEchoIsOffMeansTheAdapterReset() = runTest {
        val events = Collected()
        val (model, driver) = initialisedModel(this, events)
        model.glitch = { _ -> "010C1\r7E804410C0C7C\r\r>" }

        assertIs<DiagnosticResponse.Positive>(driver.request(0x7E0, rpmRequest, 1).messages.single())

        assertTrue(events.of(SessionEvents.ADAPTER_RESET).single().contains("echoed"), events.events.toString())
        assertRecovered(model, driver)
    }

    @Test
    fun aRequestAnswerWithTheStartUpBannerMeansTheAdapterReset() = runTest {
        val events = Collected()
        val (model, driver) = initialisedModel(this, events)
        model.glitch = { _ -> "ELM327 v2.3\r\r>" }

        assertIs<DiagnosticResponse.Positive>(driver.request(0x7E0, rpmRequest, 1).messages.single())

        assertTrue(events.of(SessionEvents.ADAPTER_RESET).single().contains("banner"), events.events.toString())
        assertRecovered(model, driver)
    }

    @Test
    fun aBannerPrintedBetweenTwoCommandsMeansTheAdapterResetToo() = runTest {
        val events = Collected()
        val (model, driver) = initialisedModel(this, events)
        driver.request(0x7E0, rpmRequest, 1)
        // The voltage dip: the adapter forgets its settings and announces itself, with no command pending.
        model.dip()
        model.adapter.emit("\r\rELM327 v2.3\r\r>")
        testScheduler.runCurrent()

        assertIs<DiagnosticResponse.Positive>(driver.request(0x7E0, rpmRequest, 1).messages.single())

        assertTrue(events.of(SessionEvents.ADAPTER_RESET).single().contains("between two commands"), events.events.toString())
        assertEquals(1, driver.stats.adapterResets)
        assertEquals(1, driver.stats.reinits)
    }

    @Test
    fun lvResetMeansTheAdapterReset() = runTest {
        val events = Collected()
        val (model, driver) = initialisedModel(this, events)
        model.glitch = { _ -> "LV RESET\r\r>" }

        assertIs<DiagnosticResponse.Positive>(driver.request(0x7E0, rpmRequest, 1).messages.single())

        assertTrue(events.of(SessionEvents.ADAPTER_RESET).single().contains("LV RESET"), events.events.toString())
        assertRecovered(model, driver)
    }

    @Test
    fun repliesWithoutHeadersWhileHeadersAreOnMeanTheAdapterReset() = runTest {
        val events = Collected()
        val (model, driver) = initialisedModel(this, events)
        model.glitch = { _ -> "41 0C 0C 7C\r\r>" }

        assertIs<DiagnosticResponse.Positive>(driver.request(0x7E0, rpmRequest, 1).messages.single())

        assertTrue(events.of(SessionEvents.ADAPTER_RESET).single().contains("no CAN headers"), events.events.toString())
        assertRecovered(model, driver)
    }

    /** One reset, one re-initialisation with the whole script, the header chosen again, and the request answered on the retry. */
    private fun assertRecovered(model: Model, driver: Elm327Driver) {
        assertEquals(1, driver.stats.adapterResets)
        assertEquals(1, driver.stats.reinits)
        val sent = model.adapter.written
        val firstRequest = sent.indexOf("010C1")
        val after = sent.drop(firstRequest + 1)
        assertEquals(script + listOf("ATSH7E0", "010C1"), after, "script again (header cache cleared), then the retry")
    }

    @Test
    fun anAdapterThatKeepsResettingFailsTheRequestInsteadOfLooping() = runTest {
        val (model, driver) = initialisedModel(this)
        model.glitch = { _ -> "LV RESET\r\r>" }
        model.persistentGlitch = true

        assertFailsWith<AdapterResetException> { driver.request(0x7E0, rpmRequest, 1) }

        assertEquals(1, driver.stats.reinits, "re-initialised once, not in a loop")
    }

    @Test
    fun anAdapterThatEchoesDespiteEchoOffIsNotTakenForResettingOnEveryCommand() = runTest {
        // Some clones answer OK to ATE0 and keep echoing. The driver learns that during initialisation and stops treating echo as a sign.
        val adapter = FakeAdapter({ currentTime }) { command ->
            when {
                command.startsWith("ATSH") -> reply(command, "OK")
                command.startsWith("AT") -> reply(command, if (command == "ATZ") "ELM327 v2.3" else "OK")
                else -> reply(command, "7E804410C0C7C")
            }
        }
        val driver = Elm327Driver(adapter, backgroundScope, config = Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO))
        driver.initialize(script)

        repeat(3) { assertIs<DiagnosticResponse.Positive>(driver.request(0x7E0, rpmRequest, 1).messages.single()) }

        assertEquals(0, driver.stats.adapterResets)
    }

    @Test
    fun detectionCanBeSwitchedOff() = runTest {
        val model = Model { currentTime }
        val driver = driverWith(model, Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO, detectResets = false))
        driver.initialize(script)
        model.glitch = { _ -> "LV RESET\r\r>" }
        val response = driver.request(0x7E0, rpmRequest, 1)
        assertEquals(listOf(ElmStatus.LV_RESET), response.statuses)
        assertEquals(0, driver.stats.adapterResets)
    }

    // Re-initialisation ------------------------------------------------------------------------------------------

    @Test
    fun reinitialiseRunsTheScriptAgainAndForgetsTheSelectedHeader() = runTest {
        val (model, driver) = initialisedModel(this)
        driver.request(0x7E0, rpmRequest, 1)
        driver.reinitialize("test")
        driver.request(0x7E0, rpmRequest, 1)

        val sent = model.adapter.written
        assertEquals(script + listOf("ATSH7E0", "010C1") + script + listOf("ATSH7E0", "010C1"), sent)
    }

    @Test
    fun reinitialiseBeforeAnyInitialisationIsAnError() = runTest {
        val driver = driverWith(Model { currentTime })
        assertFailsWith<IllegalStateException> { driver.reinitialize("too early") }
    }

    @Test
    fun applyScriptSendsTheGivenCommandsAndChangesWhatAReinitialisationRuns() = runTest {
        val (model, driver) = initialisedModel(this)
        val polling = script + "ATST32"
        driver.applyScript(polling, send = listOf("ATST32"))
        driver.reinitialize("test")

        assertEquals(script + "ATST32" + polling, model.adapter.written)
    }

    @Test
    fun initialisationReportsItsStepsAsEvents() = runTest {
        val events = Collected()
        initialisedModel(this, events)
        assertEquals(listOf("5 commands"), events.of(SessionEvents.INIT_START))
        assertEquals(script.size, events.of(SessionEvents.INIT_STEP).size)
        assertTrue(events.of(SessionEvents.INIT_STEP).first().startsWith("ATZ -> ELM327"), events.of(SessionEvents.INIT_STEP).toString())
        assertEquals(listOf("ok"), events.of(SessionEvents.INIT_END))
    }

    @Test
    fun aSinkThatThrowsDoesNotBreakTheDriver() = runTest {
        val model = Model { currentTime }
        val driver = driverWith(model, events = SessionEventSink { throw IllegalStateException("disk full") })
        driver.initialize(script)
        assertIs<DiagnosticResponse.Positive>(driver.request(0x7E0, rpmRequest, 1).messages.single())
    }

    // A caller cancelled while it waited ----------------------------------------------------------------------

    @Test
    fun aCommandCancelledWhileWaitingHasItsLateReplyAwaitedAndDroppedBeforeTheNextGoesOut() = runTest {
        var rpmCalls = 0
        lateinit var adapter: FakeAdapter
        adapter = FakeAdapter({ currentTime }) { command ->
            when (command) {
                "ATSH7E0" -> reply("OK")
                // The first request's answer comes late, after its caller has given up on it.
                "010C1" -> if (++rpmCalls == 1) later(backgroundScope, 300) { reply("7E804410C0C7C") } else reply("7E804410C1000")
                "ATCRA708" -> reply("OK")
                else -> reply("?")
            }
        }
        val driver = Elm327Driver(adapter, backgroundScope)

        val cancelled = backgroundScope.async { driver.request(0x7E0, rpmRequest, 1) }
        advanceTimeBy(100)
        cancelled.cancel()
        advanceTimeBy(1)

        val setup = driver.command("ATCRA708")
        assertEquals(listOf("OK"), setup, "the late reply is not taken for this command's")
        // Sent only once the adapter had finished the abandoned command: no STOPPED, no resend.
        assertEquals(listOf("ATSH7E0", "010C1", "ATCRA708"), adapter.written)
        assertTrue(adapter.writtenAt[2] >= 300, "sent after the late reply: ${adapter.writtenAt}")
        assertEquals(0, driver.stats.resends)

        val next = driver.request(0x7E0, rpmRequest, 1)
        val answer = assertIs<DiagnosticResponse.Positive>(next.messages.single())
        assertEquals(listOf(0x10, 0x00), answer.data.map { it.toInt() and 0xFF }.takeLast(2), "its own reply, not the late one")
    }

    @Test
    fun anAbandonedCommandWhoseReplyNeverEndsDelaysTheNextOnlyBrieflyAndOnce() = runTest {
        var silent = true
        val adapter = FakeAdapter({ currentTime }) { command ->
            when (command) {
                "ATSH7E0" -> reply("OK")
                "010C1" -> if (silent) silent = false else reply("7E804410C0C7C")
                else -> reply("OK")
            }
        }
        val driver = Elm327Driver(adapter, backgroundScope, config = Elm327Driver.Config(abandonedReplyWait = 500.milliseconds))
        val cancelled = backgroundScope.async { driver.request(0x7E0, rpmRequest, 1) }
        advanceTimeBy(100)
        cancelled.cancel()
        advanceTimeBy(1)

        val before = currentTime
        assertEquals(listOf("OK"), driver.command("ATCRA708"))
        assertEquals(500L, currentTime - before)
        assertEquals(1, driver.stats.abandonedNotSettled)
        val after = currentTime
        assertEquals(listOf("OK"), driver.command("ATAR"))
        assertEquals(0L, currentTime - after, "only the command right after the abandoned one waits")
    }
}
