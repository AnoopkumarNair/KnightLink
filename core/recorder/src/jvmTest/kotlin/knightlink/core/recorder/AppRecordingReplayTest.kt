package knightlink.core.recorder

import knightlink.core.adapter.Elm327Driver
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleSession
import knightlink.core.runtime.confirmedPollItems
import knightlink.core.runtime.pollRound
import knightlink.core.scheduler.DiagnosticRequester
import knightlink.core.scheduler.PollScheduler
import knightlink.core.sim.SimulatedTransport
import knightlink.core.sim.VirtualElm327
import knightlink.core.vehicle.CapabilityProbe
import knightlink.core.vehicle.DefinitionCoverage
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * `tools/cli replay` runs the app's own poll plan ([pollRound] over [confirmedPollItems]), so a by-command replay of an app recording
 * has an answer for every command it sends. Before, the tool asked every shared PID of every ECU (`7E4:0104`), which the app never does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppRecordingReplayTest {
    private val harness = StackHarness()

    private fun recordAppSession(): Recording {
        lateinit var sink: InMemoryRecordSink
        runTest {
            val vehicle = harness.simulation.buildVehicle()
            val simulated = SimulatedTransport(vehicle = { vehicle }, config = VirtualElm327.Config(responseDelay = 45.milliseconds))
            val transport = RecordingTransport(
                simulated,
                { InMemoryRecordSink().also { sink = it } },
                testTimeSource,
                RecordingHeader(harness.adapterDefinition.id, "app"),
            )
            val session = VehicleSession(transport, harness.adapterDefinition.initScript, harness.catalog, backgroundScope, testTimeSource)
            session.start()
            advanceTimeBy(15.seconds)
            runCurrent()
            assertIs<SessionState.Polling>(session.state.value)
            session.stop()
        }
        return Recording.parse(sink.text())
    }

    @Test
    fun aByCommandReplayOfAnAppRecordingHasAnAnswerForEveryCommandOfTheToolsPollPlan() {
        val recording = recordAppSession()
        val sent = recording.events.filter { it.kind == EventKind.TX }.joinToString("") { it.bytes.decodeToString() }.split('\r')
        assertTrue(sent.any { it.startsWith("01") && it.length > 6 && it.length % 2 == 0 }, "the app batched: $sent")

        runTest {
            val stream = ReplayByteStream(recording, ReplayMode.BY_COMMAND)
            val driver = Elm327Driver(stream, backgroundScope, config = Elm327Driver.Config(timeSource = testTimeSource))
            driver.initialize(harness.adapterDefinition.initScript)
            val report = CapabilityProbe(driver, testTimeSource).run()
            val coverage = DefinitionCoverage.compare(report, harness.catalog)
            val items = confirmedPollItems(harness.catalog, coverage, harness.catalog.ecus.first().id) { it.tier.period }
            val results = pollRound(items, PollScheduler.MAX_BATCH, DiagnosticRequester(driver::request))

            assertEquals(0, stream.unknownCommands, stream.unknownCommandTexts.toString())
            assertTrue(results.isNotEmpty() && results.all { it.error == null })
            // Every confirmed parameter was asked once, and every request was answered.
            assertEquals(items.size, results.sumOf { it.items.size })
            assertEquals(items.size, results.sumOf { it.answeredItems.size })
            // No shared PID is asked of an ECU that is not its owner in the plan: 7E4 is asked only for what only it has (9B).
            val toReductant = results.filter { it.item.requestId == 0x7E4 }.flatMap { it.items }.map { it.id }
            assertEquals(listOf("rdcm:019B"), toReductant)
        }
    }

    @Test
    fun theOldPlanThatAskedEveryEcuForSharedPidsWouldNotHaveBeenAnswered() {
        val recording = recordAppSession()
        runTest {
            val stream = ReplayByteStream(recording, ReplayMode.BY_COMMAND)
            val driver = Elm327Driver(stream, backgroundScope, config = Elm327Driver.Config(timeSource = testTimeSource))
            driver.initialize(harness.adapterDefinition.initScript)
            CapabilityProbe(driver, testTimeSource).run()
            // 7E4 reports PID 05 and 0C, but the app never asked it: the recording has no answer, and the stream says so.
            driver.request(0x7E4, byteArrayOf(0x01, 0x05), 1)
            assertEquals(listOf("7E4:0105"), stream.unknownCommandTexts.filter { it.startsWith("7E4:0105") })
        }
    }
}
