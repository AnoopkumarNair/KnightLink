package knightlink.app.ui.voice

import knightlink.app.data.AppConfig
import knightlink.app.data.DefinitionStore
import knightlink.app.data.DefinitionStoreException
import knightlink.app.data.MapAssetSource
import knightlink.app.data.RepoAssetSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThemePackTest {
    private fun pack(
        style: String = "bar-meter",
        low: String = "#B3171F",
        columns: String = "[11, 15, 11]",
        extra: String = "",
        id: String = "themes/t",
    ) = """
        { "schemaVersion": 1, "id": "$id", "name": "T"$extra,
          "visualiser": { "style": "$style",
            "colors": { "low": "$low", "high": "#FFB000", "off": "#26B3171F", "background": "#000000", "text": "#FFFFFF" },
            "columns": $columns } }
    """

    @Test
    fun theBundledEmberPackParsesAndEqualsTheBuiltInDefault() {
        val ember = DefinitionStore(RepoAssetSource()).themePack("themes/ember")
        assertEquals(ThemePack.DEFAULT, ember)
        assertEquals(listOf(11, 15, 11), ember.columns)
        assertEquals(VisualiserStyle.BAR_METER, ember.style)
    }

    @Test
    fun colourFormsAreParsedToArgb() {
        val p = ThemePack.parse(pack(low = "#B3171F"))
        assertEquals(0xFFB3171F.toInt(), p.colors.low)
        assertEquals(0x26B3171F, p.colors.off)
    }

    @Test
    fun badColoursAreRejected() {
        for (bad in listOf("B3171F", "#B3171", "#GG0000", "red", "#B3171F0", "")) {
            assertFailsWith<DefinitionStoreException>(bad) { ThemePack.parse(pack(low = bad)) }
        }
    }

    @Test
    fun unknownStyleIsRejected() {
        assertFailsWith<DefinitionStoreException> { ThemePack.parse(pack(style = "scanner")) }
    }

    @Test
    fun badColumnListsAreRejected() {
        for (bad in listOf("[11, 15]", "[11, 15, 11, 9]", "[11, 14, 11]", "[1, 15, 1]", "[11, 99, 11]", "[]")) {
            assertFailsWith<DefinitionStoreException>(bad) { ThemePack.parse(pack(columns = bad)) }
        }
    }

    @Test
    fun unknownKeysWrongIdAndFutureSchemaAreRejected() {
        assertFailsWith<DefinitionStoreException> { ThemePack.parse(pack(extra = ", \"glow\": true")) }
        assertFailsWith<DefinitionStoreException> { ThemePack.parse(pack(), expectedId = "themes/other") }
        assertFailsWith<DefinitionStoreException> { ThemePack.parse(pack().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")) }
        assertFailsWith<DefinitionStoreException> { ThemePack.parse("{") }
    }

    @Test
    fun theStoreRefusesIdsThatAreNotThemePacks() {
        val store = DefinitionStore(RepoAssetSource())
        assertFailsWith<DefinitionStoreException> { store.themePack("adapters/vgate-icar-pro-2s") }
        assertFailsWith<DefinitionStoreException> { store.themePack("themes/nothing") }
        assertFailsWith<DefinitionStoreException> { store.themePack("themes/../app-config") }
    }

    @Test
    fun aBrokenConfiguredPackFallsBackToTheBuiltInOne() {
        val logged = mutableListOf<String>()
        val broken = DefinitionStore(MapAssetSource(mapOf("definitions/themes/x.json" to pack(style = "scanner", id = "themes/x"))))
        assertEquals(ThemePack.DEFAULT, ThemePack.loadOrDefault(broken, "themes/x", logged::add))
        assertEquals(ThemePack.DEFAULT, ThemePack.loadOrDefault(broken, "themes/missing", logged::add))
        assertEquals(2, logged.size)
        assertTrue("scanner" in logged[0], logged[0])
    }

    @Test
    fun appConfigDefaultsTheThemePackAndTheBundledConfigNamesOne() {
        val minimal = """{ "schemaVersion": 1, "vehicleDefinition": "v", "adapterDefinition": "a", "demoScenario": "s" }"""
        assertEquals("themes/ember", AppConfig.parse(minimal).themePack)
        val explicit = AppConfig.parse(minimal.replace(" }", ", \"themePack\": \"themes/x\" }"))
        assertEquals("themes/x", explicit.themePack)
        assertEquals("themes/ember", AppConfig.load(RepoAssetSource()).themePack)
    }
}
