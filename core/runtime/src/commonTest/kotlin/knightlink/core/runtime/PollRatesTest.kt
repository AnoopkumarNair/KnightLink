package knightlink.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PollRatesTest {
    @Test
    fun countsRequestsAndReadingsOverTheWindow() {
        val meter = RateMeter(start = Duration.ZERO)
        // Ten seconds: a request every 100 ms (every other one carries three PIDs), rpm in each, coolant every 5 s.
        for (i in 1..100) {
            val at = (i * 100).milliseconds
            meter.request(at, if (i % 2 == 0) 3 else 1)
            meter.reading("engine.rpm", at)
            if (i % 50 == 0) meter.reading("engine.coolant_temp", at)
        }
        val rates = meter.rates(10.seconds, 10.seconds)
        assertEquals(10.0, rates.requestsPerSecond, 0.01)
        assertEquals(0.5, rates.batchedShare, 0.001)
        assertEquals(10.0, rates.signals.getValue("engine.rpm"), 0.01)
        assertEquals(0.2, rates.signals.getValue("engine.coolant_temp"), 0.01)
    }

    @Test
    fun aWindowLongerThanTheSessionAveragesOverTheSessionOnly() {
        val meter = RateMeter(start = 100.seconds)
        for (i in 1..20) meter.request(100.seconds + (i * 100).milliseconds, 1)
        // Two seconds in, asked about 10: the rate is 20 in 2 s, not 20 in 10 s.
        assertEquals(10.0, meter.rates(102.seconds, 10.seconds).requestsPerSecond, 0.01)
    }

    @Test
    fun onlyTheRecentPastCountsAndOldEventsAreForgotten() {
        val meter = RateMeter(start = Duration.ZERO, keep = 30.seconds)
        for (i in 0 until 10) meter.request(i.seconds, 1) // a burst long ago
        meter.request(59.seconds + 500.milliseconds, 1)
        meter.reading("old", 1.seconds)
        val rates = meter.rates(60.seconds, 10.seconds)
        assertEquals(0.1, rates.requestsPerSecond, 0.001)
        assertTrue("old" !in rates.signals)
    }

    @Test
    fun aSignalThatStoppedHasNoRate() {
        val meter = RateMeter(start = Duration.ZERO)
        meter.reading("engine.rpm", 1.seconds)
        assertTrue(meter.rates(20.seconds, 10.seconds).signals.isEmpty())
    }

    @Test
    fun theSummaryGroupsSignalsByRateAndNamesNoVehicle() {
        val rates = PollRates(
            requestsPerSecond = 24.3,
            batchedShare = 0.85,
            signals = mapOf("vehicle.speed" to 6.66, "engine.rpm" to 6.7, "intake.maf" to 1.0, "engine.coolant_temp" to 0.17),
            window = 30.seconds,
        )
        assertEquals(
            "last 30 s: 24 requests/s, 85 % batched; 6.7/s engine.rpm vehicle.speed; 1.0/s intake.maf; 0.2/s engine.coolant_temp",
            rates.summary(),
        )
        assertTrue(rates.summary(listOf("7E0")).contains(", batching off for 7E0;"))
    }
}
