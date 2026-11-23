package knightlink.app.session

import knightlink.core.runtime.drivetruth.DriveEventKind
import knightlink.core.runtime.drivetruth.DriveTruthConfig
import knightlink.core.runtime.drivetruth.DriveTruthNote
import knightlink.core.runtime.drivetruth.DriveTruthReport
import knightlink.core.runtime.drivetruth.FuelSource
import knightlink.core.runtime.drivetruth.GpsFix
import knightlink.core.signals.SignalValue
import knightlink.core.telemetry.Reading
import knightlink.core.telemetry.TelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import knightlink.core.runtime.drivetruth.FuelType as ReducerFuelType

class DriveTruthFeedTest {
    /** The session, as the feed sees it: a snapshot that readings are put into at their own times, and a screen refresh that asks the feed. */
    private class Rig(var fuel: FuelType? = FuelType.DIESEL, var price: Double? = 100.0) {
        val feed = DriveTruthFeed { DriveTruthConfig(DriveTruthFeed.reducerFuel(fuel), price, 50.0) }
        var snapshot = TelemetrySnapshot.EMPTY
        var fills = 0
        var faults: Set<String>? = null
        var asleep = false
        val reports = ArrayList<DriveTruthReport>()

        /** A reading arrives: the snapshot changes and the feed sees it at once, as it does in the app. */
        fun read(at: Long, id: String, value: Double) {
            snapshot = snapshot.with(listOf(Reading(null, SignalValue(id, value, ""))), at.milliseconds)
            feed.accept(snapshot, at)
        }

        fun tick(now: Long): DriveTruthReport? = feed.update(DriveFeedInput(now, asleep, fills, faults)).also { if (it != null) reports += it }

        /** Readings every [step] ms from [from] to [to] (inclusive), the screen asked right after each. */
        fun run(from: Long, to: Long, step: Long = 500, vararg signals: Pair<String, Double>) {
            var t = from
            while (t <= to) {
                for ((id, v) in signals) read(t, id, v)
                tick(t)
                t += step
            }
        }
    }

    private val cruise = arrayOf(DriveTruthFeed.SPEED to 50.0, DriveTruthFeed.RPM to 2000.0)

    private fun Rig.engineOffFor(from: Long, to: Long) = run(from, to, 500, DriveTruthFeed.SPEED to 0.0, DriveTruthFeed.RPM to 0.0)

    @Test
    fun theFuelRateTheSpeedAndThePriceBecomeDistanceFuelAndCost() {
        val rig = Rig()
        // A minute at 50 km/h burning 6 L/h, then the engine off for longer than the end rule.
        rig.run(1_000, 61_000, 500, *cruise, DriveTruthFeed.FUEL_RATE_LPH to 6.0)
        assertTrue(rig.feed.inProgress)
        assertTrue(rig.reports.isEmpty(), "the drive is not over")
        rig.engineOffFor(61_500, 125_000)
        val report = rig.reports.single()
        assertEquals(FuelSource.FUEL_RATE_LPH, report.fuelSource)
        assertEquals(0.1, report.fuelLitres!!, 0.01)
        assertEquals(10.0, report.costRupees!!, 1.0)
        assertEquals(50.0 * 60.0 / 3600.0, report.carDistanceKm!!, 0.05)
        assertFalse(rig.feed.inProgress)
    }

    @Test
    fun theReportComesOnceAndANewDriveStartsFreshAfterwards() {
        val rig = Rig()
        rig.run(1_000, 31_000, 500, *cruise)
        rig.engineOffFor(31_500, 100_000)
        assertEquals(1, rig.reports.size)
        rig.engineOffFor(100_500, 130_000)
        assertEquals(1, rig.reports.size, "an ended drive is not reported again")
        rig.run(131_000, 161_000, 500, *cruise)
        assertTrue(rig.feed.inProgress, "moving again starts the next drive")
        rig.engineOffFor(161_500, 230_000)
        assertEquals(2, rig.reports.size)
    }

    @Test
    fun aCarThatIdlesInTheDrivewayHasNoDriveUntilItMoves() {
        val rig = Rig()
        rig.run(1_000, 20_000, 500, DriveTruthFeed.SPEED to 0.0, DriveTruthFeed.RPM to 800.0)
        assertFalse(rig.feed.inProgress)
        // Speed with no engine speed read is not a drive either (the engine is not known to run).
        val other = Rig()
        other.run(1_000, 5_000, 500, DriveTruthFeed.SPEED to 30.0)
        assertFalse(other.feed.inProgress)
        rig.run(20_500, 25_000, 500, *cruise)
        assertTrue(rig.feed.inProgress)
    }

