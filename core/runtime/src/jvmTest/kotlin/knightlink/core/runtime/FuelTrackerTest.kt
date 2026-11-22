package knightlink.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelTrackerTest {
    private fun feed(tracker: FuelTracker, vararg levels: Double): List<FuelFill> = levels.toList().mapIndexedNotNull { i, level ->
        tracker.update(
            level,
            i * 1000L,
        )
    }

    @Test
    fun aRefuelThatHappenedWhileTheCarWasOffIsFoundAtTheNextSession() {
        // 2026-11-22: 22.7 % when the car was switched off, 100 % when it was next read (38.16 litres, a 50 litre tank).
        val tracker = FuelTracker(FuelMemory(lastPercent = 22.7))
        val fills = feed(tracker, 100.0, 100.0, 100.0)

        val fill = assertNotNull(fills.singleOrNull())
        assertEquals(22.7, fill.fromPercent, 0.001)
        assertEquals(100.0, fill.toPercent, 0.001)
        assertEquals(38.65, fill.litres(50.0), 0.01)
    }

    @Test
    fun sloshAndOneWildReadingAreNotARefuel() {
        val tracker = FuelTracker(FuelMemory(lastPercent = 30.0))
        val fills = feed(tracker, 31.0, 33.0, 29.5, 36.0, 30.0, 32.0, 29.0, 31.0)
        assertTrue(fills.isEmpty(), "found $fills")
    }

    @Test
    fun theLevelFallingWhileDrivingMovesTheBaselineDownSoALaterRefuelIsMeasuredFromTheLowerLevel() {
        val tracker = FuelTracker(FuelMemory(lastPercent = 46.0))
        feed(tracker, 45.0, 40.0, 35.0, 30.0, 25.0)
        val fill = feed(tracker, 80.0, 80.5, 79.5).single()
        assertEquals(25.0, fill.fromPercent, 0.001)
    }

    @Test
    fun aRefuelInTheMiddleOfASessionIsFoundOnceAndTheNewLevelBecomesTheBaseline() {
        val tracker = FuelTracker(FuelMemory(lastPercent = 20.0))
        assertEquals(1, feed(tracker, 20.0, 60.0, 60.0, 60.0, 59.5, 59.0).size)
        assertEquals(59.0, tracker.model.lastPercent!!, 5.0)
        assertEquals(1, tracker.model.fills.size)
    }

    @Test
    fun withNoEarlierLevelTheFirstReadingIsTheBaselineAndNothingIsFound() {
        val tracker = FuelTracker()
        assertNull(tracker.update(55.0, 0))
        assertEquals(55.0, tracker.model.lastPercent)
    }

    @Test
    fun readingsOutsideZeroToOneHundredAreIgnored() {
        val tracker = FuelTracker(FuelMemory(lastPercent = 40.0))
        assertTrue(feed(tracker, 255.0, -3.0, Double.NaN).isEmpty())
        assertEquals(40.0, tracker.model.lastPercent)
    }
}
