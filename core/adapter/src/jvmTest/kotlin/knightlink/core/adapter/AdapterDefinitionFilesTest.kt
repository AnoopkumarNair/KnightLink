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
            // What the driver may skip must not be what the layers above depend on.
            val script = definition.initScript.map { it.replace(" ", "").uppercase() }
            for (essential in listOf("ATE0", "ATH1")) {
                assertTrue(definition.essentialCommands.any { essential.startsWith(it.uppercase()) }, "${definition.id}: $essential must be essential")
            }
            // A probe script without a poll script would leave the adapter on the probe's timing for good.
            assertEquals(definition.probeScript.isNotEmpty(), definition.pollScript.isNotEmpty(), "${definition.id}: probeScript and pollScript go together")
            assertTrue(script.isNotEmpty())
        }
    }
}
