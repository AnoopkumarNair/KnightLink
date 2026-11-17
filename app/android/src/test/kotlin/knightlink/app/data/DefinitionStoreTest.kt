package knightlink.app.data

import knightlink.core.signals.SignalCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [DefinitionStore] against the repository's real definition, adapter and scenario files. */
class DefinitionStoreTest {
    private val store = DefinitionStore(RepoAssetSource())
    private val config = AppConfig.load(RepoAssetSource())

    @Test
    fun listsWhatIsBundled() {
        assertTrue(config.vehicleDefinition in store.vehicleIds(), "vehicles: ${store.vehicleIds()}")
        assertTrue(config.adapterDefinition in store.adapterIds(), "adapters: ${store.adapterIds()}")
        assertTrue(config.demoScenario in store.scenarioIds(), "scenarios: ${store.scenarioIds()}")
        // The standard layer and the sensor definitions are bundled too, but they are neither vehicles nor adapters.
        assertTrue(store.vehicleIds().none { it.startsWith("standard/") || it.startsWith("adapters/") })
        assertTrue(store.adapterIds().all { it.startsWith("adapters/") })
        assertEquals(store.vehicleIds(), store.vehicleIds().sorted())
    }

    @Test
    fun everyListedFileLoads() {
        store.vehicleIds().forEach { store.vehicleCatalog(it) }
        store.adapterIds().forEach { assertEquals(it, store.adapter(it).id) }
        store.scenarioIds().forEach { assertEquals(it, store.simulation(it).id) }
    }

    @Test
    fun vehicleCatalogIncludesTheLayersItExtends() {
        val catalog = store.vehicleCatalog(config.vehicleDefinition)
        assertTrue(catalog.ecus.isNotEmpty())
        // Engine speed is a standard parameter: it can only be in the catalog if the extended layer was loaded.
        assertNotNull(catalog.find(0x01, "0C"), "the standard layer is missing")
        // The soot signal is defined by the vehicle layer itself.
        assertTrue(catalog.parameters.any { p -> p.signals.any { it.definition.id == "dpf.soot_pct" } })
    }

    @Test
    fun theSweepPlanOfTheFullScanIsBundledAndValid() {
        val plan = store.sweepPlan()
        assertEquals("discovery/did-sweep", plan.id)
        assertTrue(plan.requestsPerSecond <= 20.0)
        assertTrue(plan.targets.isNotEmpty())
    }

    @Test
    fun aBrokenSweepPlanIsReportedAsABrokenDataFile() {
        val broken = DefinitionStore(MapAssetSource(mapOf("definitions/discovery/did-sweep.json" to """{ "schemaVersion": 1, "nonsense": true }""")))
        assertFailsWith<DefinitionStoreException> { broken.sweepPlan() }
        assertFailsWith<DefinitionStoreException> { DefinitionStore(MapAssetSource(emptyMap())).sweepPlan() }
    }

    @Test
    fun theAdapterDefinitionCarriesItsInitScript() {
        val adapter = store.adapter(config.adapterDefinition)
        assertEquals(config.adapterDefinition, adapter.id)
        assertTrue(adapter.initScript.isNotEmpty())
    }

    @Test
    fun missingAndMalformedIdsAreRefused() {
        assertFailsWith<DefinitionStoreException> { store.vehicleCatalog("vehicles/nobody/nothing") }
        assertFailsWith<DefinitionStoreException> { store.adapter("adapters/nothing") }
        assertFailsWith<DefinitionStoreException> { store.simulation("sim/nothing") }
        assertFailsWith<DefinitionStoreException> { store.simulation(config.vehicleDefinition) }
        assertFailsWith<DefinitionStoreException> { store.vehicleCatalog("../app-config") }
        assertFailsWith<DefinitionStoreException> { store.vehicleCatalog("vehicles/../../app-config") }
        assertFailsWith<DefinitionStoreException> { store.vehicleCatalog("/etc/passwd") }
    }

    @Test
    fun laterLayersOverrideEarlierOnes() {
        val base = layer("standard/base", "standard", extends = "", formula = "A")
        val vehicle = layer("vehicles/x/y", "vehicle", extends = """"standard/base"""", formula = "B")
        val catalog = DefinitionStore(MapAssetSource(mapOf("definitions/standard/base.json" to base, "definitions/vehicles/x/y.json" to vehicle)))
            .vehicleCatalog("vehicles/x/y")
        assertEquals(2.0, rpm(catalog, byteArrayOf(1, 2)))
    }

    @Test
    fun extendsChainsAreFollowedAndALayerReachedTwiceIsLoadedOnce() {
        val files = mapOf(
            "definitions/a.json" to layer("a", "standard", extends = "", formula = "A"),
            "definitions/b.json" to layer("b", "standard", extends = """"a"""", formula = "A+1"),
            "definitions/vehicles/c.json" to layer("vehicles/c", "vehicle", extends = """"a", "b"""", formula = "A+2"),
        )
        // Order is a, b, c: c wins.
        assertEquals(3.0, rpm(DefinitionStore(MapAssetSource(files)).vehicleCatalog("vehicles/c"), byteArrayOf(1)))
    }

    @Test
    fun extendsLoopsAreReportedInsteadOfOverflowingTheStack() {
        val files = mapOf(
            "definitions/a.json" to layer("a", "standard", extends = """"b"""", formula = "A"),
            "definitions/b.json" to layer("b", "standard", extends = """"a"""", formula = "A"),
        )
        val e = assertFailsWith<DefinitionStoreException> { DefinitionStore(MapAssetSource(files)).vehicleCatalog("a") }
        assertTrue("loop" in e.message.orEmpty(), e.message)
    }

    @Test
    fun invalidFilesAreReportedWithTheirId() {
        val files = mapOf("definitions/bad.json" to """{ "schemaVersion": 1, "nonsense": true }""")
        val e = assertFailsWith<DefinitionStoreException> { DefinitionStore(MapAssetSource(files)).vehicleCatalog("bad") }
        assertTrue("bad" in e.message.orEmpty(), e.message)
    }

    private fun rpm(catalog: SignalCatalog, data: ByteArray): Double =
        requireNotNull(catalog.find(0x01, "0C")).decode(data).single().value

    private fun layer(id: String, kind: String, extends: String, formula: String) = """
        {
          "schemaVersion": 1, "id": "$id", "kind": "$kind", "title": "t", "extends": [$extends],
          "parameters": [
            { "service": "01", "pid": "0C", "provenance": "standard",
              "signals": [ { "id": "engine.rpm", "formula": "$formula", "unit": "rpm" } ] }
          ]
        }
    """
}
