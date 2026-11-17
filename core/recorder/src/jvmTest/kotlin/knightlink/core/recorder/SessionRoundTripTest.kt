package knightlink.core.recorder

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.Elm327Driver
import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponseDecoder
import knightlink.core.protocol.ElmStreamFramer
import knightlink.core.runtime.confirmedPollItems
import knightlink.core.scheduler.DiagnosticRequester
import knightlink.core.scheduler.PollScheduler
import knightlink.core.scheduler.PollingLoop
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.sim.SimulationFile
import knightlink.core.sim.VirtualElm327
import knightlink.core.telemetry.TelemetryDecoder
import knightlink.core.transport.ByteStream
import knightlink.core.vehicle.CapabilityProbe
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What a session through the real driver, probe, scheduler and decoder came up with. */
internal class SessionOutcome(val banner: String?, val report: CapabilityReport, val readings: Map<String, Double>, val polls: Int)

/** Loads the real definitions and runs the real stack over any [ByteStream]: the thing a recording must reproduce. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StackHarness {
    val root = File(System.getProperty("knightlink.repoRoot"))
    val simulation = SimulationFile.parse(File(root, "fixtures/sim/harrier-dpf-regen.json").readText())
    val adapterDefinition = AdapterDefinition.parse(File(root, "definitions/adapters/vgate-icar-pro-2s.json").readText())
    val catalog: SignalCatalog = definition(simulation.definition).let { top -> SignalCatalog.of(top.extends.map(::definition) + top) }

    private fun definition(id: String): DefinitionFile = DefinitionJson.parse(File(root, "definitions/$id.json").readText())

    fun adapter() = VirtualElm327(simulation.buildVehicle(), VirtualElm327.Config(responseDelay = 45.milliseconds))

    /** Init script, probe and a handful of scheduled polls. Polling is optional because a BY_COMMAND replay may not have them. */
    suspend fun TestScope.session(stream: ByteStream, polls: Int = POLLS): SessionOutcome {
        val driver = Elm327Driver(stream, backgroundScope, config = Elm327Driver.Config(timeSource = testTimeSource))
        val banner = driver.initialize(adapterDefinition.initScript).banner
        val report = CapabilityProbe(driver, testTimeSource).run()
        val readings = LinkedHashMap<String, Double>()
        var count = 0
        if (polls > 0) {
            // The poll list the app builds: only what the probe confirmed, each parameter from the ECU that has it.
            val items = confirmedPollItems(catalog, DefinitionCoverage.compare(report, catalog), "ecm") { parameter ->
                if (parameter.signals.any { it.definition.id.startsWith("dpf.") || it.definition.id.startsWith("exhaust.") }) 500.milliseconds else 2.seconds
            }
            val decoder = TelemetryDecoder(catalog)
            PollingLoop(DiagnosticRequester(driver::request), PollScheduler(items), testTimeSource).results().take(polls).toList().forEach { result ->
                count++
                assertNull(result.error, "poll ${result.item} failed")
                result.response!!.messages.filterIsInstance<DiagnosticResponse.Positive>().flatMap(decoder::decode).forEach {
                    readings["${it.ecu}/${it.signal.id}"] = it.signal.value
                }
            }
        }
        return SessionOutcome(banner, report, readings, count)
    }

    companion object {
        // One round of the confirmed parameters (13 on the simulated car): which item is next is then independent of timing, so a replay at another speed asks the same.
        const val POLLS = 12
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRoundTripTest {
    private val harness = StackHarness()
    private val placeholderVin = harness.simulation.ecus.first { it.vin != null }.vin!!
    private val scrubbedVin = placeholderVin.take(3) + "0".repeat(14)

    private class Recorded(val outcome: SessionOutcome, val recording: Recording)

    private fun record(): Recorded {
        lateinit var recorded: Recorded
        runTest {
            val sink = InMemoryRecordSink()
            val stream = RecordingByteStream(harness.adapter(), sink, testTimeSource, RecordingHeader(harness.adapterDefinition.id, "simulated Harrier"))
            val outcome = with(harness) { session(stream) }
            stream.close()
            // Text, not objects: what a file would hold.
            recorded = Recorded(outcome, Recording.parse(sink.text()))
        }
        return recorded
    }

    private fun replay(recording: Recording, mode: ReplayMode, polls: Int = StackHarness.POLLS, speed: Double = 1.0): Pair<SessionOutcome, ReplayByteStream> {
        lateinit var result: Pair<SessionOutcome, ReplayByteStream>
        runTest {
            val stream = ReplayByteStream(recording, mode, speed)
            val outcome = with(harness) { session(stream, polls) }
            advanceUntilIdle()
            result = outcome to stream
        }
        return result
    }

    @Test
    fun theRecordingIsABytePerfectConversationOfTheRealStack() {
        val recorded = record()
        val events = recorded.recording.events
        assertEquals(harness.adapterDefinition.id, recorded.recording.header.adapter)
        assertEquals(EventKind.OPEN, events.first().kind)
        assertEquals(EventKind.CLOSE, events.last().kind)
        val sent = events.filter { it.kind == EventKind.TX }.map { it.bytes.decodeToString() }
        // The warm-up first (the echo switched off twice), then the definition's script.
        val script = listOf("ATE0", "ATE0") + harness.adapterDefinition.initScript
        assertEquals(script.map { it + "\r" }, sent.take(script.size))
        // The simulator delivers in 20-byte chunks, as a BLE adapter would; the recording keeps that.
        assertTrue(events.filter { it.kind == EventKind.RX }.all { it.bytes.size <= 20 })
        assertEquals(events.map { it.timeMs }.sorted(), events.map { it.timeMs })
    }

    @Test
    fun strictReplayThroughANewStackGivesIdenticalResultsAndConsumesEveryEvent() {
        val recorded = record()
        val (outcome, stream) = replay(recorded.recording, ReplayMode.STRICT)

        assertEquals(recorded.outcome.banner, outcome.banner)
        assertEquals(recorded.outcome.polls, outcome.polls)
        assertEquals(recorded.outcome.readings, outcome.readings)
        // The probe, including what it measured: the replay spends the recorded time, so even the timings agree.
        assertEquals(recorded.outcome.report, outcome.report)
        assertTrue(outcome.report.warnings.isEmpty(), outcome.report.warnings.toString())

        assertEquals(listOf("ECM-EngineControl", "TCM-TransmisCtrl", "RDCM-ReductantCtrl"), outcome.report.ecus.map { it.name })
        assertEquals(listOf("05", "0B", "0C", "0D", "10", "2F", "31", "42", "46", "78", "8B", "9D"), outcome.report.ecus[0].supportedPids)
        assertEquals(89.02, outcome.readings.getValue("ecm/dpf.soot_pct"), 0.005)
        assertEquals(799.0, outcome.readings.getValue("ecm/engine.rpm"), 0.005)

        assertNull(stream.firstMismatch)
        assertEquals(0, stream.remainingEvents)
    }

    @Test
    fun strictReplayAtDoubleSpeedGivesTheSameReadingsInHalfTheTime() {
        val recorded = record()
        val (outcome, _) = replay(recorded.recording, ReplayMode.STRICT, speed = 2.0)
        assertEquals(recorded.outcome.readings, outcome.readings)
        assertEquals(recorded.outcome.report.ecus, outcome.report.ecus)
    }

    @Test
    fun byCommandReplayOfTheInitAndProbeAnswersEveryCommandFromTheRecording() {
        val recorded = record()
        val (outcome, stream) = replay(recorded.recording, ReplayMode.BY_COMMAND, polls = 0)
        assertEquals(0, stream.unknownCommands, stream.unknownCommandTexts.toString())
        assertEquals(recorded.outcome.report.ecus, outcome.report.ecus)
        assertEquals(recorded.outcome.report.adapter, outcome.report.adapter)
    }

    /** Every VIN-bearing payload the real decoder finds in the rx bytes of [recording]. */
    private fun vinPayloads(recording: Recording): List<ByteArray> {
        val framer = ElmStreamFramer()
        val decoder = ElmResponseDecoder(extendedIds = false)
        return recording.events.filter { it.kind == EventKind.RX }
            .flatMap { framer.feed(it.bytes) }
            .flatMap { decoder.decode(it).messages }
            .filterIsInstance<DiagnosticResponse.Positive>()
            .filter { it.service == 0x09 && it.data.firstOrNull() == 0x02.toByte() }
            .map { it.data }
    }

    private fun mentionsVin(recording: Recording): Boolean {
        val vinHex = placeholderVin.encodeToByteArray().toHex()
        val characters = placeholderVin.drop(3).encodeToByteArray()
        val inEvents = recording.events.any { vinHex in it.payload || (it.kind.carriesBytes && placeholderVin in it.bytes.decodeToString()) }
        val inPayloads = vinPayloads(recording).any { payload -> String(payload, Charsets.ISO_8859_1).contains(String(characters, Charsets.ISO_8859_1)) }
        return inEvents || inPayloads
    }

    @Test
    fun scrubbingRemovesTheVinAndTheProbeOfTheScrubbedReplayReportsTheManufacturerCodeAndZeros() {
        val recorded = record()
        // The check must be able to fail: the unscrubbed recording does contain the placeholder VIN.
        assertTrue(mentionsVin(recorded.recording))
        assertEquals(placeholderVin, recorded.outcome.report.ecus[0].vin)

        val scrubbed = RecordingScrubber.scrub(recorded.recording)
        assertTrue(scrubbed.warnings.isEmpty(), scrubbed.warnings.toString())
        assertFalse(mentionsVin(scrubbed.recording))
        assertEquals(recorded.recording.events.map { it.timeMs to it.payload.length }, scrubbed.recording.events.map { it.timeMs to it.payload.length })
        // The text form too, which is what gets shared.
        assertFalse(placeholderVin in scrubbed.recording.toText())

        val (outcome, stream) = replay(scrubbed.recording, ReplayMode.STRICT)
        assertEquals(scrubbedVin, outcome.report.ecus[0].vin)
        assertNull(outcome.report.ecus[1].vin)
        // Nothing but the VIN changed.
        val expected = recorded.outcome.report.let { report -> report.copy(ecus = report.ecus.map { it.copy(vin = it.vin?.let { _ -> scrubbedVin }) }) }
        assertEquals(expected, outcome.report)
        assertEquals(recorded.outcome.readings, outcome.readings)
        assertNull(stream.firstMismatch)
        assertEquals(0, stream.remainingEvents)
    }
}
