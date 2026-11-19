package knightlink.core.rules

import knightlink.core.signals.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The alert rules through the whole engine, including what they must not do: speak on old, out-of-range or untrusted readings. */
class AlertRulesTest {
    private val rig = Rig()

    private fun RulesState.ids() = alerts.map { it.id }

    // ---- reading age, range, future stamps

    @Test
    fun aStaleSootOf100GivesNoSootAlert() {
        rig.tick(0, *car(soot = 100.0))
        // 10 minutes later the status is read again but the soot is not: the 100 % is history.
        val state = rig.tick(600, "regen" to 0.0, "rpm" to 800.0)
        assertTrue(state.alerts.isEmpty())
        assertNull(state.dpf.sootPct)
        assertTrue("soot" in state.staleInputs)
    }

    @Test
    fun aStaleAdblueOf1PercentGivesNoAdblueAlert() {
        rig.tick(0, *car(adblue = 1.0).filter { it.first == "adblue" }.toTypedArray())
        val state = rig.tick(40, *car())
        assertTrue(state.alerts.isEmpty())
        // Not even right at the limit: 30 s is allowed, 30.001 is not.
        val edge = Rig()
        edge.feed(0.seconds, "adblue" to 1.0, "rpm" to 800.0)
        assertEquals(listOf("def.level_critical"), edge.eval(30.seconds).ids())
        val late = Rig()
        late.feed(0.seconds, "adblue" to 1.0)
        assertTrue(late.eval(30.seconds + 1.milliseconds).alerts.isEmpty())
    }

    @Test
    fun aReadingStampedInTheFutureIsRejected() {
        rig.feed(100.seconds, "adblue" to 1.0, "coolant" to 130.0)
        val state = rig.eval(50.seconds)
        assertTrue(state.alerts.isEmpty())
        assertTrue("adblueLevel" in state.staleInputs && "coolant" in state.staleInputs)
        // Once its time has come it counts.
        assertEquals(setOf("def.level_critical", "engine.coolant_high"), rig.eval(100.seconds).ids().toSet())
    }

    @Test
    fun anOutOfRangeReadingIsRejected() {
        rig.feed(0.seconds, "adblue" to 1.0, "coolant" to 300.0, inRange = false)
        assertTrue(rig.eval(0.seconds).alerts.isEmpty())
    }

    @Test
    fun aNonFiniteReadingIsRejected() {
        rig.feed(0.seconds, "adblue" to Double.NaN)
        assertTrue(rig.eval(0.seconds).alerts.isEmpty())
    }

    @Test
    fun timeMustNotGoBackwards() {
        rig.tick(10, *car())
        assertFailsWith<IllegalArgumentException> { rig.eval(9.seconds) }
        // The same instant again is fine.
        rig.eval(10.seconds)
    }

    // ---- provenance

    @Test
    fun anInferredSignalNeverAlertsWhateverTheValue() {
        val inferred = Rig(trust = { id, _ -> if (id == "adblue") Provenance.INFERRED else Provenance.STANDARD })
        for (value in listOf(0.0, 1.0, 5.0, 50.0)) {
            val state = inferred.tick(value.toInt(), "adblue" to value, "rpm" to 800.0)
            assertTrue(state.alerts.isEmpty(), "level $value")
            assertTrue("adblueLevel" in state.staleInputs)
        }
    }

    @Test
    fun communityAndUnknownSignalsNeverAlertEither() {
        for (provenance in listOf(Provenance.COMMUNITY, null)) {
            val untrusted = Rig(trust = { id, _ -> if (id == "coolant") provenance else Provenance.STANDARD })
            assertTrue(untrusted.tick(0, "coolant" to 150.0, "rpm" to 800.0).alerts.isEmpty(), "provenance $provenance")
        }
    }

    @Test
    fun verifiedSignalsDoAlert() {
        val verified = Rig(trust = { _, _ -> Provenance.VERIFIED })
        assertEquals(listOf("def.level_critical"), verified.tick(0, "adblue" to 2.0).ids())
    }

    @Test
    fun trustIsAskedAboutTheSignalAndItsEcuAsConfigured() {
        val asked = ArrayList<Pair<String, String?>>()
        val recording = Rig(testConfig(adblueEcu = "rdcm"), trust = { id, ecu ->
            asked += id to ecu
            Provenance.STANDARD
        })
        recording.tick(0, *car())
        assertTrue("adblue" to "rdcm" in asked)
        assertTrue("soot" to null in asked)
    }

