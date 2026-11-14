package knightlink.core.sim

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.Elm327Driver
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.vehicle.CapabilityProbe
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.DuplicatePid
import knightlink.core.vehicle.FuelMethod
import knightlink.core.vehicle.ProbeStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The capability probe against the whole stack on virtual time: scenario file -> virtual ECUs -> virtual
 * ELM327 -> real driver (after the adapter definition's init script) -> probe -> report -> coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityProbeSimulationTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))

    private fun definition(id: String): DefinitionFile = DefinitionJson.parse(File(root, "definitions/$id.json").readText())

    private fun catalogFor(id: String): SignalCatalog {
        val top = definition(id)
        return SignalCatalog.of(top.extends.map(::definition) + top)
    }

    private class Outcome(val simulation: SimulationFile, val adapter: VirtualElm327, val report: CapabilityReport, val stages: List<ProbeStage>)

    private suspend fun TestScope.probeHarrier(): Outcome {
        val simulation = SimulationFile.parse(File(root, "fixtures/sim/harrier-dpf-regen.json").readText())
        val adapterDefinition = AdapterDefinition.parse(File(root, "definitions/adapters/vgate-icar-pro-2s.json").readText())
        val adapter = VirtualElm327(simulation.buildVehicle(), VirtualElm327.Config(responseDelay = 45.milliseconds))
        val driver = Elm327Driver(adapter, backgroundScope)
        driver.initialize(adapterDefinition.initScript)
        val stages = ArrayList<ProbeStage>()
        val report = CapabilityProbe(driver, testTimeSource, CapabilityProbe.Config()).run(onProgress = { stages += it })
        return Outcome(simulation, adapter, report, stages)
    }

    @Test
    fun findsTheThreeEcusWithTheirNamesCalibrationIdsAndSupportMaps() = runTest {
        val report = probeHarrier().report
        assertEquals(listOf("7E0", "7E1", "7E4"), report.ecus.map { it.requestId })
        assertEquals(listOf("7E8", "7E9", "7EC"), report.ecus.map { it.responseId })
        assertEquals(listOf("ECM-EngineControl", "TCM-TransmisCtrl", "RDCM-ReductantCtrl"), report.ecus.map { it.name })
        assertEquals(listOf(listOf("SIM-ECM-CAL-0001"), emptyList(), listOf("SIM-RDCM-CAL-001")), report.ecus.map { it.calibrationIds })
        assertEquals(
            listOf("05", "0B", "0C", "0D", "10", "2F", "31", "42", "46", "78", "8B", "9D"),
            report.ecus[0].supportedPids,
        )
        assertEquals(listOf("31"), report.ecus[1].supportedPids)
        assertEquals(listOf("05", "0C", "9B"), report.ecus[2].supportedPids)
        assertTrue(report.warnings.isEmpty(), report.warnings.toString())
    }

    @Test
    fun readsTheAdapterAndOnlyTheEngineEcuHasAVin() = runTest {
        val report = probeHarrier().report
        assertEquals("ELM327 v2.3", report.adapter.banner)
        assertEquals("OBDII to RS232 Interpreter", report.adapter.description)
        assertEquals(14.1, report.adapter.voltage)
        assertEquals("A6", report.adapter.protocolNumber)
        assertEquals(listOf("SIMHARRIER2020001", null, null), report.ecus.map { it.vin })
    }

    @Test
    fun derivesFuelMethodDpfAndDuplicates() = runTest {
        val derived = probeHarrier().report.derived
        assertEquals(FuelMethod.DIRECT_RATE, derived.fuelMethod)
        assertTrue(derived.hasDpfData)
        // The engine ECU and the reductant controller both answer 05 and 0C; 31 is on the engine ECU and the gearbox ECU.
        assertEquals(
            listOf(
                DuplicatePid("05", listOf("7E0", "7E4"), "7E0"),
                DuplicatePid("0C", listOf("7E0", "7E4"), "7E0"),
                DuplicatePid("31", listOf("7E0", "7E1"), "7E0"),
            ),
            derived.duplicates,
        )
    }

    @Test
    fun throughputMatchesTheAdaptersResponseDelay() = runTest {
        val timing = probeHarrier().report.timing
        assertEquals(10, timing.benchmarkSamples)
        assertEquals(45.0, timing.medianRoundTripMs)
        // 1000 / 45 is about 22 requests per second.
        assertEquals(1000.0 / 45, timing.estimatedRequestsPerSecond!!, 0.01)
    }

    @Test
    fun coverageAgainstTheDefinitionListsOnlyThePidsTheFixtureLacks() = runTest {
        val outcome = probeHarrier()
        val coverage = DefinitionCoverage.compare(outcome.report, catalogFor(outcome.simulation.definition))

        val provided = outcome.simulation.ecus.flatMap { it.data.keys }.filter { it.startsWith("01") }.map { it.substring(2) }.toSet()
        assertTrue(coverage.missing.none { it.pid in provided }, "missing: ${coverage.missing}")
        assertEquals(setOf("04", "0F", "11", "33", "5C"), coverage.missing.map { it.pid }.toSet())
        assertTrue(coverage.missing.all { it.catalogEcu == null }, "these are generic definitions")

        // Definitions bound to an ECU were confirmed on that ECU, the generic ones on every ECU that has the PID.
        val byPid = coverage.confirmed.associateBy({ it.pid to it.catalogEcu }, { it.answeredBy })
        assertEquals(listOf("7E4"), byPid.getValue("9B" to "rdcm"))
        assertEquals(listOf("7E0"), byPid.getValue("9D" to "ecm"))
        assertEquals(listOf("7E0", "7E4"), byPid.getValue("0C" to null))
        assertEquals(listOf("7E0", "7E1"), byPid.getValue("31" to null))
        assertTrue(coverage.unmapped.isEmpty(), "everything the car reports is defined: ${coverage.unmapped}")
    }

    @Test
    fun theAdapterOnlyEverSawReadOnlyTraffic() = runTest {
        val outcome = probeHarrier()
        val allowedServices = setOf("01", "09")
        val allowedHeaders = (0x7E0..0x7E7).map { it.toString(16).uppercase() } + "7DF"
        val diagnostic = outcome.adapter.received.filterNot { it.startsWith("AT") }
        assertTrue(diagnostic.isNotEmpty())
        for (command in outcome.adapter.received) {
            when {
                command.startsWith("ATSH") -> assertTrue(command.removePrefix("ATSH") in allowedHeaders, command)
                command.startsWith("AT") -> Unit
                // Service is the first byte; the response-count suffix may add a digit.
                else -> assertTrue(command.take(2) in allowedServices, "unexpected request $command")
            }
        }
        assertFalse(diagnostic.any { it.startsWith("04") || it.startsWith("22") || it.startsWith("2E") || it.startsWith("2F") || it.startsWith("31") })
    }

    @Test
    fun reportsEveryStageAndSharedFormsNeverCarryTheFullVin() = runTest {
        val outcome = probeHarrier()
        assertEquals(ProbeStage.entries.toList(), outcome.stages)
        val shared = outcome.report.redacted()
        assertEquals("SIM**************", shared.ecus[0].vin)
        assertNull(shared.ecus[1].vin)
        assertFalse(shared.toJson().contains("HARRIER"))
        assertEquals(outcome.report.redacted(), CapabilityReport.fromJson(shared.toJson()))
    }
}
