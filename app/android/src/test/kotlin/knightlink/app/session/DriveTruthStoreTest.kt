package knightlink.app.session

import knightlink.app.ble.StringStore
import knightlink.core.runtime.drivetruth.DriveEvent
import knightlink.core.runtime.drivetruth.DriveEventKind
import knightlink.core.runtime.drivetruth.DriveTruthNote
import knightlink.core.runtime.drivetruth.DriveTruthReport
import knightlink.core.runtime.drivetruth.FuelSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DriveTruthStoreTest {
    private class MapStore : StringStore {
        val values = HashMap<String, String>()

        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val full = DriveTruthReport(
        durationMinutes = 42.5,
        movingMinutes = 38.0,
        idleMinutes = 4.5,
        carDistanceKm = 24.8,
        gpsDistanceKm = 24.3,
        carVsGpsPercent = 2.06,
        fuelLitres = 1.9,
        fuelSource = FuelSource.FUEL_RATE_GPS,
        costRupees = 190.0,
        costPerKmRupees = 7.66,
        idleCostRupees = 12.5,
        kmPerLitre = 13.05,
        notes = listOf(DriveTruthNote.FUEL_DENSITY_ASSUMED, DriveTruthNote.TOO_SHORT_TO_COMPARE),
        events = listOf(DriveEvent(12.5, DriveEventKind.REFUEL, null), DriveEvent(300.0, DriveEventKind.FAULT_APPEARED, "P0300")),
    )

    @Test
    fun aReportComesBackExactlyAsSaved() {
        val backing = MapStore()
        DriveTruthStore(backing).save("tata-1234", full)
        assertEquals(full, DriveTruthStore(backing).load("tata-1234"))
    }

    @Test
    fun missingFiguresStayMissingNeverZero() {
        val sparse =
            DriveTruthReport(5.0, 3.0, 0.0, null, null, null, null, null, null, null, null, null, listOf(DriveTruthNote.FUEL_NOT_REPORTED), emptyList())
        assertEquals(sparse, DriveTruthStore.decode(DriveTruthStore.encode(sparse)))
    }

    @Test
    fun eachCarHasItsOwnLastDriveAndTheKeyNeverHoldsAVin() {
        val backing = MapStore()
        val store = DriveTruthStore(backing)
        store.save("a-1111", full)
        assertNull(store.load("b-2222"))
        assertEquals(setOf("drive_truth.a-1111"), backing.values.keys)
    }

    @Test
    fun damageOrAnotherVersionReadsAsNoDriveNotAnError() {
        for (bad in listOf(null, "", "not json", "[]", "{}", """{"v":99,"duration":1,"moving":1,"idle":0}""", """{"v":1,"duration":"x"}""")) {
            assertNull(DriveTruthStore.decode(bad), "$bad")
        }
    }

    @Test
    fun anUnknownNoteOrEventKindIsLeftOutNotFatal() {
        val text = DriveTruthStore.encode(full).replace("FUEL_DENSITY_ASSUMED", "FROM_THE_FUTURE").replace("REFUEL", "TELEPORT")
        val read = DriveTruthStore.decode(text)!!
        assertEquals(listOf(DriveTruthNote.TOO_SHORT_TO_COMPARE), read.notes)
        assertEquals(listOf(DriveEventKind.FAULT_APPEARED), read.events.map { it.kind })
        assertFalse(read.carDistanceKm == null)
    }
}
