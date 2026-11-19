package knightlink.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DpfStateMachineTest {
    private val rig = Rig()

    private fun RulesState.phase() = dpf.phase

    /** Drives a regeneration that started at 10 s with 100 % soot. */
    private fun startRegen(at: Int = 10, soot: Double = 100.0): RulesState = rig.tick(at, *car(soot = soot, status = 2.0))

    @Test
    fun unknownUntilTheRegenerationStatusIsRead() {
        assertEquals(DpfPhase.UNKNOWN, rig.eval(0.seconds).phase())
        // Soot alone is not enough: with the status unknown nobody can say the filter is not regenerating.
        rig.feed(1.seconds, "soot" to 50.0)
        assertEquals(DpfPhase.UNKNOWN, rig.eval(1.seconds).phase())
        val normal = rig.tick(2, *car(soot = 20.0))
        assertEquals(DpfPhase.NORMAL, normal.phase())
        assertEquals(20.0, normal.dpf.sootPct)
        assertNull(normal.dpf.current)
        assertNull(normal.dpf.last)
    }

    @Test
    fun statusKnownWithoutSootIsNormalWithoutAFigure() {
        rig.feed(0.seconds, "regen" to 0.0, "rpm" to 800.0)
        val state = rig.eval(0.seconds)
        assertEquals(DpfPhase.NORMAL, state.phase())
        assertNull(state.dpf.sootPct)
    }

    @Test
    fun sootRisingHasHysteresis() {
        assertEquals(DpfPhase.NORMAL, rig.tick(0, *car(soot = 79.0)).phase())
        assertEquals(DpfPhase.SOOT_RISING, rig.tick(1, *car(soot = 80.0)).phase())
        assertEquals(DpfPhase.SOOT_RISING, rig.tick(2, *car(soot = 76.0)).phase())
        assertEquals(DpfPhase.SOOT_RISING, rig.tick(3, *car(soot = 75.0)).phase())
        assertEquals(DpfPhase.NORMAL, rig.tick(4, *car(soot = 74.9)).phase())
        // And back up only at the rising threshold, not the clear one.
        assertEquals(DpfPhase.NORMAL, rig.tick(5, *car(soot = 78.0)).phase())
    }

    @Test
    fun theFirstActiveReadingStartsARegenerationAndRecordsTheSootBefore() {
        rig.tick(0, *car(soot = 92.0))
        val state = startRegen(at = 4, soot = 96.0)
        assertEquals(DpfPhase.REGEN_ACTIVE, state.phase())
        val current = assertNotNull(state.dpf.current)
        assertEquals(4.seconds, current.start)
        assertNull(current.end)
        assertEquals(96.0, current.sootBefore)
        assertEquals(96.0, current.peakSoot)
    }

    @Test
    fun theSootBeforeIsTheLastReadingAtOrBeforeTheStart() {
        rig.tick(0, *car(soot = 88.0))
        // The status turns active at 2 s; the soot read at that moment is the one that counts, and a later one does not rewrite it.
        rig.feed(2.seconds, "regen" to 2.0)
        val state = rig.eval(2.seconds)
        assertEquals(88.0, state.dpf.current?.sootBefore)
    }

    @Test
    fun aSootReadingTooOldBeforeTheStartIsNotUsed() {
        rig.tick(0, *car(soot = 88.0))
        rig.feed(20.seconds, "regen" to 2.0, "rpm" to 800.0)
        val state = rig.eval(20.seconds)
        assertEquals(DpfPhase.REGEN_ACTIVE, state.phase())
        assertNull(state.dpf.current?.sootBefore)
    }

    @Test
    fun thePeakSootIsTracked() {
        startRegen(soot = 97.0)
        rig.tick(11, *car(soot = 99.0, status = 2.0))
        val state = rig.tick(12, *car(soot = 80.0, status = 2.0))
        assertEquals(99.0, state.dpf.current?.peakSoot)
    }

    @Test
    fun aRegenerationThatEndsLowCompletes() {
        startRegen()
        rig.tick(11, *car(soot = 50.0, status = 2.0))
        assertEquals(DpfPhase.REGEN_ACTIVE, rig.tick(12, *car(soot = 25.0, status = 0.0)).phase())
        val done = rig.tick(13, *car(soot = 18.0, status = 0.0))
        assertEquals(DpfPhase.REGEN_COMPLETED, done.phase())
        assertNull(done.dpf.current)
        val last = assertNotNull(done.dpf.last)
        assertEquals(RegenOutcome.COMPLETED, last.outcome)
        assertEquals(10.seconds, last.start)
        assertEquals(13.seconds, last.end)
        assertEquals(100.0, last.sootBefore)
        assertEquals(18.0, last.sootAfter)
    }

    @Test
    fun aRegenerationThatEndsHighStoppedEarly() {
        startRegen()
        rig.tick(11, *car(soot = 60.0, status = 0.0))
        val state = rig.tick(12, *car(soot = 58.0, status = 0.0))
        assertEquals(DpfPhase.REGEN_INTERRUPTED, state.phase())
        assertEquals(RegenOutcome.STOPPED_EARLY, state.dpf.last?.outcome)
        assertEquals(58.0, state.dpf.last?.sootAfter)
    }

    @Test
    fun aSootValueFromTheMiddleOfTheRegenerationDoesNotDecideHowItEnded() {
        startRegen()
        rig.tick(11, *car(soot = 58.0, status = 2.0))
        // The status says it stopped, but no soot has been read since: the last soot (58 %, mid-regeneration) must not make it "stopped early".
        rig.feed(12.seconds, "regen" to 0.0, "rpm" to 800.0)
        rig.eval(12.seconds)
        rig.feed(13.seconds, "regen" to 0.0, "rpm" to 800.0)
        assertEquals(DpfPhase.REGEN_ACTIVE, rig.eval(13.seconds).phase())
        val done = rig.tick(14, *car(soot = 18.0, status = 0.0))
        assertEquals(DpfPhase.REGEN_COMPLETED, done.phase())
        assertEquals(RegenOutcome.COMPLETED, done.dpf.last?.outcome)
        assertEquals(18.0, done.dpf.last?.sootAfter)
        assertTrue(rig.spokenIds().none { it.startsWith("dpf.regen_interrupted") })
    }

    @Test
    fun oneGlitchOfNotActiveDoesNotEndARegeneration() {
        startRegen()
        assertEquals(DpfPhase.REGEN_ACTIVE, rig.tick(11, *car(soot = 90.0, status = 0.0)).phase())
        assertEquals(DpfPhase.REGEN_ACTIVE, rig.tick(12, *car(soot = 88.0, status = 2.0)).phase())
        assertEquals(DpfPhase.REGEN_ACTIVE, rig.tick(13, *car(soot = 86.0, status = 0.0)).phase())
        assertEquals(DpfPhase.REGEN_ACTIVE, rig.tick(14, *car(soot = 84.0, status = 2.0)).phase())
    }

    @Test
    fun theSameReadingSeenAgainIsNotCountedTwice() {
        startRegen()
        rig.tick(11, *car(soot = 20.0, status = 0.0))
        // Ticks with no new data see the same "not active" reading; it is still one reading.
        assertEquals(DpfPhase.REGEN_ACTIVE, rig.eval(11.seconds).phase())
        assertEquals(DpfPhase.REGEN_ACTIVE, rig.eval(12.seconds).phase())
        assertEquals(DpfPhase.REGEN_COMPLETED, rig.tick(12, *car(soot = 20.0, status = 0.0)).phase())
    }

    @Test
    fun anyBitOfTheMaskCounts() {
        assertEquals(DpfPhase.REGEN_ACTIVE, rig.tick(0, *car(status = 3.0)).phase())
        val other = Rig()
        assertEquals(DpfPhase.NORMAL, other.tick(0, *car(status = 1.0)).phase())
    }

    @Test
    fun theEngineStoppingEndsItAsEngineOffByRpm() {
        startRegen()
        val state = rig.tick(12, *car(soot = 70.0, status = 2.0, rpm = 0.0))
        assertEquals(DpfPhase.REGEN_INTERRUPTED, state.phase())
        val last = assertNotNull(state.dpf.last)
        assertEquals(RegenOutcome.ENGINE_OFF, last.outcome)
        assertEquals(70.0, last.sootAfter)
        assertEquals(12.seconds, last.end)
    }

    @Test
    fun noIgnitionEndsItAsEngineOffToo() {
        startRegen()
        val state = rig.tick(12, *car(soot = 70.0, status = 2.0), link = LinkStatus.NO_IGNITION)
        assertEquals(DpfPhase.REGEN_INTERRUPTED, state.phase())
        assertEquals(RegenOutcome.ENGINE_OFF, state.dpf.last?.outcome)
        // The still-fresh "active" reading does not start it again.
        assertEquals(DpfPhase.REGEN_INTERRUPTED, rig.eval(13.seconds, LinkStatus.NO_IGNITION).phase())
    }

    @Test
    fun aStaleStatusMidRegenerationStaysActiveInMemoryAndResolvesWhenDataReturns() {
        startRegen()
        rig.tick(11, *car(soot = 90.0, status = 2.0))
        // Nothing for 30 s: the status is unknown. Do not guess; do not raise anything new.
        val stale = rig.eval(41.seconds)
        assertEquals(DpfPhase.REGEN_ACTIVE, stale.phase())
        assertTrue("regenStatus" in stale.staleInputs)
        assertEquals(emptyList(), stale.announcements)
        // Data returns: two readings of "not active" with low soot complete it.
        assertEquals(DpfPhase.REGEN_ACTIVE, rig.tick(50, *car(soot = 20.0, status = 0.0)).phase())
        assertEquals(DpfPhase.REGEN_COMPLETED, rig.tick(51, *car(soot = 20.0, status = 0.0)).phase())
    }

    @Test
    fun aStaleStatusMidRegenerationThatReturnsHighIsStoppedEarly() {
        startRegen()
        rig.eval(60.seconds)
        rig.tick(70, *car(soot = 70.0, status = 0.0))
        assertEquals(DpfPhase.REGEN_INTERRUPTED, rig.tick(71, *car(soot = 70.0, status = 0.0)).phase())
    }

    @Test
    fun aStaleStatusWithTheLinkDownStaysActiveToo() {
        startRegen()
        assertEquals(DpfPhase.REGEN_ACTIVE, rig.eval(60.seconds, LinkStatus.LINK_DOWN).phase())
    }

    @Test
    fun noSootReadAfterTheStopMeansNoVerdictYet() {
        startRegen()
        rig.feed(20.seconds, "regen" to 0.0, "rpm" to 800.0)
        rig.eval(20.seconds)
        rig.feed(21.seconds, "regen" to 0.0, "rpm" to 800.0)
        val state = rig.eval(21.seconds)
        // The only soot known is from the start (100 %): it says nothing about how the regeneration ended, so there is no verdict and no alert.
        assertEquals(DpfPhase.REGEN_ACTIVE, state.phase())
        assertNull(state.dpf.last)
        assertTrue(state.alerts.none { it.id.startsWith("dpf.regen_interrupted") })
        val after = rig.tick(22, *car(soot = 95.0, status = 0.0))
        assertEquals(DpfPhase.REGEN_INTERRUPTED, after.phase())
        assertEquals(95.0, after.dpf.last?.sootAfter)
    }

    @Test
    fun noSootEverReadMeansNoVerdictYet() {
        val bare = Rig()
        bare.feed(10.seconds, "regen" to 2.0, "rpm" to 800.0)
        assertEquals(DpfPhase.REGEN_ACTIVE, bare.eval(10.seconds).phase())
        bare.feed(11.seconds, "regen" to 0.0, "rpm" to 800.0)
        bare.eval(11.seconds)
        bare.feed(12.seconds, "regen" to 0.0, "rpm" to 800.0)
        assertEquals(DpfPhase.REGEN_ACTIVE, bare.eval(12.seconds).phase())
        bare.feed(13.seconds, "soot" to 15.0, "regen" to 0.0, "rpm" to 800.0)
        assertEquals(DpfPhase.REGEN_COMPLETED, bare.eval(13.seconds).phase())
    }

    @Test
    fun theResultIsHeldThenTheStateFallsBackAndTheLastRegenerationIsKept() {
        startRegen()
        rig.tick(11, *car(soot = 20.0, status = 0.0))
        val done = rig.tick(12, *car(soot = 20.0, status = 0.0))
        assertEquals(DpfPhase.REGEN_COMPLETED, done.phase())
        assertEquals(DpfPhase.REGEN_COMPLETED, rig.tick(131, *car(soot = 20.0)).phase())
        val after = rig.tick(132, *car(soot = 20.0))
        assertEquals(DpfPhase.NORMAL, after.phase())
        assertEquals(RegenOutcome.COMPLETED, after.dpf.last?.outcome)
    }

    @Test
    fun afterTheHoldHighSootShowsAsRising() {
        startRegen()
        rig.tick(11, *car(soot = 85.0, status = 0.0))
        rig.tick(12, *car(soot = 85.0, status = 0.0))
        assertEquals(DpfPhase.SOOT_RISING, rig.tick(132, *car(soot = 85.0)).phase())
    }

    @Test
    fun afterTheHoldWithTheStatusUnknownItIsUnknown() {
        startRegen()
        rig.tick(11, *car(soot = 20.0, status = 0.0))
        rig.tick(12, *car(soot = 20.0, status = 0.0))
        assertEquals(DpfPhase.UNKNOWN, rig.eval(200.seconds).phase())
    }

    @Test
    fun aNewRegenerationAfterAResultStartsAtOnce() {
        startRegen()
        rig.tick(11, *car(soot = 20.0, status = 0.0))
        rig.tick(12, *car(soot = 20.0, status = 0.0))
        val again = rig.tick(20, *car(soot = 95.0, status = 2.0))
        assertEquals(DpfPhase.REGEN_ACTIVE, again.phase())
        assertEquals(20.seconds, again.dpf.current?.start)
        assertEquals(RegenOutcome.COMPLETED, again.dpf.last?.outcome)
    }

    @Test
    fun interruptedThenResumed() {
        startRegen()
        rig.tick(11, *car(soot = 60.0, status = 0.0))
        val stopped = rig.tick(12, *car(soot = 58.0, status = 0.0))
        assertEquals(DpfPhase.REGEN_INTERRUPTED, stopped.phase())
        assertTrue(stopped.alerts.any { it.id == "dpf.regen_interrupted" })
        val resumed = rig.tick(30, *car(soot = 57.0, status = 2.0))
        assertEquals(DpfPhase.REGEN_ACTIVE, resumed.phase())
        assertEquals(30.seconds, resumed.dpf.current?.start)
        assertEquals(57.0, resumed.dpf.current?.sootBefore)
        assertEquals(RegenOutcome.STOPPED_EARLY, resumed.dpf.last?.outcome)
        // A new regeneration is the answer to the warning.
        assertTrue(resumed.alerts.none { it.id == "dpf.regen_interrupted" })
    }

    @Test
    fun withoutADpfSectionThePhaseIsAlwaysUnknown() {
        val bare = Rig(testConfig(dpf = null))
        assertEquals(DpfPhase.UNKNOWN, bare.tick(0, *car(status = 2.0, soot = 100.0)).phase())
    }
}
