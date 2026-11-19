package knightlink.core.adapter

import knightlink.core.common.hexToBytes
import knightlink.core.protocol.DiagnosticResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** "Response pending" (7F xx 78): the driver keeps reading the same request's replies instead of reporting the pending answer. */
@OptIn(ExperimentalCoroutinesApi::class)
class Elm327DriverPendingTest {
    private val request = "221E12".hexToBytes()
    private val pending = "7E8037F2278"
    private val final = "7E80462 1E120A".replace(" ", "")

    private fun TestScope.driver(onRequest: FakeAdapter.() -> Unit): Pair<Elm327Driver, FakeAdapter> {
        val adapter = FakeAdapter({ currentTime }) { command ->
            if (command.startsWith("ATSH")) reply("OK") else onRequest()
        }
        return Elm327Driver(adapter, backgroundScope, config = Elm327Driver.Config(warmUp = false)) to adapter
    }

    @Test
    fun aFinalAnswerInTheSameReadReplacesThePendingOne() = runTest {
        val (driver, _) = driver { reply(pending, final) }
        val response = driver.request(0x7E0, request, expectedResponses = null)
        assertIs<DiagnosticResponse.Positive>(response.messages.single())
    }

    @Test
    fun aFinalAnswerThatComesLaterIsReadWithoutSendingTheRequestAgain() = runTest {
        val (driver, adapter) = driver {
            reply(pending)
            later(this@runTest, 700) { reply(final) }
        }
        val response = driver.request(0x7E0, request, expectedResponses = null)
        assertIs<DiagnosticResponse.Positive>(response.messages.single())
        assertEquals(1, adapter.written.count { it == "221E12" }, "no resend: that would restart the unit's work")
        assertEquals(1, driver.stats.pendingWaits)
        assertEquals(0, driver.stats.pendingGaveUp)
    }

    @Test
    fun aFinalAnswerThatNeverComesGivesTheWaitUpAfterFiveSecondsAndKeepsThePendingAnswer() = runTest {
        val (driver, adapter) = driver { reply(pending) }
        val response = driver.request(0x7E0, request, expectedResponses = null)
        val only = assertIs<DiagnosticResponse.Negative>(response.messages.single())
        assertEquals(0x78, only.code)
        assertEquals(1, driver.stats.pendingGaveUp)
        assertEquals(1, adapter.written.count { it == "221E12" })
        assertEquals(true, currentTime in 5_000L..5_500L, "took $currentTime ms")
    }

    @Test
    fun anotherPendingRoundIsReadUpToThreeTimes() = runTest {
        val (driver, _) = driver {
            reply(pending)
            later(this@runTest, 300) { reply(pending) }
            later(this@runTest, 600) { reply(pending) }
            later(this@runTest, 900) { reply(final) }
        }
        val response = driver.request(0x7E0, request, expectedResponses = null)
        assertIs<DiagnosticResponse.Positive>(response.messages.single())
        assertEquals(true, currentTime < 2_000)
    }

    @Test
    fun aReplyThatTakesMoreThanTwoSecondsIsLogged() = runTest {
        val events = ArrayList<knightlink.core.common.SessionEvent>()
        val adapter = FakeAdapter({ currentTime }) { command ->
            if (command.startsWith("ATSH")) {
                reply("OK")
            } else {
                later(this@runTest, 2_500) { reply(final) }
            }
        }
        val driver = Elm327Driver(
            adapter,
            backgroundScope,
            config = Elm327Driver.Config(warmUp = false, timeSource = testTimeSource),
            events = knightlink.core.common.SessionEventSink { events += it },
        )
        driver.request(0x7E0, request, expectedResponses = null)
        val slow = events.filter { it.name == knightlink.core.common.SessionEvents.SLOW_REPLY }
        assertEquals(1, slow.size, events.toString())
        assertEquals(true, "221E12" in slow.single().detail && "2500 ms" in slow.single().detail, slow.single().detail)
    }

    @Test
    fun theTwentyMillisecondCeilingStays() {
        assertEquals(20.milliseconds, Elm327Driver.Config.DEFAULT_MIN_REQUEST_INTERVAL)
        assertEquals(5.seconds, Elm327Driver.Config().pendingTimeout)
    }
}
