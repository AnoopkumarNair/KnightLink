package knightlink.core.rules

import knightlink.core.signals.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class AnswersTest {
    private val rig = Rig()

    private fun keys(phrases: List<Phrase>) = phrases.map { it.key }

    @Test
    fun noWarningsWhenEverythingIsKnownAndFine() {
        val state = rig.tick(0, *car())
        assertEquals(listOf(Phrase("answer.warnings.none")), Answers.anyWarnings(state))
    }

    @Test
    fun unknownWhenNothingCanBeChecked() {
        assertEquals(listOf(Phrase("answer.warnings.unknown")), Answers.anyWarnings(rig.eval(0.seconds)))
        // The snapshot keeps the last values while the link is down: ten minutes on, they say nothing.
        rig.tick(0, *car(adblue = 50.0))
        assertEquals(listOf(Phrase("answer.warnings.unknown")), Answers.anyWarnings(rig.eval(600.seconds, LinkStatus.LINK_DOWN)))
    }

    @Test
    fun oneWarningWithItsPhrase() {
        val state = rig.tick(0, *car(adblue = 8.0))
        assertEquals(
            listOf(Phrase("answer.warnings.one"), Phrase("def.level_low", mapOf("level" to "8"))),
            Answers.anyWarnings(state),
        )
    }

    @Test
    fun manyWarningsInPriorityOrder() {
        val state = rig.tick(0, *car(adblue = 8.0, coolant = 120.0))
        val answer = Answers.anyWarnings(state)
        assertEquals(Phrase("answer.warnings.many", mapOf("count" to "2")), answer.first())
        assertEquals(listOf("engine.coolant_high", "def.level_low"), keys(answer.drop(1)))
    }

    @Test
    fun infoAlertsAreNotWarnings() {
        rig.tick(10, *car(soot = 100.0, status = 2.0))
        rig.tick(11, *car(soot = 30.0, status = 0.0))
        val state = rig.tick(12, *car(soot = 20.0, status = 0.0))
        assertEquals(true, state.alerts.any { it.priority == AlertPriority.INFO })
        assertEquals(listOf(Phrase("answer.warnings.none")), Answers.anyWarnings(state))
    }

    @Test
    fun partialWhenAnAlertInputIsStale() {
        rig.tick(0, *car())
        // Coolant is slow to refresh in real life, but here it is 40 s old: the rest was checked, that one was not.
        val state = rig.tick(40, "soot" to 20.0, "regen" to 0.0, "rpm" to 800.0, "adblue" to 50.0, "speed" to 40.0, "egt1" to 150.0)
        assertEquals(listOf("answer.warnings.none", "answer.warnings.partial"), keys(Answers.anyWarnings(state)))
        val warned = rig.tick(41, "soot" to 20.0, "regen" to 0.0, "rpm" to 800.0, "adblue" to 8.0, "speed" to 40.0, "egt1" to 150.0)
        assertEquals(listOf("answer.warnings.one", "def.level_low", "answer.warnings.partial"), keys(Answers.anyWarnings(warned)))
    }

    @Test
    fun anInferredSignalNeverFeedsAnAnswer() {
        val inferred = Rig(trust = { _, _ -> Provenance.INFERRED })
        val state = inferred.tick(0, *car(adblue = 1.0, coolant = 150.0, soot = 100.0, status = 2.0))
        assertEquals(listOf(Phrase("answer.warnings.unknown")), Answers.anyWarnings(state))
        assertEquals(Phrase("answer.switch_off.unknown"), Answers.switchOff(state))
        assertEquals(Phrase("answer.dpf.unknown"), Answers.dpf(state))
    }

    @Test
    fun switchOffAnswers() {
        assertEquals(Phrase("answer.switch_off.safe"), Answers.switchOff(rig.tick(0, *car())))
        assertEquals(Phrase("answer.switch_off.engine_off"), Answers.switchOff(Rig().tick(0, *car(rpm = 0.0))))
        assertEquals(Phrase("answer.switch_off.unknown"), Answers.switchOff(Rig().eval(0.seconds)))
    }

    @Test
    fun switchOffDuringARegenerationWithAndWithoutAnEstimate() {
        assertEquals(Phrase("answer.switch_off.regen"), Answers.switchOff(rig.tick(10, *car(soot = 100.0, status = 2.0))))
        // 100 -> 70 in 60 s: 40 more points at 0.5 %/s is 80 s, about a minute.
        assertEquals(Phrase("answer.switch_off.regen_minutes", mapOf("minutes" to "1")), Answers.switchOff(rig.tick(70, *car(soot = 70.0, status = 2.0))))
        // 100 -> 80 in 400 s: 50 more points at 0.05 %/s is 1000 s, about 17 minutes.
        assertEquals(Phrase("answer.switch_off.regen_minutes", mapOf("minutes" to "17")), Answers.switchOff(rig.tick(410, *car(soot = 80.0, status = 2.0))))
    }

    @Test
    fun switchOffWhenHotWithAndWithoutSeconds() {
        assertEquals(
            Phrase("answer.switch_off.hot_seconds", mapOf("seconds" to "120")),
            Answers.switchOff(rig.tick(0, *car(egt = 400.0, speed = 0.0))),
        )
        assertEquals(
            Phrase("answer.switch_off.hot_seconds", mapOf("seconds" to "31")),
            Answers.switchOff(rig.tick(89, *car(egt = 400.0, speed = 0.0)).copy(switchOff = SwitchOffAdvice.NotYet(SwitchOffReason.HOT_EXHAUST, 30.4.seconds))),
        )
        assertEquals(Phrase("answer.switch_off.hot"), Answers.switchOff(rig.tick(200, *car(egt = 400.0, speed = 0.0))))
    }

    @Test
    fun dpfAnswers() {
        assertEquals(Phrase("answer.dpf.unknown"), Answers.dpf(Rig().eval(0.seconds)))
        assertEquals(Phrase("answer.dpf.normal", mapOf("soot" to "42")), Answers.dpf(rig.tick(0, *car(soot = 41.6))))
        assertEquals(Phrase("answer.dpf.soot_rising", mapOf("soot" to "85")), Answers.dpf(rig.tick(1, *car(soot = 85.0))))
        assertEquals(Phrase("answer.dpf.regen_active"), Answers.dpf(rig.tick(10, *car(soot = 99.0, status = 2.0))))
        rig.tick(11, *car(soot = 20.0, status = 0.0))
        assertEquals(Phrase("answer.dpf.completed", mapOf("soot" to "20")), Answers.dpf(rig.tick(12, *car(soot = 20.0, status = 0.0))))
    }

    @Test
    fun dpfAnswerAfterAnInterruption() {
        rig.tick(10, *car(soot = 99.0, status = 2.0))
        rig.tick(11, *car(soot = 60.0, status = 0.0))
        assertEquals(Phrase("answer.dpf.interrupted", mapOf("soot" to "60")), Answers.dpf(rig.tick(12, *car(soot = 60.0, status = 0.0))))
    }

    @Test
    fun normalWithoutASootFigureIsUnknown() {
        rig.feed(0.seconds, "regen" to 0.0, "rpm" to 800.0)
        assertEquals(Phrase("answer.dpf.unknown"), Answers.dpf(rig.eval(0.seconds)))
    }
}