    @Test
    fun aConfiguredEcuMeansOnlyThatUnitsReadingCounts() {
        val ecu = Rig(testConfig(adblueEcu = "rdcm"))
        ecu.feed(0.seconds, "adblue" to 1.0, ecu = "ecm")
        assertTrue(ecu.eval(0.seconds).alerts.isEmpty())
        ecu.feed(1.seconds, "adblue" to 1.0, ecu = "rdcm")
        assertEquals(listOf("def.level_critical"), ecu.eval(1.seconds).ids())
    }

    // ---- AdBlue

    @Test
    fun adblueLowThenCriticalReplacesLowWithHysteresis() {
        assertTrue(rig.tick(0, *car(adblue = 10.5)).alerts.isEmpty())
        val low = rig.tick(1, *car(adblue = 10.0))
        assertEquals(listOf("def.level_low"), low.ids())
        assertEquals(AlertPriority.WARNING, low.alerts.single().priority)
        assertEquals(Phrase("def.level_low", mapOf("level" to "10")), low.alerts.single().phrase)
        // Hysteresis: still low at 12, cleared above 12.
        assertEquals(listOf("def.level_low"), rig.tick(2, *car(adblue = 12.0)).ids())
        assertTrue(rig.tick(3, *car(adblue = 12.1)).alerts.isEmpty())
        // Critical takes over; only one is active.
        rig.tick(4, *car(adblue = 9.0))
        val critical = rig.tick(5, *car(adblue = 3.0))
        assertEquals(listOf("def.level_critical"), critical.ids())
        assertEquals(AlertPriority.CRITICAL, critical.alerts.single().priority)
        // Back up through the margins: critical until above 5, then low until above 12.
        assertEquals(listOf("def.level_critical"), rig.tick(6, *car(adblue = 5.0)).ids())
        assertEquals(listOf("def.level_low"), rig.tick(7, *car(adblue = 5.1)).ids())
        assertTrue(rig.tick(8, *car(adblue = 12.5)).alerts.isEmpty())
    }

    @Test
    fun anAdblueAlertIsKeptWhileTheLevelIsUnknown() {
        rig.tick(0, *car(adblue = 2.0))
        val state = rig.tick(100, "rpm" to 800.0)
        assertEquals(listOf("def.level_critical"), state.ids())
        assertTrue("adblueLevel" in state.staleInputs)
    }

    @Test
    fun adblueIsSpokenAtOnceThenEveryTwoMinutesWhileLive() {
        val times = (0..400).filter { rig.tick(it, *car(adblue = 2.0)).announcements.isNotEmpty() }
        assertEquals(listOf(0, 120, 240, 360), times)
    }

    @Test
    fun alertsAreNotRepeatedWhileTheLinkIsDown() {
        rig.tick(0, *car(adblue = 2.0))
        val down = (1..400).filter { rig.eval(it.seconds, LinkStatus.LINK_DOWN).announcements.isNotEmpty() }
        assertTrue(down.isEmpty())
    }

    // ---- coolant

    @Test
    fun coolantHighIsCriticalWithHysteresis() {
        assertTrue(rig.tick(0, *car(coolant = 109.9)).alerts.isEmpty())
        val high = rig.tick(1, *car(coolant = 110.0))
        assertEquals(AlertPriority.CRITICAL, high.alerts.single().priority)
        assertEquals(Phrase("engine.coolant_high", mapOf("temp" to "110")), high.alerts.single().phrase)
        assertEquals(listOf("engine.coolant_high"), rig.tick(2, *car(coolant = 107.0)).ids())
        assertTrue(rig.tick(3, *car(coolant = 106.9)).alerts.isEmpty())
    }

    // ---- DPF alerts

    @Test
    fun regenActiveIsAnAdvisorySpokenOnceAtTheStartAndEndsWithTheRegeneration() {
        rig.tick(5, *car(soot = 90.0))
        val start = rig.tick(10, *car(soot = 100.0, status = 2.0))
        assertEquals(listOf("dpf.regen_active"), start.ids())
        assertEquals(AlertPriority.ADVISORY, start.alerts.single().priority)
        assertEquals(listOf("dpf.regen_active"), start.announcements.map { it.id })
        for (t in 11..60) assertTrue(rig.tick(t, *car(soot = 90.0, status = 2.0)).announcements.isEmpty(), "t=$t")
    }

