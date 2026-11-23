package knightlink.app.session

import knightlink.core.runtime.CannotReadReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GuidedStepTest {
    private val frames = DemoDrive.frames

    private fun states(flow: GuidedFlow) = flow.items.map { it.state }

    private fun idle() = UiState.IDLE

    @Test
    fun idleIsAllPendingAndAsksToConnect() {
        val flow = guidedFlow(idle())
        assertEquals(GuidedStep.entries, flow.items.map { it.step })
        assertTrue(flow.items.all { it.state == StepState.PENDING })
        assertEquals(StepSentence.ADAPTER_WAITING, flow.headline.detail.sentence)
        assertFalse(flow.canDriveNow)
    }

    @Test
    fun connectingMakesTheAdapterStepActive() {
        val flow = guidedFlow(frames[0])
        assertEquals(StepState.ACTIVE, flow.item(GuidedStep.ADAPTER).state)
        assertEquals(StepSentence.ADAPTER_CONNECTING, flow.headline.detail.sentence)
        assertEquals(StepState.PENDING, flow.item(GuidedStep.IGNITION).state)
    }

    @Test
    fun whileTheCarSleepsTheIgnitionStepAsksToStartTheCar() {
        val flow = guidedFlow(frames[2])
        assertEquals(StepState.DONE, flow.item(GuidedStep.ADAPTER).state)
        assertEquals(StepState.ACTIVE, flow.item(GuidedStep.IGNITION).state)
        assertEquals(StepSentence.IGNITION_WAITING, flow.headline.detail.sentence)
        val asleep = guidedFlow(frames[2].copy(connection = ConnectionStatus.VehicleAsleep(2)))
        assertEquals(StepState.ACTIVE, asleep.item(GuidedStep.IGNITION).state)
    }

    @Test
    fun negotiatingTicksTheIgnitionAndStartsTheProtocolStep() {
        val flow = guidedFlow(frames[3])
        assertEquals(StepState.DONE, flow.item(GuidedStep.IGNITION).state)
        assertEquals(StepState.ACTIVE, flow.item(GuidedStep.CAR_ANSWERS).state)
        assertEquals(StepState.PENDING, flow.item(GuidedStep.CAR_IDENTIFIED).state)
    }

    @Test
    fun readingTheVehicleInformationTicksTheProtocolAndWorksOnTheIdentity() {
        val flow = guidedFlow(frames[4])
        assertEquals(StepState.DONE, flow.item(GuidedStep.CAR_ANSWERS).state)
        assertEquals(StepState.ACTIVE, flow.item(GuidedStep.CAR_IDENTIFIED).state)
    }

    @Test
    fun aCarThatNeverAnswersFailsTheIgnitionStepWithRetryAndNoOtherStepClaimsProgress() {
        val flow = guidedFlow(frames[3].copy(connection = ConnectionStatus.CannotRead(CannotReadReason.NoResponse, null)))
        val ignition = flow.item(GuidedStep.IGNITION)
        assertEquals(StepState.FAILED, ignition.state)
        assertTrue(ignition.canRetry)
        assertEquals(StepSentence.IGNITION_FAILED, flow.headline.detail.sentence)
        assertEquals(StepState.PENDING, flow.item(GuidedStep.CAR_ANSWERS).state)
    }

    @Test
    fun anUnsupportedProtocolFailsTheProtocolStepAndNamesIt() {
        val flow = guidedFlow(frames[3].copy(connection = ConnectionStatus.CannotRead(CannotReadReason.UnsupportedProtocol("29 bit CAN"), "29 bit CAN")))
        val step = flow.item(GuidedStep.CAR_ANSWERS)
        assertEquals(StepState.FAILED, step.state)
        assertEquals("29 bit CAN", step.detail.arg)
        assertTrue(step.canRetry)
        assertEquals(StepState.DONE, flow.item(GuidedStep.IGNITION).state)
    }

    @Test
    fun aBlockedGatewayFailsTheProtocolStep() {
        val flow = guidedFlow(frames[3].copy(connection = ConnectionStatus.CannotRead(CannotReadReason.GatewayBlocked, null)))
        assertEquals(StepSentence.ANSWER_GATEWAY, flow.item(GuidedStep.CAR_ANSWERS).detail.sentence)
        assertEquals(StepState.FAILED, flow.item(GuidedStep.CAR_ANSWERS).state)
    }

    @Test
    fun aFailedLinkBeforeTheCarWasReadFailsTheAdapterStep() {
        val flow = guidedFlow(UiState(ConnectionStatus.Failed("no bluetooth"), vehicle = null, liveValues = emptyList()))
        assertEquals(StepState.FAILED, flow.item(GuidedStep.ADAPTER).state)
        assertTrue(flow.item(GuidedStep.ADAPTER).canRetry)
        assertEquals(StepSentence.ADAPTER_FAILED, flow.headline.detail.sentence)
    }

    @Test
    fun theLadderIsTheHealthCheckWithProgressAndASkip() {
        val flow = guidedFlow(frames[5])
        val health = flow.item(GuidedStep.HEALTH_CHECK)
        assertEquals(StepState.ACTIVE, health.state)
        assertEquals(StepSentence.HEALTH_LEARNING, health.detail.sentence)
        assertEquals(0.5f, health.detail.fraction)
        assertTrue(health.canSkip)
        assertEquals(StepState.DONE, flow.item(GuidedStep.CAR_IDENTIFIED).state)
        assertEquals(StepState.PENDING, flow.item(GuidedStep.READY_TO_DRIVE).state)
        assertFalse(flow.canDriveNow)
    }

    @Test
    fun theBodyScanIsTheHealthCheckToo() {
        val running = frames[7].copy(bodyScan = BodyScanProgress.Running("Cluster", 4, 10, 30))
        val health = guidedFlow(running).item(GuidedStep.HEALTH_CHECK)
        assertEquals(StepState.ACTIVE, health.state)
        assertEquals(StepSentence.HEALTH_RUNNING, health.detail.sentence)
        assertEquals("Cluster", health.detail.arg)
        assertEquals(0.4f, health.detail.fraction)
        assertTrue(health.canSkip, "the parked body scan can be skipped too")
    }

    @Test
    fun whenTheHealthCheckIsDoneTheCarMayBeDrivenAndSaysSo() {
        val flow = guidedFlow(frames[7])
        assertEquals(StepState.DONE, flow.item(GuidedStep.HEALTH_CHECK).state)
        assertEquals(StepState.ACTIVE, flow.item(GuidedStep.READY_TO_DRIVE).state)
        assertTrue(flow.canDriveNow)
        assertEquals(StepSentence.READY_NOW, flow.headline.detail.sentence)
        assertEquals(StepSentence.DRIVE_WAITING, flow.item(GuidedStep.DRIVE_DETECTED).detail.sentence)
    }

    @Test
    fun aLadderThatStoppedOrWasFlaggedStillLetsTheDriverGo() {
        val stopped = guidedFlow(frames[7].copy(ladder = LadderProgress.Stopped("L2", "car moved")))
        assertEquals(StepSentence.HEALTH_STOPPED, stopped.item(GuidedStep.HEALTH_CHECK).detail.sentence)
        assertEquals(StepState.DONE, stopped.item(GuidedStep.HEALTH_CHECK).state)
        assertTrue(stopped.canDriveNow)
        val flagged = guidedFlow(frames[7].copy(ladder = LadderProgress.Flagged("a new code")))
        assertEquals(StepSentence.HEALTH_FLAGGED, flagged.item(GuidedStep.HEALTH_CHECK).detail.sentence)
        assertTrue(flagged.canDriveNow)
    }

    @Test
    fun drivingTicksReadyAndStartsTheDriveStep() {
        val flow = guidedFlow(frames[8])
        assertEquals(StepState.DONE, flow.item(GuidedStep.READY_TO_DRIVE).state)
        assertEquals(StepState.ACTIVE, flow.item(GuidedStep.DRIVE_DETECTED).state)
        assertFalse(flow.canDriveNow)
    }

    @Test
    fun afterTheDriveTheReportStepWaitsForTheReportAndThenIsDone() {
        val building = guidedFlow(frames[9])
        assertEquals(StepState.DONE, building.item(GuidedStep.DRIVE_DETECTED).state)
        assertEquals(StepState.ACTIVE, building.item(GuidedStep.DRIVE_ENDED).state)
        val done = guidedFlow(frames[10])
        assertNotNull(frames[10].driveTruth)
        assertTrue(done.items.all { it.state == StepState.DONE }, states(done).toString())
    }

    @Test
    fun aLostLinkAfterTheCarWasReadOnlyTouchesTheAdapterStep() {
        val flow = guidedFlow(frames[7].copy(connection = ConnectionStatus.Reconnecting(1, 3, "link lost")))
        assertEquals(StepState.ACTIVE, flow.item(GuidedStep.ADAPTER).state)
        assertEquals(StepSentence.ADAPTER_LINK_LOST, flow.item(GuidedStep.ADAPTER).detail.sentence)
        assertEquals(StepState.DONE, flow.item(GuidedStep.CAR_IDENTIFIED).state)
    }

    @Test
    fun theDemoFramesNeverGoBackwards() {
        var lastDone = -1
        for (frame in frames) {
            val done = guidedFlow(frame).items.count { it.state == StepState.DONE }
            assertTrue(done >= lastDone, "a step un-ticked: $done after $lastDone")
            lastDone = done
        }
        assertEquals(GuidedStep.entries.size, lastDone)
    }
}