    @Test
    fun readingsTheSessionStillHoldsFromBeforeAreNotADrive() {
        val rig = Rig()
        rig.snapshot = rig.snapshot.with(
            listOf(Reading(null, SignalValue(DriveTruthFeed.SPEED, 60.0, "")), Reading(null, SignalValue(DriveTruthFeed.RPM, 2500.0, ""))),
            0.milliseconds,
        )
        // The feed first looks a minute later (a connection that was busy with something else): the readings are from before.
        rig.feed.accept(rig.snapshot, 60_000)
        rig.tick(60_000)
        assertFalse(rig.feed.inProgress)
    }

    @Test
    fun aSessionThatSleepsEndsTheDrive() {
        val rig = Rig()
        rig.run(1_000, 31_000, 500, *cruise)
        rig.asleep = true
        val report = rig.tick(32_000)
        assertNotNull(report)
        assertFalse(DriveTruthNote.DRIVE_NOT_ENDED in report.notes)
    }

    @Test
    fun theSpeedOfEachReadingKeepsItsOwnTime() {
        val rig = Rig()
        rig.read(1_000, DriveTruthFeed.SPEED, 36.0)
        rig.read(1_000, DriveTruthFeed.RPM, 1500.0)
        rig.tick(1_000)
        // The speed arrives every 500 ms, but the screen only asks every 2 s: the reading times, not the asking times, give the distance.
        rig.read(1_500, DriveTruthFeed.SPEED, 36.0)
        rig.read(2_000, DriveTruthFeed.SPEED, 36.0)
        rig.read(2_000, DriveTruthFeed.RPM, 1500.0)
        rig.read(2_500, DriveTruthFeed.SPEED, 36.0)
        rig.tick(2_600)
        val report = rig.feed.unfinished()!!
        assertEquals(36.0 * 1.5 / 3600.0, report.carDistanceKm!!, 0.0005)
        assertTrue(DriveTruthNote.DRIVE_NOT_ENDED in report.notes)
    }

    @Test
    fun aPausedStretchOfPollingShowsAsAGapAndIsNeverFilled() {
        val rig = Rig()
        rig.run(1_000, 20_000, 500, *cruise)
        // Passive capture or a link loss: nothing is read for 20 s, the screen still refreshes.
        var t = 20_500L
        while (t < 40_000) rig.tick(t).also { t += 500 }
        rig.run(40_000, 50_000, 500, *cruise)
        val report = rig.feed.unfinished()!!
        assertTrue(DriveTruthNote.SPEED_SIGNAL_GAPS in report.notes)
        assertNull(report.carDistanceKm, "too many gaps: no distance rather than a made-up one")
    }

    @Test
    fun airFlowIsOnlyUsedForFuelOnAPetrolCar() {
        fun reportFor(fuel: FuelType?): DriveTruthReport {
            val rig = Rig(fuel = fuel)
            rig.run(1_000, 61_000, 500, *cruise, DriveTruthFeed.MAF to 20.0)
            return rig.feed.unfinished()!!
        }
        val petrol = reportFor(FuelType.PETROL)
        assertEquals(FuelSource.MAF, petrol.fuelSource)
        // 20 g/s of air is 20 / 14.7 g/s of petrol: 6.57 litres an hour at 0.745 kg per litre.
        assertEquals(6.57 / 60.0, petrol.fuelLitres!!, 0.01)
        assertTrue(DriveTruthNote.FUEL_FROM_AIRFLOW in petrol.notes)
        for (other in listOf(FuelType.DIESEL, FuelType.CNG, FuelType.HYBRID, null)) {
            val report = reportFor(other)
            assertNull(report.fuelSource, "$other: no fuel from air flow")
            assertNull(report.costRupees)
            assertTrue(DriveTruthNote.FUEL_NOT_REPORTED in report.notes)
        }
    }

    @Test
    fun theMassFuelRateIsConvertedWithTheDensityOfTheFuelAndSaysSo() {
        val rig = Rig()
        // 2.0 g/s of diesel at 0.832 kg per litre is 8.65 L/h.
        rig.run(1_000, 61_000, 500, *cruise, DriveTruthFeed.FUEL_RATE_GPS to 2.0)
        val report = rig.feed.unfinished()!!
        assertEquals(FuelSource.FUEL_RATE_GPS, report.fuelSource)
        assertEquals(2.0 * 3.6 / 0.832 / 60.0, report.fuelLitres!!, 0.01)
        assertTrue(DriveTruthNote.FUEL_DENSITY_ASSUMED in report.notes)
    }

    @Test
    fun theFuelLevelAndTheTankGiveTheFallbackWhenNothingReportsTheRate() {
        val rig = Rig()
        // 10 points of a 50 litre tank over the drive: 5 litres, approximate.
        var level = 80.0
        var t = 1_000L
        while (t <= 61_000) {
            rig.read(t, DriveTruthFeed.SPEED, 50.0)
            rig.read(t, DriveTruthFeed.RPM, 2000.0)
            if (t % 6_000L == 1_000L) {
                rig.read(t, DriveTruthFeed.FUEL_LEVEL, level)
                level -= 1.0
            }
            rig.tick(t)
            t += 500
        }
        val report = rig.feed.unfinished()!!
        assertEquals(FuelSource.FUEL_LEVEL_DROP, report.fuelSource)
        assertEquals(5.0, report.fuelLitres!!, 0.6)
        assertTrue(DriveTruthNote.FUEL_ONLY_APPROXIMATE in report.notes)
    }

