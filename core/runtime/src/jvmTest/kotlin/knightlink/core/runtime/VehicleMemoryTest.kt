package knightlink.core.runtime

import knightlink.core.scan.DidAnswer
import knightlink.core.scan.DidSweepPlan
import knightlink.core.scan.ScanOutcome
import knightlink.core.scan.ScanResult
import knightlink.core.scan.SweepResult
import knightlink.core.vehicle.AdapterInfo
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DerivedFacts
import knightlink.core.vehicle.EcuReport
import knightlink.core.vehicle.Timing
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VehicleMemoryTest {
    private val vin = "MAT" + "0123456789ABC"

    private fun report(vin: String? = this.vin, calibration: List<String> = listOf("CAL-1")) = CapabilityReport(
        adapter = AdapterInfo(null, null, null, null, null),
        timing = Timing(0.0, 0, null, null),
        ecus = listOf(EcuReport("7E0", "7E8", "ECM", calibration, listOf("0C", "0D"), vin)),
        derived = DerivedFacts.of(emptyList()),
    )

    @Test
    fun sha256MatchesTheStandardTestVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun theKeyIsAHashOfVinAndCalibrationIdsAndNeverTheVin() {
        val key = assertNotNull(VehicleKey.of(report(), "salt-a"))
        assertEquals(24, key.length)
        assertFalse(vin in key)
        assertEquals(key, VehicleKey.of(report(), "salt-a"))
        assertNotEquals(key, VehicleKey.of(report(), "salt-b"), "another installation, another key")
        assertNotEquals(key, VehicleKey.of(report(calibration = listOf("CAL-2")), "salt-a"))
        assertNotEquals(key, VehicleKey.of(report(vin = "MAT" + "0123456789ABD"), "salt-a"))
        assertNull(VehicleKey.of(report(vin = null, calibration = emptyList()), "salt-a"), "nothing tells vehicles apart")
    }

    @Test
    fun theStoredMemoryHoldsNoVinAndRoundTrips() {
        val store = InMemoryVehicleMemoryStore()
        val memory = VehicleMemory(
            key = "abc",
            probe = report().redacted(),
            discovery = mapOf("7E0:22E001" to DidHistory(12, "0A", false)),
            gear = GearModel(listOf(GearCluster(33.0, 20))),
        )
        store.save(memory)
        assertEquals("abc", store.lastKey())
        assertEquals(memory, store.load("abc"))
        assertTrue(store.texts().none { vin in it }, "no full VIN in what is stored")
        assertNull(VehicleMemory.fromJson("not json"), "a damaged file is a fresh start")
    }

    private fun scan(vararg dids: Pair<String, String>) = ScanResult(
        outcome = ScanOutcome.COMPLETED,
        sweeps = listOf(SweepResult("7E0", "engine", 10, 10, dids.map { (did, hex) -> DidAnswer(did, hex) })),
    )

    @Test
    fun f4IdentifiersAreNotPolledAndUnchangingOnesAreAskedOnceTheRestSlowly() {
        val plan = DidSweepPlan.parse(File(System.getProperty("knightlink.repoRoot"), "definitions/discovery/did-sweep.json").readText())
        val result = scan("E001" to "0A", "E011" to "0B0C", "F40C" to "1A2B", "F40D" to "30")
        val items = discoveryPollItems(result, plan)
        assertEquals(listOf("7E0:22E001", "7E0:22E011"), items.map { it.id }, "F4xx mirror Mode 01 PIDs")
        assertTrue(items.all { it.period >= 5.seconds }, "slowly: ${items.map { it.period }}")

        val constant = discoveryPollItems(result, plan, constantItems = setOf("7E0:22E001"))
        assertEquals(Duration.INFINITE, constant.first { it.id == "7E0:22E001" }.period)
        assertTrue(constant.first { it.id == "7E0:22E011" }.period.isFinite())
        assertEquals(5.seconds, discoveryPeriod(3))
        assertEquals(7500.milliseconds, discoveryPeriod(60))
    }

    @Test
    fun aDidIsConstantOnlyAfterEnoughUnchangedAnswers() {
        assertFalse(DidHistory(3, "0A", false).constant)
        assertTrue(DidHistory(DidHistory.MIN_SAMPLES_FOR_CONSTANT, "0A", false).constant)
        assertFalse(DidHistory(500, "0B", true).constant)
    }
}
