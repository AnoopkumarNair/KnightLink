package knightlink.core.recorder

import knightlink.core.adapter.AdapterDisconnectedException
import knightlink.core.adapter.Elm327Driver
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleSession
import knightlink.core.sim.SimulatedTransport
import knightlink.core.sim.VirtualElm327
import knightlink.core.transport.TransportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the session layer reacts to when an adapter goes away is the incoming stream ending. A recording of such a drop
 * has to hold that, or it cannot be replayed faithfully.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HangUpRoundTripTest {
    private val harness = StackHarness()

    private class Outcome(val recordings: List<Recording>, val finalState: SessionState)

    /** Lets virtual time pass. (advanceUntilIdle() would not: the session lives in backgroundScope.) */
    private fun TestScope.elapse(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    /**
     * A real [VehicleSession] on the simulator, recorded link by link. The adapter is unplugged at the moment a command
     * reaches it, so the request is in flight, then the session reconnects and polls on a new link until it is stopped.
     */
    private fun record(): Outcome {
        val sinks = ArrayList<InMemoryRecordSink>()
        lateinit var state: SessionState
        runTest {
            val vehicle = harness.simulation.buildVehicle()
            val simulated = SimulatedTransport(vehicle = { vehicle }, config = VirtualElm327.Config(responseDelay = 45.milliseconds))
            val transport = RecordingTransport(
                simulated,
                { InMemoryRecordSink().also { sinks += it } },
                testTimeSource,
                RecordingHeader(harness.adapterDefinition.id, "the adapter hangs up"),
            )
            val session = VehicleSession(transport, harness.adapterDefinition.initScript, harness.catalog, backgroundScope, testTimeSource)
            session.start()
            elapse(10.seconds)
            assertIs<SessionState.Polling>(session.state.value)

            val adapter = simulated.current!!
            val seen = adapter.received.size
            while (adapter.received.size == seen) elapse(1.milliseconds)
            adapter.disconnect()
            elapse(20.seconds)

            state = session.state.value
            session.stop()
        }
        return Outcome(sinks.map { Recording.parse(it.text()) }, state)
    }

    @Test
    fun theRecordingOfTheLostLinkEndsWithTheHangUpAndThenTheCloseOfTheSessionTearingItDown() {
        val outcome = record()

        assertEquals(2, outcome.recordings.size, "one recording per link")
        val first = outcome.recordings[0].events
        assertEquals(listOf(EventKind.EOF, EventKind.CLOSE), first.takeLast(2).map { it.kind })
        assertEquals(EventKind.TX, first[first.size - 3].kind, "the request that was in flight when the adapter went away")
        assertEquals(1, first.count { it.kind == EventKind.EOF })
        assertEquals(first.map { it.timeMs }.sorted(), first.map { it.timeMs })

        // The session did reconnect and poll again, on a link of its own; stopping it is not a hang-up.
        val second = outcome.recordings[1].events
        assertIs<SessionState.Polling>(outcome.finalState)
        assertTrue(second.count { it.kind == EventKind.TX } > harness.adapterDefinition.initScript.size)
        assertTrue(second.none { it.kind == EventKind.EOF })
        assertEquals(EventKind.CLOSE, second.last().kind)

        // The text form, which is what a file would hold, reads back the same.
        assertEquals(outcome.recordings[0], Recording.parse(outcome.recordings[0].toText()))
    }

    @Test
    fun aStrictReplayOfTheLostLinkEndsInAdapterDisconnectedExceptionAndThenRejectsWrites() {
        val first = record().recordings[0]
        val commands = first.events.filter { it.kind == EventKind.TX }.joinToString("") { it.bytes.decodeToString() }.split('\r').dropLast(1)

        runTest {
            val stream = ReplayByteStream(first, ReplayMode.STRICT)
            val driver = Elm327Driver(stream, backgroundScope)
            var answered = 0
            var disconnected: AdapterDisconnectedException? = null
            for (command in commands) {
                try {
                    driver.command(command, 30.seconds)
                    answered++
                } catch (e: AdapterDisconnectedException) {
                    disconnected = e
                    break
                }
            }

            assertEquals(commands.size - 1, answered, "everything but the request that was in flight is answered")
            assertNotNull(disconnected, "the last request must end in the adapter hanging up, not in a timeout")
            assertTrue("closed the connection" in disconnected.reason, disconnected.reason)
            assertNull(stream.firstMismatch)
            assertEquals(0, stream.remainingEvents)
            assertFailsWith<TransportException> { stream.write(byteArrayOf(0x41)) }
        }
    }
}
