package knightlink.tools.archtest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** ADR 0002: no full VIN is ever stored in a definition or fixture file. */
class DataFilesTest {
    private val vin = Regex("""\b[A-HJ-NPR-Z0-9]{17}\b""")
    private val simulatorPlaceholder = "SIMHARRIER2020001"

    @Test
    fun noRealVinInDefinitionsOrFixtures() {
        val files =
            listOf("definitions", "fixtures").flatMap { dir ->
                File(RepoFiles.root, dir).walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
            }
        assertTrue(files.isNotEmpty(), "Expected JSON files under definitions/ and fixtures/; is knightlink.repoRoot correct?")
        val offences =
            files.flatMap { file ->
                vin.findAll(file.readText()).map { it.value }.filter { it != simulatorPlaceholder }.map { "${RepoFiles.relative(file)}: '$it'" }.toList()
            }
        assertTrue(offences.isEmpty(), "VIN-shaped tokens found (only $simulatorPlaceholder is allowed):\n" + offences.joinToString("\n"))
    }
}
