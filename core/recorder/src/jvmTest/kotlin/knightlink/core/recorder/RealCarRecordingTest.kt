package knightlink.core.recorder

import knightlink.core.adapter.Elm327Driver
import knightlink.core.common.hexToBytes
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.runtime.confirmedPollItems
import knightlink.core.runtime.pollRound
import knightlink.core.scheduler.DiagnosticRequester
import knightlink.core.telemetry.TelemetryDecoder
import knightlink.core.vehicle.CapabilityProbe
import knightlink.core.vehicle.DefinitionCoverage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first real-car capture (2026-11-17, a 2020 Harrier, app build `0b12b3f`, scrubbed by `tools/cli scrub`): what it holds, that it is
 * safe to keep in the repository, and that the stack still makes sense of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealCarRecordingTest {
    private val harness = StackHarness()
    private val text = File(harness.root, "fixtures/raw/harrier-2026-11-17-apartment-drive.jsonl").readText().replace("\r\n", "\n")
    private val recording = Recording.parse(text)

    private val ecmPids = listOf(
        "01", "04", "05", "0B", "0C", "0D", "10", "13", "1C", "1F", "21", "2F", "30", "31", "33", "34", "42", "45", "46", "49", "4A", "4C", "4F",
        "62", "63", "68", "69", "6B", "6D", "71", "78", "7A", "83", "85", "88", "8B", "8E", "92", "98", "9D", "A1",
    )

    @Test
    fun theFileHoldsNoVin() {
        assertFalse(recording.truncated)
        // A VIN is 17 characters from this alphabet; the recording holds hex that the adapter printed, so check the decoded bytes too.
        val vin = Regex("""\b[A-HJ-NPR-Z0-9]{17}\b""")
        val hexOnly = Regex("""[0-9A-F]{17}""")
        assertTrue(vin.findAll(text).none { !hexOnly.matches(it.value) }, "a VIN-shaped token is in the raw text")
        val decoded = recording.events.filter { it.kind == EventKind.TX || it.kind == EventKind.RX }.joinToString("") { it.bytes.decodeToString() }
        assertTrue(vin.findAll(decoded).none { !hexOnly.matches(it.value) }, "a VIN-shaped token is in the exchanged bytes")
        // The VIN reply was cut to its first frame by the response-count digit, which is what the scrubber replaced; nothing is left to scrub.
        assertTrue(RecordingScrubber.scrub(recording).recording.events.size <= recording.events.size)
    }

    @Test
    fun multiFrameRepliesAreTruncatedToTheirFirstFrameBecauseOfTheCountDigit() {
        // The evidence for the response-count bug: with the digit, the adapter stopped after the first CAN frame of 0178 (EGT, 11 bytes)
        // and 018B (DPF, 9 bytes), and never sent flow control, so the consecutive frames (7E8 21 ...) are not in the recording.
        val exchanges = indexExchanges(recording.events)
        for ((command, firstFrame) in mapOf("01781" to "7E8100B4178", "018B1" to "7E81009418B")) {
            val replies = exchanges.filter { it.key == command }
            assertTrue(replies.isNotEmpty(), "$command was not asked")
            for (exchange in replies) {
                val reply = exchange.rxIndices.joinToString("") { recording.events[it].bytes.decodeToString() }
                assertTrue(reply.startsWith(firstFrame), "$command: $reply")
                assertFalse("7E821" in reply, "$command has a consecutive frame")
            }
        }
    }

    @Test
    fun replayedByCommandThroughTheProbeItGivesTheEcusAndPidsOfTheCar() = runTest {
        val stream = ReplayByteStream(recording, ReplayMode.BY_COMMAND)
        val driver = Elm327Driver(stream, backgroundScope, config = Elm327Driver.Config(timeSource = testTimeSource))
        driver.initialize(harness.adapterDefinition.initScript)
        val report = CapabilityProbe(driver, testTimeSource).run()

        assertEquals(listOf("7E0", "7E1", "7E4"), report.ecus.map { it.requestId })
        assertEquals(ecmPids, report.ecus[0].supportedPids)
        assertEquals(listOf("01", "30", "31"), report.ecus[1].supportedPids)
        assertEquals(listOf("01", "04", "05", "0C", "0D", "1C", "21", "33", "46", "85", "9B"), report.ecus[2].supportedPids)
        // The probe now sends the VIN request without the count digit; the recording holds it with the digit, which does not matter to a replay.
        assertEquals(0, stream.unknownCommands, stream.unknownCommandTexts.toString())
    }

    @Test
    fun theStandardDefinitionsDecodeTheRealBytesToWhatTheDriveWasLike() = runTest {
        val stream = ReplayByteStream(recording, ReplayMode.BY_COMMAND)
        val driver = Elm327Driver(stream, backgroundScope, config = Elm327Driver.Config(timeSource = testTimeSource))
        driver.initialize(harness.adapterDefinition.initScript)
        val decoder = TelemetryDecoder(harness.catalog)

        suspend fun series(requestId: Int, pidHex: String, signal: String, samples: Int = 181): List<Double> = (1..samples).mapNotNull {
            // Single-frame replies: the count digit is safe and is what the app sends for them.
            driver.request(requestId, "01$pidHex".hexToBytes(), 1).messages.filterIsInstance<DiagnosticResponse.Positive>()
                .flatMap(decoder::decode).firstOrNull { reading -> reading.signal.id == signal }?.signal?.value
        }

        val coolant = series(0x7E0, "05", "engine.coolant_temp")
        assertEquals(24.0, coolant.min(), 0.001)
        assertEquals(42.0, coolant.max(), 0.001)
        val rpm = series(0x7E0, "0C", "engine.rpm")
        assertTrue(rpm.max() in 1900.0..2000.0, "rpm up to ${rpm.max()}")
        assertEquals(0.0, rpm.min(), 0.001)
        val speed = series(0x7E0, "0D", "vehicle.speed")
        assertEquals(25.0, speed.max(), 0.001)
        assertTrue(series(0x7E0, "04", "engine.load_pct").all { it in 0.0..100.0 })
        assertTrue(series(0x7E0, "0B", "intake.map_kpa").all { it in 50.0..90.0 })
        assertTrue(series(0x7E0, "10", "intake.maf").all { it in 0.0..45.0 })
        assertTrue(series(0x7E0, "2F", "fuel.level_pct").all { it in 60.0..62.0 })
        assertEquals(7.0, series(0x7E0, "31", "distance.since_dtc_clear_km").first(), 0.001)
        assertEquals(91.0, series(0x7E0, "33", "ambient.pressure_kpa").first(), 0.001)
        assertTrue(series(0x7E0, "42", "electrical.module_voltage").all { it in 9.0..15.0 })
        assertTrue(series(0x7E0, "46", "ambient.air_temp").all { it in 24.0..27.0 })
        val fuel = series(0x7E0, "9D", "fuel.rate_gps")
        assertTrue(fuel.max() in 1.0..2.0, "fuel rate up to ${fuel.max()} g/s")
        assertTrue(series(0x7E0, "9D", "fuel.vehicle_rate_gps").max() in 1.0..2.0, "the vehicle fuel rate follows the engine fuel rate")
        // AdBlue: temperature and level from the reductant controller (7E4).
        assertTrue(series(0x7E4, "9B", "def.temp").all { it == 23.0 })
        assertTrue(series(0x7E4, "9B", "def.level_pct").all { it in 90.0..97.0 })
    }

    @Test
    fun theAppsPollPlanIsAnsweredByTheRecordingExceptForParametersItDidNotKnowYet() = runTest {
        val stream = ReplayByteStream(recording, ReplayMode.BY_COMMAND)
        val driver = Elm327Driver(stream, backgroundScope, config = Elm327Driver.Config(timeSource = testTimeSource))
        driver.initialize(harness.adapterDefinition.initScript)
        val report = CapabilityProbe(driver, testTimeSource).run()
        val coverage = DefinitionCoverage.compare(report, harness.catalog)

        // The poll list is the app's (each parameter from its ECU), and every PID the Harrier reports is now defined.
        val items = confirmedPollItems(harness.catalog, coverage, "ecm") { it.tier.period }
        assertEquals(emptyList(), coverage.unmapped.filter { it.requestId == "7E0" }.flatMap { it.pids }, "the engine ECU's PIDs are all defined")

        // The build that made the recording asked single PIDs it knew: those are answered, by command, for the same ECU as the app asks.
        val results = pollRound(items.filter { it.id.removePrefix("ecm:01") in OLD_POLL }, maxBatch = 1, requester = DiagnosticRequester(driver::request))
        val answered = results.filter { it.answered }.map { it.item.id.substringAfter(":01") }.sorted()
        assertEquals(listOf("04", "05", "0B", "0C", "0D", "10", "2F", "31", "33", "42", "46", "9D"), answered)
    }

    private companion object {
        /** What the app of that build polled from the engine ECU (the definitions then held these and 0178 and 018B, which were cut short). */
        val OLD_POLL = setOf("04", "05", "0B", "0C", "0D", "10", "2F", "31", "33", "42", "46", "9D")
    }
}
