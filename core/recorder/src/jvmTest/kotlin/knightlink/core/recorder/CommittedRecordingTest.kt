package knightlink.core.recorder

import knightlink.core.adapter.Elm327Driver
import knightlink.core.common.hexToBytes
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.telemetry.TelemetryDecoder
import knightlink.core.vehicle.CapabilityProbe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the on-disk format: a recording that was written once (by `probe --record`, then `scrub`) and committed must
 * keep parsing, re-serialise to the same bytes, and keep driving the unmodified stack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommittedRecordingTest {
    private val harness = StackHarness()
    private val text = File(harness.root, "fixtures/recordings/sim-harrier-probe.jsonl").readText().replace("\r\n", "\n")
    private val recording = Recording.parse(text)

    @Test
    fun theFileParsesAndWritesBackToTheSameText() {
        assertFalse(recording.truncated)
        assertEquals("adapters/vgate-icar-pro-2s", recording.header.adapter)
        assertEquals(text, recording.toText())
        assertEquals(EventKind.OPEN, recording.events.first().kind)
        assertEquals(EventKind.CLOSE, recording.events.last().kind)
        assertEquals(
            harness.adapterDefinition.initScript.map {
                it + "\r"
            },
            recording.events.filter { it.kind == EventKind.TX }.take(9).map { it.bytes.decodeToString() },
        )
    }

    @Test
    fun theFileHoldsNoVinBeyondTheManufacturerCode() {
        val vin = harness.simulation.ecus.first { it.vin != null }.vin!!
        assertFalse(vin in text)
        // The scrubber has nothing left to do: scrubbing a scrubbed recording changes nothing.
        val again = RecordingScrubber.scrub(recording)
        assertTrue(again.warnings.isEmpty())
        assertEquals(recording, again.recording)
    }

    @Test
    fun replayedByCommandThroughTheRealDriverAndProbeItReportsTheSimulatedCar() = runTest {
        val stream = ReplayByteStream(recording, ReplayMode.BY_COMMAND)
        // The file predates the warm-up, so it holds none: the driver is told not to send one, or the replay would not know it.
        val driver = Elm327Driver(stream, backgroundScope, config = Elm327Driver.Config(warmUp = false))
        driver.initialize(harness.adapterDefinition.initScript)
        val report = CapabilityProbe(driver, testTimeSource).run()

        assertEquals(0, stream.unknownCommands, stream.unknownCommandTexts.toString())
        assertTrue(report.warnings.isEmpty(), report.warnings.toString())
        assertEquals(listOf("ECM-EngineControl", "TCM-TransmisCtrl", "RDCM-ReductantCtrl"), report.ecus.map { it.name })
        assertEquals(listOf("7E0", "7E1", "7E4"), report.ecus.map { it.requestId })
        assertEquals(listOf("05", "0B", "0C", "0D", "10", "2F", "31", "42", "46", "78", "8B", "9D"), report.ecus[0].supportedPids)
        assertEquals(listOf("SIM00000000000000", null, null), report.ecus.map { it.vin })
        assertEquals("ELM327 v2.3", report.adapter.banner)

        // The polls the recording also holds, asked in an order of our own choosing.
        val decoder = TelemetryDecoder(harness.catalog)
        suspend fun read(requestId: Int, payloadHex: String) = driver.request(requestId, payloadHex.hexToBytes(), 1)
            .messages.filterIsInstance<DiagnosticResponse.Positive>().flatMap(decoder::decode).associate { it.signal.id to it.signal.value }
        assertEquals(799.0, read(0x7E0, "010C").getValue("engine.rpm"), 0.005)
        assertEquals(89.02, read(0x7E0, "018B").getValue("dpf.soot_pct"), 0.005)
        assertEquals(799.0, read(0x7E0, "010C").getValue("engine.rpm"), 0.005, "a repeated request is answered from the recording again")
        assertEquals(0, stream.unknownCommands, stream.unknownCommandTexts.toString())
    }
}
