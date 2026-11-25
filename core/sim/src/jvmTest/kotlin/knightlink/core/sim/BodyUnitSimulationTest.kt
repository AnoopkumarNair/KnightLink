package knightlink.core.sim

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.common.SessionEvent
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.runtime.BodyOutcome
import knightlink.core.runtime.BodyScanState
import knightlink.core.runtime.InMemoryVehicleMemoryStore
import knightlink.core.runtime.ResolvedVehicle
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleMemoryStore
import knightlink.core.runtime.VehicleResolver
import knightlink.core.runtime.VehicleSession
import knightlink.core.scan.CandidateChecks
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The one-time check of the body units (instrument cluster 700 and the like) against the simulator, whose cluster, like a real one, is heard
 * only when the adapter listens to 708 and has flow control set for 700: with the ordinary 7E8 filter every request of it ends in NO DATA.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BodyUnitSimulationTest {
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
        val states = ArrayList<SessionState>()
        val cluster: VirtualEcu get() = vehicle.ecu(0x700)
    }

    private fun checks(json: String, appliesTo: String = "[]") =
        CandidateChecks.parse("""{"schemaVersion":1,"id":"t","appliesTo":$appliesTo,"checks":$json}""")

    private val clusterChecks = checks(
        """[
            {"requestId":"700","label":"instrument cluster","unprobed":true,"dids":["F187","0101"],"ranges":[{"from":"F190","to":"F193"}]},
            {"requestId":"701","label":"body controller","unprobed":true,"dids":["F190"]}
        ]""",
    )

    /** The Harrier simulation plus a cluster at 700 (answering from 708) that is parked, unless [speedKmh] says otherwise. */
    private fun TestScope.rig(
        candidates: CandidateChecks? = clusterChecks,
        speedKmh: Int = 0,
        memory: VehicleMemoryStore? = null,
        configure: (VehicleSession.Config) -> VehicleSession.Config = { it },
        vehicle: VirtualVehicle = vehicleWithCluster(speedKmh),
        unsupported: Set<String> = emptySet(),
    ): Rig {
        val simulated = SimulatedTransport(vehicle = { vehicle }, config = VirtualElm327.Config(responseDelay = 45.milliseconds, unsupported = unsupported))
        val config = VehicleSession.Config(
            driver = adapterDefinition.driverConfig(),
            probeScript = adapterDefinition.probeScript,
            pollScript = adapterDefinition.pollScript,
            pollPeriod = { 500.milliseconds },
            candidateChecks = candidates,
            memory = memory,
        ).let(configure)
        val events = Collected()
        val session = VehicleSession(simulated, adapterDefinition.initScript, catalog, backgroundScope, testTimeSource, config, events)
        return Rig(vehicle, simulated, session, events).also { rig ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { rig.states += it } }
        }
    }

    private fun vehicleWithCluster(speedKmh: Int, needsSession: Boolean = true): VirtualVehicle {
        val base = simulation.buildVehicle()
        base.ecu(0x7E0).set("010D", speedKmh.toString(16).padStart(2, '0'))
        val cluster = VirtualEcu(0x700, 0x708)
        cluster.needsCustomFlowControl = true
        cluster.needsDefaultSession = needsSession
        cluster.set("1001", "003201F4")
        cluster.set("1902FF", "FF")
        // 10 ASCII bytes: a reply of two frames (13 bytes with the service and identifier), which goes on only when flow control is set for 700.
        cluster.set("22F187", "504152544E554D424552")
        cluster.set("220101", "1234")
        return VirtualVehicle(base.ecus + cluster)
    }

    private fun TestScope.run(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun List<String>.indexOfCommand(command: String, from: Int = 0): Int = drop(from).indexOfFirst { it.filterNot(Char::isWhitespace) == command }.let {
        if (it <
            0
        ) {
            -1
        } else {
            it + from
        }
    }

    /** The background slices as they were before 2026-11-25: also while the car moves (these tests are about how a slice asks). */
    private val slicesWhileMoving = { c: VehicleSession.Config -> c.copy(bodyPauseWhileMoving = false) }

    private fun isDiagnostic(command: String): Boolean = !command.startsWith("AT") && command.isNotBlank()

    // (a) the identifiers are answered and recorded in the parked stage ----------------------------------------------

    @Test
    fun theParkedStageSetsTheReceiveSideAndTheClusterAnswersItsIdentifiers() = runTest {
        val rig = rig()
        rig.session.start()
        run(40.seconds)

        val sent = rig.adapter.received
        // The receive side first, in this order, then the session request, the fault codes and the identifiers.
        val cra = sent.indexOfCommand("ATCRA708")
        val fcsh = sent.indexOfCommand("ATFCSH700", cra)
        val fcsd = sent.indexOfCommand("ATFCSD300000", fcsh)
        val fcsm = sent.indexOfCommand("ATFCSM1", fcsd)
        val faults = sent.indexOfCommand("1902FF", fcsm)
        val refused = sent.indexOfCommand("22F1871", faults)
        // The unit refuses 22 until it is in its default session: only now is 10 01 sent (right after an ATSH), and the identifier asked again.
        val session = sent.indexOfCommand("10011", refused)
        val again = sent.indexOfCommand("22F1871", session)
        assertTrue(cra >= 0 && cra < fcsh && fcsh < fcsd && fcsd < fcsm && fcsm < faults && faults < refused, sent.toString())
        assertTrue(refused < session && session < again, sent.toString())
        assertEquals("ATSH700", sent[session - 1], "the header goes out right before the session request")

        // What it answered is in the session's events (identifiers and lengths, not values), and the unit is done.
        val notes = rig.events.of(SessionEvents.BODY_SCAN)
        val cluster = notes.single { it.startsWith("700 instrument cluster: finished") }
        assertTrue(cluster.contains("F187(10)") && cluster.contains("0101(2)"), cluster)
        assertTrue(notes.any { it.startsWith("700 instrument cluster: 10 01 (after F187 was refused with 7F) -> positive") }, notes.toString())
        assertTrue(notes.any { it.startsWith("700 instrument cluster: 19 02 FF -> positive") }, notes.toString())
        val setup = rig.events.of(SessionEvents.BODY_SETUP)
        assertTrue(setup.containsAll(listOf("ATCRA708 -> OK", "ATFCSH700 -> OK", "ATFCSD300000 -> OK", "ATFCSM1 -> OK")), setup.toString())
        assertEquals(BodyScanState.Complete(answered = 2), rig.session.bodyScan.value)
        assertIs<SessionState.Polling>(rig.session.state.value)
    }

    // (b) the normal filter comes back and polling goes on ------------------------------------------------------------

    @Test
    fun afterTheStageTheOrdinaryFilterAndFlowControlAreBackAndPollingContinues() = runTest {
        val rig = rig()
        rig.session.start()
        run(40.seconds)

        val sent = rig.adapter.received
        val lastBodyRequest = sent.indexOfLast { it.startsWith("22F19") || it.startsWith("220101") }
        val ar = sent.indexOfCommand("ATAR", lastBodyRequest)
        val cm = sent.indexOfCommand("ATCM7F8", ar)
        val cf = sent.indexOfCommand("ATCF7E8", cm)
        val fc = sent.indexOfCommand("ATFCSM0", cf)
        assertTrue(lastBodyRequest in 0 until ar && ar < cm && cm < cf && cf < fc, sent.toString())
        assertTrue(rig.events.of(SessionEvents.BODY_SETUP).containsAll(listOf("ATAR -> OK", "ATCM7F8 -> OK", "ATCF7E8 -> OK", "ATFCSM0 -> OK")))
        // 7E0 is polled and answered again afterwards (its reply passes the restored filter).
        val after = sent.drop(fc)
        assertTrue(after.count { it.startsWith("010C") || it.startsWith("0105") } > 5, "polling did not go on: ${after.take(20)}")
        val rpm = rig.session.telemetry.value.readings.firstOrNull { it.reading.signal.id == "engine.rpm" }
        assertTrue(rpm != null, "no engine speed reading")
    }

    // (c) the car moving skips or aborts the parked stage ------------------------------------------------------------

    @Test
    fun aMovingCarSkipsTheParkedStageAndTheUnitsAreAskedInTheBackground() = runTest {
        val rig = rig(speedKmh = 40, configure = slicesWhileMoving)
        rig.session.start()
        run(12.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(BodyScanState.Skipped(moving = true, reason = "the car moves (40 km/h)"), rig.session.bodyScan.value)
        assertTrue(rig.adapter.received.none { it.startsWith("ATCRA") }, "nothing before the first background slice")

        run(60.seconds)
        val sent = rig.adapter.received
        assertTrue(sent.indexOfCommand("ATCRA708") >= 0, "the background mode uses the same receive setup")
        assertTrue(sent.any { it.startsWith("22F187") }, "the cluster is asked in the background")
        // And the ordinary filter is back after every slice, so the engine unit keeps answering.
        assertTrue(sent.count { it == "ATAR" } >= 2, sent.count { it == "ATAR" }.toString())
        assertTrue(
            rig.states.none {
                it is SessionState.Recovering || it is SessionState.Reconnecting || it is SessionState.VehicleAsleep
            },
            rig.states.toString(),
        )
    }

    @Test
    fun aCandidateCheckMarkedUnprobedIsAskedEvenThoughTheProbeDidNotListTheUnitAndItsReplyIsActuallyHeard() = runTest {
        // The probe never lists 700 (it is not on the OBD addresses); the old check sent its requests under the 7E8 filter and logged NO DATA for all.
        val rig = rig(speedKmh = 40, configure = slicesWhileMoving)
        rig.session.start()
        run(60.seconds)
        val notes = rig.events.of(SessionEvents.BODY_SCAN)
        assertTrue(notes.any { it.startsWith("700 instrument cluster: 19 02 FF -> positive") }, notes.toString())
        assertTrue(notes.any { it.startsWith("700 instrument cluster: finished") && it.contains("F187(10)") }, notes.toString())
    }

    @Test
    fun anUnreadableSpeedAlsoSkipsTheParkedStage() = runTest {
        val vehicle = vehicleWithCluster(0)
        vehicle.ecu(0x7E0).remove("010D")
        val rig = rig(vehicle = vehicle)
        rig.session.start()
        run(12.seconds)
        val state = assertIs<BodyScanState.Skipped>(rig.session.bodyScan.value)
        assertFalse(state.moving)
        assertTrue(rig.adapter.received.none { it.startsWith("ATCRA") })
    }

    @Test
    fun theStageStopsAtOnceWhenTheCarStartsToMove() = runTest {
        // 512 identifiers take half a minute at the scan's pace: long enough to start moving in the middle.
        val long = checks("""[{"requestId":"700","label":"instrument cluster","unprobed":true,"ranges":[{"from":"F000","to":"F1FF"}]}]""")
        val rig = rig(candidates = long)
        rig.session.start()
        run(12.seconds)
        assertIs<BodyScanState.Running>(rig.session.bodyScan.value)
        rig.vehicle.ecu(0x7E0).set("010D", "14")
        run(8.seconds)

        val asked = rig.adapter.received.count { it.startsWith("22F") }
        assertTrue(asked in 1..300, "stopped early, not after all 512: $asked")
        assertTrue(rig.events.of(SessionEvents.BODY_SCAN).any { it.startsWith("the car is moving (20 then 20 km/h") }, rig.events.of(SessionEvents.BODY_SCAN).toString())
        val partial = assertIs<BodyScanState.Partial>(rig.session.bodyScan.value)
        assertEquals(BodyOutcome.MOVING, partial.ended, "the screen says why the stage ended, not \"you can drive\"")
        assertEquals(20, partial.speedKmh)
        assertTrue(rig.adapter.received.contains("ATCF7E8"), "restored")
    }

    /** 2026-11-24 10:35:19: one reading of 3 km/h (an automatic creeping, the car standing) ended a parked stage 21 s in. */
    @Test
    fun aLowSpeedReadingDoesNotEndTheParkedStage() = runTest {
        val long = checks("""[{"requestId":"700","label":"instrument cluster","unprobed":true,"ranges":[{"from":"F000","to":"F1FF"}]}]""")
        val rig = rig(candidates = long)
        rig.session.start()
        run(12.seconds)
        assertIs<BodyScanState.Running>(rig.session.bodyScan.value)
        rig.vehicle.ecu(0x7E0).set("010D", "03")
        run(12.seconds)
        val notes = rig.events.of(SessionEvents.BODY_SCAN)
        assertTrue(notes.any { it.startsWith("the speed read 3 km/h, below 5") }, notes.toString())
        assertTrue(notes.none { it.startsWith("the car is moving") }, notes.toString())
        assertIs<BodyScanState.Running>(rig.session.bodyScan.value, "still running")
    }

    @Test
    fun aSingleHighSpeedReadingThatIsNotConfirmedDoesNotEndTheParkedStage() = runTest {
        val long = checks("""[{"requestId":"700","label":"instrument cluster","unprobed":true,"ranges":[{"from":"F000","to":"F1FF"}]}]""")
        val rig = rig(candidates = long)
        rig.session.start()
        run(12.seconds)
        assertIs<BodyScanState.Running>(rig.session.bodyScan.value)
        fun speedReads() = rig.adapter.received.count { it.startsWith("010D") }
        val before = speedReads()
        rig.vehicle.ecu(0x7E0).set("010D", "14")
        var waited = 0
        while (speedReads() == before && waited++ < 400) run(20.milliseconds)
        run(300.milliseconds)
        // One noisy reading: the value is back to 0 before it is read again.
        rig.vehicle.ecu(0x7E0).set("010D", "00")
        run(10.seconds)
        val notes = rig.events.of(SessionEvents.BODY_SCAN)
        assertTrue(notes.any { it.startsWith("the speed read 20 km/h once, then 0 km/h: not confirmed") }, notes.toString())
        assertTrue(notes.none { it.startsWith("the car is moving") }, notes.toString())
        assertIs<BodyScanState.Running>(rig.session.bodyScan.value, "still running")
    }

    @Test
    fun aFollowingCandidateFileKeepsTheProgressOfUnchangedUnitsAndAsksTheChangedUnitOnlyItsNewIdentifiers() = runTest {
        val store = InMemoryVehicleMemoryStore()
        val v1 = CandidateChecks.parse(
            """{"schemaVersion":1,"id":"v1","checks":[
                {"requestId":"700","label":"instrument cluster","unprobed":true,"dids":["F187"]},
                {"requestId":"701","label":"body controller","unprobed":true,"dids":["F190"]}]}""",
        )
        val controller = VirtualEcu(0x701, 0x709)
        controller.needsCustomFlowControl = true
        controller.set("1902FF", "FF")
        controller.set("22F190", "01")
        val first = rig(candidates = v1, memory = store, vehicle = VirtualVehicle(vehicleWithCluster(0).ecus + controller))
        first.session.start()
        run(40.seconds)
        assertIs<BodyScanState.Complete>(first.session.bodyScan.value)
        first.session.stop()

        val v2 = CandidateChecks.parse(
            """{"schemaVersion":1,"id":"v2","continues":"v1","checks":[
                {"requestId":"700","label":"instrument cluster","unprobed":true,"dids":["0101","0102"]},
                {"requestId":"701","label":"body controller","unprobed":true,"dids":["F190"]}]}""",
        )
        val second = rig(candidates = v2, memory = store, vehicle = first.vehicle)
        second.session.start()
        run(40.seconds)
        val sent = second.adapter.received
        assertTrue(sent.any { it.startsWith("220101") }, "the cluster is asked its new identifiers: $sent")
        assertTrue(sent.none { it.startsWith("22F187") }, "not the old ones")
        assertTrue(sent.none { it == "ATCRA709" }, "the unchanged body controller is not asked again")
        assertTrue(second.events.of(SessionEvents.BODY_SCAN).any { it.startsWith("700 instrument cluster: 22 0101 -> positive, 2 data bytes: 1234") })
        assertTrue(store.texts().single().contains("\"v2\""))
    }

    @Test
    fun theStageEndsAtTheTimeCapAndSaysSo() = runTest {
        val long = checks("""[{"requestId":"700","label":"instrument cluster","unprobed":true,"ranges":[{"from":"F000","to":"F7FF"}]}]""")
        val rig = rig(candidates = long, configure = { it.copy(bodyStageCap = 10.seconds) })
        rig.session.start()
        run(40.seconds)
        assertIs<BodyScanState.Partial>(rig.session.bodyScan.value)
        assertIs<SessionState.Polling>(rig.session.state.value)
    }

    @Test
    fun skippingEndsTheParkedStageAtOnceAndTheRestIsAskedInTheBackground() = runTest {
        val long = checks("""[{"requestId":"700","label":"instrument cluster","unprobed":true,"ranges":[{"from":"F000","to":"F7FF"}]}]""")
        val rig = rig(candidates = long, configure = { it.copy(bodyStageCap = 5.minutes) })
        rig.session.start()
        run(10.seconds)
        assertIs<BodyScanState.Running>(rig.session.bodyScan.value, "the stage is still running before the skip")
        rig.session.skipBodyScan()
        run(5.seconds)
        assertIs<BodyScanState.Partial>(rig.session.bodyScan.value)
        assertIs<SessionState.Polling>(rig.session.state.value)
        assertTrue(rig.adapter.received.contains("ATCF7E8"), "the ordinary receive filter is back")
        assertTrue(rig.events.of(SessionEvents.BODY_SCAN).any { it.contains("skipped") }, "the stage says it was skipped")
    }

    @Test
    fun skippingWhenNothingRunsDoesNothingAndDoesNotSkipTheNextStage() = runTest {
        val rig = rig()
        rig.session.skipBodyScan()
        rig.session.start()
        run(40.seconds)
        assertEquals(BodyScanState.Complete(answered = 2), rig.session.bodyScan.value)
    }

    // (d) a silent unit is absent, never trouble -------------------------------------------------------------------------

    @Test
    fun aClusterThatNeverAnswersTriggersNeitherTheWatchdogNorTheAsleepLogic() = runTest {
        val vehicle = vehicleWithCluster(0)
        vehicle.ecu(0x700).silent = true
        val long =
            checks(
                """[{"requestId":"700","label":"instrument cluster","unprobed":true,"ranges":[{"from":"F100","to":"F13F"}]},{"requestId":"701","label":"body controller","unprobed":true,"dids":["F190"]}]""",
            )
        val rig = rig(candidates = long, vehicle = vehicle, configure = { it.copy(asleepAfter = 3.seconds, silenceWindow = 5.seconds) })
        rig.session.start()
        run(90.seconds)

        assertIs<SessionState.Polling>(rig.session.state.value)
        assertTrue(
            rig.states.none {
                it is SessionState.Recovering || it is SessionState.Reconnecting || it is SessionState.VehicleAsleep
            },
            rig.states.toString(),
        )
        assertTrue(
            rig.events.of(SessionEvents.ASLEEP).isEmpty() && rig.events.of(SessionEvents.SILENCE).isEmpty() && rig.events.of(SessionEvents.REINIT).isEmpty(),
        )
        assertTrue(rig.events.of(SessionEvents.BODY_SCAN).any { it.contains("absent") }, rig.events.of(SessionEvents.BODY_SCAN).toString())
    }

    @Test
    fun aSilentClusterWhileDrivingAlsoCostsNothing() = runTest {
        val vehicle = vehicleWithCluster(60)
        vehicle.ecu(0x700).silent = true
        val rig = rig(vehicle = vehicle, configure = { it.copy(asleepAfter = 3.seconds, silenceWindow = 5.seconds) })
        rig.session.start()
        run(120.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
        assertTrue(
            rig.states.none {
                it is SessionState.Recovering || it is SessionState.Reconnecting || it is SessionState.VehicleAsleep
            },
            rig.states.toString(),
        )
    }

    // (e) the default session is requested once per unit ------------------------------------------------------------------

    @Test
    fun theDefaultSessionIsRequestedOnceForEachBodyUnit() = runTest {
        val rig =
            rig(
                candidates = checks(
                    """[{"requestId":"700","label":"instrument cluster","unprobed":true,"dids":["F187","0101"],"ranges":[{"from":"F190","to":"F193"}]}]""",
                ),
                speedKmh = 40,
                configure = slicesWhileMoving,
            )
        rig.session.start()
        // Several background slices: the unit is gone through over more than one of them.
        run(150.seconds)
        val sent = rig.adapter.received
        assertEquals(1, sent.count { it == "10011" }, "10 01 for 700 once, after the first plain 22 was refused")
        assertEquals(1, sent.count { it == "1902FF" }, "19 02 FF for 700 once")
        // ... and only a body unit gets it: never the engine-bay units.
        assertTrue(sent.none { it.startsWith("10") && it != "10011" })
    }

    @Test
    fun noServiceThatChangesTheVehicleIsEverSentWhateverTheCheckDoes() = runTest {
        val rig = rig()
        rig.session.start()
        run(60.seconds)
        val services = rig.adapter.received.filter(::isDiagnostic).map { it.take(2) }.toSet()
        assertTrue(services.all { it in setOf("01", "09", "22", "19", "10") }, services.toString())
    }

    // Once per vehicle -----------------------------------------------------------------------------------------------------

    @Test
    fun aVehicleThatWasCheckedIsNotCheckedAgainInTheNextSession() = runTest {
        val store = InMemoryVehicleMemoryStore()
        val first = rig(memory = store)
        first.session.start()
        run(40.seconds)
        assertTrue(first.adapter.received.contains("ATCRA708"))
        first.session.stop()

        val second = rig(memory = store, vehicle = first.vehicle)
        second.session.start()
        run(40.seconds)
        assertIs<SessionState.Polling>(second.session.state.value)
        // The cluster is done for good; 701 never answered, so it is tried again (up to three times in all), but nothing else is.
        assertTrue(second.adapter.received.none { it == "ATCRA708" }, "asked again: ${second.adapter.received.filter { it.startsWith("ATCRA") }}")
        assertTrue(store.texts().single().contains("\"body\""))
    }

    /**
     * As on the phone: the checks come with the vehicle's definition (the resolver), not with the config, so their id is known only after the
     * memory was read. On 2026-11-24 every app restart ran the parked stage again from the start because of that.
     */
    @Test
    fun theProgressOfTheCheckSurvivesAnAppRestartWhenTheChecksComeFromTheResolvedDefinition() = runTest {
        val store = InMemoryVehicleMemoryStore()
        val viaResolver: (VehicleSession.Config) -> VehicleSession.Config = {
            it.copy(candidateChecks = null, resolver = VehicleResolver { ResolvedVehicle(catalog, known = true, candidateChecks = clusterChecks) })
        }
        val first = rig(candidates = null, memory = store, configure = viaResolver)
        first.session.start()
        run(40.seconds)
        assertTrue(first.adapter.received.contains("ATCRA708"), "the first session checks the cluster")
        assertIs<BodyScanState.Complete>(first.session.bodyScan.value)
        first.session.stop()

        // The app is closed and opened again: a new session on the same memory.
        val second = rig(candidates = null, memory = store, configure = viaResolver, vehicle = first.vehicle)
        second.session.start()
        run(40.seconds)
        assertIs<SessionState.Polling>(second.session.state.value)
        assertTrue(second.adapter.received.none { it == "ATCRA708" }, "checked again: ${second.adapter.received.filter { it.startsWith("ATCRA") }}")
        assertTrue(second.events.of(SessionEvents.BODY_SCAN).none { it.startsWith("parked stage: ") && it.contains("2 body units") }, second.events.of(SessionEvents.BODY_SCAN).toString())
    }

    @Test
    fun theBatteryVoltageIsReadOncePerConnectionAndNoted() = runTest {
        val rig = rig(candidates = null)
        rig.session.start()
        run(10.seconds)
        assertEquals(listOf("14.1V"), rig.events.of(SessionEvents.BATTERY))
        assertEquals(1, rig.adapter.received.count { it == "ATRV" })
    }

    // Only on a known car ---------------------------------------------------------------------------------------------------

    private val oneUnit = """[{"requestId":"700","label":"instrument cluster","unprobed":true,"dids":["F187","0101"]}]"""

    @Test
    fun aVehicleWhoseVinIsNotInAppliesToIsNeverAskedAnythingOfTheBodyUnits() = runTest {
        val rig = rig(candidates = checks(oneUnit, appliesTo = """["MAT"]"""))
        rig.session.start()
        run(60.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
        val sent = rig.adapter.received
        assertTrue(
            sent.none {
                it.startsWith("ATCRA") || it == "10011" || it.startsWith("22F18") || it.startsWith("220101") || it == "1902FF"
            },
            sent.toString(),
        )
        assertTrue(rig.events.of(SessionEvents.BODY_SCAN).contains("skipped: not a known vehicle"), rig.events.of(SessionEvents.BODY_SCAN).toString())
        assertEquals(BodyScanState.Idle, rig.session.bodyScan.value)
    }

    @Test
    fun aVehicleWhoseVinStartsWithAnAppliesToPrefixIsChecked() = runTest {
        val rig = rig(candidates = checks(oneUnit, appliesTo = """["MAT","SIM"]"""))
        rig.session.start()
        run(40.seconds)
        assertTrue(rig.adapter.received.contains("ATCRA708"))
        assertEquals(BodyScanState.Complete(answered = 2), rig.session.bodyScan.value)
    }

    @Test
    fun aVehicleWithoutAVinIsNotChecked() = runTest {
        val vehicle = vehicleWithCluster(0)
        vehicle.ecus.forEach { it.remove("0902") }
        val rig = rig(candidates = checks(oneUnit, appliesTo = """["SIM"]"""), vehicle = vehicle)
        rig.session.start()
        run(40.seconds)
        assertTrue(rig.adapter.received.none { it.startsWith("ATCRA") })
        assertTrue(rig.events.of(SessionEvents.BODY_SCAN).contains("skipped: not a known vehicle"))
    }

    // The session request --------------------------------------------------------------------------------------------------

    @Test
    fun aUnitThatAnswersWithoutASessionIsNeverSentTenZeroOne() = runTest {
        val rig = rig(vehicle = vehicleWithCluster(0, needsSession = false))
        rig.session.start()
        run(40.seconds)
        assertTrue(rig.adapter.received.none { it.startsWith("10") }, "10 01 although the first 22 was answered")
        assertEquals(BodyScanState.Complete(answered = 2), rig.session.bodyScan.value)
    }

    // The scheduler keeps its timing across background slices -----------------------------------------------------------------

    private fun List<String>.liveRequests() = count { it.startsWith("01") && !it.startsWith("0100") && it.length >= 4 }

    @Test
    fun backgroundSlicesDoNotMakeTheSlowSignalsDueAgainEveryFifteenSeconds() = runTest {
        // Every signal is asked every 30 s; a slice every 15 s must not bring them all back at once.
        val slow = { c: VehicleSession.Config -> c.copy(pollPeriod = { 30.seconds }, silenceWindow = 60.seconds, asleepAfter = Duration.INFINITE) }
        val long = checks("""[{"requestId":"700","label":"c","unprobed":true,"ranges":[{"from":"F000","to":"F7FF"}]}]""")
        val rig = rig(speedKmh = 40, configure = slow, candidates = long)
        rig.session.start()
        run(12.seconds)
        val firstRound = rig.adapter.received.liveRequests()
        run(120.seconds)
        val total = rig.adapter.received.liveRequests()
        assertTrue(firstRound > 0)
        // About four rounds of the three live units fall in 120 s at a period of 30 s; a scheduler restarted after every slice asks twice as often.
        assertTrue(total - firstRound <= 16, "${total - firstRound} live requests in 120 s after $firstRound: re-asked after every slice")
        assertTrue(rig.adapter.received.count { it == "ATAR" } >= 4, "slices did run")
    }

    // The adapter and the link ---------------------------------------------------------------------------------------------

    @Test
    fun anAdapterThatDoesNotKnowAtcraGetsAMaskAndAFilterInstead() = runTest {
        val rig = rig(unsupported = setOf("ATCRA708", "ATCRA7E8"))
        rig.session.start()
        run(40.seconds)
        val sent = rig.adapter.received
        assertTrue(sent.contains("ATCM7FF") && sent.contains("ATCF708"), sent.filter { it.startsWith("ATC") }.toString())
        assertTrue(rig.events.of(SessionEvents.BODY_SETUP).contains("ATCRA708 -> ?"))
        assertEquals(BodyScanState.Complete(answered = 2), rig.session.bodyScan.value)
        assertTrue(sent.indexOfCommand("ATCF7E8") > sent.indexOfCommand("ATCF708"), "restored")
    }

    private val longRange = """[{"requestId":"700","label":"instrument cluster","unprobed":true,"ranges":[{"from":"F000","to":"F1FF"}]}]"""

    @Test
    fun aLinkLostInTheMiddleOfTheStageLeavesNoFrozenBanner() = runTest {
        val rig = rig(candidates = checks(longRange))
        rig.session.start()
        run(15.seconds)
        assertIs<BodyScanState.Running>(rig.session.bodyScan.value)
        rig.adapter.disconnect()
        run(500.milliseconds)
        assertIs<SessionState.Reconnecting>(rig.session.state.value)
        assertIs<BodyScanState.Partial>(rig.session.bodyScan.value)
        // The next connection carries on and finishes.
        run(80.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
        assertTrue(rig.simulated.opens >= 2)
        assertIs<BodyScanState.Complete>(rig.session.bodyScan.value)
    }

    @Test
    fun anAdapterThatResetsInTheMiddleOfTheStageGetsItsReceiveSetupAgain() = runTest {
        val rig = rig(candidates = checks(longRange))
        rig.session.start()
        run(15.seconds)
        rig.adapter.powerDipBeforeNextCommand()
        run(40.seconds)
        val setup = rig.events.of(SessionEvents.BODY_SETUP)
        assertTrue(setup.any { it.startsWith("the adapter was initialised again") }, setup.toString())
        // F187 comes well after the reset, and is still heard.
        val notes = rig.events.of(SessionEvents.BODY_SCAN)
        assertTrue(notes.any { it.startsWith("700 instrument cluster: finished") && it.contains("F187(10)") }, notes.toString())
    }

    // Refusals that say "not now" ------------------------------------------------------------------------------------------

    @Test
    fun aUnitThatOnlySaysNotNowIsNotFinishedAndIsAskedAgainNextSession() = runTest {
        val store = InMemoryVehicleMemoryStore()
        val busy = vehicleWithCluster(0, needsSession = false)
        // Busy, conditions not correct, response pending, conditions, busy: none of them says anything about the identifiers.
        listOf("F180" to 0x21, "F181" to 0x22, "F182" to 0x78, "F183" to 0x22, "F184" to 0x21, "F185" to 0x21).forEach { (did, code) ->
            busy.ecu(0x700).refuse("22$did", code)
        }
        busy.ecu(0x700).remove("1902FF")
        val one = checks("""[{"requestId":"700","label":"instrument cluster","unprobed":true,"ranges":[{"from":"F180","to":"F185"}]}]""")
        val first = rig(candidates = one, memory = store, vehicle = busy)
        first.session.start()
        run(80.seconds)
        first.session.stop()
        val notes = first.events.of(SessionEvents.BODY_SCAN)
        assertTrue(notes.any { it.contains("answered not now") }, notes.toString())
        assertTrue(store.texts().single().contains("\"done\":false"), store.texts().single())

        val second = rig(candidates = one, memory = store, vehicle = busy)
        second.session.start()
        run(80.seconds)
        assertTrue(second.adapter.received.contains("ATCRA708"), "a unit that was busy is asked again")
    }

    // A late reply of something else ---------------------------------------------------------------------------------------

    @Test
    fun aReplyForAnotherIdentifierIsDroppedNotTakenForTheAnswer() = runTest {
        val vehicle = vehicleWithCluster(0, needsSession = false)
        // The unit answers the request for F187 with the reply to F188, as a late reply to the one before would arrive.
        vehicle.ecu(0x700).setRaw("22F187", "62F18841")
        val rig = rig(candidates = checks(oneUnit), vehicle = vehicle)
        rig.session.start()
        run(40.seconds)
        val notes = rig.events.of(SessionEvents.BODY_SCAN)
        val finished = notes.single { it.startsWith("700 instrument cluster: finished") }
        assertTrue(finished.contains("answered 1 identifiers") && !finished.contains("F187"), finished)
        assertEquals(1, rig.adapter.received.count { it.startsWith("22F187") }, "not asked again as if it were a cut reply")
    }

    // (h) the parked stage asked for again ------------------------------------------------------------------------------

    @Test
    fun theParkedStageCanBeRunAgainAndAsksOnlyWhatIsLeft() = runTest {
        val long = checks("""[{"requestId":"700","label":"instrument cluster","unprobed":true,"ranges":[{"from":"F000","to":"F7FF"}]}]""")
        val rig = rig(candidates = long, configure = { it.copy(bodyStageCap = 10.seconds, bodyPacedEvery = 10.minutes) })
        rig.session.start()
        run(40.seconds)
        assertIs<BodyScanState.Partial>(rig.session.bodyScan.value)
        assertEquals(1, rig.session.bodyPendingUnits.value)
        val askedBefore = rig.adapter.received.count { it.startsWith("22F") }

        val sentBeforeRestart = rig.adapter.received.size
        assertTrue(rig.session.restartBodyStage(), "accepted while polling with a unit pending")
        run(3.seconds)
        assertIs<BodyScanState.Running>(rig.session.bodyScan.value, "keep the car parked: it runs again")
        assertFalse(rig.session.restartBodyStage(), "not while it runs")
        run(30.seconds)
        assertIs<BodyScanState.Partial>(rig.session.bodyScan.value, "the same time cap ends it")
        assertIs<SessionState.Polling>(rig.session.state.value)
        val sent = rig.adapter.received.filter { it.startsWith("22F") }
        assertTrue(sent.size > askedBefore, "more identifiers were asked: ${sent.size} after $askedBefore")
        // Only an identifier refused for want of a session (asked again after 10 01) repeats.
        assertTrue(sent.size - sent.toSet().size <= 2, "repeats: ${sent.size - sent.toSet().size}")
        val firstAgain = rig.adapter.received.drop(sentBeforeRestart).first { it.startsWith("22F") }
        assertFalse(firstAgain.startsWith("22F000"), "it resumes where the first stage stopped, it does not start over: $firstAgain")
        assertTrue(rig.events.of(SessionEvents.BODY_SCAN).any { it.startsWith("parked stage again") })
        assertTrue(rig.adapter.received.contains("ATCF7E8"), "the ordinary filter is back")
    }

    @Test
    fun runningItAgainRetriesAnUnreadableSpeedForAboutTenSecondsAndSaysSo() = runTest {
        val vehicle = vehicleWithCluster(0)
        vehicle.ecu(0x7E0).remove("010D")
        val rig = rig(vehicle = vehicle, configure = { it.copy(bodyPacedEvery = 10.minutes) })
        rig.session.start()
        run(12.seconds)
        assertIs<BodyScanState.Skipped>(rig.session.bodyScan.value)
        val readsBefore = rig.adapter.received.count { it.startsWith("010D") }

        assertTrue(rig.session.restartBodyStage())
        run(20.seconds)
        val skipped = assertIs<BodyScanState.Skipped>(rig.session.bodyScan.value)
        assertFalse(skipped.moving)
        assertTrue(skipped.retried, "the screen can say it tried for a while")
        assertTrue(rig.adapter.received.count { it.startsWith("010D") } >= readsBefore + 6, "read about six times over ten seconds")
        assertTrue(rig.adapter.received.none { it.startsWith("22F") }, "nothing asked without a parked speed")
        assertIs<SessionState.Polling>(rig.session.state.value)
    }

    @Test
    fun aSpeedThatComesBackWithinTheRetriesStartsTheStage() = runTest {
        val vehicle = vehicleWithCluster(0)
        vehicle.ecu(0x7E0).remove("010D")
        val rig = rig(vehicle = vehicle, configure = { it.copy(bodyPacedEvery = 10.minutes) })
        rig.session.start()
        run(12.seconds)
        assertIs<BodyScanState.Skipped>(rig.session.bodyScan.value)

        assertTrue(rig.session.restartBodyStage())
        run(3.seconds)
        vehicle.ecu(0x7E0).set("010D", "00")
        run(30.seconds)
        assertEquals(BodyScanState.Complete(answered = 2), rig.session.bodyScan.value)
    }

    @Test
    fun itCannotBeRunAgainBeforePolling() = runTest {
        val rig = rig()
        assertFalse(rig.session.restartBodyStage())
    }

    // While the car moves, polling keeps the adapter; standing still, the parked stage starts by itself ---------------------

    private fun List<String>.bodyRequests() = count { it.startsWith("22F") || it.startsWith("ATCRA") }

    @Test
    fun whileTheCarMovesNoBackgroundSliceInterruptsPolling() = runTest {
        val long = checks(longRange)
        val paused = rig(speedKmh = 40, candidates = long)
        val old = rig(speedKmh = 40, candidates = long, configure = slicesWhileMoving)
        paused.session.start()
        old.session.start()
        run(12.seconds)
        val pausedBefore = paused.adapter.received.liveRequests()
        val oldBefore = old.adapter.received.liveRequests()
        run(120.seconds)

        assertEquals(0, paused.adapter.received.bodyRequests(), "nothing but polling while the car moves")
        assertTrue(old.adapter.received.bodyRequests() > 0, "the old way did slice")
        val pausedLive = paused.adapter.received.liveRequests() - pausedBefore
        val oldLive = old.adapter.received.liveRequests() - oldBefore
        assertTrue(pausedLive > oldLive, "polling gets the adapter: $pausedLive live requests against $oldLive with slices")
        val notes = paused.events.of(SessionEvents.BODY_SCAN)
        assertEquals(1, notes.count { it.startsWith("background body check paused") }, "one line, not one per slice: $notes")
        assertEquals(1, paused.session.bodyPendingUnits.value, "nothing lost: the unit is still pending")
    }

    @Test
    fun stopAtALightThenGoThenStopAgain() = runTest {
        val rig = rig(speedKmh = 40, candidates = checks(longRange))
        val ecu = rig.vehicle.ecu(0x7E0)
        rig.session.start()
        run(12.seconds)
        assertIs<BodyScanState.Skipped>(rig.session.bodyScan.value)
        run(30.seconds)
        assertEquals(0, rig.adapter.received.bodyRequests())

        // Stopped at a light: nothing for the first few seconds...
        ecu.set("010D", "00")
        run(5.seconds)
        assertEquals(0, rig.adapter.received.bodyRequests(), "not before the car has stood still long enough")
        // ... then the parked stage starts by itself, low-key.
        run(5.seconds)
        val running = assertIs<BodyScanState.Running>(rig.session.bodyScan.value)
        assertTrue(running.auto)
        run(5.seconds)
        val askedFirst = rig.adapter.received.count { it.startsWith("22F") }
        assertTrue(askedFirst > 0)

        // Green: it stops within a few seconds of the car moving off, and polling has the adapter again.
        ecu.set("010D", "14")
        run(4.seconds)
        val partial = assertIs<BodyScanState.Partial>(rig.session.bodyScan.value)
        assertEquals(BodyOutcome.MOVING, partial.ended)
        assertTrue(partial.auto)
        assertTrue(rig.adapter.received.contains("ATCF7E8"), "restored")
        val askedThen = rig.adapter.received.count { it.startsWith("22F") }
        run(20.seconds)
        assertEquals(askedThen, rig.adapter.received.count { it.startsWith("22F") }, "nothing asked while moving")

        // The next light comes soon: no new stage within a minute of the early stop.
        ecu.set("010D", "00")
        run(25.seconds)
        assertEquals(askedThen, rig.adapter.received.count { it.startsWith("22F") }, "held off by the cool-down")
        // After the cool-down it starts again and carries on where it stopped, to the end.
        run(120.seconds)
        val complete = assertIs<BodyScanState.Complete>(rig.session.bodyScan.value)
        assertTrue(complete.auto)
        assertEquals(0, rig.session.bodyPendingUnits.value)
        val total = rig.adapter.received.count { it.startsWith("22F") }
        assertTrue(total < 512 + 40, "progress kept, not started over: $total requests for 512 identifiers")
        val notes = rig.events.of(SessionEvents.BODY_SCAN)
        assertEquals(2, notes.count { it.startsWith("automatic parked stage") }, notes.toString())
    }

    @Test
    fun withTheAutomaticStageOffAStandingCarGetsSlicesInstead() = runTest {
        val rig = rig(speedKmh = 40, candidates = clusterChecks)
        rig.session.setAutoBodyStage(false)
        rig.session.start()
        run(40.seconds)
        assertEquals(0, rig.adapter.received.bodyRequests())
        rig.vehicle.ecu(0x7E0).set("010D", "00")
        run(60.seconds)
        assertTrue(rig.adapter.received.any { it.startsWith("22F187") }, "slices resumed once the car stood still")
        assertTrue(rig.events.of(SessionEvents.BODY_SCAN).none { it.startsWith("automatic parked stage") })
        assertTrue(rig.events.of(SessionEvents.BODY_SCAN).any { it.startsWith("background body check resumed") })
    }

    @Test
    fun aShortStopDoesNotStartTheStage() = runTest {
        val rig = rig(speedKmh = 40, candidates = checks(longRange))
        val ecu = rig.vehicle.ecu(0x7E0)
        rig.session.start()
        run(20.seconds)
        repeat(4) {
            ecu.set("010D", "00")
            run(6.seconds)
            ecu.set("010D", "1E")
            run(10.seconds)
        }
        assertEquals(0, rig.adapter.received.bodyRequests(), "stops of six seconds are not long enough")
    }

    @Test
    fun aCarThatGoesToSleepAtAStopGetsNoAutomaticStageAndNoFalseMovement() = runTest {
        val rig = rig(speedKmh = 40, candidates = checks(longRange))
        rig.session.start()
        run(20.seconds)
        rig.vehicle.ecu(0x7E0).set("010D", "00")
        run(3.seconds)
        // The ignition goes off before the car has stood still long enough: everything answers NO DATA from now on.
        rig.vehicle.ecus.forEach { it.silent = true }
        run(60.seconds)
        assertEquals(0, rig.adapter.received.bodyRequests(), "no stage while the car sleeps")
        assertTrue(rig.events.of(SessionEvents.BODY_SCAN).none { it.startsWith("automatic parked stage") }, rig.events.of(SessionEvents.BODY_SCAN).toString())
        assertTrue(rig.states.any { it is SessionState.VehicleAsleep }, rig.states.distinct().toString())
    }
}
