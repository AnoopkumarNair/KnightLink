package knightlink.app.session

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import knightlink.core.runtime.VehicleMemory
import knightlink.core.runtime.VehicleMemoryStore
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Stores [VehicleMemory] as JSON files in app-private storage (`files/vehicles/{key}.json`),
 * indexed by a salted hash of the vehicle's VIN and calibration IDs. Never stores the raw VIN.
 *
 * @param baseDir the directory where vehicle memory files are kept
 * @param prefs used to store the per-installation salt and the key of the most recently used vehicle
 */
class FileVehicleMemoryStore(
    private val baseDir: File,
    private val prefs: SharedPreferences,
) : VehicleMemoryStore {

    constructor(context: Context) : this(
        baseDir = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() },
        prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override val salt: String = prefs.getString(KEY_SALT, null) ?: run {
        val newSalt = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_SALT, newSalt) }
        newSalt
    }

    override fun lastKey(): String? = prefs.getString(KEY_LAST_KEY, null)

    override fun load(key: String): VehicleMemory? {
        val file = File(baseDir, "$key.json")
        if (!file.isFile) return null
        return try {
            VehicleMemory.fromJson(file.readText(Charsets.UTF_8))
        } catch (_: IOException) {
            null
        }
    }

    override fun save(memory: VehicleMemory) {
        baseDir.mkdirs()
        val file = File(baseDir, "${memory.key}.json")
        val temp = File(baseDir, "${memory.key}.json.tmp")
        try {
            temp.writeText(memory.toJson(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            prefs.edit { putString(KEY_LAST_KEY, memory.key) }
        } catch (_: IOException) {
            temp.delete()
        }
    }

    companion object {
        const val DIRECTORY_NAME = "vehicles"
        const val PREFERENCES_NAME = "vehicle_memory"
        const val KEY_SALT = "salt"
        const val KEY_LAST_KEY = "last_key"
    }
}
