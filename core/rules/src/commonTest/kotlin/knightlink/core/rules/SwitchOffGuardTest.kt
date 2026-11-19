package knightlink.core.rules

import knightlink.core.signals.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SwitchOffGuardTest {
    private val rig = Rig()

    @Test
    fun safeWhenEverythingIsKnownAndCalm() {
        assertEquals(SwitchOffAdvice.Safe, rig.tick(0, *car()).switchOff)
    }

    @Test
    fun engineOffByRpmOrByIgnition() {
        assertEquals(SwitchOffAdvice.EngineOff, rig.tick(0, *car(rpm = 0.0)).switchOff)
        assertEquals(SwitchOffAdvice.EngineOff, Rig().tick(0, *car(), link = LinkStatus.NO_IGNITION).switchOff)
        // Below the running speed counts as off, at it counts as on.
        assertEquals(SwitchOffAdvice.EngineOff, Rig().tick(0, *car(rpm = 299.0)).switchOff)
        assertEquals(SwitchOffAdvice.Safe, Rig().tick(0, *car(rpm = 300.0)).switchOff)
    }

    @Test
    fun unknownWithoutAnyReadingNamesWhatIsMissing() {
        val unknown = assertIs<SwitchOffAdvice.Unknown>(rig.eval(0.seconds).switchOff)
        assertEquals(setOf("rpm", "regenStatus", "egt"), unknown.missing)
    }

    @Test
    fun unknownWhenTheEngineSpeedIsStale() {
        rig.tick(0, *car())
        val unknown = assertIs<SwitchOffAdvice.Unknown>(rig.tick(10, "soot" to 20.0, "regen" to 0.0, "egt1" to 150.0).switchOff)
        assertEquals(setOf("rpm"), unknown.missing)
    }

    @Test
    fun unknownWhenTheRegenerationStatusIsUnknown() {
        val unknown = assertIs<SwitchOffAdvice.Unknown>(rig.tick(0, "rpm" to 800.0, "egt1" to 150.0).switchOff)
        assertEquals(setOf("regenStatus"), unknown.missing)
    }

    @Test
    fun unknownWhenTheExhaustTemperatureIsStaleAndNeverSafe() {
        rig.tick(0, *car(egt = 500.0))
        // 6 s later everything but the exhaust temperature is read again; the old, hot value is gone but nothing replaces it.
        val state = rig.tick(6, "soot" to 20.0, "regen" to 0.0, "rpm" to 800.0, "speed" to 40.0)
        assertEquals(SwitchOffAdvice.Unknown(setOf("egt")), state.switchOff)
    }

    @Test
    fun anInferredExhaustSensorIsUnknown() {
        val inferred = Rig(trust = { id, _ -> if (id == "egt1") Provenance.INFERRED else Provenance.STANDARD })
        assertEquals(SwitchOffAdvice.Unknown(setOf("egt")), inferred.tick(0, *car(egt = 100.0)).switchOff)
    }

    @Test
    fun theHottestFreshSensorDecides() {
        val two = Rig(testConfig(egt = listOf(SignalRef("egt1", 5000), SignalRef("egt2", 5000))))
        two.feed(0.seconds, *car(egt = 150.0), "egt2" to 450.0)
        assertEquals(SwitchOffAdvice.NotYet(SwitchOffReason.HOT_EXHAUST, 120.seconds), two.eval(0.seconds).switchOff)
        // One sensor stale: the other still counts.
        two.feed(10.seconds, *car(egt = 150.0))
        assertEquals(SwitchOffAdvice.Safe, two.eval(10.seconds).switchOff)
    }

    @Test
    fun hotExhaustCountsDownWhileStandingStillWithTheEngineRunning() {
        // Driving hot: the whole cool-down is still ahead.
        assertEquals(SwitchOffAdvice.NotYet(SwitchOffReason.HOT_EXHAUST, 120.seconds), rig.tick(0, *car(egt = 400.0, speed = 50.0)).switchOff)
        // Stops at 10 s: 120 s to go from there.
        assertEquals(SwitchOffAdvice.NotYet(SwitchOffReason.HOT_EXHAUST, 120.seconds), rig.tick(10, *car(egt = 400.0, speed = 0.0)).switchOff)
        assertEquals(SwitchOffAdvice.NotYet(SwitchOffReason.HOT_EXHAUST, 60.seconds), rig.tick(70, *car(egt = 380.0, speed = 0.0)).switchOff)
        // Time is up but the exhaust is still hot: no promise about how long.
        assertEquals(SwitchOffAdvice.NotYet(SwitchOffReason.HOT_EXHAUST, null), rig.tick(130, *car(egt = 320.0, speed = 0.0)).switchOff)
        assertEquals(SwitchOffAdvice.Safe, rig.tick(140, *car(egt = 290.0, speed = 0.0)).switchOff)
    }

    @Test
    fun movingAgainRestartsTheCooldown() {
        rig.tick(0, *car(egt = 400.0, speed = 0.0))
        rig.tick(60, *car(egt = 400.0, speed = 0.0))
        rig.tick(61, *car(egt = 400.0, speed = 5.0))
        assertEquals(SwitchOffAdvice.NotYet(SwitchOffReason.HOT_EXHAUST, 120.seconds), rig.tick(100, *car(egt = 400.0, speed = 0.0)).switchOff)
        assertEquals(SwitchOffAdvice.NotYet(SwitchOffReason.HOT_EXHAUST, 100.seconds), rig.tick(120, *car(egt = 400.0, speed = 0.0)).switchOff)
    }

    @Test
    fun anUnknownSpeedDoesNotCountAsStanding() {
        rig.tick(0, *car(egt = 400.0, speed = 0.0))
        // The speed reading goes stale at 4 s; the time stood still so far is not carried over a gap.
        val state = rig.tick(100, "soot" to 20.0, "regen" to 0.0, "rpm" to 800.0, "egt1" to 400.0)
        assertEquals(SwitchOffAdvice.NotYet(SwitchOffReason.HOT_EXHAUST, 120.seconds), state.switchOff)
    }

    @Test
    fun aRegenerationInProgressBeatsEverythingEvenWithTheEngineSpeedUnknown() {
        rig.tick(10, *car(soot = 100.0, status = 2.0))
        val noRpm = rig.tick(15, "soot" to 99.0, "regen" to 2.0)
        val advice = assertIs<SwitchOffAdvice.NotYet>(noRpm.switchOff)
        assertEquals(SwitchOffReason.REGEN_ACTIVE, advice.reason)
    }

    @Test
    fun aRegenerationInMemoryStillMeansNotYetWhileItsStatusIsStale() {
        rig.tick(10, *car(soot = 100.0, status = 2.0))
        val state = rig.eval(60.seconds)
        assertEquals(SwitchOffReason.REGEN_ACTIVE, assertIs<SwitchOffAdvice.NotYet>(state.switchOff).reason)
    }

    @Test
    fun theRegenerationWaitIsEstimatedFromTheObservedSootDrop() {
        // No drop seen yet: no estimate.
        assertNull(assertIs<SwitchOffAdvice.NotYet>(rig.tick(10, *car(soot = 100.0, status = 2.0)).switchOff).remaining)
        assertNull(assertIs<SwitchOffAdvice.NotYet>(rig.tick(12, *car(soot = 100.0, status = 2.0)).switchOff).remaining)
        // 100 -> 70 in 10 s (3 %/s): 40 more points to the completion threshold of 30 %, about 13 s.
        val remaining = assertNotNull(assertIs<SwitchOffAdvice.NotYet>(rig.tick(20, *car(soot = 70.0, status = 2.0)).switchOff).remaining)
        assertTrue(remaining > 13.seconds && remaining < 14.seconds, "remaining $remaining")
    }

    @Test
    fun anImplausiblyLongEstimateIsLeftOut() {
        rig.tick(10, *car(soot = 100.0, status = 2.0))
        // 0.01 % in 10 s would be 20 hours.
        val state = rig.tick(20, *car(soot = 99.99, status = 2.0))
        assertNull(assertIs<SwitchOffAdvice.NotYet>(state.switchOff).remaining)
    }

    @Test
    fun afterTheRegenerationItIsSafeAgainOnceTheExhaustHasCooled() {
        rig.tick(10, *car(soot = 100.0, status = 2.0, egt = 330.0))
        rig.tick(11, *car(soot = 20.0, status = 0.0, egt = 330.0))
        val done = rig.tick(12, *car(soot = 20.0, status = 0.0, egt = 330.0))
        assertEquals(DpfPhase.REGEN_COMPLETED, done.dpf.phase)
        assertEquals(SwitchOffReason.HOT_EXHAUST, assertIs<SwitchOffAdvice.NotYet>(done.switchOff).reason)
        assertEquals(SwitchOffAdvice.Safe, rig.tick(20, *car(soot = 20.0, status = 0.0, egt = 210.0)).switchOff)
    }

    @Test
    fun withoutACooldownSectionExhaustHeatIsNotChecked() {
        val bare = Rig(testConfig(cooldown = null))
        assertEquals(SwitchOffAdvice.Safe, bare.tick(0, "rpm" to 800.0, "regen" to 0.0, "soot" to 20.0).switchOff)
    }

    @Test
    fun withoutADpfSectionTheStatusIsNotRequired() {
        val bare = Rig(testConfig(dpf = null))
        assertEquals(SwitchOffAdvice.Safe, bare.tick(0, "rpm" to 800.0, "egt1" to 100.0).switchOff)
    }
}
