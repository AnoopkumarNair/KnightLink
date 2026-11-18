package knightlink.core.tpms

import knightlink.core.signals.DefinitionException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensAiryDefinitionTest {
    private val dir = File(System.getProperty("knightlink.repoRoot"), "definitions/tpms")

    private fun decoder(name: String) = TpmsDecoder(TpmsDefinition.parse(File(dir, name).readText()))

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun everyTpmsDefinitionParsesAndCompiles() {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(files.isNotEmpty())
        files.forEach { TpmsDecoder(TpmsDefinition.parse(it.readText())) }
    }

    /** An Eddystone-UID frame: frame type, tx power, 10 bytes of namespace (firmware 1.7 in bytes 2-3), 6 bytes of instance. */
    private val eddystone = bytes(
        0x00, 0xE5,
        0x11, 0x22, 0x01, 0x07, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88,
        // 0x2BC0 >> 5 = 350 kPa, flags 0; temperature 25; two bytes not read
        0x2B, 0xC0, 0x19, 0x00, 0x00, 0x00,
    )

    @Test
    fun theEddystoneLayoutReadsPressureAndTemperatureFromTheInstance() {
        val decoder = decoder("sensairy.json")
        assertEquals(TpmsDataKind.SERVICE, decoder.kind)
        val values = assertNotNull(decoder.decode(TpmsDataKind.SERVICE, decoder.key, eddystone)).associate { it.id to it.value }
        assertEquals(350.0, values["pressure_kpa"])
        assertEquals(350 / 6.89576 - 0.16, values.getValue("pressure_psi"), 1e-9)
        assertEquals(25.0, values["temp_c"])
        assertEquals(0.0, values["flags"])
        assertEquals(1.0, values["firmware_major"])
        assertEquals(7.0, values["firmware_minor"])
        assertEquals("11220107334455667788", decoder.sensorId(eddystone))
        // A signed byte: 0xFA is -6 degrees.
        val cold = eddystone.copyOf().also { it[14] = 0xFA.toByte() }
        assertEquals(-6.0, assertNotNull(decoder.decode(TpmsDataKind.SERVICE, decoder.key, cold)).single { it.id == "temp_c" }.value)
    }

    @Test
    fun otherEddystoneFramesAndOtherPlacesAreNotRead() {
        val decoder = decoder("sensairy.json")
        val url = eddystone.copyOf().also { it[0] = 0x10 }
        assertFalse(decoder.matches(TpmsDataKind.SERVICE, decoder.key, url))
        assertNull(decoder.decode(TpmsDataKind.SERVICE, decoder.key, url))
        assertNull(decoder.decode(TpmsDataKind.MANUFACTURER, decoder.key, eddystone))
        assertNull(decoder.decode(TpmsDataKind.SERVICE, decoder.key + 1, eddystone))
        assertNull(decoder.decode(TpmsDataKind.SERVICE, decoder.key, eddystone.copyOf(14)))
    }

    @Test
    fun theIBeaconLayoutReadsMajorAndMinor() {
        val decoder = decoder("sensairy-ibeacon.json")
        assertEquals(TpmsDataKind.MANUFACTURER, decoder.kind)
        val data = ByteArray(23).also {
            it[0] = 0x02
            it[1] = 0x15
            for (i in 2..17) it[i] = i.toByte()
            it[18] = 0x2B
            it[19] = 0xC0.toByte()
            it[20] = 0x19
            it[21] = 0x42
            it[22] = 0xC5.toByte()
        }
        val values = assertNotNull(decoder.decode(TpmsDataKind.MANUFACTURER, decoder.key, data)).associate { it.id to it.value }
        assertEquals(350.0, values["pressure_kpa"])
        assertEquals(25.0, values["temp_c"])
        assertEquals(0x2BC0.toDouble(), values["raw_pressure"])
        assertEquals(0x942.toDouble(), values["raw_temp"])
        assertEquals("02030405060708090A0B0C0D0E0F1011", decoder.sensorId(data))
        // Any other iBeacon-shaped data under the same company id with another prefix is not this layout's.
        assertNull(decoder.decode(TpmsDataKind.MANUFACTURER, decoder.key, data.copyOf().also { it[0] = 0x10 }))
    }

    @Test
    fun aDefinitionNeedsExactlyOnePlaceForItsData() {
        val neither = """{ "schemaVersion": 1, "id": "x", "title": "x", "provenance": "inferred", "fields": [] }"""
        assertFailsWith<DefinitionException> { TpmsDefinition.parse(neither) }
        val both = """{ "schemaVersion": 1, "id": "x", "title": "x", "companyId": "0001", "serviceUuid": "0002", "provenance": "inferred", "fields": [] }"""
        assertFailsWith<DefinitionException> { TpmsDefinition.parse(both) }
        val oddPrefix = """{ "schemaVersion": 1, "id": "x", "title": "x", "companyId": "0001", "dataPrefix": "0", "provenance": "inferred", "fields": [] }"""
        assertFailsWith<DefinitionException> { TpmsDefinition.parse(oddPrefix) }
    }
}