    @Test
    fun regenActiveIsNeitherRaisedNorClearedOnAStaleStatus() {
        rig.tick(10, *car(soot = 100.0, status = 2.0))
        val stale = rig.eval(100.seconds)
        assertEquals(listOf("dpf.regen_active"), stale.ids())
        assertTrue(stale.announcements.isEmpty())
    }

    @Test
    fun aCompletedRegenerationIsAnInfoEventSpokenOnceThatExpires() {
        rig.tick(10, *car(soot = 100.0, status = 2.0))
        rig.tick(20, *car(soot = 40.0, status = 0.0))
        val done = rig.tick(21, *car(soot = 17.65, status = 0.0))
        val event = done.alerts.single { it.id == "dpf.regen_completed" }
        assertEquals(AlertPriority.INFO, event.priority)
        assertEquals(Phrase("dpf.regen_completed", mapOf("sootBefore" to "100", "sootAfter" to "18")), event.phrase)
        assertEquals(listOf("dpf.regen_completed"), done.announcements.map { it.id })
        // The regeneration alert ended with it; the event stays for its lifetime and is not repeated.
        assertTrue(done.alerts.none { it.id == "dpf.regen_active" })
        for (t in 22..141) {
            val state = rig.tick(t, *car(soot = 17.65))
            assertTrue(state.announcements.isEmpty(), "t=$t")
            assertTrue(state.alerts.any { it.id == "dpf.regen_completed" }, "t=$t")
        }
        assertTrue(rig.tick(142, *car(soot = 17.65)).alerts.isEmpty())
    }

    @Test
    fun anEventRightAfterAnotherAnnouncementWaitsOutTheGap() {
        rig.tick(10, *car(soot = 100.0, status = 2.0))
        rig.tick(11, *car(soot = 40.0, status = 0.0))
        val done = rig.tick(12, *car(soot = 17.65, status = 0.0))
        assertTrue(done.announcements.isEmpty())
        assertEquals(listOf("dpf.regen_completed"), rig.tick(13, *car(soot = 17.65)).announcements.map { it.id })
    }

    @Test
    fun aCompletedRegenerationWithoutAKnownStartingSootSaysWhatItCan() {
        val bare = Rig()
        bare.feed(10.seconds, "regen" to 2.0, "rpm" to 800.0)
        bare.eval(10.seconds)
        bare.feed(11.seconds, "regen" to 0.0, "soot" to 12.0, "rpm" to 800.0)
        bare.eval(11.seconds)
        bare.feed(12.seconds, "regen" to 0.0, "soot" to 12.0, "rpm" to 800.0)
        val done = bare.eval(12.seconds)
        assertEquals(Phrase("dpf.regen_completed_after_only", mapOf("sootAfter" to "12")), done.alerts.single { it.id == "dpf.regen_completed" }.phrase)
    }

    @Test
    fun anInterruptedRegenerationIsAWarningSpokenOnceAndRepeatedEveryQuarterHourWhileLive() {
        rig.tick(10, *car(soot = 100.0, status = 2.0))
        rig.tick(20, *car(soot = 85.0, status = 0.0))
        val stopped = rig.tick(21, *car(soot = 85.0, status = 0.0))
        val alert = stopped.alerts.single { it.id == "dpf.regen_interrupted" }
        assertEquals(AlertPriority.WARNING, alert.priority)
        assertEquals(Phrase("dpf.regen_interrupted", mapOf("soot" to "85")), alert.phrase)
        assertEquals(listOf("dpf.regen_interrupted"), stopped.announcements.map { it.id })
        val repeats = (22..2000).filter { rig.tick(it, *car(soot = 85.0)).announcements.isNotEmpty() }
        assertEquals(listOf(921, 1821), repeats)
    }

    @Test
    fun anInterruptedAlertOutlivesTheResultHoldAndClearsWhenSootIsBackToNormal() {
        rig.tick(10, *car(soot = 100.0, status = 2.0))
        rig.tick(11, *car(soot = 60.0, status = 0.0))
        rig.tick(12, *car(soot = 60.0, status = 0.0))
        // Soot is already below the clear threshold, but the alert is not withdrawn the moment it was raised.
        assertTrue(rig.tick(100, *car(soot = 60.0)).alerts.any { it.id == "dpf.regen_interrupted" })
        assertTrue(rig.tick(132, *car(soot = 60.0)).alerts.none { it.id == "dpf.regen_interrupted" })
    }

