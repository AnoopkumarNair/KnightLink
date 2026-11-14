package knightlink.tools.archtest

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Vehicle, adapter and sensor knowledge belongs in `definitions/` (ADR 0002), never in `core/`.
 * Generic protocol constants (0x7DF, ELM327, OBD service numbers) are fine and are not matched here.
 */
class GenericCoreTest {
    private val forbiddenNames = listOf("Harrier", "Tata", "Vgate", "SensAiry", "iCar")
    private val uuid = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val mac = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")

    @Test
    fun coreModulesAreDiscovered() {
        assertTrue(RepoFiles.coreModules().size >= 9, "Expected to find the core modules; is knightlink.repoRoot correct?")
    }

    @Test
    fun productionCodeNamesNoVehicleOrAdapter() {
        val offences = mutableListOf<String>()
        for (module in RepoFiles.coreModules()) {
            for (file in RepoFiles.kotlinFiles(module) { it.endsWith("Main") }) {
                val text = file.readText()
                forbiddenNames.filter { text.contains(it) }.forEach { offences += "${RepoFiles.relative(file)}: mentions '$it'" }
            }
        }
        assertTrue(offences.isEmpty(), "Vehicle/adapter-specific names in core production code:\n" + offences.joinToString("\n"))
    }

    @Test
    fun noBleUuidOrMacLiteralsInCoreSources() {
        // Applies to main and test sources alike: tests must load such values from definitions/fixtures.
        val offences = mutableListOf<String>()
        for (module in RepoFiles.coreModules()) {
            for (file in RepoFiles.kotlinFiles(module) { true }) {
                val text = file.readText()
                uuid.findAll(text).forEach { offences += "${RepoFiles.relative(file)}: UUID literal '${it.value}'" }
                mac.findAll(text).forEach { offences += "${RepoFiles.relative(file)}: MAC address literal '${it.value}'" }
            }
        }
        assertTrue(offences.isEmpty(), "BLE UUID / MAC literals in core sources:\n" + offences.joinToString("\n"))
    }
}
