package knightlink.app.session

import kotlin.test.Test
import kotlin.test.assertEquals

class DriveStageTrackerTest {
    @Test
    fun aDriveStartsWhenTheCarMoves() {
        val tracker = DriveStageTracker()
        assertEquals(DriveStage.NOT_STARTED, tracker.update(0, 0.0, 800.0))
        assertEquals(DriveStage.NOT_STARTED, tracker.update(1000, 2.0, 800.0))
        assertEquals(DriveStage.DRIVING, tracker.update(2000, 12.0, 1500.0))
    }

    @Test
    fun aTrafficLightStopWithTheEngineRunningNeverEndsTheDrive() {
        val tracker = DriveStageTracker(endAfterMs = 60_000)
        tracker.update(0, 40.0, 2000.0)
        for (t in 1..300) assertEquals(DriveStage.DRIVING, tracker.update(t * 1000L, 0.0, 750.0))
    }

    @Test
    fun anIdleStopWhereTheSpeedIsReadButTheRpmIsZeroEndsItOnlyAfterTheLongQuiet() {
        val tracker = DriveStageTracker(endAfterMs = 60_000)
        tracker.update(0, 40.0, 2000.0)
        assertEquals(DriveStage.DRIVING, tracker.update(10_000, 0.0, 0.0))
        assertEquals(DriveStage.DRIVING, tracker.update(69_000, 0.0, 0.0))
        assertEquals(DriveStage.ENDED, tracker.update(70_000, 0.0, 0.0))
    }

    @Test
    fun movingAgainBeforeTheEndRestartsTheQuietClock() {
        val tracker = DriveStageTracker(endAfterMs = 60_000)
        tracker.update(0, 40.0, 2000.0)
        tracker.update(10_000, 0.0, 0.0)
        tracker.update(50_000, 20.0, 1500.0)
        assertEquals(DriveStage.DRIVING, tracker.update(100_000, 0.0, 0.0))
        assertEquals(DriveStage.ENDED, tracker.update(160_000, 0.0, 0.0))
    }

    @Test
    fun anUnreadableSignalIsNotKnownAndNeverCountsAsStanding() {
        val tracker = DriveStageTracker(endAfterMs = 60_000)
        tracker.update(0, 40.0, 2000.0)
        assertEquals(DriveStage.DRIVING, tracker.update(10_000, null, null))
        assertEquals(DriveStage.DRIVING, tracker.update(200_000, null, null))
        assertEquals(DriveStage.DRIVING, tracker.update(300_000, 0.0, null))
    }

    @Test
    fun aCarThatWentToSleepEndsTheDriveAfterTheQuiet() {
        val tracker = DriveStageTracker(endAfterMs = 60_000)
        tracker.update(0, 40.0, 2000.0)
        tracker.update(5_000, null, null, asleep = true)
        assertEquals(DriveStage.ENDED, tracker.update(65_000, null, null, asleep = true))
    }

    @Test
    fun sleepingBeforeAnyDriveStartsNothing() {
        val tracker = DriveStageTracker()
        assertEquals(DriveStage.NOT_STARTED, tracker.update(0, null, null, asleep = true))
        assertEquals(DriveStage.NOT_STARTED, tracker.update(1_000_000, null, null, asleep = true))
    }

    @Test
    fun movingAfterTheEndStartsANewDriveAndResetGoesBack() {
        val tracker = DriveStageTracker(endAfterMs = 1000)
        tracker.update(0, 40.0, 2000.0)
        tracker.update(1000, 0.0, 0.0)
        assertEquals(DriveStage.ENDED, tracker.update(2000, 0.0, 0.0))
        assertEquals(DriveStage.DRIVING, tracker.update(3000, 30.0, 1800.0))
        tracker.reset()
        assertEquals(DriveStage.NOT_STARTED, tracker.stage)
    }
}