    @Test
    fun aRefuelDuringTheDriveIsAnEventAndOneBeforeItIsNot() {
        val rig = Rig()
        rig.fills = 3
        rig.run(1_000, 10_000, 500, *cruise)
        rig.fills = 4
        rig.run(10_500, 12_000, 500, *cruise)
        rig.run(12_500, 30_000, 500, *cruise)
        val events = rig.feed.unfinished()!!.events
        assertEquals(1, events.count { it.kind == DriveEventKind.REFUEL })
    }

    @Test
    fun aFaultThatAppearsDuringTheDriveIsFlaggedOnceAndOnesThereBeforeAreNot() {
        val rig = Rig()
        rig.faults = setOf("P0100")
        rig.run(1_000, 5_000, 500, DriveTruthFeed.SPEED to 0.0, DriveTruthFeed.RPM to 800.0)
        rig.run(5_500, 10_000, 500, *cruise)
        assertTrue(rig.feed.unfinished()!!.events.none { it.kind == DriveEventKind.FAULT_APPEARED }, "P0100 was there before the drive")
        rig.faults = setOf("P0100", "P0300")
        rig.run(10_500, 20_000, 500, *cruise)
        val flagged = rig.feed.unfinished()!!.events.filter { it.kind == DriveEventKind.FAULT_APPEARED }
        assertEquals(listOf("P0300"), flagged.map { it.detail })
    }

    @Test
    fun faultsThatWereNeverReadCannotBeCalledNew() {
        val rig = Rig()
        rig.faults = null
        rig.run(1_000, 10_000, 500, *cruise)
        rig.faults = setOf("P0100")
        rig.run(10_500, 20_000, 500, *cruise)
        assertTrue(rig.feed.unfinished()!!.events.none { it.kind == DriveEventKind.FAULT_APPEARED })
    }

    @Test
    fun theGpsFixesOfThePhoneAreFedOnTheSessionClock() {
        val rig = Rig()
        var t = 1_000L
        var lat = 12.9
        while (t <= 12_000) {
            rig.read(t, DriveTruthFeed.SPEED, 36.0)
            rig.read(t, DriveTruthFeed.RPM, 2000.0)
            if (t % 1_000L == 0L) {
                // 0.00009 degrees of latitude is about 10 metres: a fix a second at 36 km/h.
                rig.feed.addGps(t, GpsFix(lat, 77.5, 10.0, 5.0))
                lat += 0.00009
            }
            rig.tick(t)
            t += 500
        }
        val report = rig.feed.unfinished()!!
        assertEquals(0.1, report.gpsDistanceKm!!, 0.02)
    }

    @Test
    fun withoutGoodFixesThereIsNoGpsDistance() {
        val rig = Rig()
        rig.run(1_000, 20_000, 500, *cruise)
        assertNull(rig.feed.unfinished()!!.gpsDistanceKm)
        assertTrue(DriveTruthNote.GPS_POOR in rig.feed.unfinished()!!.notes)
    }

    @Test
    fun thePriceAtTheStartOfTheDriveIsTheOneUsed() {
        val rig = Rig(price = 100.0)
        rig.run(1_000, 31_000, 500, *cruise, DriveTruthFeed.FUEL_RATE_LPH to 6.0)
        rig.price = 200.0
        rig.run(31_500, 61_000, 500, *cruise, DriveTruthFeed.FUEL_RATE_LPH to 6.0)
        val report = rig.feed.unfinished()!!
        assertEquals(report.fuelLitres!! * 100.0, report.costRupees!!, 0.01)
        val noPrice = Rig(price = null)
        noPrice.run(1_000, 31_000, 500, *cruise, DriveTruthFeed.FUEL_RATE_LPH to 6.0)
        assertNull(noPrice.feed.unfinished()!!.costRupees)
        assertTrue(DriveTruthNote.FUEL_PRICE_MISSING in noPrice.feed.unfinished()!!.notes)
    }

    @Test
    fun onlyAPetrolProfileGivesThePetrolRule() {
        assertEquals(ReducerFuelType.PETROL, DriveTruthFeed.reducerFuel(FuelType.PETROL))
        for (other in listOf(FuelType.DIESEL, FuelType.CNG, FuelType.HYBRID, null)) assertEquals(ReducerFuelType.DIESEL, DriveTruthFeed.reducerFuel(other))
    }

    @Test
    fun nothingIsUnfinishedBeforeADriveStarts() {
        assertNull(Rig().feed.unfinished())
    }
}
