package knightlink.app.voice

import knightlink.app.ble.StringStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the driver can set about the voice.
 *
 * @property spokenStatus whether connection status is spoken ("Adapter connected."); alerts and answers are not affected
 * @property volume 0.1 to 1.0, applied on top of the volume of the phone. Never 0, so the voice cannot be silenced by accident
 *   and then look broken; switching the spoken lines off is what the other setting is for.
 */
data class VoiceSettings(val spokenStatus: Boolean = DEFAULT_SPOKEN_STATUS, val volume: Float = DEFAULT_VOLUME) {
    companion object {
        const val MIN_VOLUME = 0.1f
        const val MAX_VOLUME = 1.0f
        const val DEFAULT_VOLUME = 1.0f
        const val DEFAULT_SPOKEN_STATUS = false

        /** [volume] within the allowed range; the default for something that is not a number. */
        fun clampVolume(volume: Float): Float = if (volume.isNaN()) DEFAULT_VOLUME else volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
    }
}

/**
 * The voice settings in [store] (SharedPreferences in the app, a map in tests), and the current values as a [StateFlow] so that
 * the speech engine and the announcer read the latest at the moment they speak, and the screen follows changes.
 *
 * Unreadable or out-of-range stored values fall back to the defaults or are clamped, so a damaged file never breaks the voice.
 */
class VoiceSettingsStore(private val store: StringStore) {
    private val _settings = MutableStateFlow(load())

    val settings: StateFlow<VoiceSettings> = _settings.asStateFlow()

    fun setSpokenStatus(enabled: Boolean) = update { it.copy(spokenStatus = enabled) }

    fun setVolume(volume: Float) = update { it.copy(volume = volume) }

    private fun update(change: (VoiceSettings) -> VoiceSettings) {
        synchronized(this) {
            val next = change(_settings.value).let { it.copy(volume = VoiceSettings.clampVolume(it.volume)) }
            store.put(KEY_SPOKEN_STATUS, next.spokenStatus.toString())
            store.put(KEY_VOLUME, next.volume.toString())
            _settings.value = next
        }
    }

    private fun load(): VoiceSettings =
        VoiceSettings(
            spokenStatus = when (store.get(KEY_SPOKEN_STATUS)) {
                "true" -> true
                "false" -> false
                else -> VoiceSettings.DEFAULT_SPOKEN_STATUS
            },
            volume = store.get(KEY_VOLUME)?.toFloatOrNull()?.let(VoiceSettings::clampVolume) ?: VoiceSettings.DEFAULT_VOLUME,
        )

    private companion object {
        const val KEY_SPOKEN_STATUS = "voice_spoken_status"
        const val KEY_VOLUME = "voice_volume"
    }
}
