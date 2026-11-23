package knightlink.app.session

import knightlink.core.runtime.drivetruth.DriveTruthNote
import knightlink.core.runtime.drivetruth.DriveTruthReport
import knightlink.core.runtime.drivetruth.FuelSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriveTruthCardModelTest {
    private val empty = DriveTruthReport(
        durationMinutes = 30.0,
        movingMinutes = 25.0,
        idleMinutes = 0.0,
        carDistanceKm = null,
        gpsDistanceKm = null,
        carVsGpsPercent = null,
        fuelLitres = null,
        fuelSource = null,
        costRupees = null,
        costPerKmRupees = null,
        idleCostRupees = null,
        kmPerLitre = null,
        notes = emptyList(),
        events = emptyList(),
    )

    @Test
    fun aReportWithNoFiguresShowsOnlyTheTimeAndItsNotes() {
        val card = driveTruthCard(empty.copy(notes = listOf(DriveTruthNote.FUEL_NOT_REPORTED, DriveTruthNote.FUEL_NOT_REPORTED, DriveTruthNote.GPS_POOR)))
        assertEquals(listOf<DriveTruthLine>(DriveTruthLine.Time(30.0, 25.0)), card.lines)
        assertEquals(listOf(DriveTruthNote.FUEL_NOT_REPORTED, DriveTruthNote.GPS_POOR), card.notes, "a note is said once")
    }

    @Test
    fun theDemoReportReadsAsRupeesForKilometresWithItsMethod() {
        val card = driveTruthCard(DemoDrive.report)
        val cost = card.lines.filterIsInstance<DriveTruthLine.Cost>().single()
        assertEquals(DriveTruthLine.Cost(142.0, 24.8, 5.7), cost)
        assertEquals("₹142", formatRupees(cost.rupees))
        assertEquals("₹5.7", formatRupees(cost.perKm!!))
        assertEquals("24.8", formatKm(cost.km!!))
        val method = card.lines.filterIsInstance<DriveTruthLine.Method>().single()
        assertEquals(FuelSource.FUEL_RATE_LPH, method.source)
        assertTrue(card.lines.indexOf(method) > card.lines.indexOf(cost), "the method sits under the figures it explains")
    }

    @Test
    fun moneyAppearsOnlyWhenTheReportHasMoney() {
        val litresOnly = driveTruthCard(empty.copy(fuelLitres = 2.0, fuelSource = FuelSource.MAF, notes = listOf(DriveTruthNote.FUEL_PRICE_MISSING)))
        assertTrue(litresOnly.lines.none { it is DriveTruthLine.Cost })
        assertTrue(litresOnly.lines.any { it is DriveTruthLine.Fuel })
        assertTrue(litresOnly.lines.any { it is DriveTruthLine.Method })
        assertEquals(listOf(DriveTruthNote.FUEL_PRICE_MISSING), litresOnly.notes)
    }

    @Test
    fun noMethodLineWithoutAnyFuelFigure() {
        val card = driveTruthCard(empty.copy(fuelSource = FuelSource.MAF))
        assertTrue(card.lines.none { it is DriveTruthLine.Method })
    }

    @Test
    fun theCarVersusGpsLineIsThereOnlyWithAComparisonAndKeepsItsSign() {
        assertTrue(driveTruthCard(empty).lines.none { it is DriveTruthLine.CarVsGps })
        val card = driveTruthCard(empty.copy(carDistanceKm = 10.0, gpsDistanceKm = 9.8, carVsGpsPercent = -2.04))
        assertEquals(DriveTruthLine.CarVsGps(-2.04), card.lines.filterIsInstance<DriveTruthLine.CarVsGps>().single())
        assertEquals("-2.0 %", formatSignedPercent(-2.04))
        assertEquals("+2.1 %", formatSignedPercent(2.1))
    }

    @Test
    fun distanceFallsBackToWhicheverSourceExistsAndCostUsesIt() {
        val gpsOnly = driveTruthCard(empty.copy(gpsDistanceKm = 12.0, costRupees = 60.0, costPerKmRupees = 5.0))
        assertEquals(DriveTruthLine.Cost(60.0, 12.0, 5.0), gpsOnly.lines.filterIsInstance<DriveTruthLine.Cost>().single())
        assertEquals(DriveTruthLine.Distance(null, 12.0), gpsOnly.lines.filterIsInstance<DriveTruthLine.Distance>().single())
    }

    @Test
    fun idleShowsOnlyWhenThereWasIdling() {
        assertFalse(driveTruthCard(empty).lines.any { it is DriveTruthLine.Idle })
        val idle = driveTruthCard(empty.copy(idleMinutes = 7.0, idleCostRupees = 12.0))
        assertEquals(DriveTruthLine.Idle(7.0, 12.0), idle.lines.filterIsInstance<DriveTruthLine.Idle>().single())
    }

    @Test
    fun numbersAreFormattedTheSameOnEveryPhone() {
        assertEquals("₹5.7", formatRupees(5.73))
        assertEquals("₹142", formatRupees(141.6))
        assertEquals("42 min", formatMinutes(41.6))
        assertEquals("1 h 5 min", formatMinutes(65.0))
        assertEquals("0 min", formatMinutes(-3.0))
    }
}
