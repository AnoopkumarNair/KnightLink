package knightlink.core.scan

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaultDictionaryFileTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))
    private val text = File(root, "definitions/faults/sae-j2012.json").readText()
    private val dictionary = FaultCodeDictionary.parse(text)

    @Test
    fun theFileHoldsAFewHundredGenericCodesAndNoManufacturerCodes() {
        assertTrue(dictionary.size >= 400, "${dictionary.size} codes")
        assertNotNull(dictionary.describe("P0300"))
        assertNotNull(dictionary.describe("p0301-1F"), "a failure type suffix is ignored")
        assertNull(dictionary.describe("P1234"), "manufacturer codes have no description")
        assertNull(dictionary.describe("P3000"))
        assertTrue(Regex(""""P1[0-9A-F]{3}"""").findAll(text).none(), "P1xxx is manufacturer specific")
    }

    @Test
    fun aBadFileIsRefused() {
        assertFailsWith<IllegalArgumentException> { FaultCodeDictionary.parse("""{ "schemaVersion": 1, "source": "x", "codes": { "X1234": "no" } }""") }
        assertFailsWith<IllegalArgumentException> { FaultCodeDictionary.parse("""{ "schemaVersion": 1, "source": "", "codes": {} }""") }
        assertEquals(0, FaultCodeDictionary.EMPTY.size)
    }
}
