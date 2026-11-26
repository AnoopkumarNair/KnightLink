package knightlink.core.runtime.twin

import knightlink.core.runtime.VehicleMemory
import knightlink.core.vehicle.AdapterInfo
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DerivedFacts
import knightlink.core.vehicle.EcuReport
import knightlink.core.vehicle.Timing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CarTwinTest {
    @Test
    fun roundTripsThroughJsonAndRejectsFutureSchema() {
        val twin = CarTwin(
            key = "abc123",
            identity = TwinIdentity(wmi = "MAT", make = "Tata", model = "Harrier", modelYear = 2020),
            userFacts = TwinUserFacts(odometerKm = 42000, tankCapacityL = 50.0, fuelPricePerLitre = 94.5),
        )
        val parsed = CarTwin.fromJson(twin.toJson())
        assertEquals(twin, parsed)
        assertNull(CarTwin.fromJson("""{"schemaVersion":99,"key":"abc123"}"""))
    }

    @Test
    fun migratesFromVehicleMemoryAndExposesTwinApi() {
        val ecu = EcuReport("7E0", "7E8", name = null, calibrationIds = emptyList(), supportedPids = listOf("0C", "0D"), vin = null)
        val memory = VehicleMemory(
            key = "veh-1",
            probe = CapabilityReport(
                adapter = AdapterInfo(null, null, null, null, null),
                timing = Timing(0.0, 0, null, null),
                ecus = listOf(ecu),
                derived = DerivedFacts.of(listOf(ecu)),
            ),
            model = "Harrier",
        )
        val store = InMemoryCarTwinStore()
        val migrated = CarTwinBuilder.fromVehicleMemory(memory)
        store.save(migrated)

        val api: TwinApi = LocalTwinApi { store.load("veh-1") }
        assertNotNull(api.twin())
        assertEquals("Harrier", api.identity()?.model)
        assertEquals(DomainState.COLLECTED, api.coverage().stateOf(TwinDomain.ENGINE_BASICS))
        assertEquals(listOf("0C", "0D"), api.twin()?.capabilities?.supportedPidsByEcu?.get("7E0"))
    }

    @Test
    fun capsRecentDrivesAtMax() {
        var twin = CarTwin(key = "veh-2")
        repeat(CarTwin.MAX_RECENT_DRIVES + 10) { i ->
            twin = CarTwinBuilder.recordDrive(twin, TwinDriveSummary(startedAtMs = i.toLong(), distanceKm = 1.0))
        }
        assertEquals(CarTwin.MAX_RECENT_DRIVES, twin.history.recentDrives.size)
        assertEquals(10L, twin.history.recentDrives.first().startedAtMs)
    }
}
