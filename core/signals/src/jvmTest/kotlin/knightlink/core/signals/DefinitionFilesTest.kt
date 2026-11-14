package knightlink.core.signals

import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponseDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Validates the real data files in /definitions and replays the golden frames in /fixtures against them. */
class DefinitionFilesTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))
    private val definitions = File(root, "definitions")

    private fun load(id: String): DefinitionFile = DefinitionJson.parse(File(definitions, "$id.json").readText())

    private fun signalFiles(): List<File> = listOf("standard", "vehicles").flatMap { dir ->
        File(definitions, dir).walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
    }

    @Test
    fun everyDefinitionFileIsValidAndSelfConsistent() {
        val files = signalFiles()
        assertTrue(files.size >= 2, "expected definition files under $definitions")
        for (file in files) {
            val definition = DefinitionJson.parse(file.readText())
            val expectedId = file.relativeTo(definitions).invariantSeparatorsPath.removeSuffix(".json")
            assertEquals(expectedId, definition.id, "id must match the file path")
            val layers = definition.extends.map(::load) + definition
            SignalCatalog.of(layers) // compiles every formula, checks ECU references
            assertTrue(definition.vehicle?.wmi?.let { it.length == 3 } ?: true, "${definition.id}: wmi is 3 characters")
        }
    }

    @Test
    fun signalIdsAreUniqueWithinAVehicle() {
        for (file in signalFiles()) {
            val definition = DefinitionJson.parse(file.readText())
            val catalog = SignalCatalog.of(definition.extends.map(::load) + definition)
            val duplicates = catalog.parameters
                .flatMap { p -> p.signals.map { it.definition.id to p.definition.ecu } }
                .groupBy({ it }, { it }).filterValues { it.size > 1 }.keys
            assertTrue(duplicates.isEmpty(), "${definition.id}: duplicate signals $duplicates")
        }
    }

    @Test
    fun goldenFramesDecodeToSrsValues() {
        val goldenDir = File(root, "fixtures/golden")
        val files = goldenDir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(files.isNotEmpty(), "expected golden fixtures under $goldenDir")
        val decoder = ElmResponseDecoder(extendedIds = false)
        var checked = 0

        for (file in files) {
            val golden = Json.parseToJsonElement(file.readText()).jsonObject
            val catalog = SignalCatalog.of(golden.getValue("layers").jsonArray.map { load(it.jsonPrimitive.content) })

            for (case in golden.getValue("cases").jsonArray.map { it.jsonObject }) {
                val name = "${file.name}: ${case.getValue("name").jsonPrimitive.content}"
                val response = decoder.decode(case.getValue("lines").jsonArray.map { it.jsonPrimitive.content })
                assertTrue(response.protocolErrors.isEmpty(), "$name: ${response.protocolErrors}")
                val message = assertIs<DiagnosticResponse.Positive>(response.messages.single(), name)

                val ecu = assertNotNull(catalog.ecuByResponseId(message.sourceId), "$name: unknown ECU")
                assertEquals(case.getValue("ecu").jsonPrimitive.content, ecu.id, name)

                val pid = message.data.copyOfRange(0, 1).toHex()
                val parameter = assertNotNull(catalog.find(message.service, pid, ecu.id), "$name: no definition for $pid")
                val actual = parameter.decode(message.data.copyOfRange(1, message.data.size)).associateBy { it.id }

                for ((signal, expected) in case.getValue("expect").jsonObject) {
                    val value = actual[signal] ?: fail("$name: signal $signal was not decoded")
                    assertTrue(value.inRange, "$name: $signal out of range")
                    val want = expected.jsonPrimitive.double
                    assertTrue(abs(value.value - want) < 0.005, "$name: $signal expected $want, got ${value.value}")
                    checked++
                }
            }
        }
        assertTrue(checked >= 20, "only $checked golden values were checked")
    }
}
