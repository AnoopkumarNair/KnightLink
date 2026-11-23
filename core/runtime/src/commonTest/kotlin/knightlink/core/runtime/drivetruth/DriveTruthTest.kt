package knightlink.core.runtime.drivetruth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveTruthTest {
    private val diesel = DriveTruthConfig(FuelType.DIESEL, pricePerLitre = 100.0, tankLitres = 50.0)
    private val petrol = DriveTruthConfig(FuelType.PETROL, pricePerLitre = 100.0, tankLitres = 50.0)

    /** Feeds [seconds] seconds of samples every [stepMs], built by [make] from the time in ms since [from]. */
    private fun DriveTruth.feed(from: Long, seconds: Int, stepMs: Long = 500, make: (Long) -> DriveSample) {
        var t = 0L
        while (t <= seconds * 1000L) {
            add(make(from + t))
            t += stepMs
        }
    }

    private fun cruise(t: Long, kmh: Double = 72.0, extra: DriveSample.() -> DriveSample = { this }) =
        DriveSample(t, speedKmh = kmh, rpm = 2000.0).extra()

    @Test
    fun carDistanceIsTheTrapezoidOfSpeed() {
        val d = DriveTruth(diesel)
        d.feed(0, 100) { cruise(it) } // 72 km/h for 100 s = 2.0 km
        assertEquals(2.0, d.report().carDistanceKm!!, 1e-9)
    }

    @Test
    fun trapezoidInterpolatesARamp() {
        val d = DriveTruth(diesel)
        d.add(DriveSample(0, speedKmh = 0.0, rpm = 900.0))
        d.add(DriveSample(2000, speedKmh = 36.0, rpm = 1500.0))
        assertEquals(0.01, d.report().carDistanceKm!!, 1e-9) // mean 18 km/h for 2 s
    }

    @Test
    fun aSmallGapIsNotIntegratedAndIsCounted() {
        val d = DriveTruth(diesel)
        d.feed(0, 100) { cruise(it) }
        // 5 s of silence, then 95 more seconds.
        d.feed(105_000, 95) { cruise(it) }
        val r = d.report()
        // 195 s span, 5 s gap = 2.6 percent: still reported, short by the 5 s, with the note.
        assertEquals(72.0 / 3600 * 195, r.carDistanceKm!!, 1e-6)
        assertTrue(DriveTruthNote.SPEED_SIGNAL_GAPS in r.notes)
    }

    @Test
    fun manyGapsMeanNoCarDistance() {
        val d = DriveTruth(diesel)
        d.feed(0, 60) { cruise(it) }
        d.feed(90_000, 60) { cruise(it) } // 30 s gap in 150 s: 20 percent
        val r = d.report()
        assertNull(r.carDistanceKm)
        assertTrue(DriveTruthNote.SPEED_SIGNAL_GAPS in r.notes)
    }

    @Test
    fun gapsWhileTheEngineWasOffDoNotCount() {
        val d = DriveTruth(diesel)
        d.add(DriveSample(0, speedKmh = 0.0, rpm = 0.0))
        d.feed(1_000, 60) { cruise(it) }
        d.add(DriveSample(62_000, speedKmh = 0.0, rpm = 0.0))
        d.add(DriveSample(600_000, speedKmh = 0.0, rpm = 0.0)) // adapter was away, engine off
        assertFalse(DriveTruthNote.SPEED_SIGNAL_GAPS in d.report().notes)
        assertNotNull(d.report().carDistanceKm)
    }

    @Test
    fun gpsDistanceUsesGoodFixesAndIgnoresJumps() {
        val d = DriveTruth(diesel)
        // Heading north at 0.0001 deg per second (~11.1 m/s), 60 fixes, one wild jump and one inaccurate fix in the middle.
        for (i in 0..60) {
            val lat = 12.0 + i * 0.0001
            val fix = when (i) {
                30 -> GpsFix(lat + 1.0, 77.0, 10.0, 5.0) // 111 km in a second
                31 -> GpsFix(lat + 0.5, 77.0, 10.0, 80.0) // inaccurate
                else -> GpsFix(lat, 77.0, 11.1, 5.0)
            }
            d.add(DriveSample(i * 1000L, gps = fix))
        }
        // 0.006 deg of latitude = 0.667 km; the two bad fixes add nothing.
        assertEquals(0.667, d.report().gpsDistanceKm!!, 0.005)
    }

    @Test
    fun gpsNeedsFiveGoodFixes() {
        val d = DriveTruth(diesel)
        for (i in 0..3) d.add(DriveSample(i * 1000L, gps = GpsFix(12.0 + i * 0.001, 77.0, 11.0, 5.0)))
        for (i in 4..9) d.add(DriveSample(i * 1000L, gps = GpsFix(12.0 + i * 0.001, 77.0, 11.0, 50.0)))
        val r = d.report()
        assertNull(r.gpsDistanceKm)
        assertTrue(DriveTruthNote.GPS_POOR in r.notes)
    }

    @Test
    fun standstillGpsDriftAddsNothing() {
        val d = DriveTruth(diesel)
        for (i in 0..600) {
            val wobble = if (i % 2 == 0) 0.00002 else -0.00002 // about 2 m
            d.add(DriveSample(i * 1000L, gps = GpsFix(12.0 + wobble, 77.0, 0.2, 8.0)))
        }
        assertEquals(0.0, d.report().gpsDistanceKm!!, 1e-9)
    }

    private fun longDrive(d: DriveTruth, km: Double) {
        // 72 km/h on a straight north line: 20 m/s, 0.00018 deg per second.
        val seconds = (km / 72.0 * 3600).toInt()
        for (i in 0..seconds) {
            val t = i * 1000L
            d.add(DriveSample(t, speedKmh = 72.0, rpm = 2000.0, gps = GpsFix(12.0 + i * 0.00018, 77.0, 20.0, 4.0)))
        }
    }

    @Test
    fun comparesCarWithGpsOnlyOverTheMinimum() {
        val short = DriveTruth(diesel)
        longDrive(short, 2.0)
        val shortReport = short.report()
        assertNull(shortReport.carVsGpsPercent)
        assertTrue(DriveTruthNote.TOO_SHORT_TO_COMPARE in shortReport.notes)

        val long = DriveTruth(diesel)
        longDrive(long, 4.0)
        val r = long.report()
        // GPS 20 m/s = 72 km/h is exactly what the car says, up to the 111.19 km/deg of the haversine against 0.00018 * 111.195.
        assertNotNull(r.carVsGpsPercent)
        assertEquals(0.0, r.carVsGpsPercent!!, 0.5)
        assertFalse(DriveTruthNote.TOO_SHORT_TO_COMPARE in r.notes)
    }

    @Test
    fun carReadingHighShowsAsPositivePercent() {
        val d = DriveTruth(diesel)
        for (i in 0..200) {
            // The car says 80 km/h, the ground moves at 72 km/h.
            d.add(DriveSample(i * 1000L, speedKmh = 80.0, rpm = 2000.0, gps = GpsFix(12.0 + i * 0.00018, 77.0, 20.0, 4.0)))
        }
        val dReport = d.report()
        assertEquals(DriveTruthReport.MIN_COMPARE_KM, 3.0)
        // 200 s is only 4.4 km by the car at 80 km/h: above the minimum for the car, 4.0 for GPS.
        assertEquals(11.1, dReport.carVsGpsPercent!!, 0.5)
    }

    @Test
    fun fuelRateIntegratesLitresAndPrices() {
        val d = DriveTruth(diesel)
        d.feed(0, 3600) { DriveSample(it, speedKmh = 60.0, rpm = 1800.0, fuelRateLph = 6.0) }
        val r = d.report()
        assertEquals(FuelSource.FUEL_RATE_LPH, r.fuelSource)
        assertEquals(6.0, r.fuelLitres!!, 1e-6)
        assertEquals(600.0, r.costRupees!!, 1e-6)
        assertEquals(10.0, r.kmPerLitre!!, 1e-6)
        assertEquals(10.0, r.costPerKmRupees!!, 1e-6)
        assertFalse(DriveTruthNote.FUEL_DENSITY_ASSUMED in r.notes)
    }

    @Test
    fun fuelRateInGramsPerSecondUsesTheAssumedDensity() {
        val d = DriveTruth(diesel)
        // 1.4 g/s = 5.04 kg/h = 6.0577 L/h at 0.832 kg/L.
        d.feed(0, 3600) { DriveSample(it, speedKmh = 60.0, rpm = 1800.0, fuelRateGps = 1.4) }
        val r = d.report()
        assertEquals(FuelSource.FUEL_RATE_GPS, r.fuelSource)
        assertEquals(1.4 * 3.6 / 0.832, r.fuelLitres!!, 1e-6)
        assertTrue(DriveTruthNote.FUEL_DENSITY_ASSUMED in r.notes)
    }

    @Test
    fun petrolMafBecomesFuelWithStoichiometricAfr() {
        val d = DriveTruth(petrol)
        d.feed(0, 3600) { DriveSample(it, speedKmh = 60.0, rpm = 1800.0, mafGps = 14.7) } // 1 g/s of fuel
        val r = d.report()
        assertEquals(FuelSource.MAF, r.fuelSource)
        assertEquals(3.6 / 0.745, r.fuelLitres!!, 1e-6)
        assertTrue(DriveTruthNote.FUEL_FROM_AIRFLOW in r.notes)
    }

    @Test
    fun dieselNeverUsesAirflow() {
        val d = DriveTruth(diesel)
        d.feed(0, 600) { DriveSample(it, speedKmh = 60.0, rpm = 1800.0, mafGps = 20.0) }
        val r = d.report()
        assertNull(r.fuelLitres)
        assertNull(r.fuelSource)
        assertNull(r.costRupees)
        assertTrue(DriveTruthNote.FUEL_NOT_REPORTED in r.notes)
    }

    @Test
    fun fuelRateBeatsAirflowAndGramsPerSecond() {
        val d = DriveTruth(petrol)
        d.feed(0, 600) { DriveSample(it, speedKmh = 60.0, rpm = 1800.0, fuelRateLph = 5.0, fuelRateGps = 9.0, mafGps = 40.0) }
        assertEquals(FuelSource.FUEL_RATE_LPH, d.report().fuelSource)
    }

    @Test
    fun noPriceMeansNoCostAndANote() {
        val d = DriveTruth(DriveTruthConfig(FuelType.DIESEL))
        d.feed(0, 600) { DriveSample(it, speedKmh = 60.0, rpm = 1800.0, fuelRateLph = 6.0) }
        val r = d.report()
        assertNotNull(r.fuelLitres)
        assertNull(r.costRupees)
        assertNull(r.costPerKmRupees)
        assertNull(r.idleCostRupees)
        assertTrue(DriveTruthNote.FUEL_PRICE_MISSING in r.notes)
    }

    @Test
    fun fuelGapsAboveTenPercentDropTheSource() {
        val d = DriveTruth(diesel)
        d.feed(0, 60) { DriveSample(it, speedKmh = 60.0, rpm = 1800.0, fuelRateLph = 6.0) }
        d.feed(100_000, 60) { DriveSample(it, speedKmh = 60.0, rpm = 1800.0, fuelRateLph = 6.0) }
        val r = d.report()
        assertNull(r.fuelLitres)
        assertTrue(DriveTruthNote.FUEL_NOT_REPORTED in r.notes)
    }

    @Test
    fun levelDropIsTheFallbackWhenTheTankIsKnown() {
        val d = DriveTruth(diesel)
        d.feed(0, 600, stepMs = 1000) { DriveSample(it, speedKmh = 60.0, rpm = 1800.0, fuelLevelPercent = 80.0 - 6.0 * it / 600_000) }
        val r = d.report()
        assertEquals(FuelSource.FUEL_LEVEL_DROP, r.fuelSource)
        assertEquals(3.0, r.fuelLitres!!, 0.01) // 6 points of 50 L
        assertTrue(DriveTruthNote.FUEL_ONLY_APPROXIMATE in r.notes)
    }

    @Test
    fun levelDropIgnoredAfterAnUnreportedRiseAndWithoutTank() {
        val risen = DriveTruth(diesel)
        risen.add(DriveSample(0, speedKmh = 50.0, rpm = 1500.0, fuelLevelPercent = 40.0))
        risen.add(DriveSample(1000, speedKmh = 50.0, rpm = 1500.0, fuelLevelPercent = 30.0))
        risen.add(DriveSample(2000, speedKmh = 50.0, rpm = 1500.0, fuelLevelPercent = 90.0))
        assertNull(risen.report().fuelLitres)

        val noTank = DriveTruth(DriveTruthConfig(FuelType.DIESEL, pricePerLitre = 100.0))
        noTank.add(DriveSample(0, speedKmh = 50.0, rpm = 1500.0, fuelLevelPercent = 80.0))
        noTank.add(DriveSample(1000, speedKmh = 50.0, rpm = 1500.0, fuelLevelPercent = 60.0))
        assertNull(noTank.report().fuelLitres)
    }

    @Test
    fun aReportedRefuelSplitsTheLevelSegments() {
        val d = DriveTruth(diesel)
        d.add(DriveSample(0, speedKmh = 50.0, rpm = 1500.0, fuelLevelPercent = 50.0))
        d.add(DriveSample(1000, speedKmh = 50.0, rpm = 1500.0, fuelLevelPercent = 40.0))
        d.addRefuel(1500)
        d.add(DriveSample(2000, speedKmh = 50.0, rpm = 1500.0, fuelLevelPercent = 100.0))
        d.add(DriveSample(3000, speedKmh = 50.0, rpm = 1500.0, fuelLevelPercent = 95.0))
        val r = d.report()
        assertEquals(7.5, r.fuelLitres!!, 1e-9) // 10 + 5 points of 50 L
        assertTrue(r.events.any { it.kind == DriveEventKind.REFUEL })
    }

    @Test
    fun idleIsStandstillWithTheEngineRunningAndCostsIdleFuel() {
        val d = DriveTruth(diesel)
        d.feed(0, 600) { DriveSample(it, speedKmh = 0.0, rpm = 800.0, fuelRateLph = 1.2) } // 10 min idle
        d.feed(601_000, 600) { DriveSample(it, speedKmh = 60.0, rpm = 1800.0, fuelRateLph = 6.0) }
        val r = d.report()
        assertEquals(10.0, r.idleMinutes, 0.05)
        assertEquals(10.0, r.movingMinutes, 0.1)
        assertEquals(1.2 / 6.0 * 100.0, r.idleCostRupees!!, 0.5) // 1.2 L/h for 10 min = 0.2 L = 20 rupees
    }

    @Test
    fun longIdleEventAfterTwoMinutes() {
        val d = DriveTruth(diesel)
        d.feed(0, 119) { DriveSample(it, speedKmh = 0.0, rpm = 800.0) }
        assertTrue(d.report().events.none { it.kind == DriveEventKind.LONG_IDLE })
        d.feed(119_500, 5) { DriveSample(it, speedKmh = 0.0, rpm = 800.0) }
        val events = d.report().events.filter { it.kind == DriveEventKind.LONG_IDLE }
        assertEquals(1, events.size)
        assertEquals(0.0, events.single().atSeconds, 1e-9)
    }

    @Test
    fun coldHardRevsOncePerWindow() {
        val d = DriveTruth(diesel)
        d.add(DriveSample(0, speedKmh = 20.0, rpm = 1500.0, coolantC = 40.0))
        d.feed(1_000, 100) { DriveSample(it, speedKmh = 50.0, rpm = 3500.0) }
        val cold = d.report().events.filter { it.kind == DriveEventKind.COLD_HARD_REVS }
        assertEquals(2, cold.size) // at 1 s and again 60 s later
        val warm = DriveTruth(diesel)
        warm.add(DriveSample(0, coolantC = 90.0))
        warm.add(DriveSample(1000, rpm = 3500.0))
        assertTrue(warm.report().events.isEmpty())
    }

    @Test
    fun coldHardThrottleCounts() {
        val d = DriveTruth(diesel)
        d.add(DriveSample(0, coolantC = 30.0))
        d.add(DriveSample(1000, speedKmh = 30.0, rpm = 2500.0, throttlePercent = 80.0))
        assertEquals(1, d.report().events.count { it.kind == DriveEventKind.COLD_HARD_REVS })
    }

    @Test
    fun callerEventsAreKeptInTimeOrder() {
        val d = DriveTruth(diesel)
        d.add(DriveSample(0, speedKmh = 10.0, rpm = 1500.0))
        d.addFaultAppeared(5_000, "P0420")
        d.addRefuel(2_000)
        val kinds = d.report().events.map { it.kind }
        assertEquals(listOf(DriveEventKind.REFUEL, DriveEventKind.FAULT_APPEARED), kinds)
        assertEquals("P0420", d.report().events.last().detail)
    }

    @Test
    fun aStopWithTheEngineRunningNeverEndsTheDrive() {
        val d = DriveTruth(diesel)
        d.feed(0, 30) { cruise(it) }
        d.feed(30_500, 600) { DriveSample(it, speedKmh = 0.0, rpm = 800.0) } // ten minutes at a red light, engine on
        assertFalse(d.isDriveEnded())
        assertTrue(DriveTruthNote.DRIVE_NOT_ENDED in d.report().notes)
    }

    @Test
    fun anIdleStopOfAFewSecondsDoesNotEndTheDrive() {
        val d = DriveTruth(diesel)
        d.feed(0, 30) { cruise(it) }
        d.feed(30_500, 20) { DriveSample(it, speedKmh = 0.0, rpm = 0.0) } // idle-stop: engine off for 20 s
        assertFalse(d.isDriveEnded())
        d.feed(50_500, 5) { cruise(it) }
        assertFalse(d.isDriveEnded())
        d.feed(56_000, 100) { DriveSample(it, speedKmh = 0.0, rpm = 0.0) }
        assertTrue(d.isDriveEnded())
    }

    @Test
    fun theDriveEndsAfterSixtySecondsOffAndTheReportDropsThatWait() {
        val d = DriveTruth(diesel)
        d.feed(0, 60) { cruise(it) }
        d.feed(60_500, 59) { DriveSample(it, speedKmh = 0.0, rpm = 0.0) }
        assertFalse(d.isDriveEnded())
        d.add(DriveSample(121_000, speedKmh = 0.0, rpm = 0.0))
        assertTrue(d.isDriveEnded())
        val r = d.report()
        assertFalse(DriveTruthNote.DRIVE_NOT_ENDED in r.notes)
        assertEquals(1.0, r.durationMinutes, 0.05) // the minute of waiting is not part of the drive
    }

    @Test
    fun silenceAfterKeyOffEndsTheDriveWhenTheCallerPassesTheTime() {
        val d = DriveTruth(diesel)
        d.feed(0, 30) { cruise(it) }
        d.add(DriveSample(31_000, speedKmh = 0.0, rpm = 0.0))
        assertFalse(d.isDriveEnded())
        assertTrue(d.isDriveEnded(nowMs = 100_000))
    }

    @Test
    fun anAdapterThatGoesQuietWhileTheEngineRanDoesNotEndTheDrive() {
        val d = DriveTruth(diesel)
        d.feed(0, 30) { cruise(it) }
        assertFalse(d.isDriveEnded(nowMs = 1_000_000))
    }

    @Test
    fun asleepEndsTheDrive() {
        val d = DriveTruth(diesel)
        d.feed(0, 30) { cruise(it) }
        d.markSessionAsleep()
        assertTrue(d.isDriveEnded())
    }

    @Test
    fun emptyReportIsHonest() {
        val r = DriveTruth(diesel).report()
        assertNull(r.carDistanceKm)
        assertNull(r.gpsDistanceKm)
        assertNull(r.fuelLitres)
        assertEquals(0.0, r.durationMinutes)
    }

    @Test
    fun outOfOrderSamplesAreIgnored() {
        val d = DriveTruth(diesel)
        d.add(DriveSample(10_000, speedKmh = 36.0, rpm = 1500.0))
        d.add(DriveSample(5_000, speedKmh = 200.0, rpm = 1500.0))
        d.add(DriveSample(11_000, speedKmh = 36.0, rpm = 1500.0))
        assertEquals(0.01, d.report().carDistanceKm!!, 1e-9)
    }
}
