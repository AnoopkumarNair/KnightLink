package knightlink.app.voice

import java.util.Locale

/**
 * The language to speak in: the phone's own when the speech engine has it, else British, else American English (the car's
 * messages are English whichever way the phone is set). Null when the engine has none of them, which sends the speaker to the
 * plain text-to-speech path.
 */
internal fun pickLocale(deviceDefault: Locale, isAvailable: (Locale) -> Boolean): Locale? =
    listOf(deviceDefault, Locale.UK, Locale.US).distinct().firstOrNull(isAvailable)
