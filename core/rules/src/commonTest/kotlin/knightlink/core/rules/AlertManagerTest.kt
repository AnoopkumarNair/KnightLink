package knightlink.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AlertManagerTest {
    private val manager = AlertManager(testAlertsConfig())

    private fun active(priority: AlertPriority, key: String = "k", speakable: Boolean = true, reannounce: Boolean = true) =
        Condition.Active(priority, Phrase(key), speakable, reannounce)

    private fun update(seconds: Int, vararg conditions: Pair<String, Condition>): AlertManager.Result = manager.update(seconds.seconds, conditions.toMap())

    private fun AlertManager.Result.spokenIds() = announcements.map { it.id }

    @Test
    fun aNewAlertIsAnnouncedAtOnceAndOnlyOnce() {
        val first = update(0, "a" to active(AlertPriority.WARNING))
        assertEquals(listOf("a"), first.spokenIds())
        assertEquals(Duration.ZERO, first.alerts.single().lastAnnounced)
        // The same alert again is the same alert: nothing new to say, and it keeps the time it was first seen.
        val second = update(5, "a" to active(AlertPriority.WARNING))
        assertEquals(emptyList(), second.spokenIds())
        assertEquals(1, second.alerts.size)
        assertEquals(Duration.ZERO, second.alerts.single().firstSeen)
    }

    @Test
    fun theLatestPhraseIsKeptWhileActive() {
        update(0, "a" to Condition.Active(AlertPriority.WARNING, Phrase("k", mapOf("level" to "9"))))
        val later = update(10, "a" to Condition.Active(AlertPriority.WARNING, Phrase("k", mapOf("level" to "8"))))
        assertEquals("8", later.alerts.single().phrase.params["level"])
    }

    /** Seconds (from 0, polled every second) at which an alert of [priority] that stays active is spoken. */
    private fun spokenTimes(priority: AlertPriority, until: Int): List<Int> {
        val own = AlertManager(testAlertsConfig())
        return (0..until).filter { own.update(it.seconds, mapOf("a" to active(priority))).announcements.isNotEmpty() }
    }

    @Test
    fun alertsAreRepeatedAtTheIntervalOfTheirPriority() {
        assertEquals(listOf(0, 120, 240, 360), spokenTimes(AlertPriority.CRITICAL, 400))
        // A warning every 15 minutes, counted from when it was last spoken; advisory and info only once.
        assertEquals(listOf(0, 900, 1800), spokenTimes(AlertPriority.WARNING, 2000))
        assertEquals(listOf(0), spokenTimes(AlertPriority.ADVISORY, 2000))
        assertEquals(listOf(0), spokenTimes(AlertPriority.INFO, 2000))
    }

    @Test
    fun anAlertThatClearsIsRemoved() {
        update(0, "a" to active(AlertPriority.WARNING))
        val cleared = update(10, "a" to Condition.Inactive)
        assertTrue(cleared.alerts.isEmpty())
    }

    @Test
    fun anAlertThatFlapsIsNotAnnouncedAgainWithinTheQuietPeriod() {
        update(0, "a" to active(AlertPriority.WARNING))
        update(10, "a" to Condition.Inactive)
        // Back after 30 s: active, but still silent, and the earlier announcement stands.
        val back = update(40, "a" to active(AlertPriority.WARNING))
        assertEquals(emptyList(), back.spokenIds())
        assertEquals(Duration.ZERO, back.alerts.single().lastAnnounced)
        assertEquals(40.seconds, back.alerts.single().firstSeen)
        // Cleared again and back after more than the quiet period: announced as new.
        update(50, "a" to Condition.Inactive)
        val later = update(120, "a" to active(AlertPriority.WARNING))
        assertEquals(listOf("a"), later.spokenIds())
    }

    @Test
    fun aFlappingAlertIsStillRepeatedByItsOldSchedule() {
        update(0, "a" to active(AlertPriority.CRITICAL))
        update(10, "a" to Condition.Inactive)
        update(20, "a" to active(AlertPriority.CRITICAL))
        assertEquals(emptyList(), update(119, "a" to active(AlertPriority.CRITICAL)).spokenIds())
        assertEquals(listOf("a"), update(120, "a" to active(AlertPriority.CRITICAL)).spokenIds())
    }

    @Test
    fun nonCriticalAnnouncementsInsideTheGapAreHeldAndReleasedAfterIt() {
        update(0, "a" to active(AlertPriority.WARNING))
        val held1 = update(1, "a" to active(AlertPriority.WARNING), "b" to active(AlertPriority.WARNING))
        assertEquals(emptyList(), held1.spokenIds())
        assertNull(held1.alerts.first { it.id == "b" }.lastAnnounced)
        assertEquals(emptyList(), update(2, "a" to active(AlertPriority.WARNING), "b" to active(AlertPriority.WARNING)).spokenIds())
        assertEquals(listOf("b"), update(3, "a" to active(AlertPriority.WARNING), "b" to active(AlertPriority.WARNING)).spokenIds())
        assertEquals(emptyList(), update(4, "a" to active(AlertPriority.WARNING), "b" to active(AlertPriority.WARNING)).spokenIds())
    }

    @Test
    fun anAlertThatClearsWhileHeldIsNeverAnnounced() {
        val spoken = ArrayList<String>()
        fun step(seconds: Int, vararg conditions: Pair<String, Condition>) {
            spoken += update(seconds, *conditions).spokenIds()
        }
        step(0, "a" to active(AlertPriority.WARNING))
        step(1, "a" to active(AlertPriority.WARNING), "b" to active(AlertPriority.WARNING))
        step(2, "a" to active(AlertPriority.WARNING), "b" to Condition.Inactive)
        step(10, "a" to active(AlertPriority.WARNING))
        assertEquals(listOf("a"), spoken)
    }

    @Test
    fun criticalAlertsIgnoreTheGapAndHoldBackTheOthers() {
        update(0, "a" to active(AlertPriority.WARNING))
        val critical = update(1, "a" to active(AlertPriority.WARNING), "c" to active(AlertPriority.CRITICAL), "w" to active(AlertPriority.WARNING))
        assertEquals(listOf("c"), critical.spokenIds())
        // The critical announcement counted as the batch: the held warning waits for the gap after it.
        assertEquals(emptyList(), update(2, "c" to active(AlertPriority.CRITICAL), "w" to active(AlertPriority.WARNING)).spokenIds())
        assertEquals(listOf("w"), update(4, "c" to active(AlertPriority.CRITICAL), "w" to active(AlertPriority.WARNING)).spokenIds())
    }

    @Test
    fun alertsDueTogetherAreOneBatchInPriorityOrder() {
        val result = update(
            0,
            "z" to active(AlertPriority.ADVISORY),
            "b" to active(AlertPriority.WARNING),
            "a" to active(AlertPriority.WARNING),
            "c" to active(AlertPriority.CRITICAL),
        )
        assertEquals(listOf("c", "a", "b", "z"), result.spokenIds())
        assertEquals(listOf("c", "a", "b", "z"), result.alerts.map { it.id })
    }

    @Test
    fun orderIsPriorityThenFirstSeenThenId() {
        update(0, "late-warning" to active(AlertPriority.WARNING))
        update(10, "b" to active(AlertPriority.WARNING), "a" to active(AlertPriority.WARNING), "info" to active(AlertPriority.INFO))
        val all = update(20, "crit" to active(AlertPriority.CRITICAL))
        assertEquals(listOf("crit", "late-warning", "a", "b", "info"), all.alerts.map { it.id })
    }

    @Test
    fun anUnknownConditionKeepsTheAlertAsItWasAndDoesNotRepeatIt() {
        update(0, "a" to active(AlertPriority.WARNING))
        // 20 minutes pass with the inputs gone: still listed, not cleared, and not spoken again on evidence that is no longer there.
        val unknown = update(1200, "a" to Condition.Unknown)
        assertEquals(listOf("a"), unknown.alerts.map { it.id })
        assertEquals(emptyList(), unknown.spokenIds())
        // An alert that is absent from the conditions is treated the same way.
        assertEquals(listOf("a"), manager.update(1300.seconds, emptyMap()).alerts.map { it.id })
        // When the inputs return and it still holds, it is due again.
        assertEquals(listOf("a"), update(1400, "a" to active(AlertPriority.WARNING)).spokenIds())
    }

    @Test
    fun anUnknownConditionNeverRaisesAnAlert() {
        assertTrue(update(0, "a" to Condition.Unknown).alerts.isEmpty())
    }

    @Test
    fun anAlertThatIsNotSpeakableIsListedButNotSpokenUntilItIs() {
        val silent = update(0, "a" to active(AlertPriority.WARNING, speakable = false))
        assertEquals(emptyList(), silent.spokenIds())
        assertEquals(false, silent.alerts.single().speakable)
        assertEquals(listOf("a"), update(5, "a" to active(AlertPriority.WARNING)).spokenIds())
    }

    @Test
    fun repeatsCanBeSwitchedOffPerAlert() {
        update(0, "a" to active(AlertPriority.CRITICAL, reannounce = false))
        assertEquals(emptyList(), update(500, "a" to active(AlertPriority.CRITICAL, reannounce = false)).spokenIds())
        assertEquals(listOf("a"), update(501, "a" to active(AlertPriority.CRITICAL)).spokenIds())
    }

    @Test
    fun anEventIsSpokenOnceAndExpiresAfterItsLifetime() {
        manager.raiseEvent("done", AlertPriority.INFO, Phrase("k"), Duration.ZERO)
        assertEquals(listOf("done"), manager.update(Duration.ZERO, emptyMap()).spokenIds())
        assertEquals(listOf("done"), manager.update(120.seconds, emptyMap()).alerts.map { it.id })
        // Never repeated, whatever the priority interval.
        assertEquals(emptyList(), manager.update(120.seconds, emptyMap()).spokenIds())
        assertTrue(manager.update(121.seconds, emptyMap()).alerts.isEmpty())
    }

    @Test
    fun anEventInsideTheGapIsHeldUntilItIsOver() {
        update(0, "a" to active(AlertPriority.WARNING))
        manager.raiseEvent("done", AlertPriority.INFO, Phrase("k"), 1.seconds)
        assertEquals(emptyList(), manager.update(1.seconds, emptyMap()).spokenIds())
        assertEquals(listOf("done"), manager.update(3.seconds, emptyMap()).spokenIds())
    }
}
