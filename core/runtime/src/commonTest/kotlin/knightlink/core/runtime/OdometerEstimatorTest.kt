package knightlink.core.runtime

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OdometerEstimatorTest {
    private fun near(expected: Double, actual: Double, tolerance: Double = 1e-6) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")

    @Test
    fun aSteadySixtyForAnHourIsSixtyKilometres() {
        val e = OdometerEstimator()
        for (s in 0..3600) e.addSpeed(60.0, s * 1000L)
        near(60.0, e.distanceKm)
        near(60.0, e.speedKm)
        near(85_624.0, e.odometerKm(85_564.0)!!)
    }

    @Test
    fun aRampIsIntegratedAsTrapezoids() {
        val e = OdometerEstimator()
        // 0 to 100 km/h over 100 s, a sample a second: the average is 50 km/h, 100 s of it is 1.3889 km.
        for (s in 0..100) e.addSpeed(s.toDouble(), s * 1000L)
        near(50.0 * 100 / 3600, e.distanceKm, 1e-9)
    }

    @Test
    fun stopAndGoWithIrregularSamplesStaysExact() {
        val e = OdometerEstimator()
        var t = 0L
        var expected = 0.0
        var last = 0.0
        val profile = listOf(0.0, 20.0, 40.0, 40.0, 0.0, 0.0, 30.0, 55.0, 55.0, 10.0, 0.0)
        for ((i, v) in profile.withIndex()) {
            val dt = 300L + (i % 3) * 700L
            if (i > 0) expected += (last + v) / 2 * dt / 3_600_000.0
            t += if (i > 0) dt else 0
            e.addSpeed(v, t)
            last = v
        }
        near(expected, e.distanceKm, 1e-12)
    }

    @Test
    fun invalidSpeedsAndTimeGoingBackAreIgnored() {
        val e = OdometerEstimator()
        e.addSpeed(50.0, 0)
        e.addSpeed(255.0, 1000)
        e.addSpeed(Double.NaN, 1000)
        e.addSpeed(-3.0, 1000)
        e.addSpeed(Double.POSITIVE_INFINITY, 1000)
        e.addSpeed(50.0, 1000)
        e.addSpeed(50.0, 500) // backwards
        near(50.0 / 3600, e.distanceKm)
        assertTrue(e.distanceKm.isFinite())
    }

    @Test
    fun noSamplesMeansNoDistanceAndNoStartMeansNoEstimate() {
        val e = OdometerEstimator()
        assertEquals(0.0, e.distanceKm)
        assertNull(e.odometerKm(null))
        assertNull(e.odometerKm(Double.NaN))
        assertNull(e.odometerKm(-1.0))
        assertEquals(1000.0, e.odometerKm(1000.0))
    }

    @Test
    fun aGapInTheSpeedIsFilledFromTheGps() {
        val e = OdometerEstimator()
        e.addSpeed(36.0, 0)
        // 60 s without a speed, the GPS moves about 0.6 km north meanwhile (fixes a second apart).
        for (s in 0..60) e.addGps(12.0 + s * 0.0000899, 77.0, 10.0, s * 1000L)
        e.addSpeed(36.0, 60_000)
        near(0.6, e.distanceKm, 0.01)
        assertEquals(0.0, e.speedKm, "the speed alone cannot join a gap this long")
        near(0.6, e.gpsKm, 0.01)
    }

    @Test
    fun aGpsJumpAnInaccurateFixAndAStandingCarsJitterDoNotCount() {
        val e = OdometerEstimator()
        e.addGps(12.0, 77.0, 5.0, 0)
        e.addGps(13.0, 77.0, 5.0, 1000) // 111 km in a second: a jump
        e.addGps(12.5, 77.5, 500.0, 2000) // inaccurate
        e.addGps(12.000001, 77.0, 5.0, 3000) // 0.1 m: jitter
        assertEquals(0.0, e.gpsKm)
    }

    @Test
    fun theScaleIsCalibratedFromStartAndEndAndRefusedWhenItMakesNoSense() {
        near(1.02, OdometerEstimator.calibrate(1000.0, 1051.0, 50.0)!!)
        assertNull(OdometerEstimator.calibrate(1000.0, 1000.0, 50.0), "no distance on the dashboard")
        assertNull(OdometerEstimator.calibrate(1000.0, 990.0, 50.0), "end below start")
        assertNull(OdometerEstimator.calibrate(1000.0, 1003.0, 2.0), "too short to tell")
        assertNull(OdometerEstimator.calibrate(1000.0, 1100.0, 50.0), "a factor of two is a typo")
        assertNull(OdometerEstimator.calibrate(null, 1100.0, 50.0))
        assertNull(OdometerEstimator.calibrate(1000.0, 1051.0, Double.NaN))
        val e = OdometerEstimator()
        for (s in 0..3600) e.addSpeed(50.0, s * 1000L)
        near(1051.0, e.odometerKm(1000.0, 1.02)!!)
        near(1050.0, e.odometerKm(1000.0, Double.NaN)!!, 1e-9)
        near(1050.0, e.odometerKm(1000.0, 3.0)!!, 1e-9)
    }

    @Test
    fun motionWatchConfirmsMovementAndCountsStandingTime() {
        val m = MotionWatch(5)
        val age = 3.seconds
        m.update(0.0, 0.seconds)
        m.update(0.0, 1.seconds)
        assertEquals(2.seconds, m.stillFor(2.seconds, age))
        m.update(20.0, 3.seconds)
        assertTrue(!m.moving(3.seconds, age), "one reading is not confirmed")
        m.update(20.0, 4.seconds)
        assertTrue(m.moving(4.seconds, age))
        m.update(3.0, 5.seconds)
        assertTrue(m.moving(5.seconds, age), "rolling slowly after moving still counts as moving")
        assertNull(m.stillFor(5.seconds, age))
        assertTrue(!m.moving(9.seconds, age), "a stale reading says nothing")
        m.update(0.0, 10.seconds)
        assertTrue(!m.moving(10.seconds, age))
        assertNull(m.stillFor(20.seconds, age), "stale")
    }
}
