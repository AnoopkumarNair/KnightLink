package knightlink.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GearEstimatorTest {
    /** Steady running at [ratio] rpm per km/h at [kmh], one reading every 150 ms for [ms] ms starting at [from]. */
    private fun GearEstimator.cruise(from: Long, ms: Long, ratio: Double, kmh: Double): Int {
        var gear = GearEstimator.UNKNOWN
        var t = from
        while (t < from + ms) {
            gear = update(t, ratio * kmh, kmh)
            t += 150
        }
        return gear
    }

    @Test
    fun learnsRatiosFromSteadyRunningAndNumbersThemFromTheLowestGear() {
        val estimator = GearEstimator()
        var t = 0L
        // Three steady stretches at three ratios (first, second, third), twice each: the second pass is already known.
        repeat(2) {
            for ((ratio, kmh) in listOf(60.0 to 25.0, 35.0 to 45.0, 22.0 to 70.0)) {
                estimator.cruise(t, 8_000, ratio, kmh)
                t += 8_000
                estimator.update(t, 800.0, 0.0) // stopped: the window is cleared between stretches
                t += 150
            }
        }
        assertEquals(1, estimator.cruise(t, 3_000, 60.0, 25.0))
        t += 3_000
        assertEquals(2, estimator.cruise(t, 3_000, 35.0, 45.0))
        t += 3_000
        assertEquals(3, estimator.cruise(t, 3_000, 22.0, 70.0))
        assertEquals(3, estimator.model.clusters.size)
    }

    @Test
    fun isUnknownWhileSlowWhileTheRatioMovesAndBeforeARatioIsKnown() {
        val estimator = GearEstimator()
        assertEquals(GearEstimator.UNKNOWN, estimator.cruise(0, 5_000, 30.0, 8.0), "below 10 km/h")
        // A converter that slips: revs rise while speed barely does.
        var t = 10_000L
        var gear = 0
        repeat(40) {
            gear = estimator.update(t, 1500.0 + it * 60, 40.0)
            t += 150
        }
        assertEquals(GearEstimator.UNKNOWN, gear)
        assertEquals(GearEstimator.UNKNOWN, estimator.cruise(t, 1_500, 40.0, 50.0), "a ratio seen for the first time is not a gear yet")
        assertEquals(0, estimator.update(t, 0.0, 60.0), "no revs, no ratio")
    }

    @Test
    fun whatWasLearnedSurvivesANewSession() {
        val first = GearEstimator()
        var t = 0L
        repeat(2) {
            first.cruise(t, 8_000, 50.0, 30.0)
            t += 8_000
            first.update(t, 800.0, 0.0)
            t += 150
            first.cruise(t, 8_000, 28.0, 60.0)
            t += 8_000
            first.update(t, 800.0, 0.0)
            t += 150
        }
        val json = Json.encodeToString(GearModel.serializer(), first.model)
        val second = GearEstimator(Json.decodeFromString(GearModel.serializer(), json))
        assertEquals(1, second.cruise(0, 3_000, 50.0, 30.0))
        assertEquals(2, second.cruise(10_000, 3_000, 28.0, 60.0))
    }

    @Test
    fun aRealDriveGivesAFewEstablishedRatiosAndMostlyUnknown() {
        val root = File(System.getProperty("knightlink.repoRoot"))
        val doc = Json.parseToJsonElement(File(root, "fixtures/gear/harrier-speed-rpm-drive.json").readText()).jsonObject
        fun series(
            name: String,
        ) = (doc.getValue(name) as JsonArray).map { it.jsonArray[0].jsonPrimitive.double.toLong() to it.jsonArray[1].jsonPrimitive.double }
        val events = (
            series("rpm").map {
                Triple(it.first, true, it.second)
            } + series("speed").map { Triple(it.first, false, it.second) }
            ).sortedBy { it.first }

        fun replay(estimator: GearEstimator, from: Int = 0, to: Int = events.size): List<Int> {
            var rpm: Pair<Long, Double>? = null
            var speed: Pair<Long, Double>? = null
            val gears = ArrayList<Int>()
            for (index in from until to) {
                val (at, isRpm, value) = events[index]
                if (isRpm) rpm = at to value else speed = at to value
                val r = rpm
                val s = speed
                if (r == null || s == null) continue
                val fresh = at - r.first <= 1_000 && at - s.first <= 1_000
                gears += if (fresh) estimator.update(at, r.second, s.second) else GearEstimator.UNKNOWN
            }
            return gears
        }

        val estimator = GearEstimator()
        val gears = replay(estimator)
        val established = estimator.model.clusters.filter { it.count >= 8 }
        assertTrue(established.size in 2..7, "established ratios: $established")
        val known = gears.count { it != GearEstimator.UNKNOWN }
        assertTrue(known > 20, "some steady running is recognised ($known)")
        assertTrue(known < gears.size / 2, "a stop-and-go drive is mostly unknown ($known of ${gears.size})")
        assertTrue(gears.all { it in 0..established.size })
        // The dominant steady ratio of this drive (about 33 rpm per km/h) is one of them.
        assertTrue(established.any { it.ratio in 31.0..35.0 }, established.toString())

        // The same drive replayed with what the first pass learned reports gears from its start (nothing has to be learned again).
        val remembered = GearEstimator(estimator.model)
        val again = replay(remembered)
        assertTrue(again.count { it != GearEstimator.UNKNOWN } >= known, "known again: ${again.count { it != 0 }} vs $known")
    }
}
