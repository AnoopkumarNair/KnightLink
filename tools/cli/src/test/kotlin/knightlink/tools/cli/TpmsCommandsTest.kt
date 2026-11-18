package knightlink.tools.cli

import knightlink.core.recorder.Recording
import knightlink.core.recorder.RecordingEvent
import knightlink.core.recorder.RecordingHeader
import knightlink.core.tpms.TpmsDecoder
import knightlink.core.tpms.TpmsDefinition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TpmsCommandsTest {
    private val decoders = File("definitions/tpms").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        .map { TpmsDecoder(TpmsDefinition.parse(it.readText())) }
    private val catalog = catalogFor(DEFAULT_VEHICLE)

    /** Twelve moving minutes by GPS: our sensor is heard every minute, a passing car's for two. */
    private fun drive(): Recording {
        val events = ArrayList<RecordingEvent>()
        events += RecordingEvent.open(0)
        for (minute in 0 until 12) {
            val t = minute * 60_000L + 1_000
            events += RecordingEvent.external(t, "gps - - 40.0 - -")
            // Our sensor: namespace 1122..88, 350 or 351 kPa, 25 degrees. A passing car's: another namespace.
            events += RecordingEvent.external(t + 100, "tpms s:FEAA 00E511220107334455667788${if (minute % 2 == 0) "2BC0" else "2BE0"}19000000 -60")
            if (minute in 3..4) events += RecordingEvent.external(t + 200, "tpms s:FEAA 00E5AABBCCDDEEFF001122332A00140000 -85")
        }
        events += RecordingEvent.close(13 * 60_000L)
        return Recording(RecordingHeader("adapters/x", "a drive"), events)
    }

    @Test
    fun everyPayloadIsReadWithEveryLayout() {
        val text = renderTpmsReport(drive(), decoders, catalog).joinToString("\n")
        assertTrue("3 distinct payloads" in text, text)
        assertTrue("tpms/sensairy: id 11220107334455667788" in text, text)
        assertTrue("pressure_kpa 350.0 kPa" in text && "temp_c 25.0 degC" in text, text)
        assertTrue("tpms/sensairy-ibeacon" !in text.substringAfter("Layouts:").substringAfter('\n'), "only the layout that fits reads a payload")
        assertTrue("byte  A   B   C" in text, text)
    }

    @Test
    fun theCarsSensorIsToldFromAPassingOneByMovingMinutes() {
        val lines = renderTpmsReport(drive(), decoders, catalog)
        val ours = lines.single { it.trimStart().startsWith("11220107334455667788") }
        val theirs = lines.single { it.trimStart().startsWith("AABBCCDDEEFF00112233") }
        assertTrue(ours.trimEnd().endsWith("car") && "100%" in ours, ours)
        assertTrue(theirs.trimEnd().endsWith("other"), theirs)
        assertTrue(lines.any { "12 moving minutes (GPS speed" in it }, lines.joinToString("\n"))
    }

    @Test
    fun aRecordingWithoutTyreLinesSaysSo() {
        val empty = Recording(RecordingHeader("adapters/x", "n"), listOf(RecordingEvent.open(0), RecordingEvent.close(1)))
        assertTrue("No tyre sensor" in renderTpmsReport(empty, decoders, catalog).single())
    }
}
