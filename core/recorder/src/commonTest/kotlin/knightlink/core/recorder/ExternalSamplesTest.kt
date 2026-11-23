package knightlink.core.recorder

import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.transport.TransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

class ExternalSamplesTest {
    private val header = RecordingHeader("adapters/x", "note")

    @Test
    fun aMotionLineCarriesTheSamplesOfABatchRelativeToTheLastOne() {
        val samples = listOf(
            MotionSample(1000, 0.123, -0.456, 9.81, 0.0012, -0.0004, 0.0),
            MotionSample(1040, -1.0, 0.0, 0.5, 0.1, 0.2, 0.3),
        )
        val line = ExternalSamples.motionLine(1040, samples)
        assertEquals("mot 40 0.12 -0.46 9.81 0.001 0.000 0.000;0 -1.00 0.00 0.50 0.100 0.200 0.300", line)
        val back = assertNotNull(ExternalSamples.parseMotion(1040, line))
        assertEquals(listOf(1000L, 1040L), back.map { it.timeMs })
        assertEquals(0.12, back[0].ax, 1e-9)
        assertEquals(-1.0, back[1].ax, 1e-9)
    }

    @Test
    fun aLineThatIsNotAMotionLineIsNotRead() {
        assertNull(ExternalSamples.parseMotion(0, "gps 1 2 3 4 5"))
        assertNull(ExternalSamples.parseMotion(0, "mot 1 2 3"))
        assertNull(ExternalSamples.parseMotion(0, "mot x 1 2 3 4 5 6"))
    }

    @Test
    fun aFixIsWrittenWithTheDevicePrecisionAndReadBack() {
        val line = ExternalSamples.gpsLine(GpsSample(5000, 12.345678, -45.6789012, 42.44, 270.4, 6.2))
        assertEquals("gps 12.34568 -45.67890 42.4 270 6", line)
        val fix = assertNotNull(ExternalSamples.parseGps(5000, line))
        assertEquals(12.34568, fix.latitude!!, 1e-9)
        assertEquals(42.4, fix.speedKmh!!, 1e-9)
    }

    @Test
    fun aFixWithNoPositionIsStillAFix() {
        val line = ExternalSamples.gpsLine(GpsSample(5000, null, null, 12.0, null, null))
        assertEquals("gps - - 12.0 - -", line)
        val fix = assertNotNull(ExternalSamples.parseGps(5000, line))
        assertNull(fix.latitude)
        assertEquals(12.0, fix.speedKmh!!, 1e-9)
    }

    @Test
    fun aSharedFixIsRoundedToAboutAKilometreAndKeepsTheRest() {
        val shared = ExternalSamples.scrub("gps 12.34568 -45.67890 42.4 270 6")
        assertEquals("gps 12.35 -45.68 42.4 270 6", shared)
        assertEquals("gps - - 12.0 - -", ExternalSamples.scrub("gps - - 12.0 - -"))
    }

    @Test
    fun aFixThatCannotBeReadIsReplacedByOneWithNoPositionRatherThanPassedOn() {
        assertEquals("gps - - - - -", ExternalSamples.scrub("gps 12.34568 -45.67890 oops"))
        assertEquals("gps - - - - -", ExternalSamples.scrub("gps 12.34568"))
    }

    @Test
    fun otherLinesAreNotTouched() {
        assertEquals("mot 0 1.00 2.00 3.00 0.001 0.002 0.003", ExternalSamples.scrub("mot 0 1.00 2.00 3.00 0.001 0.002 0.003"))
    }

    @Test
    fun aTyreSensorLineCarriesCompanyPayloadAndSignalStrengthAndReadsBack() {
        val line = ExternalSamples.tpmsLine(TpmsSample(7000, 0x0A1B, "00ff1c0420", -71))
        assertEquals("tpms 0A1B 00FF1C0420 -71", line)
        assertEquals(TpmsSample(7000, 0x0A1B, "00FF1C0420", -71), ExternalSamples.parseTpms(7000, line))
        assertEquals("tpms 0001 - -", ExternalSamples.tpmsLine(TpmsSample(0, 1, "", null)))
        assertEquals(TpmsSample(5, 1, "", null), ExternalSamples.parseTpms(5, "tpms 0001 - -"))
    }

