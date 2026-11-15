package knightlink.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppConfigTest {
    private val assets = RepoAssetSource()
    private val store = DefinitionStore(assets)

    @Test
    fun theBundledConfigParsesAndNamesDataThatExists() {
        val config = AppConfig.load(assets)
        // Each of these throws if the file is missing or invalid.
        store.vehicleCatalog(config.vehicleDefinition)
        store.adapter(config.adapterDefinition)
        val scenario = store.simulation(config.demoScenario)
        // The demo must simulate the vehicle the app decodes it as; a mismatch would poll nothing.
        assertEquals(config.vehicleDefinition, scenario.definition)
    }

    @Test
    fun unknownKeysAreRejected() {
        val e = assertFailsWith<DefinitionStoreException> {
            AppConfig.parse("""{ "schemaVersion": 1, "vehicleDefinition": "a", "adapterDefinition": "b", "demoScenario": "c", "extra": 1 }""")
        }
        assertTrue(AppConfig.PATH in e.message.orEmpty(), e.message)
    }

    @Test
    fun missingKeysAreRejected() {
        assertFailsWith<DefinitionStoreException> { AppConfig.parse("""{ "schemaVersion": 1, "vehicleDefinition": "a" }""") }
    }

    @Test
    fun aNewerSchemaIsRejectedInsteadOfHalfRead() {
        assertFailsWith<DefinitionStoreException> {
            AppConfig.parse("""{ "schemaVersion": 2, "vehicleDefinition": "a", "adapterDefinition": "b", "demoScenario": "c" }""")
        }
    }
}
