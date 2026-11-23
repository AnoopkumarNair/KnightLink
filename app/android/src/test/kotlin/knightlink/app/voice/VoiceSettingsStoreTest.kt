package knightlink.app.voice

import knightlink.app.ble.StringStore
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceSettingsStoreTest {
    private class MapStore(val values: MutableMap<String, String> = HashMap()) : StringStore {
        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun defaultsAreSpokenStatusOffAndFullVolume() {
        assertEquals(VoiceSettings(spokenStatus = false, volume = 1.0f), VoiceSettingsStore(MapStore()).settings.value)
    }

    @Test
    fun changesAreSavedAndLoadedAgainByANewStore() {
        val backing = MapStore()
        val store = VoiceSettingsStore(backing)

        store.setSpokenStatus(false)
        store.setVolume(0.4f)

        assertEquals(VoiceSettings(false, 0.4f), store.settings.value)
        assertEquals(VoiceSettings(false, 0.4f), VoiceSettingsStore(backing).settings.value)
    }

    @Test
    fun volumeIsClampedToTheAllowedRange() {
        val store = VoiceSettingsStore(MapStore())

        store.setVolume(0f)
        assertEquals(0.1f, store.settings.value.volume)
        store.setVolume(7f)
        assertEquals(1.0f, store.settings.value.volume)
        store.setVolume(Float.NaN)
        assertEquals(1.0f, store.settings.value.volume)
    }

    @Test
    fun damagedStoredValuesFallBackToDefaultsOrAreClamped() {
        val backing = MapStore(mutableMapOf("voice_spoken_status" to "maybe", "voice_volume" to "loud"))
        assertEquals(VoiceSettings(), VoiceSettingsStore(backing).settings.value)

        backing.values["voice_volume"] = "-3"
        assertEquals(0.1f, VoiceSettingsStore(backing).settings.value.volume)
        backing.values["voice_volume"] = "NaN"
        assertEquals(1.0f, VoiceSettingsStore(backing).settings.value.volume)
    }

    @Test
    fun theFlowFollowsEachChange() {
        val store = VoiceSettingsStore(MapStore())

        store.setSpokenStatus(false)

        assertEquals(false, store.settings.value.spokenStatus)
        store.setSpokenStatus(true)
        assertEquals(true, store.settings.value.spokenStatus)
    }

    @Test
    fun theLanguageIsTheDevicesWhenAvailableThenBritishThenAmerican() {
        assertEquals(Locale.GERMANY, pickLocale(Locale.GERMANY) { true })
        assertEquals(Locale.UK, pickLocale(Locale.GERMANY) { it == Locale.UK || it == Locale.US })
        assertEquals(Locale.US, pickLocale(Locale.GERMANY) { it == Locale.US })
        assertNull(pickLocale(Locale.GERMANY) { false })
    }
}
