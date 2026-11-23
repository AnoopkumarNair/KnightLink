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

    /** What a Harrier's probe shows: 7E0 with 41 PIDs, 7E1 with 3, 7E4 with 11. */
    private fun harrier(vin: String?): CapabilityReport {
        fun pids(count: Int) = (1..count).map { it.toString(16).uppercase().padStart(2, '0') }
        val ecus = listOf(
            EcuReport("7E0", "7E8", null, emptyList(), pids(41), vin),
            EcuReport("7E1", "7E9", null, emptyList(), pids(3), null),
            EcuReport("7E4", "7EC", null, emptyList(), pids(11), null),
        )
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
        val resolved = resolver.resolve(harrier(vin("MAT", 'L')))
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
            val resolved = logging.resolve(harrier(vin("MAT", code)))
            assertTrue(resolved.known, "year character $code")
            assertEquals("vehicles/tata/harrier-2020-kryotec-at", resolved.definitionId)
        }
        assertTrue(log.any { "year character" in it }, log.toString())
    }

    @Test
    fun aRememberedProbeWithAMaskedVinStillMatchesByItsManufacturerCode() {
        assertTrue(resolver.resolve(harrier("MAT" + "*".repeat(14))).known)
    }

    @Test
    fun anotherTataWhoseUnitsDoNotLookLikeTheHarrierIsAnUnknownCar() {
        // A Nexon or a Punch: the manufacturer code matches, but the probe shows one engine unit with other PIDs, or other units.
        val nexon = report(vin("MAT", 'N'), pids = (1..30).map { it.toString(16).uppercase().padStart(2, '0') })
        val resolved = resolver.resolve(nexon)
        assertFalse(resolved.known)
        assertNull(resolved.definitionId)
        assertNull(resolved.scanPlan)
        // The right 7E0 alone is not enough either: the other units must be there.
        val partial = harrier(vin("MAT", 'N'))
        val onlyEngine = report(vin("MAT", 'N'), pids = partial.ecus.first().supportedPids)
        assertFalse(resolver.resolve(onlyEngine).known)
        assertTrue(resolver.resolve(partial).known)
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
