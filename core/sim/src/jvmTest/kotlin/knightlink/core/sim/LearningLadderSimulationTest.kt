package knightlink.core.sim

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.common.SessionEventSink
import knightlink.core.common.toHex
import knightlink.core.runtime.LadderConfig
import knightlink.core.runtime.LadderLevel
import knightlink.core.runtime.LadderState
import knightlink.core.runtime.ProtocolNegotiator
import knightlink.core.runtime.ReadLimits
import knightlink.core.runtime.ResolvedVehicle
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleResolver
import knightlink.core.runtime.VehicleSession
import knightlink.core.scan.FaultCodeDictionary
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The learning ladder on a virtual car the app has no definition for: each level, its abort rules and the hard lines. */
@OptIn(ExperimentalCoroutinesApi::class)
class LearningLadderSimulationTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))
    private val adapter = AdapterDefinition.parse(File(root, "definitions/adapters/vgate-icar-pro-2s.json").readText())
    private val generic: SignalCatalog = SignalCatalog.of(
        listOf(DefinitionJson.parse(File(root, "definitions/standard/sae-j1979-mode01.json").readText())),
    )
    private val dictionary = FaultCodeDictionary.parse(File(root, "definitions/faults/sae-j2012.json").readText())
    private val vin = "ZZZ" + "1234567" + "89ABCD" + "E"

    private class Rig(val session: VehicleSession, val transport: SimulatedTransport, val vehicle: VirtualVehicle, val events: List<String>) {
        val adapter: VirtualElm327 get() = checkNotNull(transport.current)
        val sent: List<String> get() = adapter.received
    }

    private fun engine(rpm: String = "0000", speed: String = "00"): VirtualEcu = VirtualEcu(0x7E0, 0x7E8).also { ecu ->
        ecu.set("0101", "00076100")
        ecu.set("010C", rpm)
        ecu.set("010D", speed)
        ecu.set("0105", "5A")
        ecu.set("0902", "01" + vin.encodeToByteArray().toHex())
        ecu.set("0904", "01" + "CAL-1".encodeToByteArray().toHex() + "00".repeat(11))
        ecu.set("090A", "01" + "ENGINE".encodeToByteArray().toHex() + "00".repeat(14))
        ecu.setRaw("03", "4300")
        ecu.setRaw("07", "4700")
        ecu.setRaw("0A", "4A00")
        ecu.set("22F187", "31323334")
        ecu.set("22F18A", "41424344")
        ecu.set("22F190", vin.encodeToByteArray().toHex())
        ecu.set("22F195", "0102")
        ecu.set("22F1A0", "0102")
        ecu.set("220101", "0A")
    }

    private fun bodyUnit(request: Int): VirtualEcu = VirtualEcu(request, request + 8).also { ecu ->
        ecu.needsCustomFlowControl = true
        ecu.set("22F190", vin.encodeToByteArray().toHex())
        ecu.set("22F187", "35363738")
        ecu.set("22F100", "01")
    }

    private fun TestScope.rig(
        vehicle: VirtualVehicle,
        level: LadderLevel = LadderLevel.L4,
        deepScan: Boolean = true,
        ladder: LadderConfig? = LadderConfig(deepScan = deepScan),
        memory: knightlink.core.runtime.VehicleMemoryStore? = null,
    ): Rig {
        val transport = SimulatedTransport(vehicle = { vehicle }, config = VirtualElm327.Config(responseDelay = 45.milliseconds))
        val resolver = VehicleResolver { ResolvedVehicle(generic, known = false, allowedLevel = level) }
        val config = VehicleSession.Config(
            driver = adapter.driverConfig(),
            probeScript = adapter.probeScript,
            pollScript = adapter.pollScript,
            adapterId = adapter.id,
            negotiation = ProtocolNegotiator.Config(),
            readLimits = ReadLimits(),
            resolver = resolver,
            readFaults = true,
            faultDictionary = dictionary,
            ladder = ladder,
            memory = memory,
        )
        val log = ArrayList<String>()
        val sink = SessionEventSink { event -> log += event.toString() }
        return Rig(VehicleSession(transport, adapter.initScript, generic, backgroundScope, testTimeSource, config, sink), transport, vehicle, log)
    }

    private fun TestScope.run(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun payload(command: String): String {
        val compact = command.filterNot { it.isWhitespace() }.uppercase()
        return if (!compact.startsWith("AT") && compact.length % 2 == 1) compact.dropLast(1) else compact
    }

    private fun dids(sent: List<String>) = sent.map(::payload).filter { it.startsWith("22") }.toSet()

    private fun headers(sent: List<String>) = sent.filter { it.startsWith("ATSH") }.map { it.removePrefix("ATSH").toInt(16) }.toSet()

    private val hardLines = listOf("2E", "27", "31", "10", "11", "14", "28", "85", "3E", "2F", "04", "08")

    private fun assertHardLines(sent: List<String>) {
        for (command in sent) {
            val p = payload(command)
            if (p.startsWith("AT")) continue
            assertTrue(hardLines.none { p.startsWith(it) }, "never sent: $command")
        }
        assertTrue(headers(sent).all { it in 0x700..0x7FF && it !in 0x7E8..0x7EF }, "request ids stay in 700-7FF: ${headers(sent)}")
        assertTrue(sent.none { it.startsWith("ATPP") || it.startsWith("ATMA") || it.startsWith("ATSH18") })
    }

    // Levels --------------------------------------------------------------------------------------------------

    @Test
    fun levelOneAsksTheIdentificationIdentifiersOfTheUnitsThatAnsweredAndNothingElse() = runTest {
        val rig = rig(VirtualVehicle(listOf(engine())), level = LadderLevel.L1)
        rig.session.start()
        run(2.minutes)

        assertIs<SessionState.Polling>(rig.session.state.value)
        val state = assertIs<LadderState.Complete>(rig.session.ladder.value)
        assertEquals(LadderLevel.L1, state.highest)
        assertEquals(setOf("22F187", "22F18A", "22F18C", "22F190", "22F191", "22F195"), dids(rig.sent))
        assertEquals(setOf(0x7DF, 0x7E0, 0x7E1, 0x7E2, 0x7E3, 0x7E4, 0x7E5, 0x7E6, 0x7E7), headers(rig.sent), "the negotiation's 7DF, the probe's units")
        assertTrue(rig.sent.none { it.startsWith("ATCM") || it.startsWith("ATCF") || it.startsWith("ATFC") }, "no receive or flow-control change at L1")
        assertHardLines(rig.sent)
    }

    @Test
    fun levelTwoFindsAUnitOnAnotherRequestIdAtNoMoreThanTenRequestsASecond() = runTest {
        val body = bodyUnit(0x726)
        val rig = rig(VirtualVehicle(listOf(engine(), body)), level = LadderLevel.L2)
        rig.session.start()
        run(3.minutes)

        assertIs<LadderState.Complete>(rig.session.ladder.value)
        val asked = headers(rig.sent.dropWhile { it != "ATCM700" })
        assertTrue(0x726 in asked && 0x700 in asked && 0x7FE in asked)
        assertTrue(0x7DF !in asked && asked.none { it in 0x7E8..0x7EF })
        // Only F190 goes to the units that were not known yet.
        val discoveryRequests = rig.sent.withIndex().filter { (_, c) -> c.startsWith("ATSH7") && c.removePrefix("ATSH").toInt(16) !in 0x7E0..0x7E7 }
        assertEquals(setOf("22F190"), dids(rig.sent).intersect(setOf("22F190")))
        assertEquals(
            setOf("ATCM700", "ATCF700", "ATFCSH726", "ATFCSD300000", "ATFCSM1", "ATFCSM0", "ATCM7F8", "ATCF7E8"),
            rig.sent.filter {
                it.startsWith("ATC") ||
                    it.startsWith("ATFC")
            }.toSet(),
        )
        assertTrue(discoveryRequests.size >= 230, "${discoveryRequests.size} identifiers asked")
        assertHardLines(rig.sent)
    }

    @Test
    fun levelThreeSweepsTheRangesOnTheUnitsThatAnsweredWithinTheirTimeBoxes() = runTest {
        val body = bodyUnit(0x726)
        val rig = rig(
            VirtualVehicle(listOf(engine(), body)),
            level = LadderLevel.L3,
            ladder = LadderConfig(ranges = listOf(0xF100..0xF1FF, 0x0100..0x01FF), l3PerUnit = 3.minutes),
        )
        rig.session.start()
        run(12.minutes)

        assertIs<LadderState.Complete>(rig.session.ladder.value)
        val asked = dids(rig.sent)
        assertTrue("22F100" in asked && "22F1FF" in asked && "220100" in asked && "2201FF" in asked)
        assertTrue(asked.none { it.substring(2).toInt(16) !in (0xF100..0xF1FF) + (0x0100..0x01FF) && it.substring(2).toInt(16) != 0xF190 })
        assertHardLines(rig.sent)
        assertTrue(rig.sent.any { it == "ATSH726" })
    }

    @Test
    fun theDeepScanSwitchOffKeepsTheLadderAtLevelOne() = runTest {
        val rig = rig(VirtualVehicle(listOf(engine(), bodyUnit(0x726))), level = LadderLevel.L4, deepScan = false)
        rig.session.start()
        run(5.minutes)
        assertEquals(LadderLevel.L1, (assertIs<LadderState.Complete>(rig.session.ladder.value)).highest)
        assertFalse("22F100" in dids(rig.sent))
        assertTrue(rig.sent.none { it.startsWith("ATCM700") || it.startsWith("ATSH726") }, "no discovery without the deep scan")
    }

    @Test
    fun skipEndsTheLadderCleanlyAndPollingGoesOn() = runTest {
        val rig = rig(VirtualVehicle(listOf(engine(), bodyUnit(0x726))), level = LadderLevel.L3)
        rig.session.start()
        backgroundScope.launch {
            delay(20.seconds)
            rig.session.skipLadder()
        }
        run(3.minutes)
        val state = assertIs<LadderState.Stopped>(rig.session.ladder.value)
        assertTrue("skipped" in state.reason, state.reason)
        assertIs<SessionState.Polling>(rig.session.state.value)
        // The receive filter is back to the OBD one before polling.
        assertTrue(rig.sent.lastIndexOf("ATCM7F8") > rig.sent.lastIndexOf("ATCM700"))
    }

    @Test
    fun theParkedTimeBoxIsHonestEvenInTheMiddleOfAUnit() = runTest {
        val rig = rig(VirtualVehicle(listOf(engine())), level = LadderLevel.L3, ladder = LadderConfig(totalBox = 40.seconds, l3PerUnit = 5.minutes))
        rig.session.start()
        run(3.minutes)
        val state = assertIs<LadderState.Stopped>(rig.session.ladder.value)
        assertTrue("time box" in state.reason, state.reason)
        assertIs<SessionState.Polling>(rig.session.state.value)
    }

    @Test
    fun aCompletedLadderIsRememberedPerCarAndRunsAgainOnlyOnRequest() = runTest {
        val memory = knightlink.core.runtime.InMemoryVehicleMemoryStore()
        val vehicle = VirtualVehicle(listOf(engine()))
        val first = rig(vehicle, level = LadderLevel.L2, memory = memory)
        first.session.start()
        run(3.minutes)
        assertIs<LadderState.Complete>(first.session.ladder.value)
        assertEquals(1, first.events.count { it == "ladder: L1 starts" })
        first.session.stop()

        val second = rig(vehicle, level = LadderLevel.L2, memory = memory)
        second.session.start()
        run(2.minutes)
        assertIs<SessionState.Polling>(second.session.state.value)
        assertEquals(0, second.events.count { it == "ladder: L1 starts" }, "the car was learned on an earlier connection")
        assertTrue(second.events.any { it.startsWith("ladder: not run: it was done on this car") }, second.events.toString())

        assertTrue(second.session.rerunLadder())
        run(3.minutes)
        assertEquals(1, second.events.count { it == "ladder: L1 starts" })
        assertIs<SessionState.Polling>(second.session.state.value)
    }

    @Test
    fun theLadderOnlyRunsOnAnUnknownCarAndOnlyWhenItIsConfigured() = runTest {
        val rig = rig(VirtualVehicle(listOf(engine())), ladder = null)
        rig.session.start()
        run(1.minutes)
        assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(LadderState.Idle, rig.session.ladder.value)
        assertTrue(rig.sent.none { payload(it).startsWith("22") })
    }

    // Abort rules --------------------------------------------------------------------------------------------------

    @Test
    fun theLadderStopsWhenTheCarStartsToMove() = runTest {
        val vehicle = VirtualVehicle(listOf(engine()))
        val rig = rig(vehicle, level = LadderLevel.L2)
        rig.session.start()
        backgroundScope.launch {
            delay(14.seconds)
            vehicle.ecu(0x7E0).set("010D", "0A")
        }
        run(3.minutes)
        val state = assertIs<LadderState.Stopped>(rig.session.ladder.value)
        assertTrue("moves" in state.reason, state.reason)
        assertIs<SessionState.Polling>(rig.session.state.value)
    }

    @Test
    fun theLadderStopsWhenTheEngineStartsOrStops() = runTest {
        val vehicle = VirtualVehicle(listOf(engine()))
        val rig = rig(vehicle, level = LadderLevel.L2)
        rig.session.start()
        backgroundScope.launch {
            delay(14.seconds)
            vehicle.ecu(0x7E0).set("010C", "1AF8")
        }
        run(3.minutes)
        val state = assertIs<LadderState.Stopped>(rig.session.ladder.value)
        assertTrue("engine" in state.reason, state.reason)
    }

    @Test
    fun theLadderStopsOnALowBatteryWithTheEngineOff() = runTest {
        val rig = rig(VirtualVehicle(listOf(engine())), level = LadderLevel.L2)
        rig.session.start()
        backgroundScope.launch {
            delay(14.seconds)
            rig.adapter.voltage = "11.4V"
        }
        run(3.minutes)
        val state = assertIs<LadderState.Stopped>(rig.session.ladder.value)
        assertTrue("battery" in state.reason, state.reason)
    }

    @Test
    fun aUnitThatRefusesThreeRequestsInARowIsNotAskedAnyMoreAndTheLevelGoesOn() = runTest {
        val vehicle = VirtualVehicle(listOf(engine(), bodyUnit(0x726)))
        val rig = rig(vehicle, level = LadderLevel.L3, ladder = LadderConfig(ranges = listOf(0xF100..0xF11F)))
        rig.session.start()
        run(1.seconds)
        vehicle.ecu(0x726).refusesEverythingWith = 0x33
        run(15.minutes)
        assertIs<LadderState.Complete>(rig.session.ladder.value)
        // The refusing unit was asked three times at most before the guard stopped it, at each level that reached it.
        val toBody = rig.sent.withIndex().filter { it.value == "ATSH726" }.size
        assertTrue(toBody in 1..20, "$toBody headers set for the refusing unit")
        assertHardLines(rig.sent)
    }

    @Test
    fun aStormOfCanErrorsStopsTheLevelAndTheBusIsProbedAgain() = runTest {
        val vehicle = VirtualVehicle(listOf(engine()))
        val rig = rig(vehicle, level = LadderLevel.L2)
        rig.session.start()
        backgroundScope.launch {
            delay(20.seconds)
            rig.adapter.busFault = "CAN ERROR"
        }
        run(5.minutes)
        // The simulated bus fault belongs to the first connection; the session reconnects and the ladder runs afresh on the next one.
        assertTrue(rig.events.any { it.startsWith("ladder: L2 aborted (level): the adapter reported bus trouble 2 times") }, rig.events.toString())
        assertTrue(rig.events.any { it.startsWith("ladder: probing the bus again") })
        assertTrue(rig.events.any { it.startsWith("ladder: stopped at L2:") })
    }

    @Test
    fun aNewFaultCodeAfterALevelFlagsTheCarAndStopsEverything() = runTest {
        val vehicle = VirtualVehicle(listOf(engine()))
        val rig = rig(vehicle, level = LadderLevel.L3)
        rig.session.start()
        backgroundScope.launch {
            delay(14.seconds)
            vehicle.ecu(0x7E0).setRaw("03", "43010133")
        }
        run(10.minutes)
        val state = assertIs<LadderState.Flagged>(rig.session.ladder.value)
        assertTrue("P0133" in state.reason, state.reason)
        assertFalse(rig.sent.any { payload(it) == "22F100" }, "nothing was swept after the flag")
        assertHardLines(rig.sent)
    }
}
