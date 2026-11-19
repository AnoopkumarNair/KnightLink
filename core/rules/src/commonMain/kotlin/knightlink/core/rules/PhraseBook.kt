package knightlink.core.rules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A phrase file that is malformed, or a phrase that cannot be rendered. */
public class PhraseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

@Serializable
private data class PhraseFile(val schemaVersion: Int, val language: String, val phrases: Map<String, String>)

/**
 * The words for one language. Wording is data so that it can be changed, translated or reworded by a persona without touching the rules; the
 * rules only choose a key and supply numbers.
 */
public class PhraseBook private constructor(public val language: String, private val templates: Map<String, String>) {
    /** Every key this book can render. */
    public val keys: Set<String> get() = templates.keys

    /** The template of [key] with its `{param}` markers, for tools and tests. */
    public fun template(key: String): String = templates[key] ?: throw PhraseException("No phrase '$key' in the '$language' phrase book")

    /** @throws PhraseException on an unknown key or a parameter the template needs but [phrase] lacks */
    public fun render(phrase: Phrase): String =
        placeholder.replace(template(phrase.key)) { match ->
            phrase.params[match.groupValues[1]] ?: throw PhraseException("Phrase '${phrase.key}' needs '${match.groupValues[1]}'")
        }

    public companion object {
        private val json = Json { ignoreUnknownKeys = false }
        private val placeholder = Regex("""\{(\w+)}""")

        /** The parameter names a template uses. */
        public fun parametersOf(template: String): Set<String> = placeholder.findAll(template).map { it.groupValues[1] }.toSet()

        /** @throws PhraseException if the text is not a valid phrase file */
        public fun parse(text: String): PhraseBook {
            val file = try {
                json.decodeFromString(PhraseFile.serializer(), text)
            } catch (e: IllegalArgumentException) {
                throw PhraseException("Invalid phrase file: ${e.message}", e)
            }
            if (file.schemaVersion !in 1..SUPPORTED_RULES_SCHEMA_VERSION) throw PhraseException("Phrase schema ${file.schemaVersion} is not supported")
            if (file.language.isBlank()) throw PhraseException("Phrase file has no language")
            file.phrases.forEach { (key, value) -> if (key.isBlank() || value.isBlank()) throw PhraseException("Phrase '$key' is empty") }
            return PhraseBook(file.language, file.phrases)
        }
    }
}
