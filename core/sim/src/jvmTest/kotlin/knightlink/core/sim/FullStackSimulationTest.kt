package knightlink.core.sim

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.Elm327Driver
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.scheduler.DiagnosticRequester
import knightlink.core.scheduler.PollScheduler
import knightlink.core.scheduler.PollingLoop
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.telemetry.TelemetryDecoder
import knightlink.core.telemetry.pollItems
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole stack on virtual time: scenario file -> virtual ECUs -> virtual ELM327 -> real driver ->
 * scheduler -> real definition files -> readings. No hardware, runs in milliseconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FullStackSimulationTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))

    private fun definition(id: String): DefinitionFile =
        DefinitionJson.parse(File(root, "definitions/$id.json").readText())

    private fun catalogFor(id: String): SignalCatalog {
        val top = definition(id)
        return SignalCatalog.of(top.extends.map(::definition) + top)
    }

    @Test
    fun dpfRegenerationScenarioIsObservedEndToEnd() = runTest {
        val simulation = SimulationFile.parse(File(root, "fixtures/sim/harrier-dpf-regen.json").readText())
        val adapterDefinition = AdapterDefinition.parse(File(root, "definitions/adapters/vgate-icar-pro-2s.json").readText())
        val catalog = catalogFor(simulation.definition)
        val vehicle = simulation.buildVehicle()
        // 45 ms per command is in the range measured for ELM327 adapters over BLE.
        val adapter = VirtualElm327(vehicle, VirtualElm327.Config(responseDelay = 45.milliseconds))
        val driver = Elm327Driver(adapter, backgroundScope)

        assertEquals("ELM327 v2.3", driver.initialize(adapterDefinition.initScript).banner)

        val items = catalog.pollItems(primaryEcu = "ecm") { parameter ->
            if (parameter.signals.any { it.definition.id.startsWith("dpf.") || it.definition.id.startsWith("exhaust.") }) 500.milliseconds else 2.seconds
        }
        val loop = PollingLoop(DiagnosticRequester(driver::request), PollScheduler(items), testTimeSource)
        val decoder = TelemetryDecoder(catalog)

        backgroundScope.launch { simulation.play(vehicle) }

        val soot = ArrayList<Double>()
        val status = ArrayList<Double>()
        val latest = HashMap<String, Double>()
        var regenSeen = false
        var polls = 0
        loop.results()
            .takeWhile { !(regenSeen && latest["dpf.regen_status"] == 0.0) }
            .collect { result ->
                polls++
                assertTrue(result.error == null, "poll ${result.item} failed: ${result.error}")
                for (message in result.response!!.messages.filterIsInstance<DiagnosticResponse.Positive>()) {
                    for (reading in decoder.decode(message)) {
                        latest[reading.signal.id] = reading.signal.value
                        if (reading.signal.id == "dpf.soot_pct") soot += reading.signal.value
                        if (reading.signal.id == "dpf.regen_status") status += reading.signal.value
                    }
                }
                if (latest["dpf.regen_status"] == 2.0) regenSeen = true
            }

        // The DPF story from the SRS: soot climbs to 100 %, regeneration runs, soot ends at 17.65 %.
        assertEquals(89.02, soot.first(), 0.005)
        assertEquals(100.0, soot.max(), 0.005)
        assertEquals(17.65, soot.last(), 0.005)
        assertEquals(listOf(0.0, 2.0, 0.0), status.zipWithNext().filter { it.first != it.second }.map { it.second }.let { listOf(status.first()) + it })

        // Every definition was exercised, including the AdBlue controller on its own ECU.
        assertEquals(95.686, latest.getValue("def.level_pct"), 0.005)
        assertEquals(14.1, latest.getValue("electrical.module_voltage"), 0.005)
        assertTrue(latest.getValue("exhaust.egt_b1s1") > 100)

        // SRS rule 1: never use the functional address for polling; every request names its ECU.
        val headers = adapter.received.filter { it.startsWith("ATSH") }
        assertTrue(headers.isNotEmpty())
        assertFalse(headers.any { it == "ATSH7DF" })
        // Header changes are a small share of the traffic thanks to ECU affinity.
        assertTrue(headers.size < polls / 4, "${headers.size} header changes for $polls polls")
    }

    @Test
    fun everySimulationFileBuildsAndPointsAtARealDefinition() {
        val files = File(root, "fixtures/sim").listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val simulation = SimulationFile.parse(file.readText())
            assertEquals("sim/${file.nameWithoutExtension}", simulation.id)
            val catalog = catalogFor(simulation.definition)
            val vehicle = simulation.buildVehicle()
            // A scenario may hold control units the definition does not know (the full scan finds those), but never lacks one it names.
            assertTrue(
                vehicle.ecus.map {
                    it.requestId
                }.toSet().containsAll(catalog.ecus.map { it.requestCanId }),
                "${simulation.id}: lacks an ECU of its definition",
            )
            assertTrue(simulation.timeline.all { step -> simulation.ecus.any { it.id == step.ecu } }, "${simulation.id}: timeline names an unknown ECU")
        }
    }
}
