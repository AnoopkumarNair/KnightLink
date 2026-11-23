package knightlink.core.sim

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.common.toHex
import knightlink.core.runtime.CannotReadReason
import knightlink.core.runtime.FaultsState
import knightlink.core.runtime.InMemoryProtocolMemoryStore
import knightlink.core.runtime.ProtocolNegotiator
import knightlink.core.runtime.ProtocolRecord
import knightlink.core.runtime.ReadLimits
import knightlink.core.runtime.ResolvedVehicle
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleResolver
import knightlink.core.runtime.VehicleSession
import knightlink.core.scan.FaultCodeDictionary
import knightlink.core.scan.FaultKind
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.vehicle.ObdProtocol
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Cars the app has no definition for, on virtual time: what it sends to them, how it finds their protocol, and when it gives up. The simulated
 * cars are built here from raw replies, like the simulator does everywhere, and know nothing of the app's decoding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnknownCarSimulationTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))
    private val adapter = AdapterDefinition.parse(File(root, "definitions/adapters/vgate-icar-pro-2s.json").readText())
    private val generic: SignalCatalog = SignalCatalog.of(
        listOf(DefinitionJson.parse(File(root, "definitions/standard/sae-j1979-mode01.json").readText())),
    )
    private val dictionary = FaultCodeDictionary.parse(File(root, "definitions/faults/sae-j2012.json").readText())

    // Built from pieces: nothing in the repository holds a VIN-shaped literal of a real make.
    private val vin = "ZZZ" + "1234567" + "89ABCD" + "E"

    private val unknownCar = VehicleResolver { ResolvedVehicle(generic, known = false) }

    private fun definition(id: String): DefinitionFile = DefinitionJson.parse(File(root, "definitions/$id.json").readText())

    private fun ecu(request: Int, pids: Map<String, String>, withInfo: Boolean = true): VirtualEcu =
        VirtualEcu(request, request + 8).also { ecu ->
            pids.forEach { (pid, data) -> ecu.set("01$pid", data) }
            if (withInfo) {
                ecu.set("0902", "01" + vin.encodeToByteArray().toHex())
                ecu.set("0904", "01" + "CAL-1".encodeToByteArray().toHex() + "00".repeat(11))
                ecu.set("090A", "01" + "ENGINE".encodeToByteArray().toHex() + "00".repeat(14))
            }
            // Fault codes: none stored, none pending, none permanent (the count byte only).
            ecu.setRaw("03", "4300")
            ecu.setRaw("07", "4700")
            ecu.setRaw("0A", "4A00")
        }

    /** A petrol car, manual gearbox: the engine ECU alone, a handful of PIDs, no fuel level, no 7E1. */
    private fun petrol(): VirtualVehicle {
        val engine = ecu(
            0x7E0,
            mapOf("01" to "00076100", "05" to "5A", "0C" to "1AF8", "0D" to "28", "11" to "40", "0F" to "3C"),
        )
        engine.setRaw("03", "43010133")
        return VirtualVehicle(listOf(engine))
    }

    /** A diesel: the engine ECU says compression ignition (PID 01), gives a fuel rate and the particulate filter PIDs. */
    private fun diesel(): VirtualVehicle = VirtualVehicle(
        listOf(
            ecu(
                0x7E0,
                mapOf(
                    "01" to "000F6100",
                    "05" to "5A",
                    "0C" to "1AF8",
                    "0D" to "28",
                    "10" to "0A00",
                    "7A" to "0F0123456789AB",
                    "8B" to "7300E300EF00B3",
                    "9D" to "001D001D",
                ),
            ),
        ),
    )

    private class Rig(val session: VehicleSession, val transport: SimulatedTransport) {
        val adapter: VirtualElm327 get() = checkNotNull(transport.current)
    }

    private fun TestScope.rig(
        vehicle: VirtualVehicle,
        adapterConfig: VirtualElm327.Config,
        resolver: VehicleResolver? = unknownCar,
        negotiation: ProtocolNegotiator.Config? = ProtocolNegotiator.Config(),
        limits: ReadLimits? = ReadLimits(),
        memory: InMemoryProtocolMemoryStore? = null,
    ): Rig {
        val transport = SimulatedTransport(vehicle = { vehicle }, config = adapterConfig)
        val config = VehicleSession.Config(
            driver = adapter.driverConfig(),
            probeScript = adapter.probeScript,
            pollScript = adapter.pollScript,
            adapterId = adapter.id,
            negotiation = negotiation,
            protocolMemory = memory,
            readLimits = limits,
            resolver = resolver,
            readFaults = true,
            faultDictionary = dictionary,
        )
        val session = VehicleSession(transport, adapter.initScript, generic, backgroundScope, testTimeSource, config)
        return Rig(session, transport)
    }

    private fun TestScope.run(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private val protocolCar = VirtualElm327.Config(responseDelay = 45.milliseconds, carProtocol = '6', searchDelay = 2.seconds)

    /** A command as the test compares it: the many polls of service 01 are one family; everything else is exact. */
    private fun family(command: String): String {
        val compact = command.filterNot { it.isWhitespace() }.uppercase()
        if (compact.startsWith("AT")) return compact
        val request = if (compact.length % 2 == 1) compact.dropLast(1) else compact
        return if (request.startsWith("01") && request !in setOf("0100", "0101", "0141")) "01:poll" else request
    }

    // (a) -----------------------------------------------------------------------------------------------------

    @Test
    fun aPetrolCarWithFewPidsIsReadWithTheStandardSignalsAndNothingElse() = runTest {
        val rig = rig(petrol(), protocolCar)
        rig.session.start()
        run(30.seconds)

        assertIs<SessionState.Polling>(rig.session.state.value)
        val facts = rig.session.facts.value
        assertEquals(false, facts.known)
        assertEquals(ObdProtocol.CAN_11_500, facts.protocol)
        assertNull(facts.definitionId)
        val telemetry = rig.session.telemetry.value
        assertEquals(1726.0, telemetry.value("engine.rpm")!!, 0.0)
        assertEquals(40.0, telemetry.value("vehicle.speed")!!, 0.0)
        assertNull(telemetry.value("fuel.level_pct"), "the car has no fuel level PID, so none is asked for or shown")
        assertNull(telemetry.value("transmission.gear_calc"), "no calculated gear on a car the app does not know")

        // The exact set of commands that went to an unknown car (the polls of service 01 are one family).
        val sent = rig.adapter.received.map(::family).toSet()
        val expected = setOf(
            "ATE0", "ATD", "ATZ", "ATL0", "ATS0", "ATH1", "ATSP6", "ATAT1", "ATST32", "ATST64", "ATTP6", "ATSH7DF",
            "0100", "ATDPN", "ATI", "AT@1", "ATRV", "ATSH7E0", "ATSH7E1", "ATSH7E2", "ATSH7E3", "ATSH7E4", "ATSH7E5", "ATSH7E6", "ATSH7E7",
            "0900", "0902", "0904", "090A",
            "0101", "0141", "03", "07", "0A", "1902FF", "01:poll",
        )
        // 0141 is asked only of a unit that lists it: this one does not.
        assertEquals(expected - "0141", sent)
        // Never: a sweep of identifiers, the default session, security access, I/O control, or a header other than an engine-bay unit's.
        assertTrue(rig.adapter.received.none { it.startsWith("22") || it.startsWith("10") || it.startsWith("27") || it.startsWith("2F") })
        assertTrue(rig.adapter.received.none { it.startsWith("ATCRA") || it.startsWith("ATCF") || it.startsWith("ATFCS") || it == "ATMA" })
        assertTrue(
            rig.adapter.received.filter { it.startsWith("ATSH") }.all {
                it.removePrefix("ATSH").toInt(16) in 0x7E0..0x7E7 ||
                    it.removePrefix("ATSH") == "7DF"
            },
        )

        // What the car said about its faults: one confirmed code, described from the generic list.
        val faults = assertIs<FaultsState.Ready>(rig.session.faults.value).report
        assertEquals(listOf("P0133"), faults.faults.map { it.code })
        assertEquals(FaultKind.CONFIRMED, faults.faults.single().kind)
        assertNotNull(faults.faults.single().description)
        assertEquals(false, faults.readiness?.compressionIgnition)
        assertEquals(false, rig.session.startScan(), "no full scan on an unknown car")
        assertEquals(false, rig.session.setCaptureMode(knightlink.core.runtime.CaptureMode.PASSIVE), "no passive capture on an unknown car")
    }

    @Test
    fun theFaultCodesCanBeReadAgainOnRequestWithoutClearingAnything() = runTest {
        val rig = rig(petrol(), protocolCar)
        rig.session.start()
        run(30.seconds)
        val before = rig.adapter.received.count { it == "03" }
        assertTrue(rig.session.refreshFaults())
        run(10.seconds)
        assertEquals(before + 1, rig.adapter.received.count { it == "03" })
        assertTrue(rig.adapter.received.none { it.startsWith("04") || it.startsWith("14") || it.startsWith("11") })
        assertIs<SessionState.Polling>(rig.session.state.value)
    }

    // (b) -----------------------------------------------------------------------------------------------------

    @Test
    fun aDieselWithAFuelRateAndFilterPidsShowsThemAndIsKnownToBeADiesel() = runTest {
        val rig = rig(diesel(), protocolCar)
        rig.session.start()
        run(30.seconds)
        val polling = assertIs<SessionState.Polling>(rig.session.state.value)
        assertTrue(polling.report.derived.hasDpfData)
        assertEquals(true, rig.session.facts.value.compressionIgnition)
        assertNotNull(rig.session.telemetry.value.value("fuel.rate_gps"))
        assertNotNull(rig.session.telemetry.value.value("dpf.soot_pct"))
        assertNull(rig.session.telemetry.value.value("fuel.rate_lph"), "no 5E on this car")
    }

    // (c) -----------------------------------------------------------------------------------------------------

    @Test
    fun anEngineEcuThatWakesLateIsWaitedForAndEndsInThePollList() = runTest {
        val vehicle = VirtualVehicle(
            listOf(
                ecu(0x7E0, mapOf("01" to "00076100", "05" to "5A", "0C" to "1AF8", "0D" to "28")),
                ecu(0x7E1, mapOf("01" to "00000000", "05" to "5A"), withInfo = false),
            ),
        )
        vehicle.ecu(0x7E0).silent = true
        val rig = rig(vehicle, protocolCar)
        backgroundScope.launch {
            delay(5.seconds)
            vehicle.ecu(0x7E0).silent = false
        }
        rig.session.start()
        run(60.seconds)
        val polling = assertIs<SessionState.Polling>(rig.session.state.value)
        assertTrue(polling.report.ecus.any { it.requestId == "7E0" }, polling.report.ecus.map { it.requestId }.toString())
        assertNotNull(rig.session.telemetry.value.value("engine.rpm"))
    }

    // (d) -----------------------------------------------------------------------------------------------------

    @Test
    fun aCarThatAnswersNothingEndsInCannotReadWithinTheTimeLimitAndRetryWorks() = runTest {
        val vehicle = petrol().also { it.awake = false }
        val rig = rig(vehicle, protocolCar)
        rig.session.start()
        run(60.seconds)
        val state = assertIs<SessionState.CannotReadVehicle>(rig.session.state.value)
        assertEquals(CannotReadReason.NoResponse, state.reason)
        // Several protocols were tried, none answered, and nothing but the negotiation's own requests went to the car.
        assertTrue(rig.adapter.received.any { it == "ATSP0" })
        assertTrue(rig.adapter.received.none { it.startsWith("ATSH") && it != "ATSH7DF" })

        vehicle.awake = true
        assertTrue(rig.session.retry())
        run(30.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
    }

    @Test
    fun aSilentCarIsGivenUpOnEvenWhenTheAdapterHasReadAHarrierBefore() = runTest {
        val memory = InMemoryProtocolMemoryStore()
        memory.save(ProtocolRecord(adapterId = adapter.id, vinPrefix = "MAT", protocol = "6", dpn = "A6"))
        val vehicle = petrol().also { it.awake = false }
        val rig = rig(vehicle, protocolCar, memory = memory)
        rig.session.start()
        run(60.seconds)
        assertEquals(CannotReadReason.NoResponse, assertIs<SessionState.CannotReadVehicle>(rig.session.state.value).reason)
    }

    @Test
    fun aResolverThatThrowsLeavesAnUnknownCarWithNoFeaturesOfAKnownOne() = runTest {
        val rig = rig(petrol(), protocolCar, resolver = VehicleResolver { throw IllegalStateException("a broken definition file") })
        rig.session.start()
        run(30.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(false, rig.session.facts.value.known)
        assertEquals(false, rig.session.startScan())
        assertEquals(false, rig.session.setCaptureMode(knightlink.core.runtime.CaptureMode.PASSIVE))
        assertTrue(rig.adapter.received.none { it.startsWith("22") || it.startsWith("10") })
        assertNull(rig.session.telemetry.value.value("transmission.gear_calc"))
    }

    @Test
    fun withoutALimitAnAsleepCarIsWaitedForAsBefore() = runTest {
        val vehicle = petrol().also { it.awake = false }
        val rig = rig(vehicle, protocolCar, limits = null)
        rig.session.start()
        run(5.minutesOf())
        assertTrue(rig.session.state.value is SessionState.WaitingForIgnition || rig.session.state.value is SessionState.Negotiating)
        vehicle.awake = true
        run(40.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
    }

    private fun Int.minutesOf(): Duration = (this * 60).seconds

    // (e) -----------------------------------------------------------------------------------------------------

    @Test
    fun aCarOn29BitCanIsDetectedRecordedAndNotPolled() = runTest {
        for (code in listOf('7', '9')) {
            val rig = rig(petrol(), VirtualElm327.Config(responseDelay = 45.milliseconds, carProtocol = code, searchDelay = 2.seconds))
            rig.session.start()
            run(40.seconds)
            val state = assertIs<SessionState.CannotReadVehicle>(rig.session.state.value)
            val reason = assertIs<CannotReadReason.UnsupportedProtocol>(state.reason)
            assertTrue("29 bit" in reason.protocolName, reason.protocolName)
            assertEquals("A$code", rig.session.facts.value.dpn)
            assertTrue(rig.adapter.received.none { it.startsWith("ATSH") && it != "ATSH7DF" }, "nothing is asked of the units over a bus the app cannot read")
            rig.session.stop()
        }
    }

    @Test
    fun aLegacyBusIsDetectedAndEndsInTheSameState() = runTest {
        val rig = rig(petrol(), VirtualElm327.Config(responseDelay = 45.milliseconds, carProtocol = '3', searchDelay = 2.seconds))
        rig.session.start()
        run(40.seconds)
        val state = assertIs<SessionState.CannotReadVehicle>(rig.session.state.value)
        assertIs<CannotReadReason.UnsupportedProtocol>(state.reason)
    }

    // (f) -----------------------------------------------------------------------------------------------------

    @Test
    fun aGatewayThatRefusesWithSecurityAccessDeniedIsNeverAskedAgainAndEndsInGatewayBlocked() = runTest {
        val vehicle = petrol()
        vehicle.ecus.forEach { it.refusesEverythingWith = 0x33 }
        val rig = rig(vehicle, protocolCar)
        rig.session.start()
        run(60.seconds)
        val state = assertIs<SessionState.CannotReadVehicle>(rig.session.state.value)
        assertEquals(CannotReadReason.GatewayBlocked, state.reason)
        assertTrue(rig.adapter.received.none { it.startsWith("27") }, "service 27 is never sent")
        assertTrue(rig.adapter.received.none { it.startsWith("22") || it.startsWith("10") })
    }

    // Protocol negotiation -----------------------------------------------------------------------------------

    @Test
    fun anElevenBit250kCarIsFoundByTheAdaptersSearchLockedAndRemembered() = runTest {
        val memory = InMemoryProtocolMemoryStore()
        val rig = rig(petrol(), VirtualElm327.Config(responseDelay = 45.milliseconds, carProtocol = '8', searchDelay = 2.seconds), memory = memory)
        rig.session.start()
        run(40.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(ObdProtocol.CAN_11_250, rig.session.facts.value.protocol)
        val sent = rig.adapter.received
        // The scripted protocol did not answer; the search did, was told to wait, was locked, and the probe's timeout was put back.
        assertTrue(sent.indexOf("ATSP0") < sent.indexOf("ATSP8"), sent.toString())
        assertTrue("ATSTFF" in sent)
        assertTrue(sent.indexOf("ATSTFF") < sent.lastIndexOf("ATST64"))
        val record = assertNotNull(memory.last(adapter.id))
        assertEquals("8", record.protocol)
        assertEquals("A8", record.dpn)
        assertEquals("ZZZ", record.vinPrefix)
        assertTrue(record.viaAuto)
        assertTrue(memory.all().none { vin in it.toString() }, "no VIN in the protocol memory")
    }

    @Test
    fun theRememberedProtocolIsTriedFirstOnTheNextSession() = runTest {
        val memory = InMemoryProtocolMemoryStore()
        val first = rig(petrol(), VirtualElm327.Config(responseDelay = 45.milliseconds, carProtocol = '8', searchDelay = 2.seconds), memory = memory)
        first.session.start()
        run(40.seconds)
        first.session.stop()
        val second = rig(petrol(), VirtualElm327.Config(responseDelay = 45.milliseconds, carProtocol = '8', searchDelay = 2.seconds), memory = memory)
        second.session.start()
        run(30.seconds)
        assertIs<SessionState.Polling>(second.session.state.value)
        assertTrue("ATSP0" !in second.adapter.received, "no search needed: ${second.adapter.received}")
        assertTrue("ATSP8" in second.adapter.received)
    }

    @Test
    fun theScriptedProtocolIsTriedFirstAndAVinThatMatchesNoDefinitionIsUnknown() = runTest {
        val harrier = definition("vehicles/tata/harrier-2020-kryotec-at")
        val known = VehicleResolver { report ->
            if (report.ecus.firstNotNullOfOrNull { it.vin }?.startsWith("MAT") == true) {
                ResolvedVehicle(SignalCatalog.of(harrier.extends.map(::definition) + harrier), known = true, definitionId = harrier.id)
            } else {
                ResolvedVehicle(generic, known = false)
            }
        }
        val simulation = SimulationFile.parse(File(root, "fixtures/sim/harrier-dpf-regen.json").readText())
        val rig = rig(simulation.buildVehicle(), VirtualElm327.Config(responseDelay = 45.milliseconds), resolver = known)
        rig.session.start()
        run(30.seconds)
        // The simulator's placeholder VIN is not the make's: it is an unknown car to the resolver, which is the point of the next assertion.
        assertEquals(false, rig.session.facts.value.known)
        assertEquals("ATSP6", rig.adapter.received.first { it.startsWith("ATSP") })
    }
}
