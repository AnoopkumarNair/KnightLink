package knightlink.core.sim

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.common.SessionEvent
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.runtime.BodyOutcome
import knightlink.core.runtime.BodyScanState
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleSession
import knightlink.core.scan.CandidateChecks
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The instrument cluster asked in its extended session (`10 03`, `3E 00` while it lasts, `10 01` always afterwards), against the simulator,
 * whose cluster answers some identifiers only in that session. Read-only otherwise, and never toward another unit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClusterExtendedSessionSimulationTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))
    private val simulation = SimulationFile.parse(File(root, "fixtures/sim/harrier-dpf-regen.json").readText())
    private val adapterDefinition = AdapterDefinition.parse(File(root, "definitions/adapters/vgate-icar-pro-2s.json").readText())
    private val catalog = catalogFor(simulation.definition)

    private fun definition(id: String): DefinitionFile = DefinitionJson.parse(File(root, "definitions/$id.json").readText())

    private fun catalogFor(id: String): SignalCatalog {
        val top = definition(id)
        return SignalCatalog.of(top.extends.map(::definition) + top)
    }

    private class Collected : SessionEventSink {
        val events = ArrayList<SessionEvent>()

        override suspend fun emit(event: SessionEvent) {
            events += event
        }

        fun of(name: String): List<String> = events.filter { it.name == name }.map { it.detail }
    }

    private class Rig(val vehicle: VirtualVehicle, val simulated: SimulatedTransport, val session: VehicleSession, val events: Collected) {
        val adapter: VirtualElm327 get() = simulated.current!!
        val cluster: VirtualEcu get() = vehicle.ecu(0x700)
    }

    /** The extended pass over [range] (F4A6 first), and a body controller at 701 asked in the default session. */
    private fun checks(range: String = """{"from":"F400","to":"F4FF"}""") = CandidateChecks.parse(
        """{"schemaVersion":1,"id":"t","checks":[
            {"requestId":"700","label":"cluster x","unprobed":true,"session":"extended","dids":["F4A6"],"ranges":[$range]},
            {"requestId":"701","label":"body controller","unprobed":true,"dids":["F190"]}
        ]}""",
    )

    private fun TestScope.rig(
        speedKmh: Int = 0,
        candidates: CandidateChecks = checks(),
        configure: (VehicleSession.Config) -> VehicleSession.Config = { it },
        setupCluster: (VirtualEcu) -> Unit = {},
    ): Rig {
        val base = simulation.buildVehicle()
        base.ecu(0x7E0).set("010D", speedKmh.toString(16).padStart(2, '0'))
        val cluster = VirtualEcu(0x700, 0x708)
        cluster.needsCustomFlowControl = true
        cluster.set("1001", "003201F4")
        cluster.set("1003", "003201F4")
        cluster.set("3E00", "")
        cluster.set("1902FF", "FF")
        cluster.setExtended("22F4A6", "0001E240")
        cluster.setExtended("22F4A7", "1234")
        setupCluster(cluster)
        val body = VirtualEcu(0x701, 0x709)
        body.needsCustomFlowControl = true
        body.set("1001", "003201F4")
        body.set("1003", "003201F4")
        body.set("22F190", "41")
        val vehicle = VirtualVehicle(base.ecus + cluster + body)
        val simulated = SimulatedTransport(vehicle = { vehicle }, config = VirtualElm327.Config(responseDelay = 45.milliseconds))
        val config = VehicleSession.Config(
            driver = adapterDefinition.driverConfig(),
            probeScript = adapterDefinition.probeScript,
            pollScript = adapterDefinition.pollScript,
            pollPeriod = { 500.milliseconds },
            candidateChecks = candidates,
        ).let(configure)
        val events = Collected()
        val session = VehicleSession(simulated, adapterDefinition.initScript, catalog, backgroundScope, testTimeSource, config, events)
        return Rig(vehicle, simulated, session, events)
    }

    private fun TestScope.run(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    /** The diagnostic requests (no AT commands) sent while the adapter had [header] set. */
    private fun List<String>.sentTo(header: String): List<String> {
        val out = ArrayList<String>()
        var current = ""
        for (line in this) {
            val compact = line.filterNot(Char::isWhitespace)
            if (compact.startsWith("ATSH")) current = compact.removePrefix("ATSH") else if (!compact.startsWith("AT")) if (current == header) out += compact
        }
        return out
    }

    @Test
    fun theSessionIsOpenedTheIdentifiersReadAndTheSessionAlwaysClosed() = runTest {
        val rig = rig()
        rig.session.start()
        run(80.seconds)
        val notes = rig.events.of(SessionEvents.BODY_SCAN)
        assertEquals(1, rig.cluster.extendedSessions)
        assertFalse(rig.cluster.inExtendedSession, "10 01 afterwards")
        assertTrue(notes.any { it.startsWith("700 cluster x: 22 F4A6 -> positive, 4 data bytes: 0001E240") }, notes.toString())
        val toCluster = rig.adapter.received.sentTo("700")
        assertTrue(toCluster.indexOf("10031") < toCluster.indexOf("22F4A61"), "F4A6 first, in the session: $toCluster")
        assertTrue(toCluster.contains("3E001"), "tester present while in the session")
        val sessions = rig.events.of(SessionEvents.SESSION)
        assertTrue(sessions.any { it.contains("10 03") && it.contains("positive") }, sessions.toString())
        assertTrue(sessions.any { it.contains("10 01") }, sessions.toString())
        assertIs<BodyScanState.Complete>(rig.session.bodyScan.value)
    }

    @Test
    fun noOtherUnitEverGetsTheExtendedSessionOrTesterPresent() = runTest {
        val rig = rig()
        rig.session.start()
        run(80.seconds)
        val sent = rig.adapter.received
        for (header in listOf("7E0", "7DF", "701", "703", "705", "750", "7E1")) {
            assertTrue(sent.sentTo(header).none { it.startsWith("1003") || it.startsWith("3E") }, "$header: ${sent.sentTo(header)}")
        }
        assertEquals(0, rig.vehicle.ecu(0x701).let { if (it.inExtendedSession) 1 else 0 })
    }

    @Test
    fun aRefusedSessionIsLoggedWithItsCodeAndNotRetriedInALoop() = runTest {
        val rig = rig(setupCluster = { it.refuse("1003", 0x12) })
        rig.session.start()
        run(200.seconds)
        val toCluster = rig.adapter.received.sentTo("700")
        assertEquals(1, toCluster.count { it == "10031" }, "asked once: $toCluster")
        assertTrue(toCluster.none { it.startsWith("22") }, "no identifier asked without the session")
        assertTrue(rig.events.of(SessionEvents.SESSION).any { it.contains("10 03") && it.contains("refused, code 12") })
        assertTrue(toCluster.contains("10011"), "closed all the same")
        assertIs<SessionState.Polling>(rig.session.state.value)
    }

    @Test
    fun aNotNowRefusalIsNotFinishedForGoodAndNotRetriedOnTheSameConnection() = runTest {
        // Conditions not correct (22): another connection may do; the extended pass stays pending, not done for the lifetime.
        val rig = rig(setupCluster = { it.refuse("1003", 0x22) })
        rig.session.start()
        run(30.seconds)
        assertEquals(1, rig.session.bodyPendingUnits.value, "the extended pass is still pending")
        run(200.seconds)
        assertEquals(1, rig.adapter.received.sentTo("700").count { it == "10031" }, "no loop on this connection")
        assertTrue(rig.events.of(SessionEvents.BODY_SCAN).any { it.contains("tried again in a later stage (1 so far)") })
    }

    @Test
    fun movementEndsTheSweepAndTheDefaultSessionIsRestored() = runTest {
        val rig = rig(candidates = checks("""{"from":"F000","to":"F4FF"}"""))
        rig.session.start()
        run(15.seconds)
        assertTrue(rig.cluster.inExtendedSession, "in the session during the stage")
        rig.vehicle.ecu(0x7E0).set("010D", "14")
        run(5.seconds)
        val partial = assertIs<BodyScanState.Partial>(rig.session.bodyScan.value)
        assertEquals(BodyOutcome.MOVING, partial.ended)
        assertFalse(rig.cluster.inExtendedSession, "10 01 on movement")
        assertTrue(rig.adapter.received.contains("ATCF7E8"), "the ordinary filter restored")
        assertEquals(1, rig.session.bodyPendingUnits.value.coerceAtMost(1), "progress kept for later")
    }

    @Test
    fun aLostLinkInTheSessionEndsTheStageCleanlyAndTheNextConnectionOpensItAfresh() = runTest {
        val rig = rig(candidates = checks("""{"from":"F000","to":"F4FF"}"""))
        rig.session.start()
        run(15.seconds)
        assertTrue(rig.cluster.inExtendedSession)
        rig.adapter.disconnect()
        run(500.milliseconds)
        assertIs<SessionState.Reconnecting>(rig.session.state.value)
        assertIs<BodyScanState.Partial>(rig.session.bodyScan.value)
        run(120.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
        // The next connection did not assume a session: it asked 10 03 again before any identifier.
        val toCluster = rig.adapter.received.sentTo("700")
        val firstRead = toCluster.indexOfFirst { it.startsWith("22") }
        assertTrue(firstRead < 0 || toCluster.indexOf("10031") in 0 until firstRead, toCluster.take(10).toString())
    }

    @Test
    fun switchedOffTheClusterIsNeverPutInTheExtendedSession() = runTest {
        val rig = rig(configure = { it.copy(bodyExtendedSession = false) })
        rig.session.start()
        run(80.seconds)
        assertEquals(0, rig.cluster.extendedSessions)
        assertTrue(rig.adapter.received.sentTo("700").none { it.startsWith("1003") || it.startsWith("3E") })
    }

    @Test
    fun whileMovingTheSessionIsNeverOpened() = runTest {
        val rig = rig(speedKmh = 40, configure = { it.copy(bodyPauseWhileMoving = false) })
        rig.session.start()
        run(150.seconds)
        assertEquals(0, rig.cluster.extendedSessions, "background slices never ask the extended pass")
        assertTrue(rig.adapter.received.any { it.startsWith("22F190") }, "the default-session unit is still asked in slices")
    }

    @Test
    fun stoppingTheSessionInTheMiddleTriesToEndTheExtendedSessionAndSaysWhatHappened() = runTest {
        // On Disconnect the driver is stopped together with the stage, so the 10 01 cannot always go out: it is tried, bounded, and logged;
        // the cluster then leaves the session by itself (no TesterPresent any more). Nothing hangs and nothing throws out of the session.
        val rig = rig(candidates = checks("""{"from":"F000","to":"F4FF"}"""))
        rig.session.start()
        run(15.seconds)
        assertTrue(rig.cluster.inExtendedSession)
        rig.session.stop()
        run(5.seconds)
        assertIs<SessionState.Stopped>(rig.session.state.value)
        val notes = rig.events.of(SessionEvents.SESSION)
        assertTrue(notes.last().contains("10 01 (back to the default session) ->"), notes.toString())
        val sent = rig.adapter.received.size
        run(10.seconds)
        assertEquals(sent, rig.adapter.received.size, "no TesterPresent or anything else after the stop")
    }

    @Test
    fun aClusterThatStopsAnsweringTesterPresentDoesNotHangTheStage() = runTest {
        val rig = rig(candidates = checks("""{"from":"F000","to":"F4FF"}"""), setupCluster = {
            it.remove("3E00")
            it.refuse("3E00", 0x11)
        })
        rig.session.start()
        run(150.seconds)
        val notes = rig.events.of(SessionEvents.SESSION)
        assertEquals(1, notes.count { it.contains("3E 00") }, "logged once: $notes")
        assertFalse(rig.cluster.inExtendedSession)
        assertIs<SessionState.Polling>(rig.session.state.value)
    }
}
