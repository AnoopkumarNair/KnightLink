package knightlink.app.voice

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Phonetic lexicon processor that expands automotive acronyms and technical terms
 * (e.g. DPF -> D P F, TPMS -> T P M S) before passing text to on-device TTS.
 */
class LexiconEngine(val replacements: Map<String, String>) {

    // Compile regex patterns with word boundaries
    private val compiledRules: List<Pair<Regex, String>> = replacements.map { (key, value) ->
        val escaped = Regex.escape(key)
        val pattern = if (key.all { it.isLetterOrDigit() }) "\\b$escaped\\b" else escaped
        Regex(pattern, RegexOption.IGNORE_CASE) to value
    }

    /**
     * Expands known acronyms and technical terms in [text].
     */
    fun expand(text: String): String {
        var result = text
        for ((regex, replacement) in compiledRules) {
            result = regex.replace(result, replacement)
        }
        return result
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(lexiconFile: File): LexiconEngine {
            if (!lexiconFile.exists()) return default()
            return runCatching {
                val map: Map<String, String> = json.decodeFromString(lexiconFile.readText())
                LexiconEngine(map)
            }.getOrDefault(default())
        }

        fun default(): LexiconEngine = LexiconEngine(
            mapOf(
                "DPF" to "D P F",
                "TPMS" to "T P M S",
                "EGT" to "E G T",
                "ATF" to "A T F",
                "DEF" to "D E F",
                "ECU" to "E C U",
                "DTC" to "D T C",
                "RPM" to "R P M",
            ),
        )
    }
}
