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
    fun ignoresOtherManufacturersAndShortPayloads() {
        val decoder = TpmsDecoder(TpmsDefinition.parse(File(dir, "sensairy.json").readText()))
        assertNull(decoder.decode(0x004C, ByteArray(8)))
        assertNull(decoder.decode(0xBA06, ByteArray(3)))
    }
}
