package knightlink.tools.archtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VinScanTest {
    // Made up; like every real VIN it has no I, O or Q.
    private val vin = "MAT123456ABC12345"

    private fun hex(text: String) = text.encodeToByteArray().joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }

    private fun recording(vararg events: Pair<String, String>): String =
        (
            listOf("""{"schemaVersion":1,"kind":"knightlink-session","adapter":null,"note":null}""") +
                events.map { (d, text) -> """{"t":1,"d":"$d","x":"${hex(text)}"}""" }
            ).joinToString("\n", postfix = "\n")

    @Test
    fun aVinInRawBytesIsFoundAfterHexDecoding() {
        assertEquals(listOf(vin), VinScan.offencesInRecording(recording("rx" to "ATSET $vin\r\r>")))
        assertEquals(listOf(vin), VinScan.offencesInRecording(recording("tx" to "$vin\r")))
    }

    @Test
    fun theHexIsNotMistakenForAVin() {
        assertTrue(VinScan.offencesInRecording(recording("rx" to "41 00 BE 3F A8 13\r\r>")).isEmpty())
    }

    @Test
    fun aVinPrintedAsAMultiFrameReplyIsFoundWithSpacesOnAndOff() {
        val bytes = "49 02 01".split(' ').map { it.toInt(16) } + vin.map { it.code }
        fun h(values: List<Int>, sep: String) = values.joinToString(sep) { it.toString(16).uppercase().padStart(2, '0') }
        for (sep in listOf(" ", "")) {
            val frames = listOf(
                "7E8$sep" + h(listOf(0x10, 0x14) + bytes.take(6), sep),
                "7E8$sep" + h(listOf(0x21) + bytes.drop(6).take(7), sep),
                "7E8$sep" + h(listOf(0x22) + bytes.drop(13), sep),
            )
            val text = frames.joinToString("\r", postfix = "\r\r>")
            assertEquals(listOf(vin), VinScan.offencesInRecording(recording("rx" to text)), "separator '$sep'")
        }
    }

    @Test
    fun theScrubbedFormIsNotAnOffence() {
        assertTrue(VinScan.offencesInRecording(recording("rx" to "ATSET MAT00000000000000\r\r>")).isEmpty())
    }

    @Test
    fun theSimulatorPlaceholderIsAllowedInJsonOnly() {
        // It contains an I, so the VIN pattern cannot see it; the allowance is explicit all the same.
        assertTrue(VinScan.offencesInJson("""{"vin":"${VinScan.SIMULATOR_PLACEHOLDER}"}""").isEmpty())
        assertEquals(listOf(VinScan.SIMULATOR_PLACEHOLDER), VinScan.offencesInRecording(recording("rx" to VinScan.SIMULATOR_PLACEHOLDER)))
    }

    @Test
    fun jsonFilesAreStillScannedForRealVins() {
        assertEquals(listOf(vin), VinScan.offencesInJson("""{"vin":"$vin"}"""))
    }
}
