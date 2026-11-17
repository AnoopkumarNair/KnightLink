package knightlink.core.tpms

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensAiryDefinitionTest {
    private val dir = File(System.getProperty("knightlink.repoRoot"), "definitions/tpms")

    @Test
    fun everyTpmsDefinitionParsesAndCompiles() {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(files.isNotEmpty())
        files.forEach { TpmsDecoder(TpmsDefinition.parse(it.readText())) }
    }

    @Test
    fun decodesPressureAndTemperaturePerSrsFormula() {
        val decoder = TpmsDecoder(TpmsDefinition.parse(File(dir, "sensairy.json").readText()))
        // SRS 2.4: temperature = uint8(B2), pressure psi = ((B3 << 8) | B4) / 32
        val payload = byteArrayOf(0x00, 0x00, 28, 0x04, 0x20)
        val values = assertNotNull(decoder.decode(0xBA06, payload)).associate { it.id to it.value }
        assertEquals(28.0, values["temp_c"])
        assertEquals(33.0, values["pressure_psi"])
    }

    @Test
    fun theOpenProtocolLayoutDecodesThePublishedExamples() {
        val decoder = TpmsDecoder(TpmsDefinition.parse(File(dir, "sensairy-open.json").readText()))
        // vendor, model, six bytes of sensor id, battery 0x21 (3.3 V), temperature 0x19 (+25), pressure 0x015E (350 kPa)
        val payload = byteArrayOf(0x01, 0x02, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x21, 0x19, 0x01, 0x5E)
        val values = assertNotNull(decoder.decode(0xBA06, payload)).associate { it.id to it.value }
        assertEquals(3.3, values.getValue("battery_v"), 1e-9)
        assertEquals(25.0, values["temp_c"])
        assertEquals(350.0, values["pressure_kpa"])
        assertEquals(50.76, values.getValue("pressure_psi"), 0.01)
        assertEquals("102030405060", decoder.sensorId(payload))
        // A signed byte, as in the page's example: 0xFA is -6 degrees.
        payload[9] = 0xFA.toByte()
        assertEquals(-6.0, assertNotNull(decoder.decode(0xBA06, payload)).single { it.id == "temp_c" }.value)
    }

    @Test
    fun aLayoutWithoutASensorIdGivesNone() {
        val decoder = TpmsDecoder(TpmsDefinition.parse(File(dir, "sensairy.json").readText()))
        assertNull(decoder.sensorId(ByteArray(12)))
    }

    @Test
    fun ignoresOtherManufacturersAndShortPayloads() {
        val decoder = TpmsDecoder(TpmsDefinition.parse(File(dir, "sensairy.json").readText()))
        assertNull(decoder.decode(0x004C, ByteArray(8)))
        assertNull(decoder.decode(0xBA06, ByteArray(3)))
    }
}
