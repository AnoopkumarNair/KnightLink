package knightlink.tools.archtest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** ADR 0002: no full VIN is ever stored in a definition or fixture file. */
class DataFilesTest {
    @Test
    fun noRealVinInDefinitionsOrFixtures() {
        val files =
            listOf("definitions", "fixtures").flatMap { dir ->
                File(RepoFiles.root, dir).walkTopDown().filter { it.isFile && it.extension in setOf("json", "jsonl") }.toList()
            }
        assertTrue(files.any { it.extension == "json" }, "Expected JSON files under definitions/ and fixtures/; is knightlink.repoRoot correct?")
        assertTrue(files.any { it.extension == "jsonl" }, "Expected the committed session recording under fixtures/recordings/")
        val offences =
            files.flatMap { file ->
                val text = file.readText()
                // Recordings hold bytes as hex, so they are decoded first; the simulator's placeholder VIN is allowed in .json only.
                val found = if (file.extension == "jsonl") VinScan.offencesInRecording(text) else VinScan.offencesInJson(text)
                found.map { "${RepoFiles.relative(file)}: '$it'" }
            }
        assertTrue(
            offences.isEmpty(),
            "VIN-shaped tokens found (only ${VinScan.SIMULATOR_PLACEHOLDER} is allowed, and only in .json):\n" + offences.joinToString("\n"),
        )
    }
}
