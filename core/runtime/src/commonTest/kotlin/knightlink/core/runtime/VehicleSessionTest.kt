package knightlink.core.runtime

import knightlink.core.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The session's connection handling against scripted adapters that never get as far as live data. */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleSessionTest {
    private val init = listOf("ATZ", "ATE0")

    private fun failingTransport() = FakeTransport { throw TransportException("no adapter in range") }

    @Test
    fun startsIdle() = runTest {
        val session = VehicleSession(failingTransport(), init, testCatalog(), backgroundScope, testTimeSource)
        assertEquals(SessionState.Idle, session.state.value)
        runCurrent()
        assertEquals(SessionState.Idle, session.state.value)
    }

    @Test
    fun openFailuresAreRetriedWithDoublingBackoffUpToTheMaximum() = runTest {
        val transport = failingTransport()
        val config = VehicleSession.Config(initialBackoff = 1.seconds, maxBackoff = 5.seconds)
        val session = VehicleSession(transport, init, testCatalog(), backgroundScope, testTimeSource, config)
        val states = recordStates(session)

        session.start()
        advanceTimeBy(15.seconds)
        runCurrent()

        val cause = "could not open the link: no adapter in range"
        assertEquals(
            listOf(
                Timed(0, SessionState.Idle),
                Timed(0, SessionState.Connecting(1)),
                Timed(0, SessionState.Reconnecting(2, 1.seconds, cause)),
                Timed(1_000, SessionState.Connecting(2)),
                Timed(1_000, SessionState.Reconnecting(3, 2.seconds, cause)),
                Timed(3_000, SessionState.Connecting(3)),
                Timed(3_000, SessionState.Reconnecting(4, 4.seconds, cause)),
                Timed(7_000, SessionState.Connecting(4)),
                // 8 s would exceed the maximum.
                Timed(7_000, SessionState.Reconnecting(5, 5.seconds, cause)),
                Timed(12_000, SessionState.Connecting(5)),
                Timed(12_000, SessionState.Reconnecting(6, 5.seconds, cause)),
            ),
            states,
        )
        assertEquals(5, transport.opens)
    }

    @Test
    fun aBackoffFactorOfOneKeepsTheWaitConstant() = runTest {
        val config = VehicleSession.Config(initialBackoff = 2.seconds, maxBackoff = 10.seconds, backoffFactor = 1.0)
        val session = VehicleSession(failingTransport(), init, testCatalog(), backgroundScope, testTimeSource, config)
        val states = recordStates(session)
        session.start()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(listOf(2.seconds, 2.seconds, 2.seconds), states.map { it.state }.filterIsInstance<SessionState.Reconnecting>().map { it.retryIn })
    }

    @Test
    fun givesUpAfterMaxConnectAttempts() = runTest {
        val transport = failingTransport()
        val config = VehicleSession.Config(maxConnectAttempts = 3)
        val session = VehicleSession(transport, init, testCatalog(), backgroundScope, testTimeSource, config)
        val states = recordStates(session)

        session.start()
        settle()

        val failed = assertIs<SessionState.Failed>(session.state.value)
        assertTrue(failed.reason.contains("3"), failed.reason)
        assertTrue(failed.reason.contains("no adapter in range"), failed.reason)
        assertEquals(3, transport.opens)
        // Waited 1 s and 2 s between the three attempts, and did not wait again after the last one.
        assertEquals(3_000, states.last().atMs)
        assertEquals(listOf(1, 2, 3), states.map { it.state }.filterIsInstance<SessionState.Connecting>().map { it.attempt })

        // Failed is terminal: nothing more happens, and start() does not revive it.
        session.start()
        advanceTimeBy(1.seconds * 100)
        assertEquals(3, transport.opens)
    }

    @Test
    fun aSingleAllowedAttemptFailsImmediately() = runTest {
        val session = VehicleSession(failingTransport(), init, testCatalog(), backgroundScope, testTimeSource, VehicleSession.Config(maxConnectAttempts = 1))
        val states = recordStates(session)
        session.start()
        settle()
        assertIs<SessionState.Failed>(session.state.value)
        assertEquals(0, states.last().atMs, "no waiting after the only attempt")
    }

    @Test
    fun anyExceptionFromOpenCountsAsAConnectionFailureNotOnlyTransportException() = runTest {
        val transport = FakeTransport { throw IllegalStateException("bluetooth stack went away") }
        val session = VehicleSession(transport, init, testCatalog(), backgroundScope, testTimeSource, VehicleSession.Config(maxConnectAttempts = 2))
        session.start()
        settle()
        val failed = assertIs<SessionState.Failed>(session.state.value)
        assertTrue(failed.reason.contains("bluetooth stack went away"), failed.reason)
    }

    @Test
    fun anAdapterThatRejectsTheInitScriptIsAConnectionFailureAndEveryStreamIsClosed() = runTest {
        val streams = ArrayList<FakeStream>()
        val transport = FakeTransport { FakeStream(::rejectingAdapter).also { streams += it } }
        val session = VehicleSession(transport, init, testCatalog(), backgroundScope, testTimeSource, VehicleSession.Config(maxConnectAttempts = 2))
        val states = recordStates(session)

        session.start()
        settle()

        assertTrue(states.any { it.state == SessionState.Initialising })
        val reconnecting = states.map { it.state }.filterIsInstance<SessionState.Reconnecting>().single()
        assertTrue(reconnecting.cause.contains("rejected 'ATZ'"), reconnecting.cause)
        assertEquals(2, transport.opens)
        assertEquals(listOf(1, 1), streams.map { it.closes })
        assertEquals(listOf("ATZ"), streams.first().written)
        assertIs<SessionState.Failed>(session.state.value)
    }

    @Test
    fun aStreamThatEndsDuringInitialisationIsReportedAtOnceNotAfterTheResetTimeout() = runTest {
        lateinit var stream: FakeStream
        val transport = FakeTransport { FakeStream(::deadAdapter).also { stream = it } }
        val session = VehicleSession(transport, init, testCatalog(), backgroundScope, testTimeSource)
        val states = recordStates(session)

        session.start()
        runCurrent()
        assertEquals(SessionState.Initialising, session.state.value)
        stream.end(IllegalStateException("socket reset"))
        runCurrent()

        val reconnecting = assertIs<SessionState.Reconnecting>(session.state.value)
        assertTrue(reconnecting.cause.contains("socket reset"), reconnecting.cause)
        assertEquals(0, currentTime)
        assertEquals(listOf(0L), states.map { it.atMs }.distinct())
        assertEquals(1, stream.closes)
    }

    @Test
    fun aCarThatDoesNotAnswerTheProbeIsRetriedInsteadOfPollingNothing() = runTest {
        val streams = ArrayList<FakeStream>()
        val transport = FakeTransport { FakeStream(::silentCar).also { streams += it } }
        val session = VehicleSession(transport, init, testCatalog(), backgroundScope, testTimeSource)
        val states = recordStates(session)

        session.start()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertTrue(states.any { it.state is SessionState.Probing }, "probing never started")
        val reconnecting = states.map { it.state }.filterIsInstance<SessionState.Reconnecting>().first()
        assertTrue(reconnecting.cause.contains("ignition"), reconnecting.cause)
        assertEquals(2, transport.opens, "the second attempt should start after the 1 s backoff")
        assertEquals(1, streams.first().closes)
        assertTrue(states.none { it.state is SessionState.Polling })
    }

    @Test
    fun startIsIdempotent() = runTest {
        val transport = failingTransport()
        val session = VehicleSession(transport, init, testCatalog(), backgroundScope, testTimeSource)
        session.start()
        session.start()
        runCurrent()
        session.start()
        runCurrent()
        assertEquals(1, transport.opens)
    }

    @Test
    fun stopBeforeStartEndsStoppedAndStartDoesNothingAfterwards() = runTest {
        val transport = failingTransport()
        val session = VehicleSession(transport, init, testCatalog(), backgroundScope, testTimeSource)
        session.stop()
        assertEquals(SessionState.Stopped, session.state.value)
        session.start()
        settle()
        assertEquals(0, transport.opens)
        assertEquals(SessionState.Stopped, session.state.value)
    }

    @Test
    fun stopWhileOpeningStopsWithoutFurtherAttempts() = runTest {
        val opening = CompletableDeferred<Nothing>()
        val transport = FakeTransport { opening.await() }
        val session = VehicleSession(transport, init, testCatalog(), backgroundScope, testTimeSource)
        session.start()
        runCurrent()
        assertEquals(SessionState.Connecting(1), session.state.value)

        session.stop()

        assertEquals(SessionState.Stopped, session.state.value)
        settle()
        assertEquals(1, transport.opens)
    }

    @Test
    fun stopDuringTheBackoffWaitStopsAndNeverReconnects() = runTest {
        val transport = failingTransport()
        val session = VehicleSession(transport, init, testCatalog(), backgroundScope, testTimeSource)
        session.start()
        advanceTimeBy(500.milliseconds)
        assertIs<SessionState.Reconnecting>(session.state.value)

        session.stop()

        assertEquals(SessionState.Stopped, session.state.value)
        advanceTimeBy(1.seconds * 100)
        assertEquals(1, transport.opens)
    }

    @Test
    fun stopDuringInitialisationClosesTheStreamOnceAndSendsNothingMore() = runTest {
        lateinit var stream: FakeStream
        val transport = FakeTransport { FakeStream(::deadAdapter).also { stream = it } }
        val session = VehicleSession(transport, init, testCatalog(), backgroundScope, testTimeSource)
        session.start()
        advanceTimeBy(1.seconds)
        assertEquals(SessionState.Initialising, session.state.value)

        session.stop()

        assertEquals(SessionState.Stopped, session.state.value)
        assertEquals(1, stream.closes)
        val written = stream.written.size
        advanceTimeBy(1.seconds * 100)
        assertEquals(written, stream.written.size)
        assertEquals(1, transport.opens)
    }

    @Test
    fun stoppingTwiceIsHarmless() = runTest {
        lateinit var stream: FakeStream
        val session = VehicleSession(FakeTransport { FakeStream(::deadAdapter).also { stream = it } }, init, testCatalog(), backgroundScope, testTimeSource)
        session.start()
        runCurrent()
        session.stop()
        session.stop()
        assertEquals(SessionState.Stopped, session.state.value)
        assertEquals(1, stream.closes)
    }

    @Test
    fun cancellingTheScopeEndsInStoppedAndClosesTheStream() = runTest {
        lateinit var stream: FakeStream
        val sessionScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val session = VehicleSession(FakeTransport { FakeStream(::deadAdapter).also { stream = it } }, init, testCatalog(), sessionScope, testTimeSource)
        session.start()
        runCurrent()
        assertEquals(SessionState.Initialising, session.state.value)

        sessionScope.cancel()
        advanceUntilIdle()

        assertEquals(SessionState.Stopped, session.state.value)
        assertEquals(1, stream.closes)
    }

    @Test
    fun theCallerBeingCancelledDuringStopDoesNotLeaveTheStreamOpen() = runTest {
        lateinit var stream: FakeStream
        val session = VehicleSession(FakeTransport { FakeStream(::deadAdapter).also { stream = it } }, init, testCatalog(), backgroundScope, testTimeSource)
        session.start()
        runCurrent()
        // UNDISPATCHED: stop() is already waiting for the session to wind down when the caller is cancelled.
        val stopper = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { session.stop() }
        stopper.cancel()
        runCurrent()

        assertEquals(SessionState.Stopped, session.state.value)
        assertEquals(1, stream.closes)
    }

    @Test
    fun configRejectsNonsense() {
        assertFailsWith<IllegalArgumentException> { VehicleSession.Config(initialBackoff = 0.seconds) }
        assertFailsWith<IllegalArgumentException> { VehicleSession.Config(initialBackoff = 5.seconds, maxBackoff = 1.seconds) }
        assertFailsWith<IllegalArgumentException> { VehicleSession.Config(backoffFactor = 0.5) }
        assertFailsWith<IllegalArgumentException> { VehicleSession.Config(maxConnectAttempts = 0) }
    }

    @Test
    fun anUnknownPrimaryEcuIsRejectedWhenTheSessionIsBuilt() = runTest {
        assertFailsWith<IllegalArgumentException> {
            VehicleSession(failingTransport(), init, testCatalog(), backgroundScope, testTimeSource, VehicleSession.Config(primaryEcu = "nope"))
        }
    }
}
