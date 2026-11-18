package knightlink.core.rules

import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponseDecoder
import knightlink.core.protocol.ElmStreamFramer
import knightlink.core.recorder.Recording
import knightlink.core.recorder.TrafficExchange
import knightlink.core.recorder.trafficExchanges
import knightlink.core.telemetry.TelemetryDecoder
import knightlink.core.telemetry.TelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The rules on the first real drive (193 s, parked, no regeneration): it must stay silent, which is the "zero false alerts" requirement tested
 * on a real car's data. Its 018B and 0178 replies are cut to their first frame and do not decode, so the DPF and exhaust inputs are genuinely
 * unknown here, and the rules have to say so instead of guessing.
 */
class RealRecordingTest {
    private val recording = Recording.parse(Repo.text("fixtures/raw/harrier-2026-11-17-apartment-drive.jsonl"))
    private val config = Repo.rules()
    private val catalog = Repo.catalog(config.vehicle)
    private val decoder = TelemetryDecoder(catalog)

    private fun readingsOf(exchange: TrafficExchange) =
        ElmStreamFramer().feed(exchange.response).firstOrNull()
            ?.let { ElmResponseDecoder(extendedIds = false).decode(it).messages }.orEmpty()
            .filterIsInstance<DiagnosticResponse.Positive>()
            .flatMap(decoder::decode)

    /** Replays the drive: [lag] is how much older than the exchange its readings are stamped. States after every exchange and on a 1 s tick. */
    private fun replay(lag: Duration = Duration.ZERO): List<Pair<Duration, RulesState>> {
        val engine = RulesEngine(config, CatalogSignalTrust(catalog))
        val exchanges = recording.trafficExchanges().filter { it.header != null && it.request.startsWith("01") }.sortedBy { it.timeMs }
        val states = ArrayList<Pair<Duration, RulesState>>()
        var snapshot = TelemetrySnapshot.EMPTY
        var clock = Duration.ZERO
        fun evaluate(at: Duration) {
            clock = maxOf(clock, at)
            states += clock to engine.evaluate(snapshot, clock)
        }
        var nextTick = 1_000L
        for (exchange in exchanges) {
            while (nextTick <= exchange.timeMs) {
                evaluate(nextTick.milliseconds)
                nextTick += 1_000
            }
            snapshot = snapshot.with(readingsOf(exchange), exchange.timeMs.milliseconds - lag)
            evaluate(exchange.timeMs.milliseconds)
        }
        // Past the end of the drive: nothing may come to life from old readings either.
        val end = exchanges.last().timeMs + 60_000
        while (nextTick <= end) {
            evaluate(nextTick.milliseconds)
            nextTick += 1_000
        }
        return states
    }

    @Test
    fun theRealDriveIsReplayedThroughTheDecodersAndProducesReadings() {
        val states = replay()
        assertTrue(states.size > 1_000, "states: ${states.size}")
    }

    @Test
    fun theWholeDriveRaisesNothingAndSaysNothing() {
        val states = replay()
        val spoken = states.flatMap { it.second.announcements }
        assertTrue(spoken.isEmpty(), "spoken: ${spoken.map { it.id }}")
        val alerts = states.flatMap { it.second.alerts }
        assertTrue(alerts.isEmpty(), "alerts: ${alerts.map { it.id }.toSet()}")
    }

    @Test
    fun theDpfIsUnknownThroughoutBecauseItsRepliesDoNotDecode() {
        assertEquals(setOf(DpfPhase.UNKNOWN), replay().map { it.second.dpf.phase }.toSet())
    }

    @Test
    fun itIsNeverSafeToSwitchOffWhileTheExhaustTemperatureIsUnknown() {
        val states = replay().map { it.second }
        val unknownExhaust = states.filter { "egt" in it.staleInputs }
        assertTrue(unknownExhaust.isNotEmpty())
        assertTrue(unknownExhaust.none { it.switchOff == SwitchOffAdvice.Safe })
    }

    @Test
    fun theAdblueRuleReallyEvaluatedBecauseTheLevelWasSeenFresh() {
        val states = replay().map { it.second }
        assertTrue(states.any { "adblueLevel" !in it.staleInputs }, "the AdBlue level was never fresh, so its rule never ran")
        assertTrue(states.any { "coolant" !in it.staleInputs }, "the coolant temperature was never fresh, so its rule never ran")
        assertTrue(states.any { "rpm" !in it.staleInputs })
    }

    @Test
    fun withEveryReadingAMinuteOldTheAnswerIsUnknownAndNothingIsRaised() {
        val states = replay(lag = 60_000.milliseconds).map { it.second }
        assertTrue(states.all { it.alerts.isEmpty() && it.announcements.isEmpty() })
        for (state in states) {
            assertEquals(listOf(Phrase("answer.warnings.unknown")), Answers.anyWarnings(state), "at ${state.at}")
            assertEquals(Phrase("answer.switch_off.unknown"), Answers.switchOff(state), "at ${state.at}")
        }
    }
}
