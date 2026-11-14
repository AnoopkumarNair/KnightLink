package knightlink.core.adapter

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdapterDefinitionFilesTest {
    private val dir = File(System.getProperty("knightlink.repoRoot"), "definitions/adapters")

    @Test
    fun everyAdapterDefinitionParses() {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val definition = AdapterDefinition.parse(file.readText())
            assertEquals("adapters/${file.nameWithoutExtension}", definition.id)
            assertTrue(definition.initScript.isNotEmpty(), "${definition.id}: empty init script")
            assertTrue(
                definition.initScript.any { it.replace(" ", "").equals("ATH1", ignoreCase = true) },
                "${definition.id}: headers must be switched on (ATH1); the protocol layer depends on them",
            )
        }
    }
}