    @Test
    fun serviceDataIsMarkedAsSuchAndReadsBack() {
        val line = ExternalSamples.tpmsLine(TpmsSample(9000, 0xFEAA, "00e5", -64, serviceData = true))
        assertEquals("tpms s:FEAA 00E5 -64", line)
        assertEquals(TpmsSample(9000, 0xFEAA, "00E5", -64, serviceData = true), ExternalSamples.parseTpms(9000, line))
        assertEquals(line, ExternalSamples.scrub(line))
        assertNull(ExternalSamples.parseTpms(0, "tpms s:FEA 00 -64"))
        assertNull(ExternalSamples.parseTpms(0, "tpms x:FEAA 00 -64"))
    }

    @Test
    fun aMalformedTyreSensorLineIsNotRead() {
        assertNull(ExternalSamples.parseTpms(0, "gps 1 2 3 4 5"))
        assertNull(ExternalSamples.parseTpms(0, "tpms 0A1B 00FF1C0420"))
        assertNull(ExternalSamples.parseTpms(0, "tpms A1B 00FF -70"))
        assertNull(ExternalSamples.parseTpms(0, "tpms 0A1B 00F -70"))
        assertNull(ExternalSamples.parseTpms(0, "tpms 0A1B 00FG -70"))
        assertNull(ExternalSamples.parseTpms(0, "tpms 0A1B 00FF loud"))
        assertNull(ExternalSamples.parseTpms(0, "tpms 0A1B 00FF -300"))
        assertNull(ExternalSamples.parseTpms(0, "tpms 0A1B " + "00".repeat(ExternalSamples.MAX_TPMS_PAYLOAD_BYTES + 1) + " -70"))
        assertNull(ExternalSamples.parseTpms(0, "tpms 0A1B 00FF -70 extra"))
    }

    @Test
    fun aSharedTyreSensorLineKeepsOnlyItsThreeFieldsAndFailsClosed() {
        assertEquals("tpms 0A1B 00FF1C0420 -71", ExternalSamples.scrub("tpms 0A1B 00FF1C0420 -71"))
        // A device address must never leave the phone, even if a future writer slipped one in (built here: core sources hold no address literals).
        val address = List(6) { "A1" }.joinToString(":")
        assertEquals("tpms - - -", ExternalSamples.scrub("tpms 0A1B 00FF1C0420 -71 $address"))
        assertEquals("tpms - - -", ExternalSamples.scrub("tpms $address 00FF -71"))
        assertEquals("tpms - - -", ExternalSamples.scrub("tpms"))
        // Not a tyre-sensor line: a tag that only starts with the same letters is left to other rules.
        assertEquals("tpmsx 1", ExternalSamples.scrub("tpmsx 1"))
    }

    @Test
    fun theScrubberRoundsEveryPositionInARecordingAndLeavesTheRest() {
        val recording = Recording(
            header,
            listOf(
                RecordingEvent.open(0),
                RecordingEvent.external(1000, "gps 52.52001 13.40495 0.0 - 5"),
                RecordingEvent.external(1010, "mot 0 0.10 0.20 9.80 0.000 0.001 0.002"),
                RecordingEvent.external(1500, "tpms 0A1B 00FF1C0420 -71"),
                RecordingEvent.external(2000, "gps 52.52012 13.40511 31.5 88 4"),
                RecordingEvent.close(3000),
            ),
        )
        val scrubbed = RecordingScrubber.scrub(recording).recording
        val payloads = scrubbed.events.filter { it.kind == EventKind.EXTERNAL }.map { it.payload }
        assertEquals(
            listOf(
                "gps 52.52 13.40 0.0 - 5",
                "mot 0 0.10 0.20 9.80 0.000 0.001 0.002",
                "tpms 0A1B 00FF1C0420 -71",
                "gps 52.52 13.41 31.5 88 4",
            ),
            payloads,
        )
        assertEquals(recording.events.map { it.timeMs }, scrubbed.events.map { it.timeMs })
        // Nothing of the precise position is left anywhere in the text.
        val text = scrubbed.toText()
        assertFalse("52.5200" in text || "13.4049" in text || "13.4051" in text, text)
    }

    @Test
    fun aFileWithExternalSamplesIsSchema3AndOneWithOnlyEventsStaysSchema2() {
        val withExternal = Recording(header, listOf(RecordingEvent.open(0), RecordingEvent.event(1, "x"), RecordingEvent.external(2, "gps - - - - -")))
        val onlyEvents = Recording(header, listOf(RecordingEvent.open(0), RecordingEvent.event(1, "x")))
        assertTrue("\"schemaVersion\":3" in withExternal.toText().lineSequence().first())
        assertTrue("\"schemaVersion\":2" in onlyEvents.toText().lineSequence().first())
        assertEquals(withExternal, Recording.parse(withExternal.toText()))
    }

