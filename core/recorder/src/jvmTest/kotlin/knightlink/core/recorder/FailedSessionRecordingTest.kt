package knightlink.core.recorder

import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleSession
import knightlink.core.sim.SimulatedTransport
import knightlink.core.sim.VirtualElm327
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.transport.TransportKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The first car session failed without a usable trace. These pin the cure: a session that fails during initialisation or
 * during the probe is recorded from its first step, with the session's own account of why alongside the traffic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailedSessionRecordingTest {
    private val harness = StackHarness()

    /** An adapter that has not woken up: it answers every command with `?`. */
    private class RejectingStream : ByteStream {
        private val channel = Channel<ByteArray>(Channel.UNLIMITED)

        override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

        override suspend fun write(bytes: ByteArray) {
            channel.send("?\r\r>".encodeToByteArray())
        }

        override suspend fun close() {
            channel.close()
        }
    }

    private class Sinks {
        val all = ArrayList<InMemoryRecordSink>()

        fun create(): RecordSink = InMemoryRecordSink().also { all += it }
    }

    @Test
    fun aFailedInitialisationLeavesARecordingThatShowsEveryStepAndWhyItEnded() = runTest {
        val sinks = Sinks()
        val rejecting = object : Transport {
            override val kind = TransportKind.SIMULATED

            override suspend fun open(): ByteStream = RejectingStream()
        }
        val transport = RecordingTransport(rejecting, sinks::create, testTimeSource, RecordingHeader(harness.adapterDefinition.id, "test"))
        val session = VehicleSession(
            transport,
            harness.adapterDefinition.initScript,
            harness.catalog,
            backgroundScope,
            testTimeSource,
            VehicleSession.Config(maxConnectAttempts = 1, driver = harness.adapterDefinition.driverConfig()),
            events = transport,
        )

        session.start()
        advanceTimeBy(30.seconds)
        runCurrent()
        assertIs<SessionState.Failed>(session.state.value)

        val events = sinks.all.single().toRecording().events
        val notes = events.filter { it.kind == EventKind.EVENT }.map { it.payload }
        // On record from the very first step: the connect attempt came before the link opened.
        assertEquals(EventKind.EVENT, events.first().kind)
        assertTrue(notes.first().startsWith("state: Connecting"), notes.toString())
        assertTrue(notes.any { it.startsWith("connect-attempt") })
        assertTrue(notes.any { it.startsWith("link-up") })
        assertTrue(notes.any { it.startsWith("init-start") })
        assertTrue(notes.any { it.contains("ATE0 -> rejected, essential") }, notes.toString())
        assertTrue(notes.last().startsWith("end: Gave up after 1 failed connection attempts"), notes.last())
        // The traffic is there too: the warm-up and the script up to the command that failed.
        val sent = events.filter { it.kind == EventKind.TX }.map { it.bytes.decodeToString() }
        assertEquals(listOf("ATE0\r", "ATE0\r", "ATD\r"), sent.take(3))
        assertEquals(EventKind.CLOSE, events.last { it.kind != EventKind.EVENT }.kind)
    }

    @Test
    fun aCarThatDoesNotAnswerTheProbeIsRecordedWithTheProbeTrafficAndItsOutcome() = runTest {
        val sinks = Sinks()
        val vehicle = harness.simulation.buildVehicle().also { it.awake = false }
        val simulated = SimulatedTransport(vehicle = { vehicle }, config = VirtualElm327.Config(responseDelay = 45.milliseconds))
        val transport = RecordingTransport(simulated, sinks::create, testTimeSource, RecordingHeader(harness.adapterDefinition.id, "test"))
        val session = VehicleSession(
            transport,
            harness.adapterDefinition.initScript,
            harness.catalog,
            backgroundScope,
            testTimeSource,
            VehicleSession.Config(probeScript = harness.adapterDefinition.probeScript, pollScript = harness.adapterDefinition.pollScript),
            events = transport,
        )

        session.start()
        advanceTimeBy(10.seconds)
        runCurrent()
        assertIs<SessionState.WaitingForIgnition>(session.state.value)

        val events = sinks.all.single().toRecording().events
        val notes = events.filter { it.kind == EventKind.EVENT }.map { it.payload }
        assertTrue(notes.any { it.startsWith("init-end") })
        assertTrue(notes.any { it.startsWith("probe-result: no ECU answered") }, notes.toString())
        assertTrue(notes.any { it.startsWith("state: WaitingForIgnition") }, notes.toString())
        val sent = events.filter { it.kind == EventKind.TX }.map { it.bytes.decodeToString() }
        assertTrue("ATI\r" in sent, "the probe's own traffic is in the recording")
        assertTrue(sent.any { it.startsWith("0100") }, "so is the sweep")
        assertTrue(sent.indexOf("ATST64\r") in 0 until sent.indexOf("ATI\r"), "the longer timeout is set before the probe")
    }
}