    @Test
    fun anInterruptedAlertStaysWhileSootIsStillHigh() {
        rig.tick(10, *car(soot = 100.0, status = 2.0))
        rig.tick(11, *car(soot = 90.0, status = 0.0))
        rig.tick(12, *car(soot = 90.0, status = 0.0))
        for (t in 13..400 step 20) assertTrue(rig.tick(t, *car(soot = 85.0)).alerts.any { it.id == "dpf.regen_interrupted" }, "t=$t")
        assertTrue(rig.tick(401, *car(soot = 74.0)).alerts.none { it.id == "dpf.regen_interrupted" })
    }

    @Test
    fun aRegenerationInterruptedByTheEngineBeingSwitchedOffIsSpokenOnceEvenWithTheEngineOff() {
        rig.tick(10, *car(soot = 100.0, status = 2.0))
        val off = rig.tick(20, *car(soot = 70.0, status = 2.0), link = LinkStatus.NO_IGNITION)
        val alert = off.alerts.single { it.id == "dpf.regen_interrupted" }
        assertEquals(Phrase("dpf.regen_interrupted_engine_off", mapOf("soot" to "70")), alert.phrase)
        assertEquals(listOf("dpf.regen_interrupted"), off.announcements.map { it.id })
        // But it is not repeated while the link is not live.
        val later = (21..3000).filter { rig.eval(it.seconds, LinkStatus.NO_IGNITION).announcements.isNotEmpty() }
        assertTrue(later.isEmpty())
    }

    @Test
    fun sootHighNeedsTwoMinutesOfFreshReadingsWithNoRegeneration() {
        val times = ArrayList<Int>()
        for (t in 0..300) if (rig.tick(t, *car(soot = 100.0)).announcements.any { it.id == "dpf.soot_high" }) times += t
        assertEquals(listOf(120), times)
        val state = rig.tick(301, *car(soot = 100.0))
        assertEquals(Phrase("dpf.soot_high", mapOf("soot" to "100")), state.alerts.single { it.id == "dpf.soot_high" }.phrase)
    }

    @Test
    fun sootHighIsNotRaisedWhenTheReadingsKeepDroppingOut() {
        // Fresh soot at 100 % but the status is unknown for a few seconds in every minute: the run is never "continuous".
        for (t in 0..600 step 10) {
            val state = if (t % 60 == 0) rig.tick(t, "soot" to 100.0, "rpm" to 800.0) else rig.tick(t, *car(soot = 100.0))
            assertTrue(state.alerts.none { it.id == "dpf.soot_high" }, "t=$t")
        }
    }

    @Test
    fun sootHighIsKeptThroughAGapAndClearsWhenSootDrops() {
        for (t in 0..130) rig.tick(t, *car(soot = 100.0))
        assertTrue(rig.eval(500.seconds).alerts.any { it.id == "dpf.soot_high" })
        assertTrue(rig.tick(510, *car(soot = 99.0)).alerts.none { it.id == "dpf.soot_high" })
    }

    @Test
    fun sootHighDoesNotRaiseDuringARegeneration() {
        for (t in 0..300) assertTrue(rig.tick(t, *car(soot = 100.0, status = 2.0)).alerts.none { it.id == "dpf.soot_high" }, "t=$t")
    }

    @Test
    fun sootHighIsOffWithoutAStatusSignal() {
        val config = testConfig()
        val noStatus = Rig(config.copy(signals = config.signals.copy(regenStatus = null)))
        for (t in 0..300) assertTrue(noStatus.tick(t, "soot" to 100.0, "rpm" to 800.0).alerts.isEmpty())
    }

    @Test
    fun ruleSectionsLeftOutAreOff() {
        val off = Rig(testConfig(dpf = null, adblue = null, coolant = null, cooldown = null))
        val state = off.tick(0, *car(soot = 100.0, status = 2.0, adblue = 1.0, coolant = 150.0))
        assertTrue(state.alerts.isEmpty())
        assertEquals(emptySet(), state.staleInputs)
    }

    @Test
    fun theWholeListIsInPriorityOrder() {
        rig.tick(0, *car(adblue = 8.0))
        rig.tick(1, *car(adblue = 8.0, coolant = 120.0, soot = 100.0, status = 2.0))
        val state = rig.tick(2, *car(adblue = 8.0, coolant = 120.0, soot = 100.0, status = 2.0))
        assertEquals(listOf("engine.coolant_high", "def.level_low", "dpf.regen_active"), state.ids())
    }
}