    private class Quiet : Transport {
        override val kind = TransportKind.SIMULATED

        override suspend fun open(): ByteStream = object : ByteStream {
            override val incoming: Flow<ByteArray> = emptyFlow()

            override suspend fun write(bytes: ByteArray) = Unit

            override suspend fun close() = Unit
        }
    }

    @Test
    fun aRecordingTransportWritesExternalSamplesOnlyWhenItWasMadeForThem() = runTest {
        val written = InMemoryRecordSink()
        val transport = RecordingTransport(Quiet(), { written }, TestTimeSource(), header, externalSamples = true)
        transport.external("gps - - - - -")
        assertTrue(written.lines.isEmpty(), "nothing before the first link")
        transport.open()
        transport.external("gps - - 5.0 - -")
        assertTrue("\"schemaVersion\":3" in written.lines.first())
        assertEquals(listOf("gps - - 5.0 - -"), written.toRecording().events.filter { it.kind == EventKind.EXTERNAL }.map { it.payload })

        val plain = InMemoryRecordSink()
        val ordinary = RecordingTransport(Quiet(), { plain }, TestTimeSource(), header)
        ordinary.open()
        ordinary.external("gps - - 5.0 - -")
        assertTrue(plain.toRecording().events.none { it.kind == EventKind.EXTERNAL })
        assertTrue("\"schemaVersion\":2" in plain.lines.first())
    }

    @Test
    fun aReplaySkipsExternalSamplesAndStillReachesTheEnd() = runTest {
        val recording = Recording(
            header,
            listOf(
                RecordingEvent.open(0),
                tx(10, "ATZ\r"),
                RecordingEvent.external(15, "mot 0 0.10 0.20 9.80 0.000 0.001 0.002"),
                rx(50, "ELM327 v1.5\r\r>"),
                RecordingEvent.external(60, "gps - - 3.0 - -"),
                RecordingEvent.close(100),
            ),
        )
        val exchanges = indexExchanges(recording.events)
        assertEquals(listOf("ATZ"), exchanges.map { it.key })
        assertEquals(1, exchanges.single().rxIndices.size)
        val replay = ReplayByteStream(recording, ReplayMode.STRICT)
        replay.write("ATZ\r".encodeToByteArray())
        assertNull(replay.firstMismatch)
    }

    @Test
    fun aDriveModeLineNamesOnlyAKnownModeAndSurvivesScrubbing() {
        assertEquals("mode SPORT", ExternalSamples.modeLine("sport"))
        assertEquals("mode -", ExternalSamples.modeLine("anything the driver typed"))
        assertEquals("mode ECO", ExternalSamples.scrub("mode ECO"))
        assertEquals("mode -", ExternalSamples.scrub("mode my street 12"))
    }

    @Test
    fun aCaptureLineSaysPassiveOrActiveAndNothingElseSurvivesScrubbing() {
        assertEquals("capture passive", ExternalSamples.captureLine(passive = true))
        assertEquals("capture active", ExternalSamples.captureLine(passive = false))
        assertEquals("capture passive", ExternalSamples.scrub("capture passive"))
        assertEquals("capture active", ExternalSamples.scrub("capture active"))
        assertEquals("capture active", ExternalSamples.scrub("capture my street 12"))
    }

    @Test
    fun theDashboardReadingsAreWholeKilometresAtTheStartOrTheEnd() {
        assertEquals("odo 12345 start", ExternalSamples.odometerLine(12345, end = false))
        assertEquals("odo 12399 end", ExternalSamples.odometerLine(12399, end = true))
        assertEquals("dte 310 start", ExternalSamples.rangeLine(310, end = false))
        assertEquals("dte - end", ExternalSamples.rangeLine(null, end = true))
        assertEquals("odo - start", ExternalSamples.odometerLine(-5, end = false))
        assertEquals("odo 12345 start", ExternalSamples.scrub("odo 12345 start"))
        assertEquals("dte 310 end", ExternalSamples.scrub("dte 310 end"))
    }

    @Test
    fun aSharedCopyKeepsOnlyANumberAndTheWordStartOrEndInTheDashboardLines() {
        assertEquals("odo - start", ExternalSamples.scrub("odo my street 12 start"))
        assertEquals("odo - start", ExternalSamples.scrub("odo 12345678901 end").replace("end", "start"))
        assertEquals("dte - start", ExternalSamples.scrub("dte 12.5 whatever"))
        assertEquals("dte - start", ExternalSamples.scrub("dte"))
        assertEquals("odo 7 end", ExternalSamples.scrub("odo 7 end with a secret"))
    }
}
