package knightlink.app

import android.content.SharedPreferences
import androidx.core.content.edit
import knightlink.app.ble.StringStore

/** The app's small settings, in the SharedPreferences file [FILE]; shared by the screen's view model and the service. */
class SharedPreferencesStringStore(private val preferences: SharedPreferences) : StringStore {
    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(key: String, value: String?) {
        preferences.edit { if (value == null) remove(key) else putString(key, value) }
    }

    companion object {
        const val FILE = "knightlink"
    }
}
