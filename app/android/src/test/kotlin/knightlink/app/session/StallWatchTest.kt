package knightlink.app.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StallWatchTest {
    @Test
    fun onTimeTicksAndTheFirstTickSayNothing() {
        val watch = StallWatch()
        assertNull(watch.tick(1_000, 1_000))
        assertNull(watch.tick(6_000, 6_000))
        assertNull(watch.tick(11_100, 11_100))
        // A little late is normal: the threshold is 10 s over the expected 5.
        assertNull(watch.tick(24_000, 24_000))
    }

    @Test
    fun aSuspendedProcessorIsToldFromAFrozenProcess() {
        val sleeping = StallWatch()
        sleeping.tick(0, 0)
        // 250 s passed, the processor ran 2 s of them.
        val slept = assertNotNull(sleeping.tick(250_000, 2_000))
        assertTrue(slept.processorSlept)
        assertEquals(248_000, slept.suspendedMs)
        assertTrue(slept.describe().contains("suspended for 248 s"), slept.describe())

        val frozen = StallWatch()
        frozen.tick(0, 0)
        // 250 s passed and the processor ran all of them: the process did not.
        val stalled = assertNotNull(frozen.tick(250_000, 249_500))
        assertFalse(stalled.processorSlept)
        assertTrue(stalled.describe().contains("frozen or starved"), stalled.describe())
    }

    @Test
    fun aStallIsReportedOnceAndTheNextTickIsNormalAgain() {
        val watch = StallWatch()
        watch.tick(0, 0)
        assertNotNull(watch.tick(100_000, 1_000))
        assertNull(watch.tick(105_000, 6_000))
    }

    @Test
    fun aClockThatMovesOddlyNeverMakesANegativeSleep() {
        val watch = StallWatch()
        watch.tick(0, 10_000)
        val stall = assertNotNull(watch.tick(60_000, 5_000))
        assertEquals(60_000, stall.suspendedMs)
    }
}
