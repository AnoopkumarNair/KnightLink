package knightlink.core.rules

import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import java.io.File

/** The repository's own data files, as the app would load them. */
internal object Repo {
    val root = File(System.getProperty("knightlink.repoRoot"))

    const val HARRIER_RULES = "rules/tata/harrier-2020-kryotec-at"

    fun text(path: String): String = File(root, path).readText().replace("\r\n", "\n")

    fun rules(id: String = HARRIER_RULES): RulesConfig = RulesConfig.parse(text("definitions/$id.json"))

    fun phrases(): PhraseBook = PhraseBook.parse(text("definitions/phrases/en.json"))

    private fun definition(id: String): DefinitionFile = DefinitionJson.parse(text("definitions/$id.json"))

    /** The catalog of a vehicle definition with the layers it extends, like the session builds it. */
    fun catalog(vehicleId: String): SignalCatalog {
        val top = definition(vehicleId)
        return SignalCatalog.of(top.extends.map(::definition) + top)
    }
}
