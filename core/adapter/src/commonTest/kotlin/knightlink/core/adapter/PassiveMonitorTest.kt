package knightlink.core.adapter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The passive capture path: a narrow gate of its own, and a monitor that always stops. Nothing here may put a request on the bus. */
@OptIn(ExperimentalCoroutinesApi::class)
class PassiveMonitorTest {
    @Test
    fun thePassivePathAllowsOnlyTheListedAdapterCommands() {
        for (command in listOf("ATCSM1", "ATCAF0", "ATCAF1", "ATH1", "ATS0", "ATDPN", "ATAR", "ATCF100", "ATCM700", "AT DPN", "atcf 7e8")) {
            assertNull(ReadOnlyPolicy.passiveViolation(command), command)
        }
    }

    @Test
    fun thePassivePathRefusesEverythingElse() {
        val refused = listOf(
            "2EF19000", "3101FF00", "04", "0100", "22F190", // diagnostic requests, writes included
            "ATSH7E0", "ATZ", "ATSP6", "ATE0", "ATCSM0", "ATCF10", "ATMA", "AT MA", "ATCM70000", "ATCF1G0", "ATPP0CSV01", "ATWM1234", "ATMR12", "",
        )
        for (command in refused) assertNotNull(ReadOnlyPolicy.passiveViolation(command), command)
    }

    @Test
    fun theOrdinaryGateStillRefusesMonitoringAndWrites() {
        for (command in listOf("ATMA", "ATCSM1", "2EF19000", "31010203", "04")) assertNotNull(ReadOnlyPolicy.violation(command), command)
    }

    @Test
    fun onlyTheMonitorCommandPassesTheMonitorGate() {
        assertNull(ReadOnlyPolicy.monitorViolation("ATMA"))
        for (command in listOf("ATMR", "ATMT", "ATCSM1", "0100", "2EF19000", "ATZ")) assertNotNull(ReadOnlyPolicy.monitorViolation(command), command)
    }

    @Test
    fun aPassiveCommandThatTheAdapterAnswersWithStoppedIsSentOnceMoreAndAStaleReplyIsDiscarded() = runTest {
        var sent = 0
        val adapter = FakeAdapter({ currentTime }) { command ->
            if (command == "ATCSM1") {
                sent++
                if (sent == 1) reply("STOPPED") else reply("OK")
            }
        }
        val driver = driver(adapter)
        // A stale frame and prompt from a poll that was cancelled: gone before the command is written.
        adapter.emit("1A0 12 34\rSTOPPED\r\r>")
        runCurrent()
        assertEquals(listOf("OK"), driver.passiveCommand("ATCSM1"))
        assertEquals(listOf("ATCSM1", "ATCSM1"), adapter.written)
    }

    private fun TestScope.monitorAdapter(replyAfterStop: Boolean = true): FakeAdapter = FakeAdapter({ currentTime }) { command ->
        when {
            command == "ATMA" -> emit("1A0 12 34\r1B4 FF\rSEARCHING...\r") // frames, no prompt: monitoring runs
            command == " " -> if (replyAfterStop) emit("1A0 12 34\rSTOPPED\r\r>")
            command.startsWith("AT") -> reply("OK")
        }
    }

    private fun TestScope.driver(adapter: FakeAdapter) =
        Elm327Driver(adapter, backgroundScope, config = Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO))

    @Test
    fun monitoringListensForTheSliceThenSendsOnlyTheStopCharacterAndReturnsEveryLine() = runTest {
        val adapter = monitorAdapter()
        val lines = driver(adapter).monitor(3.seconds, 2.seconds)
        assertEquals(listOf("ATMA", " "), adapter.written)
        assertTrue(adapter.writtenAt[1] - adapter.writtenAt[0] >= 3000, "listened ${adapter.writtenAt[1] - adapter.writtenAt[0]} ms")
        assertEquals(listOf("1A0 12 34", "1B4 FF", "SEARCHING...", "1A0 12 34", "STOPPED"), lines)
    }

    @Test
    fun monitoringStopsAtOnceWhenAskedTo() = runTest {
        val adapter = monitorAdapter()
        val until = CompletableDeferred<Unit>()
        val driver = driver(adapter)
        backgroundScope.launch {
            delay(500.milliseconds)
            until.complete(Unit)
        }
        driver.monitor(30.seconds, 2.seconds, until)
        assertTrue(currentTime < 1000, "stopped after $currentTime ms")
        assertEquals(listOf("ATMA", " "), adapter.written)
    }

    @Test
    fun anAdapterThatNeverAnswersTheStopCharacterTimesOutAfterTwoTriesInsteadOfHanging() = runTest {
        val adapter = monitorAdapter(replyAfterStop = false)
        val failure = assertFailsWith<AdapterTimeoutException> { driver(adapter).monitor(1.seconds, 2.seconds) }
        assertTrue(failure.message!!.contains("ATMA"))
        assertEquals(listOf("ATMA", " ", " "), adapter.written)
    }

    @Test
    fun theDriverRefusesAnythingButTheListedCommandsOnThePassivePath() = runTest {
        val adapter = monitorAdapter()
        val driver = driver(adapter)
        for (command in listOf("2EF19000", "3101FF00", "04", "ATSH7E0", "ATZ")) {
            assertFailsWith<ReadOnlyViolationException> { driver.passiveCommand(command) }
        }
        assertEquals(listOf("OK"), driver.passiveCommand("ATCSM1"))
        assertEquals(listOf("ATCSM1"), adapter.written)
    }
}
