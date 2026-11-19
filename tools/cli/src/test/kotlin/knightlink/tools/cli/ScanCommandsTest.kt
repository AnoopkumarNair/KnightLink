package knightlink.tools.cli

import knightlink.core.common.SessionEvents
import knightlink.core.common.hexToBytes
import knightlink.core.recorder.Recording
import knightlink.core.recorder.RecordingEvent
import knightlink.core.recorder.RecordingHeader
import knightlink.core.scan.BatteryReading
import knightlink.core.scan.DidAnswer
import knightlink.core.scan.DtcDecoder
import knightlink.core.scan.EcuReadiness
import knightlink.core.scan.Identifier
import knightlink.core.scan.MonitorStatus
import knightlink.core.scan.ScanEcu
import knightlink.core.scan.ScanOutcome
import knightlink.core.scan.ScanResult
import knightlink.core.scan.SweepResult
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanCommandsTest {
    private fun tx(t: Long, text: String) = RecordingEvent.tx(t, text.encodeToByteArray())

    private fun rx(t: Long, text: String) = RecordingEvent.rx(t, text.encodeToByteArray())

    private val catalog = catalogFor(DEFAULT_VEHICLE)

    /** A drive: the gearbox identifier is read twice a second, the car accelerates from standstill, the phone feels it. */
    private fun drive(): Recording {
        val events = ArrayList<RecordingEvent>()
        events += RecordingEvent.open(0)
        events += tx(100, "ATSH7E1\r")
        events += rx(130, "OK\r\r>")
        events += tx(200, "221E121\r")
        events += rx(240, "7E904621E120A\r\r>")
        events += tx(300, "ATSH7E0\r")
        events += rx(330, "OK\r\r>")
        // A batched read of rpm and speed: 41 0C <2 bytes> 0D <1 byte>.
        events += tx(400, "010C0D\r")
        events += rx(440, "7E806410C1F400D28\r\r>")
        events += RecordingEvent.external(450, "mot 40 0.00 0.00 0.00 0.000 0.000 0.000;0 3.00 4.00 0.00 0.000 0.000 0.000")
        events += RecordingEvent.external(460, "gps 52.52 13.40 36.0 90 5")
        events += tx(500, "ATSH7E1\r")
        events += rx(530, "OK\r\r>")
        events += tx(600, "221E12\r")
        events += rx(640, "7E904621E1214\r\r>")
        events += tx(700, "221E12\r")
        events += rx(740, "7E9037F2231\r\r>")
        events += tx(800, "221E66\r")
        events += rx(840, "NO DATA\r\r>")
        events += RecordingEvent.close(900)
        return Recording(RecordingHeader("adapters/x", "a drive"), events)
    }

    @Test
    fun theTimelineShowsEachReadOfTheIdentifierNextToTheVehicleAndThePhone() {
        val lines = renderDidTimeline(drive(), "7e1", "1e12", catalog)
        assertEquals("Data identifier 1E12 of 7E1: 3 reads", lines[0])
        val rows = lines.drop(2)
        assertEquals(3, rows.size)
        // First read: before any speed was read, and before any phone sample.
        assertTrue(rows[0].contains("0A"), rows[0])
        assertTrue(rows[0].trim().split(' ').filter { it.isNotEmpty() }.takeLast(4) == listOf("-", "-", "-", "-"), rows[0])
        // Second read, 200 ms after the batched read of rpm 2000 and speed 40; the phone feels 5 m/s² (3-4-5), GPS says 36 km/h.
        val second = rows[1].trim().split(Regex("\\s+"))
        assertEquals(listOf("0.60", "14", "40", "2000", "5.00", "36.0"), second)
        // A refusal is shown as such.
        assertTrue("7F 31" in rows[2], rows[2])
    }

    @Test
    fun aRecordingWithoutPhoneSamplesSaysSo() {
        val bare = drive().let { it.copy(events = it.events.filterNot { e -> e.kind == knightlink.core.recorder.EventKind.EXTERNAL }) }
        val lines = renderDidTimeline(bare, "7E1", "1E12", catalog)
        assertTrue("no phone samples" in lines[0], lines[0])
    }

    @Test
    fun aReadThatWasNeverMadeIsReportedAndABadIdentifierIsRefused() {
        assertTrue(renderDidTimeline(drive(), "7E4", "1E12", catalog).last().contains("no read of 221E12 to 7E4"))
        assertFailsWith<IllegalArgumentException> { renderDidTimeline(drive(), "7E1", "12", catalog) }
    }

    // The report ---------------------------------------------------------------------------------------------

    private fun result(): ScanResult {
        val codes = DtcDecoder.uds(("FF" + "0A1F1F09" + "01230004" + "C1000010" + "C1030080").hexToBytes()).let { DtcDecoder.classify(it) }
        return ScanResult(
            outcome = ScanOutcome.COMPLETED,
            durationMs = 600_000,
            pausedMs = 30_000,
            stagesDone = listOf("probe", "readiness", "discovery", "identification", "faults", "sweep"),
            ecus = listOf(
                ScanEcu(
                    "7E0",
                    "7E8",
                    "probe",
                    "ECM",
                    identity = listOf(Identifier("F190", "VIN", null, "MAT" + "*".repeat(14)), Identifier("F187", "spare part number", "414243", "ABC")),
                    storedCodes = listOf("P0A1F"),
                    pendingCodes = emptyList(),
                    udsFaults = codes,
                ),
                ScanEcu("700", "708", "discovery", null),
            ),
            readiness = listOf(EcuReadiness("7E0", MonitorStatus.decode("010FEB40".hexToBytes(), withLamp = true))),
            sweeps = listOf(
                SweepResult("7E1", "gearbox", 2049, 2049, listOf(DidAnswer("1E12", "0A"), DidAnswer("1E7E", "")), refused = 2047),
            ),
            battery = listOf(BatteryReading(0, "14.1V"), BatteryReading(30_100, "14.0V")),
            warnings = listOf("a thing"),
        )
    }

    @Test
    fun theReportListsControlUnitsIdentitiesFaultsByStatusAndTheIdentifiersThatAnswered() {
        val text = renderScanReport(result()).joinToString("\n")
        assertTrue("Full scan: COMPLETED, 10.0 min" in text, text)
        assertTrue("7E0 -> 7E8  ECM  [found by probe]" in text)
        assertTrue("700 -> 708  (no name)  [found by discovery]" in text)
        assertTrue("F190 VIN: \"MAT**************\"" in text, text)
        assertTrue("F187 spare part number: \"ABC\"" in text, text)
        assertTrue("confirmed: P0A1F-1F(09)" in text)
        assertTrue("pending: P0123(04)" in text)
        assertTrue("warning lamp requested: U0103(80)" in text)
        assertTrue("only say their test has not run (not faults)" in text)
        assertTrue("Faults: 1 confirmed, 1 pending, 1 asking for the warning lamp; 1 codes only say" in text, text)
        assertTrue("PM filter".lowercase() in text.lowercase() || "particulate filter NOT complete" in text, text)
        assertTrue("1E12 = 0A  [inferred]" in text)
        assertTrue("1E7E = (seen, not read whole)" in text)
        assertTrue("7E1 gearbox: 2 answered of 2049 asked" in text)
        assertTrue("Battery: 14.1V at 0 s, 14.0V at 30 s" in text)
        assertTrue("warning: a thing" in text)
    }

    @Test
    fun noFullVinIsEverPrinted() {
        val text = renderScanReport(result()).joinToString("\n")
        assertFalse(Regex("[A-HJ-NPR-Z0-9]{17}").containsMatchIn(text.replace("*", "")), text)
    }

    @Test
    fun theLastCompletedScanIsPreferredToALaterCancelledOne() {
        val done = result()
        val lost = done.copy(outcome = ScanOutcome.LINK_LOST, detail = "link lost", stagesDone = listOf("probe"))
        val events = listOf(
            RecordingEvent.open(0),
            RecordingEvent.event(500, SessionEvents.SCAN_RESULT + ": " + done.toJson()),
            RecordingEvent.event(900, SessionEvents.SCAN_RESULT + ": " + lost.toJson()),
        )
        val recording = Recording(RecordingHeader(), events)
        assertEquals(done, scanResultOf(recording))
        assertEquals(listOf(done, lost), scanResultsIn(recording))
        // With none completed, the last one of any kind.
        assertEquals(lost, scanResultOf(Recording(RecordingHeader(), listOf(RecordingEvent.open(0), events[2]))))
    }

    @Test
    fun theResultIsFoundInTheRecordingsEventOrInTheFileBesideIt() {
        val event = RecordingEvent.event(500, SessionEvents.SCAN_RESULT + ": " + result().toJson())
        val inRecording = Recording(RecordingHeader(), listOf(RecordingEvent.open(0), event))
        assertEquals(result(), scanResultOf(inRecording))

        val directory = Files.createTempDirectory("scan-cli").toFile()
        try {
            val file = File(directory, "20261117T101500.000Z.jsonl")
            val without = Recording(RecordingHeader(), listOf(RecordingEvent.open(0)))
            assertNull(scanResultOf(without, file))
            File(directory, "20261117T101500.000Z.scan.json").writeText(result().toJson(pretty = true))
            assertEquals(result(), assertNotNull(scanResultOf(without, file)))
        } finally {
            directory.deleteRecursively()
        }
    }
}
