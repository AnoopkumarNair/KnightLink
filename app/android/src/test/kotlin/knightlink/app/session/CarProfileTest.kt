package knightlink.app.session

import knightlink.app.ble.StringStore
import knightlink.core.vehicle.SupportTier
import knightlink.core.vehicle.VehicleIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CarProfileTest {
    private class MapStore : StringStore {
        val values = HashMap<String, String>()

        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private fun identity(vinLast4: String?, make: String = "Tata Motors") =
        VehicleIdentity(make, true, "Harrier", 2020, vinLast4, "CAN", SupportTier.FULL)

    @Test
    fun theKeyHoldsTheMakeAndTheEndOfTheVinNeverMore() {
        val store = CarProfileStore(MapStore())
        assertEquals("tatamotors-1234", store.keyFor(identity("1234")))
        assertNull(store.keyFor(identity(null)), "without the end of a VIN two cars cannot be told apart")
        assertNull(store.keyFor(null))
    }

    @Test
    fun aProfileComesBackAsSaved() {
        val backing = MapStore()
        val store = CarProfileStore(backing)
        val profile = CarProfile("Tata", "Harrier", "Blue", FuelType.DIESEL, Transmission.AUTOMATIC, 48_210, 1234L)
        store.save("k", profile)
        assertEquals(profile, CarProfileStore(backing).load("k"))
        assertNull(store.load("other"))
    }

    @Test
    fun clearingFieldsRemovesThemAndTheNameFallsBackToMakeAndModel() {
        val store = CarProfileStore(MapStore())
        store.save("k", CarProfile("Tata", "Harrier", "Blue", FuelType.DIESEL))
        store.save("k", CarProfile("Tata", "Harrier"))
        val loaded = store.load("k")!!
        assertNull(loaded.nickname)
        assertNull(loaded.fuelType)
        assertEquals("Tata Harrier", loaded.displayName)
        assertEquals("Blue", CarProfile("Tata", "Harrier", "Blue").displayName)
    }

    @Test
    fun anUnknownMakeShowsTheModelOnly() {
        assertEquals("Roamer", CarProfile(knightlink.core.vehicle.UNKNOWN_MAKE, "Roamer").displayName)
    }

    @Test
    fun aDamagedFieldCostsOnlyItself() {
        val backing = MapStore()
        val store = CarProfileStore(backing)
        store.save("k", CarProfile("Tata", "Harrier", fuelType = FuelType.DIESEL, odometerKm = 100))
        backing.values["car.k.fuel"] = "steam"
        backing.values["car.k.odometer"] = "lots"
        val loaded = store.load("k")!!
        assertNull(loaded.fuelType)
        assertNull(loaded.odometerKm)
        assertEquals("Harrier", loaded.model)
    }

    @Test
    fun theFuelPriceIsEmptyByDefaultAndTakesOnlySensibleNumbers() {
        val store = FuelPriceStore(MapStore())
        assertNull(store.load())
        assertEquals(104.5, store.save("104,5"))
        assertEquals(104.5, store.load())
        assertEquals(104.5, store.save("abc"), "an unusable text leaves the price as it was")
        assertEquals(104.5, store.save("0"))
        assertEquals(104.5, store.save("5000"))
        assertNull(store.save(""), "an empty text clears it")
        assertNull(store.load())
    }

    @Test
    fun parseAcceptsACommaAndRefusesNonsense() {
        assertEquals(96.0, FuelPriceStore.parse(" 96 "))
        assertEquals(96.7, FuelPriceStore.parse("96.7"))
        assertNull(FuelPriceStore.parse("-3"))
        assertNull(FuelPriceStore.parse("NaN"))
        assertNull(FuelPriceStore.parse(null))
    }
}
