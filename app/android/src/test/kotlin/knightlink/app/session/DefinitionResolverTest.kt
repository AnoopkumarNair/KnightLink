package knightlink.app.session

import knightlink.app.data.DefinitionStore
import knightlink.app.data.RepoAssetSource
import knightlink.core.vehicle.AdapterInfo
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.DerivedFacts
import knightlink.core.vehicle.EcuReport
import knightlink.core.vehicle.FuelMethod
import knightlink.core.vehicle.Timing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefinitionResolverTest {
    private val store = DefinitionStore(RepoAssetSource())
    private val generic = store.standardCatalog()
    private val resolver = DefinitionResolver(store, generic, sweepPlan = store.sweepPlan(), latestYear = 2027)

    private fun report(vin: String?, pids: List<String> = listOf("04", "05", "0C", "0D")): CapabilityReport {
        val ecus = listOf(EcuReport("7E0", "7E8", null, emptyList(), pids, vin))
        return CapabilityReport(
            adapter = AdapterInfo(null, null, null, null, null),
            timing = Timing(0.0, 0, null, null),
            ecus = ecus,
            derived = DerivedFacts.of(ecus),
        )
    }

    private fun vin(prefix: String, yearCode: Char) = prefix + "XXXXXX" + yearCode + "XXXXXXX"

    @Test
    fun aVinOfTheDefinitionsMakeAndYearGetsTheDefinitionAndItsFeatures() {
        val resolved = resolver.resolve(report(vin("MAT", 'L')))
        assertTrue(resolved.known)
        assertEquals("vehicles/tata/harrier-2020-kryotec-at", resolved.definitionId)
        assertEquals("Harrier", resolved.model)
        assertNotNull(resolved.candidateChecks)
        assertNotNull(resolved.scanPlan)
        assertTrue(resolved.catalog.ecus.isNotEmpty())
    }

    @Test
    fun aHarrierVinWhoseYearCharacterIsOddStillGetsTheDefinitionAndTheOddityIsLogged() {
        val log = ArrayList<String>()
        val logging = DefinitionResolver(store, generic, sweepPlan = store.sweepPlan(), latestYear = 2027, log = { log += it })
        for (code in listOf('K', 'A', '1', 'U')) {
            val resolved = logging.resolve(report(vin("MAT", code)))
            assertTrue(resolved.known, "year character $code")
            assertEquals("vehicles/tata/harrier-2020-kryotec-at", resolved.definitionId)
        }
        assertTrue(log.any { "year character" in it }, log.toString())
    }

    @Test
    fun aRememberedProbeWithAMaskedVinStillMatchesByItsManufacturerCode() {
        assertTrue(resolver.resolve(report("MAT" + "*".repeat(14))).known)
    }

    @Test
    fun anyOtherCarGetsTheStandardSignalsOnlyAndNoFeaturesOfAKnownCar() {
        for (other in listOf(vin("MZB", 'T'), vin("MA1", 'T'), null)) {
            val resolved = resolver.resolve(report(other))
            assertFalse(resolved.known, "$other")
            assertNull(resolved.definitionId)
            assertNull(resolved.candidateChecks)
            assertNull(resolved.seedScan)
            assertNull(resolved.scanPlan)
            assertTrue(resolved.catalog.ecus.isEmpty())
        }
    }

    @Test
    fun theGenericProfileIsBuiltFromWhatTheProbeFound() {
        val resolved = resolver.resolve(report(vin("MZB", 'T'), pids = listOf("05", "0C", "0D")))
        val coverage = DefinitionCoverage.compare(report(vin("MZB", 'T'), pids = listOf("05", "0C", "0D")), resolved.catalog)
        assertEquals(listOf("05", "0C", "0D"), coverage.confirmed.map { it.pid })
        assertTrue(coverage.unmapped.isEmpty())
    }

    @Test
    fun aDieselGetsNoFuelEstimateFromTheAirFlow() {
        val withMaf = report(vin("MA1", 'T'), pids = listOf("0C", "10"))
        val coverage = DefinitionCoverage.compare(withMaf, generic)
        assertEquals(FuelMethod.MAF, vehicleSummary(withMaf, coverage, diesel = false).fuelMethod)
        assertEquals(FuelMethod.MAF, vehicleSummary(withMaf, coverage, diesel = null).fuelMethod)
        assertEquals(FuelMethod.UNKNOWN, vehicleSummary(withMaf, coverage, diesel = true).fuelMethod)
        val direct = report(vin("MA1", 'T'), pids = listOf("0C", "10", "5E"))
        assertEquals(FuelMethod.DIRECT_RATE, vehicleSummary(direct, coverage, diesel = true).fuelMethod)
    }
}
