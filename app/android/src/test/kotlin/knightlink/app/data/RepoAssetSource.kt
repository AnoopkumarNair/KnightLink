package knightlink.app.data

import java.io.File

/**
 * The bundled data as it is in the repository, laid out as the APK's assets are: the `definitions` and `sim` directories are what the
 * `bundleRepositoryData` Gradle task copies from `definitions/` and `fixtures/sim/`, and `app-config.json` is the app's own asset.
 * Reading the repository directly means the tests cover the real files, not copies.
 */
class RepoAssetSource(private val root: File = repoRoot()) : AssetSource {
    private fun file(path: String): File = when {
        path == "definitions" || path.startsWith("definitions/") -> File(root, path)
        path == "sim" -> File(root, "fixtures/sim")
        path.startsWith("sim/") -> File(root, "fixtures/$path")
        path == AppConfig.PATH -> File(root, "app/android/src/main/assets/${AppConfig.PATH}")
        // Anything else is not bundled, whatever happens to be in the repository under that name.
        else -> File(root, "nonexistent-$path")
    }

    override fun open(path: String): String = file(path).readText(Charsets.UTF_8)

    override fun list(dir: String): List<String> = file(dir).list()?.sorted().orEmpty()

    companion object {
        fun repoRoot(): File = File(requireNotNull(System.getProperty("knightlink.repoRoot")) { "knightlink.repoRoot is not set" })
    }
}

/** An in-memory [AssetSource] for tests that need files the repository does not have (layers that override each other, loops). */
class MapAssetSource(private val files: Map<String, String>) : AssetSource {
    override fun open(path: String): String = files[path] ?: throw java.io.FileNotFoundException(path)

    override fun list(dir: String): List<String> =
        files.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/").substringBefore('/') }.distinct().sorted()
}
