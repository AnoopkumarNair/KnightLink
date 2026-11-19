package knightlink.app.ui.voice

import knightlink.app.data.DefinitionStore
import knightlink.app.data.DefinitionStoreException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** How the voice is drawn. Only [BAR_METER] exists so far; the data format names a style by [id]. */
enum class VisualiserStyle(val id: String) {
    BAR_METER("bar-meter"),
    ;

    companion object {
        fun fromId(id: String): VisualiserStyle? = entries.firstOrNull { it.id == id }
    }
}

/** Colours as ARGB ints. [low] is the colour of the segments nearest the middle, [high] of the outermost; [off] is an unlit segment. */
data class VisualiserColors(val low: Int, val high: Int, val off: Int, val background: Int, val text: Int)

/**
 * A theme pack: the look of the speaking screen, as data (`definitions/themes/<name>.json`).
 *
 * @property columns number of segments of each column, left to right. Exactly three, each odd (so there is a middle segment) and
 *   between [MIN_SEGMENTS] and [MAX_SEGMENTS].
 */
data class ThemePack(
    val id: String,
    val name: String,
    val style: VisualiserStyle,
    val colors: VisualiserColors,
    val columns: List<Int>,
) {
    init {
        require(columns.size == COLUMN_COUNT) { "columns needs exactly $COLUMN_COUNT entries, got ${columns.size}" }
        columns.forEach {
            require(it in MIN_SEGMENTS..MAX_SEGMENTS && it % 2 == 1) {
                "a column needs an odd number of segments from $MIN_SEGMENTS to $MAX_SEGMENTS, got $it"
            }
        }
    }

    @Serializable
    private data class FileModel(val schemaVersion: Int, val id: String, val name: String, val visualiser: VisualiserModel)

    @Serializable
    private data class VisualiserModel(val style: String, val colors: ColorsModel, val columns: List<Int>)

    @Serializable
    private data class ColorsModel(val low: String, val high: String, val off: String, val background: String, val text: String)

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val COLUMN_COUNT = 3
        const val MIN_SEGMENTS = 3
        const val MAX_SEGMENTS = 31

        private val json = Json { ignoreUnknownKeys = false }
        private val HEX_COLOR = Regex("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})")

        /** The built-in pack, used when the configured one cannot be loaded. Same values as `definitions/themes/ember.json`. */
        val DEFAULT = ThemePack(
            id = "themes/ember",
            name = "Ember",
            style = VisualiserStyle.BAR_METER,
            colors = VisualiserColors(
                low = 0xFFB3171F.toInt(),
                high = 0xFFFFB000.toInt(),
                off = 0x26B3171F,
                background = 0xFF0B0708.toInt(),
                text = 0xFFF2E6DC.toInt(),
            ),
            columns = listOf(11, 15, 11),
        )

        /**
         * @param expectedId when not null, the file's `id` must equal it.
         * @throws DefinitionStoreException if the text is not a valid pack: unknown key, unknown style, bad colour or column list.
         */
        fun parse(text: String, expectedId: String? = null): ThemePack {
            try {
                val file = json.decodeFromString(FileModel.serializer(), text)
                require(file.schemaVersion in 1..SUPPORTED_SCHEMA_VERSION) {
                    "schema ${file.schemaVersion} is not supported (up to $SUPPORTED_SCHEMA_VERSION)"
                }
                require(expectedId == null || file.id == expectedId) { "id '${file.id}' does not match its location '$expectedId'" }
                require(file.name.isNotBlank()) { "name is empty" }
                val v = file.visualiser
                val style = requireNotNull(VisualiserStyle.fromId(v.style)) {
                    "unknown visualiser style '${v.style}' (known: ${VisualiserStyle.entries.joinToString { it.id }})"
                }
                val c = v.colors
                val colors = VisualiserColors(
                    low = color("low", c.low),
                    high = color("high", c.high),
                    off = color("off", c.off),
                    background = color("background", c.background),
                    text = color("text", c.text),
                )
                return ThemePack(file.id, file.name, style, colors, v.columns)
            } catch (e: SerializationException) {
                throw DefinitionStoreException("Invalid theme pack: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw DefinitionStoreException("Invalid theme pack: ${e.message}", e)
            }
        }

        /** The pack [id] from [store], or [DEFAULT] (after calling [log] with the reason) if it cannot be used. Never throws. */
        fun loadOrDefault(store: DefinitionStore, id: String, log: (String) -> Unit): ThemePack =
            try {
                store.themePack(id)
            } catch (e: DefinitionStoreException) {
                log("Theme pack '$id' not used, falling back to the built-in one: ${e.message}")
                DEFAULT
            }

        private fun color(field: String, value: String): Int {
            require(HEX_COLOR.matches(value)) { "colour $field '$value' is not #RRGGBB or #AARRGGBB" }
            val digits = value.substring(1).toLong(16)
            return (if (value.length == 7) digits or 0xFF000000L else digits).toInt()
        }
    }
}
