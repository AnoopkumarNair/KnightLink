package knightlink.app.voice

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class KnightPackManager(
    val packsDir: File,
    private val cacheDir: File,
    private val bundledPackStreamProvider: (() -> InputStream?)? = null,
) {
    constructor(context: Context) : this(
        packsDir = File(context.filesDir, "packs"),
        cacheDir = context.cacheDir,
        bundledPackStreamProvider = {
            try {
                val assetList = context.assets.list("packs") ?: emptyArray()
                val bundledName = assetList.firstOrNull { it.equals("Saathi.knightpack", ignoreCase = true) }
                    ?: assetList.firstOrNull { it.endsWith(".knightpack", ignoreCase = true) }
                bundledName?.let { context.assets.open("packs/$it") }
            } catch (_: Exception) {
                null
            }
        },
    )

    private val json = Json { ignoreUnknownKeys = true }

    init {
        packsDir.mkdirs()
        cacheDir.mkdirs()
        ensureBundledPackInstalled()
    }

    /**
     * Extracts bundled default KnightPack (`Saathi.knightpack`) from APK assets on first launch if not already installed.
     */
    fun ensureBundledPackInstalled() {
        val defaultDir = File(packsDir, "saathi")
        if (defaultDir.exists() && File(defaultDir, "manifest.json").exists()) return

        try {
            bundledPackStreamProvider?.invoke()?.use { input ->
                installPack(input)
            }
        } catch (_: Exception) {
            // Assets missing or read error: non-fatal, packs can be imported later
        }
    }

    /**
     * Unpacks a .knightpack ZIP stream into `context.filesDir/packs/{persona_id}/`.
     */
    fun installPack(inputStream: InputStream): Result<KnightPackManifest> = runCatching {
        var manifest: KnightPackManifest? = null
        val tempExtractDir = File(cacheDir, "kp_import_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.replace('\\', '/')
                    // Avoid zip-slip vulnerability
                    val targetFile = File(tempExtractDir, entryName)
                    if (!targetFile.canonicalPath.startsWith(tempExtractDir.canonicalPath)) {
                        error("Zip entry tried to escape extract target: $entryName")
                    }

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        targetFile.outputStream().use { fos -> zis.copyTo(fos) }
                        if (entryName == "manifest.json") {
                            manifest = json.decodeFromString<KnightPackManifest>(targetFile.readText())
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val extractedManifest = manifest ?: error("Invalid .knightpack: missing manifest.json")
            val targetDir = File(packsDir, extractedManifest.personaId.lowercase())
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            if (!tempExtractDir.renameTo(targetDir)) {
                // If rename fails across mounts, fallback to copyRecursively
                tempExtractDir.copyRecursively(targetDir, overwrite = true)
                tempExtractDir.deleteRecursively()
            }

            extractedManifest
        } finally {
            if (tempExtractDir.exists()) tempExtractDir.deleteRecursively()
        }
    }

    fun getInstalledPacks(): List<InstalledKnightPack> =
        packsDir.listFiles { f -> f.isDirectory && File(f, "manifest.json").exists() }
            ?.mapNotNull(::loadPack)
            .orEmpty()

    fun getActivePack(preferredPersonaId: String? = null): InstalledKnightPack? {
        val packs = getInstalledPacks()
        if (packs.isEmpty()) return null
        if (preferredPersonaId != null) {
            val matching = packs.firstOrNull { it.manifest.personaId.equals(preferredPersonaId, ignoreCase = true) }
            if (matching != null) return matching
        }
        return packs.firstOrNull { it.manifest.personaId.equals("saathi", ignoreCase = true) } ?: packs.first()
    }

    fun loadPack(dir: File): InstalledKnightPack? = runCatching {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return null
        val manifest = json.decodeFromString<KnightPackManifest>(manifestFile.readText())

        val visualizerFile = File(dir, "visualizer.json")
        val visualizer = if (visualizerFile.exists()) {
            json.decodeFromString<VisualizerConfig>(visualizerFile.readText())
        } else {
            VisualizerConfig()
        }

        val personaFile = File(dir, "persona.json")
        val persona = if (personaFile.exists()) {
            runCatching { json.decodeFromString<PersonaMetadata>(personaFile.readText()) }.getOrNull()
        } else {
            null
        }

        val lexiconFile = File(dir, "lexicon.json")
        val lexicon = LexiconEngine.load(lexiconFile)

        val dialogueFile = File(dir, "car_dialogue.json")
        val dialogue = if (dialogueFile.exists()) {
            runCatching { json.decodeFromString<CarDialogueMatrix>(dialogueFile.readText()).events }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        InstalledKnightPack(
            dir = dir,
            manifest = manifest,
            visualizer = visualizer,
            persona = persona,
            lexicon = lexicon,
            dialogue = dialogue,
        )
    }.getOrNull()
}
