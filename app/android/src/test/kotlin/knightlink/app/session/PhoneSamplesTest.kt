package knightlink.app.session

import knightlink.core.recorder.ExternalSamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PhoneSamplesTest {
    @Test
    fun aBatchIsWrittenOnceItsFirstSampleIsHalfASecondOld() {
        val batcher = MotionBatcher(maxAgeMs = 500)
        // 25 Hz: a sample every 40 ms from t = 1000.
        val lines = ArrayList<String>()
        for (i in 0 until 30) {
            val time = 1000L + i * 40
            batcher.acceleration(time, 0.1 * i, 0.0, 9.8, nowMs = time)?.let { lines += it }
        }
        assertEquals(2, lines.size, "30 samples over 1.2 s: two lines of 14 samples, and two samples still waiting")
        val first = assertNotNull(ExternalSamples.parseMotion(1000L + 13 * 40, lines[0]))
        assertEquals(14, first.size)
        assertEquals(1000L, first.first().timeMs)
        assertEquals(1000L + 13 * 40, first.last().timeMs)
        assertEquals(0.1 * 13, first.last().ax, 0.006)
    }

    @Test
    fun readingsAreAveragedIntoOneSamplePerBin() {
        val batcher = MotionBatcher(maxAgeMs = 1000, binMs = 100)
        val lines = ArrayList<Pair<Long, String>>()
        // 100 Hz for 2.5 s: a reading every 10 ms, acceleration x rising 0.01 per reading.
        for (i in 0 until 250) {
            val time = 1000L + i * 10
            batcher.acceleration(time, 0.01 * i, 0.0, 9.8, nowMs = time)?.let { lines += time to it }
        }
        batcher.flush(3500)?.let { lines += 3500L to it }
        val samples = lines.flatMap { (at, line) -> ExternalSamples.parseMotion(at, line).orEmpty() }
        assertEquals(25, samples.size, "250 readings over 2.5 s are 25 bins of 100 ms")
        assertEquals(0.045, samples.first().ax, 0.006)
        assertEquals(1045L, samples.first().timeMs, "the bin's mean time")
        assertEquals(9.8, samples.first().az, 0.006)
        assertEquals(true, lines.size in 2..3, "about a second of samples to a line: ${lines.size} lines")
    }

    @Test
    fun theRotationOfEachReadingIsAveragedToo() {
        val batcher = MotionBatcher(maxAgeMs = 10_000, binMs = 100)
        batcher.rotation(0.0, 0.0, 1.0)
        batcher.acceleration(0, 0.0, 0.0, 0.0, nowMs = 0)
        batcher.rotation(0.0, 0.0, 3.0)
        batcher.acceleration(50, 0.0, 0.0, 0.0, nowMs = 50)
        val sample = assertNotNull(ExternalSamples.parseMotion(50, assertNotNull(batcher.flush(50)))).single()
        assertEquals(2.0, sample.gz, 0.0015)
    }

    @Test
    fun aBatchThatArrivesLateKeepsEverySamplesOwnAge() {
        // The sensor hub hands over readings a second late: each is old when the line is made, and the line says so.
        val batcher = MotionBatcher(maxAgeMs = 500)
        val line = assertNotNull(batcher.acceleration(1000, 1.0, 2.0, 3.0, nowMs = 2000))
        // The event is stamped when it is written (2000 ms): the sample is 1000 ms before it.
        assertEquals("mot 1000 1.00 2.00 3.00 0.000 0.000 0.000", line)
        assertEquals(1000L, assertNotNull(ExternalSamples.parseMotion(2000, line)).single().timeMs)
    }

    @Test
    fun aRotationReadingIsPairedWithTheAccelerationsThatFollowIt() {
        val batcher = MotionBatcher(maxAgeMs = 10_000)
        batcher.acceleration(0, 1.0, 0.0, 0.0, nowMs = 0)
        batcher.rotation(0.5, -0.25, 0.125)
        batcher.acceleration(40, 2.0, 0.0, 0.0, nowMs = 40)
        val samples = assertNotNull(ExternalSamples.parseMotion(40, batcher.flush(40)!!))
        assertEquals(listOf(0.0, 0.5), samples.map { it.gx })
        assertEquals(-0.25, samples[1].gy, 1e-9)
        assertEquals(0.125, samples[1].gz, 1e-9)
    }

    @Test
    fun aBatchIsNeverLargerThanTheLimitEvenWhenNoTimePasses() {
        val batcher = MotionBatcher(maxAgeMs = 10_000, maxSamples = 5)
        val lines = (0 until 12).mapNotNull { batcher.acceleration(it.toLong(), 0.0, 0.0, 0.0, nowMs = it.toLong()) }
        assertEquals(2, lines.size)
        assertEquals(5, assertNotNull(ExternalSamples.parseMotion(5, lines[0])).size)
        assertEquals(2, assertNotNull(ExternalSamples.parseMotion(11, batcher.flush(11)!!)).size, "the rest is written by the flush at the end")
    }

    @Test
    fun anEmptyBatchWritesNothing() {
        assertNull(MotionBatcher().flush(1000))
    }

    @Test
    fun aClockThatStepsBackDoesNotMakeANegativeAge() {
        val batcher = MotionBatcher(maxAgeMs = 10_000)
        batcher.acceleration(5000, 0.0, 0.0, 0.0, nowMs = 5000)
        val samples = assertNotNull(ExternalSamples.parseMotion(5000, batcher.flush(nowMs = 4000)!!))
        assertEquals(5000L, samples.single().timeMs)
    }

    @Test
    fun aFixIsWrittenInKilometresAnHourAndWithoutWhatThePhoneDidNotGive() {
        assertEquals("gps 12.34568 45.67890 36.0 270 5", gpsLineOf(0, 12.34568, 45.6789, 10f, 270f, 5f))
        assertEquals("gps 12.34568 45.67890 - - -", gpsLineOf(0, 12.34568, 45.6789, null, null, null))
    }
}
