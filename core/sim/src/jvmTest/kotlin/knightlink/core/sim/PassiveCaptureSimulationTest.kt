package knightlink.core.sim

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.common.SessionEvent
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.runtime.CaptureMode
import knightlink.core.runtime.IdGroup
import knightlink.core.runtime.PassiveCapture
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleSession
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.telemetry.TelemetrySnapshot
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Passive capture against the simulator: the adapter only listens while it is on, the slices are noted, and polling comes back as it was. */
@OptIn(ExperimentalCoroutinesApi::class)
class PassiveCaptureSimulationTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))
    private val simulation = SimulationFile.parse(File(root, "fixtures/sim/harrier-dpf-regen.json").readText())
    private val adapterDefinition = AdapterDefinition.parse(File(root, "definitions/adapters/vgate-icar-pro-2s.json").readText())
    private val catalog = catalogFor(simulation.definition)

    private fun definition(id: String): DefinitionFile = DefinitionJson.parse(File(root, "definitions/$id.json").readText())

    private fun catalogFor(id: String): SignalCatalog {
        val top = definition(id)
        return SignalCatalog.of(top.extends.map(::definition) + top)
    }

    private class CountingStream(private val adapter: VirtualElm327) : ByteStream by adapter {
        var closes = 0
            private set

        override suspend fun close() {
            closes++
            adapter.close()
        }
    }

    private class CountingTransport(private val inner: SimulatedTransport) : Transport {
        val streams = ArrayList<CountingStream>()
        override val kind = inner.kind

        override suspend fun open(): ByteStream = CountingStream(inner.open()).also { streams += it }
    }

    private class Collected : SessionEventSink {
        val events = ArrayList<SessionEvent>()

        override suspend fun emit(event: SessionEvent) {
            events += event
        }

        fun of(name: String): List<String> = events.filter { it.name == name }.map { it.detail }
    }

    private class Rig(
        val vehicle: VirtualVehicle,
        val simulated: SimulatedTransport,
        val transport: CountingTransport,
        val session: VehicleSession,
        val events: Collected,
    ) {
        val adapter: VirtualElm327 get() = simulated.current!!
    }

    private val shortCapture = PassiveCapture.Config(
        sliceDuration = 1.seconds,
        groups = listOf(IdGroup(0x100, 0x700), IdGroup(0x200, 0x700)),
        idleDelay = 1.seconds,
        silenceLimit = 60.seconds,
    )

    private fun TestScope.rig(capture: PassiveCapture.Config = shortCapture, bufferFrames: Int? = null): Rig {
        val vehicle = simulation.buildVehicle()
        // What the car says by itself: four identifiers at different rates.
        vehicle.broadcast(0x1A0, 100) { n -> byteArrayOf(n.toByte(), 0x34) }
        vehicle.broadcast(0x1B4, 200) { byteArrayOf(0x01) }
        vehicle.broadcast(0x0C9, 250) { byteArrayOf(0x02, 0x03) }
        vehicle.broadcast(0x2C0, 500) { byteArrayOf(0x04) }
        val simulated = SimulatedTransport(
            vehicle = { vehicle },
            config = VirtualElm327.Config(responseDelay = 45.milliseconds, timeSource = testTimeSource, monitorBufferFrames = bufferFrames),
        )
        val transport = CountingTransport(simulated)
        val config = VehicleSession.Config(
            driver = adapterDefinition.driverConfig(),
            probeScript = adapterDefinition.probeScript,
            pollScript = adapterDefinition.pollScript,
            pollPeriod = { 500.milliseconds },
            asleepAfter = Duration.INFINITE,
            capture = capture,
        )
        val events = Collected()
        val session = VehicleSession(transport, adapterDefinition.initScript, catalog, backgroundScope, testTimeSource, config, events)
        return Rig(vehicle, simulated, transport, session, events)
    }

    private fun TestScope.run(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun TelemetrySnapshot.rpmAt(): Duration? = readings.firstOrNull { it.reading.signal.id == "engine.rpm" }?.at

    private fun isDiagnostic(command: String): Boolean = !command.startsWith("AT") && command.isNotBlank()

    @Test
    fun enteringPassiveStopsAllPollingAndNotesWhatEachSliceHeard() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
        val before = rig.adapter.received.size

        assertTrue(rig.session.setCaptureMode(CaptureMode.PASSIVE))
        run(10.seconds)
        assertEquals(CaptureMode.PASSIVE, rig.session.captureMode.value)

        // From the first ATMA on, nothing but adapter settings and the monitor command went to the adapter: no request reached the car.
        val sent = rig.adapter.received.drop(before)
        assertTrue(sent.isNotEmpty() && sent.contains("ATMA"), sent.toString())
        assertTrue(sent.none(::isDiagnostic), "requests during passive capture: ${sent.filter(::isDiagnostic)}")
        assertTrue(sent.none { it.startsWith("2E") || it.startsWith("31") || it.startsWith("04") })

        val notes = rig.events.of(SessionEvents.PASSIVE_SLICE)
        assertEquals("passive: all ids 1.0 s, 21 frames, 4 ids (1A0:10 1B4:5 0C9:4 2C0:2), buffer-full 0", notes[0])
        // The filters are honoured: a group hears only its own identifiers.
        assertEquals("passive: group 100-1FF 1.0 s, 15 frames, 2 ids (1A0:10 1B4:5), buffer-full 0", notes[1])
        assertEquals("passive: group 200-2FF 1.0 s, 2 frames, 1 ids (2C0:2), buffer-full 0", notes[2])
        // Then the cycle starts again, while passive mode lasts.
        assertTrue(notes.size > 4 && notes[3].startsWith("passive: all ids"), notes.toString())
        assertEquals(1, rig.events.of(SessionEvents.CAPTURE_MODE).count { it.startsWith("passive capture on") })
    }

    @Test
    fun leavingPassiveRestoresTheAdapterAndPollsAgainWithoutProbingTheCarAgain() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        rig.session.setCaptureMode(CaptureMode.PASSIVE)
        run(5.seconds)
        val probes = rig.adapter.received.count { it.replace(" ", "") == "0100" }
        val vinReads = rig.adapter.received.count { it.replace(" ", "").startsWith("0902") }
        val rpmBefore = rig.session.telemetry.value.rpmAt()

        assertTrue(rig.session.setCaptureMode(CaptureMode.ACTIVE))
        run(5.seconds)

        assertEquals(CaptureMode.ACTIVE, rig.session.captureMode.value)
        assertIs<SessionState.Polling>(rig.session.state.value)
        val received = rig.adapter.received
        val stop = received.lastIndexOf("ATMA")
        val restored = received.drop(stop)
        for (command in listOf("ATAR", "ATCAF1", "ATCM7F8", "ATCF7E8")) assertTrue(restored.contains(command), "$command missing in $restored")
        assertEquals(probes, received.count { it.replace(" ", "") == "0100" }, "the car was probed again")
        assertEquals(vinReads, received.count { it.replace(" ", "").startsWith("0902") })
        assertTrue(restored.any(::isDiagnostic), "polling did not resume")
        assertTrue(rig.session.telemetry.value.rpmAt()!! > rpmBefore!!, "no new reading after passive capture")
        assertEquals(1, rig.transport.streams.size)
        assertTrue(rig.events.of(SessionEvents.CAPTURE_MODE).any { it == "back to active polling" })
    }

    @Test
    fun aLinkThatDropsInPassiveModeComesBackActive() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        rig.session.setCaptureMode(CaptureMode.PASSIVE)
        run(3.seconds)
        rig.adapter.disconnect()
        run(1.seconds)
        assertEquals(CaptureMode.ACTIVE, rig.session.captureMode.value)
        run(10.seconds)
        assertEquals(2, rig.transport.streams.size)
        assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(CaptureMode.ACTIVE, rig.session.captureMode.value)
        assertTrue(rig.events.of(SessionEvents.CAPTURE_MODE).any { it.contains("link was lost") })
        assertTrue(rig.adapter.received.any(::isDiagnostic), "no polling on the new link")
    }

    @Test
    fun stoppingTheSessionFromPassiveModeEndsCleanly() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        rig.session.setCaptureMode(CaptureMode.PASSIVE)
        run(2500.milliseconds)
        rig.session.stop()
        assertEquals(SessionState.Stopped, rig.session.state.value)
        assertEquals(1, rig.transport.streams.single().closes)
        assertEquals(CaptureMode.ACTIVE, rig.session.captureMode.value)
    }

    @Test
    fun passiveModeIsRefusedWhileTheSessionIsNotPolling() = runTest {
        val rig = rig()
        assertFalse(rig.session.setCaptureMode(CaptureMode.PASSIVE))
        assertEquals(CaptureMode.ACTIVE, rig.session.captureMode.value)
    }

    @Test
    fun aSilentBusIsNotedKeepsCyclingSlowlyAndFinallyGoesBackToPolling() = runTest {
        val rig = rig(capture = shortCapture.copy(silenceLimit = 6.seconds))
        rig.session.start()
        run(10.seconds)
        rig.session.setCaptureMode(CaptureMode.PASSIVE)
        run(1.seconds)
        rig.vehicle.awake = false
        run(5.seconds)
        assertEquals(CaptureMode.PASSIVE, rig.session.captureMode.value)
        val notes = rig.events.of(SessionEvents.PASSIVE_SLICE)
        assertTrue(notes.any { it.contains("the bus is silent") }, notes.toString())
        assertTrue(notes.any { it.contains("0 frames, 0 ids") }, notes.toString())
        run(10.seconds)
        assertEquals(CaptureMode.ACTIVE, rig.session.captureMode.value)
        assertTrue(rig.events.of(SessionEvents.CAPTURE_MODE).any { it.contains("the bus was silent") })
    }

    @Test
    fun bufferFullIsCountedInTheSliceNote() = runTest {
        val rig = rig(bufferFrames = 8)
        rig.session.start()
        run(10.seconds)
        rig.session.setCaptureMode(CaptureMode.PASSIVE)
        run(2.seconds)
        assertEquals("passive: all ids 1.0 s, 8 frames, 3 ids (1A0:5 1B4:2 0C9:1), buffer-full 1", rig.events.of(SessionEvents.PASSIVE_SLICE).first())
        assertNotNull(rig.session.telemetry.value.rpmAt())
    }
}
