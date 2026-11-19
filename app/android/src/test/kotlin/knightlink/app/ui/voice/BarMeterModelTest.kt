package knightlink.app.ui.voice

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BarMeterModelTest {
    private fun lit(level: Float, count: Int) = (0 until count).count { BarMeterModel.isLit(abs(it - count / 2), level, count) }

    @Test
    fun silenceLightsNothingAndFullLevelLightsEverything() {
        assertEquals(0, lit(0f, 15))
        assertEquals(15, lit(1f, 15))
        assertEquals(11, lit(1f, 11))
        assertEquals(-1, BarMeterModel.litRadius(0f, 15))
        assertEquals(7, BarMeterModel.litRadius(1f, 15))
    }

    @Test
    fun fillGrowsSymmetricallyAndMonotonically() {
        var previous = 0
        for (i in 0..100) {
            val n = lit(i / 100f, 15)
            assertTrue(n >= previous, "level ${i / 100f} lit $n after $previous")
            assertTrue(n == 0 || n % 2 == 1, "level ${i / 100f} lit $n")
            previous = n
        }
        assertEquals(15, previous)
    }

    @Test
    fun outOfRangeAndNanLevelsAreClamped() {
        assertEquals(15, lit(5f, 15))
        assertEquals(0, lit(-1f, 15))
        assertEquals(0, lit(Float.NaN, 15))
    }

    @Test
    fun rampRunsFromTheMiddleToTheEnds() {
        assertEquals(0f, BarMeterModel.rampPosition(0, 15))
        assertEquals(1f, BarMeterModel.rampPosition(7, 15))
        assertEquals(1f, BarMeterModel.rampPosition(5, 11))
        assertTrue(BarMeterModel.rampPosition(3, 15) in 0.3f..0.5f)
    }

    @Test
    fun colourBlendHitsItsEndpoints() {
        assertEquals(0xFF102030.toInt(), BarMeterModel.lerpColor(0xFF102030.toInt(), 0xFFFFB000.toInt(), 0f))
        assertEquals(0xFFFFB000.toInt(), BarMeterModel.lerpColor(0xFF102030.toInt(), 0xFFFFB000.toInt(), 1f))
        assertEquals(0xFF808080.toInt(), BarMeterModel.lerpColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }

    @Test
    fun smoothingRisesFasterThanItFalls() {
        val up = BarMeterModel.smooth(0f, 1f, 30f)
        val down = 1f - BarMeterModel.smooth(1f, 0f, 30f)
        assertTrue(up > 0.6f && up < 0.7f, "30 ms of attack: $up")
        assertTrue(down < 0.2f, "30 ms of release: $down")
        assertTrue(up > 3 * down)
    }

    @Test
    fun smoothingIsFrameRateIndependentAndNeverOvershoots() {
        var coarse = 0f
        repeat(5) { coarse = BarMeterModel.smooth(coarse, 0.8f, 20f) }
        var fine = 0f
        repeat(10) { fine = BarMeterModel.smooth(fine, 0.8f, 10f) }
        assertEquals(coarse, fine, 1e-4f)
        assertEquals(0.5f, BarMeterModel.smooth(0.5f, 0.5f, 16f))
        assertTrue(BarMeterModel.smooth(0.2f, 0.9f, 10_000f) <= 0.9f)
        assertEquals(0.3f, BarMeterModel.smooth(0.3f, 1f, 0f))
    }

    @Test
    fun sideColumnsAreQuieterAndSlowerThanTheCentre() {
        assertTrue(BarMeterModel.sideTarget(1f) < 1f)
        assertTrue(BarMeterModel.SIDE_ATTACK_MS > BarMeterModel.ATTACK_MS)
        assertTrue(BarMeterModel.SIDE_RELEASE_MS > BarMeterModel.RELEASE_MS)
    }

    @Test
    fun syntheticLevelStaysInRangeIsDeterministicAndIsNotASmoothWave() {
        val times = 0L..20_000L step 7
        val samples = times.map { BarMeterModel.syntheticLevel(it, seed = 3) }
        assertTrue(samples.all { it in 0f..1f })
        assertEquals(samples, times.map { BarMeterModel.syntheticLevel(it, seed = 3) })
        assertTrue(samples.max() > 0.6f && samples.min() < 0.2f, "range ${samples.min()}..${samples.max()}")
        // Syllable peaks differ from each other: a handful of distinct heights.
        val peaks = (0L until 40L).map { (BarMeterModel.syntheticLevel(it * 170L + 85L, 3) * 20).toInt() }.toSet()
        assertTrue(peaks.size >= 5, "peaks $peaks")
        val early = 0L..2_000L step 10
        assertTrue(early.map { BarMeterModel.syntheticLevel(it, 1) } != early.map { BarMeterModel.syntheticLevel(it, 2) })
        assertTrue(BarMeterModel.syntheticLevel(-5, 0) in 0f..1f)
    }

    @Test
    fun breathingIsSlowAndInRange() {
        assertEquals(0f, BarMeterModel.breath(0), 1e-4f)
        assertEquals(1f, BarMeterModel.breath(1600), 1e-4f)
        assertTrue((0L..10_000L step 50).all { BarMeterModel.breath(it) in 0f..1f })
        assertEquals(1f, BarMeterModel.listeningGlow(0, 1f))
        assertEquals(0f, BarMeterModel.listeningGlow(3, 1f))
        assertEquals(0f, BarMeterModel.listeningGlow(0, 0f))
    }

    @Test
    fun thinkingHeadTravelsUpAndDownTheColumn() {
        assertEquals(0f, BarMeterModel.thinkingHead(0, 15), 1e-4f)
        assertEquals(14f, BarMeterModel.thinkingHead(1100, 15), 1e-4f)
        assertEquals(0f, BarMeterModel.thinkingHead(2200, 15), 1e-4f)
        assertTrue((0L..5_000L step 13).all { BarMeterModel.thinkingHead(it, 15) in 0f..14f })
        assertEquals(1f, BarMeterModel.thinkingGlow(4, 4f))
        assertFalse(BarMeterModel.thinkingGlow(9, 4f) > 0f)
    }
}
