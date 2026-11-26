package knightlink.app.session

import android.content.Context
import knightlink.core.runtime.twin.CarTwin
import knightlink.core.runtime.twin.CarTwinStore
import java.io.File

/**
 * Persists one [CarTwin] JSON file per vehicle key under `filesDir/twins/` (app-private, no raw VIN).
 */
class FileCarTwinStore(private val root: File) : CarTwinStore {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY))

    private val lock = Any()

    override fun lastKey(): String? = synchronized(lock) {
        val file = File(root, LAST_KEY_FILE)
        if (!file.isFile) return null
        file.readText(Charsets.UTF_8).trim().takeIf { isValidKey(it) }
    }

    override fun load(key: String): CarTwin? {
        if (!isValidKey(key)) return null
        return synchronized(lock) {
            val file = File(root, "$key$EXTENSION")
            if (!file.isFile) return null
            CarTwin.fromJson(file.readText(Charsets.UTF_8))
        }
    }

    override fun save(twin: CarTwin) {
        if (!isValidKey(twin.key)) return
        synchronized(lock) {
            if (!root.exists()) root.mkdirs()
            val target = File(root, "${twin.key}$EXTENSION")
            val temp = File(root, "${twin.key}$EXTENSION.tmp")
            temp.writeText(twin.toJson(), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.writeText(twin.toJson(), Charsets.UTF_8)
                temp.delete()
            }
            File(root, LAST_KEY_FILE).writeText(twin.key, Charsets.UTF_8)
        }
    }

    private companion object {
        const val DIRECTORY = "twins"
        const val LAST_KEY_FILE = "last.key"
        const val EXTENSION = ".json"
        val KEY_PATTERN = Regex("[a-zA-Z0-9_-]{1,64}")

        fun isValidKey(key: String): Boolean = KEY_PATTERN.matches(key)
    }
}
