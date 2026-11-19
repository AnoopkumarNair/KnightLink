package knightlink.app.voice

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KnightPackManagerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun createZip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun installsAndLoadsPack() {
        val filesDir = folder.newFolder("files")
        val cacheDir = folder.newFolder("cache")
        val manager = KnightPackManager(packsDir = File(filesDir, "packs"), cacheDir = cacheDir)

        val manifestJson = """
            {
                "schema_version": 1,
                "engine": "google-litertlm-qwen3",
                "persona_id": "test_bot",
                "name": "Test Bot",
                "driver_name": "Tester",
                "description": "Test persona"
            }
        """.trimIndent()

        val visualizerJson = """
            {
                "archetype": "matrix-waveform",
                "primary_color": "#FFAA00"
            }
        """.trimIndent()

        val lexiconJson = """
            {
                "OBD": "O B D"
            }
        """.trimIndent()

        val zipBytes = createZip(
            mapOf(
                "manifest.json" to manifestJson,
                "visualizer.json" to visualizerJson,
                "lexicon.json" to lexiconJson,
            ),
        )

        val result = manager.installPack(ByteArrayInputStream(zipBytes))
        assertTrue(result.isSuccess, "installPack should succeed: ${result.exceptionOrNull()}")

        val manifest = result.getOrThrow()
        assertEquals("test_bot", manifest.personaId)
        assertEquals("Test Bot", manifest.name)

        val installed = manager.getInstalledPacks()
        assertEquals(1, installed.size)

        val active = manager.getActivePack("test_bot")
        assertNotNull(active)
        assertEquals("matrix-waveform", active.visualizer.archetype)
        assertEquals("O B D", active.lexicon.expand("OBD"))
    }
}
