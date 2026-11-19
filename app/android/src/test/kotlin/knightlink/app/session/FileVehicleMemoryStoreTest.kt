package knightlink.app.session

import knightlink.core.runtime.DidHistory
import knightlink.core.runtime.GearCluster
import knightlink.core.runtime.GearModel
import knightlink.core.runtime.VehicleMemory
import knightlink.core.vehicle.AdapterInfo
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DerivedFacts
import knightlink.core.vehicle.Timing
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileVehicleMemoryStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeSharedPreferences : android.content.SharedPreferences {
        val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in map
        override fun edit(): android.content.SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        inner class Editor : android.content.SharedPreferences.Editor {
            override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
                if (value != null) map[key] = value else map.remove(key)
                return this
            }

            override fun putStringSet(key: String, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
            override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor = apply { map[key] = value }
            override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor = apply { map[key] = value }
            override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor = apply { map[key] = value }
            override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor = apply { map[key] = value }
            override fun remove(key: String): android.content.SharedPreferences.Editor = apply { map.remove(key) }
            override fun clear(): android.content.SharedPreferences.Editor = apply { map.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }

    @Test
    fun saltIsPersistedAcrossInstances() {
        val dir = folder.newFolder("vehicles")
        val prefs = FakeSharedPreferences()
        val store1 = FileVehicleMemoryStore(dir, prefs)
        val salt1 = store1.salt
        assertTrue(salt1.isNotEmpty())

        val store2 = FileVehicleMemoryStore(dir, prefs)
        assertEquals(salt1, store2.salt)
    }

    @Test
    fun saveAndLoadMemory() {
        val dir = folder.newFolder("vehicles")
        val prefs = FakeSharedPreferences()
        val store = FileVehicleMemoryStore(dir, prefs)

        assertNull(store.lastKey())
        assertNull(store.load("unknown_key"))

        val probe = CapabilityReport(
            adapter = AdapterInfo("ELM327 v2.3", "OBDII to RS232 Interpreter", "14.1V", 14.1, "A6"),
            timing = Timing(probeMs = 812.5, benchmarkSamples = 10, medianRoundTripMs = 45.0, estimatedRequestsPerSecond = 22.2),
            ecus = emptyList(),
            derived = DerivedFacts.of(emptyList()),
        )
        val memory = VehicleMemory(
            key = "vehicle_hash_12345",
            probe = probe,
            discovery = mapOf("7E0:22E001" to DidHistory(samples = 15, lastHex = "00AA", changed = false)),
            gear = GearModel(listOf(GearCluster(35.5, 10))),
        )

        store.save(memory)
        assertEquals("vehicle_hash_12345", store.lastKey())

        val loaded = store.load("vehicle_hash_12345")
        assertNotNull(loaded)
        assertEquals(memory.key, loaded.key)
        assertEquals(memory.discovery["7E0:22E001"]?.samples, loaded.discovery["7E0:22E001"]?.samples)
        assertEquals(1, loaded.gear.clusters.size)
        assertEquals(35.5, loaded.gear.clusters[0].ratio)
    }
}
