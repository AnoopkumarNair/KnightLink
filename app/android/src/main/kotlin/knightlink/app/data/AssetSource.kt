package knightlink.app.data

import android.content.res.AssetManager

/**
 * Read access to the bundled data files, behind an interface so that [DefinitionStore] can be tested on the JVM against
 * the repository's real files. Paths use `/` and are relative to the asset root, e.g. `definitions/adapters/x.json`.
 */
interface AssetSource {
    /**
     * @return the text (UTF-8) of the file at [path].
     * @throws java.io.IOException if there is no such file or it cannot be read.
     */
    fun open(path: String): String

    /** @return the names (not paths) of the entries directly inside [dir], files and directories alike; empty if there is no such directory. */
    fun list(dir: String): List<String>
}

/** The APK's assets. Kept to a few lines on purpose: everything above it is tested on the JVM. */
class AndroidAssetSource(private val assets: AssetManager) : AssetSource {
    override fun open(path: String): String = assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    override fun list(dir: String): List<String> = assets.list(dir)?.toList().orEmpty()
}
