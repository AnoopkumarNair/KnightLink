package knightlink.tools.archtest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keeps the core module graph layered as designed. New core modules must be added to [allowed] deliberately. */
class ModuleDependencyTest {
    private val allowed: Map<String, Set<String>> =
        mapOf(
            "common" to emptySet(),
            "transport" to emptySet(),
            "protocol" to setOf("common"),
            "signals" to setOf("common"),
            "tpms" to setOf("signals", "common"),
            "adapter" to setOf("transport", "protocol", "common"),
            "scheduler" to setOf("protocol", "common"),
            "telemetry" to setOf("protocol", "signals", "scheduler", "common"),
            "sim" to setOf("transport", "common"),
            "vehicle" to setOf("protocol", "signals", "common"),
            "scan" to setOf("protocol", "signals", "vehicle", "common"),
            "runtime" to setOf("adapter", "transport", "protocol", "scheduler", "telemetry", "signals", "vehicle", "scan", "common"),
            "recorder" to setOf("transport", "protocol", "common"),
        )

    private val voiceAllowed: Map<String, Set<String>> = mapOf("api" to emptySet())

    private val accessor = Regex("""projects\.(core|tools|voice)\.(\w+)""")
    private val pathString = Regex("""project\(\s*":(core|tools|voice):([\w-]+)"\s*\)""")
    private val sourceSetBlock = Regex("""(\w+)\.dependencies\s*\{""")
    private val plainBlock = Regex("""^\s*(\w+)\s*\{""")
    private val testConfiguration = Regex("""^\s*test\w*\(""")

    @Test
    fun everyCoreModuleIsInTheRuleTable() {
        val actual = RepoFiles.coreModules().map { it.name }.toSet()
        assertEquals(emptySet(), actual - allowed.keys, "Core modules missing from the dependency rule table (add them deliberately)")
        assertEquals(emptySet(), allowed.keys - actual, "Rule table lists core modules that no longer exist")
    }

    @Test
    fun coreModulesOnlyDependOnAllowedModules() {
        val violations = mutableListOf<String>()
        for (module in RepoFiles.coreModules()) {
            val allowedDeps = allowed[module.name] ?: continue
            val deps = productionDependencies(File(module, "build.gradle.kts").readText(), "core")
            (deps - allowedDeps).forEach { violations += "core/${module.name} must not depend on core/$it" }
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun everyVoiceModuleIsInTheRuleTable() {
        val actual = RepoFiles.voiceModules().map { it.name }.toSet()
        assertEquals(emptySet(), actual - voiceAllowed.keys, "Voice modules missing from the dependency rule table (add them deliberately)")
        assertEquals(emptySet(), voiceAllowed.keys - actual, "Rule table lists voice modules that no longer exist")
    }

    @Test
    fun voiceModulesOnlyDependOnAllowedVoiceModules() {
        val violations = mutableListOf<String>()
        for (module in RepoFiles.voiceModules()) {
            val allowedDeps = voiceAllowed[module.name] ?: continue
            val text = File(module, "build.gradle.kts").readText()
            (productionDependencies(text, "voice") - allowedDeps).forEach { violations += "voice/${module.name} must not depend on voice/$it" }
            // The contract is a leaf: it may not lean on the car side or on tooling in any form, tests included.
            for (group in listOf("core", "tools")) {
                references(stripComments(text), group).forEach { violations += "voice/${module.name} depends on $group/$it" }
            }
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun coreNeverDependsOnTools() {
        val violations = mutableListOf<String>()
        for (module in RepoFiles.coreModules()) {
            // Test blocks are included on purpose: tools may not leak into core in any form.
            val refs = references(stripComments(File(module, "build.gradle.kts").readText()), "tools")
            refs.forEach { violations += "core/${module.name} depends on tools/$it" }
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun parserIgnoresTestSourceSets() {
        val sample =
            """
            kotlin {
                sourceSets {
                    commonMain.dependencies {
                        api(projects.core.common)
                    }
                    commonTest.dependencies {
                        implementation(projects.core.signals)
                    }
                    jvmTest.dependencies {
                        implementation(projects.core.adapter)
                    }
                }
            }
            """.trimIndent()
        assertEquals(setOf("common"), productionDependencies(sample, "core"))
    }

    private fun references(
        text: String,
        group: String,
    ): Set<String> =
        (accessor.findAll(text) + pathString.findAll(text))
            .filter { it.groupValues[1] == group }
            .map { it.groupValues[2] }
            .toSet()

    private fun stripComments(text: String): String = text.lines().joinToString("\n") { it.substringBefore("//") }

    /** Projects of [group] referenced outside test source sets (commonTest, jvmTest, testImplementation(...) and the like). */
    private fun productionDependencies(
        buildFile: String,
        group: String,
    ): Set<String> {
        val result = mutableSetOf<String>()
        // One label per open brace, so a reference is attributed to every block that encloses it.
        val open = ArrayDeque<String>()
        for (line in stripComments(buildFile).lines()) {
            val insideTest = open.any { it.endsWith("Test") }
            if (!insideTest && !testConfiguration.containsMatchIn(line)) result += references(line, group)
            val label = sourceSetBlock.find(line)?.groupValues?.get(1) ?: plainBlock.find(line)?.groupValues?.get(1) ?: ""
            for (ch in line) {
                if (ch == '{') open.addLast(label)
                if (ch == '}' && open.isNotEmpty()) open.removeLast()
            }
        }
        return result
    }
}
