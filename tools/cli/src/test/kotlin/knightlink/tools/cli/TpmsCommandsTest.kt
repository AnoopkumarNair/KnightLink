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
            events += RecordingEvent.external(t + 100, "tpms BA06 0102102030405060211901${"%02X".format(0x5E + minute % 2)} -60")
            if (minute in 3..4) events += RecordingEvent.external(t + 200, "tpms BA06 0102AABBCCDDEEFF201C0160 -85")
        }
        events += RecordingEvent.close(13 * 60_000L)
        return Recording(RecordingHeader("adapters/x", "a drive"), events)
    }

    @Test
    fun everyPayloadIsReadWithEveryLayout() {
        val text = renderTpmsReport(drive(), decoders, catalog).joinToString("\n")
        assertTrue("3 distinct payloads" in text, text)
        assertTrue("tpms/sensairy: " in text && "tpms/sensairy-open: id 102030405060" in text, text)
        assertTrue("pressure_kpa 350.0 kPa" in text && "temp_c 25.0 degC" in text, text)
        assertTrue("byte  A   B   C" in text, text)
    }

    @Test
    fun theCarsSensorIsToldFromAPassingOneByMovingMinutes() {
        val lines = renderTpmsReport(drive(), decoders, catalog)
        val ours = lines.single { it.trimStart().startsWith("102030405060") }
        val theirs = lines.single { it.trimStart().startsWith("AABBCCDDEEFF") }
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
