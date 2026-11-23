package knightlink.core.vehicle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real data files of `definitions/identity`. */
class IdentityFilesTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))

    @Test
    fun theManufacturerCodesParseAndAConflictingEntryIsNotMarkedVerified() {
        val table = WmiTable.parse(File(root, "definitions/identity/wmi.json").readText())
        assertTrue(table.size >= 10)
        assertEquals("Tata", table.makeOf("MAT" + "X".repeat(14)))
        assertEquals("Mahindra", table.makeOf("MA1" + "X".repeat(14)))
        assertEquals("Kia", table.makeOf("MZB" + "X".repeat(14)))
        assertEquals(false, table.lookup("MBH" + "X".repeat(14))?.verified)
    }

    @Test
    fun everyMakeOfTheModelListIsNamedByTheManufacturerCodes() {
        val models = ModelList.parse(File(root, "definitions/identity/models.json").readText())
        assertTrue("Seltos" in models.modelsFor("Kia"))
        assertTrue("Thar Roxx" in models.modelsFor("Mahindra"))
        assertTrue("Harrier" in models.modelsFor("tata"))
        assertTrue(models.modelsFor("Nobody").isEmpty())
        val wmi = File(root, "definitions/identity/wmi.json").readText()
        val makes = Regex(""""make":\s*"([^"]+)"""").findAll(wmi).map { it.groupValues[1] }.toSet()
        for (make in models.allMakes) assertTrue(make in makes, "$make has models but no manufacturer code")
    }
}
