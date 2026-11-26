package knightlink.app.session

import knightlink.core.runtime.CannotReadReason
import knightlink.core.vehicle.ProbeStage
import knightlink.core.vehicle.UNKNOWN_MAKE

/** The eight steps of the guided flow, in order. */
enum class GuidedStep {
    ADAPTER,
    IGNITION,
    CAR_ANSWERS,
    CAR_IDENTIFIED,
    HEALTH_CHECK,
    READY_TO_DRIVE,
    DRIVE_DETECTED,
    DRIVE_ENDED,
}

enum class StepState { PENDING, ACTIVE, DONE, FAILED }

/**
 * The one plain sentence under a step. The screen maps each to a string resource; [arg] is the text that goes into it (a protocol name, the car's
 * name, a level) and [fraction] a progress from 0 to 1 when the step knows how far it is.
 */
enum class StepSentence {
    // Adapter
    ADAPTER_WAITING,
    ADAPTER_CONNECTING,
    ADAPTER_FOUND,
    ADAPTER_LINK_LOST,
    ADAPTER_FAILED,

    // Ignition
    IGNITION_PROMPT,
    IGNITION_WAITING,
    IGNITION_OK,
    IGNITION_FAILED,

    // Protocol
    ANSWER_LATER,
    ANSWER_LOOKING,
    ANSWER_OK,
    ANSWER_PROTOCOL_UNSUPPORTED,
    ANSWER_GATEWAY,

    // Identity
    IDENTIFY_LATER,
    IDENTIFY_READING,
    IDENTIFY_OK,

    // Health
    HEALTH_LATER,
    HEALTH_RUNNING,
    HEALTH_LEARNING,
    HEALTH_OK,
    HEALTH_STOPPED,
    HEALTH_FLAGGED,

    // Drive
    READY_LATER,
    READY_NOW,
    READY_DONE,
    DRIVE_LATER,
    DRIVE_WAITING,
    DRIVE_UNDER_WAY,
    DRIVE_STARTED,
    REPORT_LATER,
    REPORT_BUILDING,
    REPORT_READY,
}

data class StepDetail(val sentence: StepSentence, val arg: String? = null, val fraction: Float? = null)

/**
 * One row of the guided flow. [canRetry] is set on a failed step (the controller's retry applies), [canSkip] on a health check whose learning
 * ladder or parked body scan can be ended early.
 */
data class GuidedStepItem(
    val step: GuidedStep,
    val state: StepState,
    val detail: StepDetail,
    val canRetry: Boolean = false,
    val canSkip: Boolean = false,
)

/** The whole flow for the screen: the steps, and the one live sentence to show big (the failed step, else the active one, else the last done). */
data class GuidedFlow(val items: List<GuidedStepItem>) {
    val headline: GuidedStepItem
        get() = items.firstOrNull { it.state == StepState.FAILED }
            ?: items.firstOrNull { it.state == StepState.ACTIVE }
            ?: items.lastOrNull { it.state == StepState.DONE }
            ?: items.first()

    /** True from the moment the car may be driven until the drive is over: the screen then shows "You can drive now" prominently. */
    val canDriveNow: Boolean
        get() = items.first { it.step == GuidedStep.READY_TO_DRIVE }.state == StepState.ACTIVE

    fun item(step: GuidedStep): GuidedStepItem = items.first { it.step == step }
}

/**
 * Turns the session's state into the guided flow. Pure: the same [UiState] gives the same steps, so every transition is tested on the JVM.
 *
 * Rules, in short: the adapter is done once the link is set up; the ignition is asked for until the car answers; "car answers" is the protocol
 * negotiation and probe; "identified" holds from the moment the probe produced an identity; the health check is the parked work (learning
 * ladder, body units, fault codes) and is done when none of it runs any more; "ready to drive" is active from then until the car moves.
 */
fun guidedFlow(state: UiState): GuidedFlow {
    val c = state.connection
    val identified = state.identity != null
    val protocolKnown = state.identity?.protocol != null || state.vehicle != null
    val failedAtAdapter = c is ConnectionStatus.Failed && !identified
    val linkLost = c is ConnectionStatus.Reconnecting || c is ConnectionStatus.Recovering
    val cannotRead = c as? ConnectionStatus.CannotRead
    val linkUp = !(c == ConnectionStatus.Idle || c is ConnectionStatus.Connecting || c == ConnectionStatus.Initialising || failedAtAdapter) &&
        !(linkLost && !identified && !protocolKnown)

    val adapter = when {
        c == ConnectionStatus.Idle -> row(GuidedStep.ADAPTER, StepState.PENDING, StepSentence.ADAPTER_WAITING)
        failedAtAdapter -> row(GuidedStep.ADAPTER, StepState.FAILED, StepSentence.ADAPTER_FAILED, canRetry = true)
        linkLost -> row(GuidedStep.ADAPTER, StepState.ACTIVE, StepSentence.ADAPTER_LINK_LOST)
        linkUp -> row(GuidedStep.ADAPTER, StepState.DONE, StepSentence.ADAPTER_FOUND)
        else -> row(GuidedStep.ADAPTER, StepState.ACTIVE, StepSentence.ADAPTER_CONNECTING)
    }

    val asleepBeforeDrive = c is ConnectionStatus.VehicleAsleep && !identified && state.driveStage == DriveStage.NOT_STARTED
    val ignition = when {
        !linkUp -> row(GuidedStep.IGNITION, StepState.PENDING, StepSentence.IGNITION_PROMPT)
        cannotRead?.reason == CannotReadReason.NoResponse -> row(GuidedStep.IGNITION, StepState.FAILED, StepSentence.IGNITION_FAILED, canRetry = true)
        c is ConnectionStatus.WaitingForIgnition || asleepBeforeDrive ->
            row(GuidedStep.IGNITION, StepState.ACTIVE, StepSentence.IGNITION_WAITING)
        else -> row(GuidedStep.IGNITION, StepState.DONE, StepSentence.IGNITION_OK)
    }

    val ignitionDone = ignition.state == StepState.DONE
    val probingInfo = c is ConnectionStatus.Probing && c.stage in INFO_STAGES
    val answers = when {
        !ignitionDone -> row(GuidedStep.CAR_ANSWERS, StepState.PENDING, StepSentence.ANSWER_LATER)
        cannotRead?.reason is CannotReadReason.UnsupportedProtocol ->
            row(GuidedStep.CAR_ANSWERS, StepState.FAILED, StepSentence.ANSWER_PROTOCOL_UNSUPPORTED, arg = cannotRead.protocol, canRetry = true)
        cannotRead?.reason == CannotReadReason.GatewayBlocked -> row(GuidedStep.CAR_ANSWERS, StepState.FAILED, StepSentence.ANSWER_GATEWAY, canRetry = true)
        protocolKnown || identified || probingInfo || c == ConnectionStatus.Live || c is ConnectionStatus.Learning ->
            row(GuidedStep.CAR_ANSWERS, StepState.DONE, StepSentence.ANSWER_OK, arg = state.identity?.protocol)
        else -> row(GuidedStep.CAR_ANSWERS, StepState.ACTIVE, StepSentence.ANSWER_LOOKING)
    }

    val answersDone = answers.state == StepState.DONE
    val name = state.identity?.let { listOfNotNull(it.make.takeIf { make -> make != UNKNOWN_MAKE }, it.model, it.year?.toString()).joinToString(" ") }
    val identify = when {
        !answersDone -> row(GuidedStep.CAR_IDENTIFIED, StepState.PENDING, StepSentence.IDENTIFY_LATER)
        identified -> row(GuidedStep.CAR_IDENTIFIED, StepState.DONE, StepSentence.IDENTIFY_OK, arg = name?.takeIf { it.isNotBlank() })
        else -> row(GuidedStep.CAR_IDENTIFIED, StepState.ACTIVE, StepSentence.IDENTIFY_READING)
    }

    val identifyDone = identify.state == StepState.DONE
    val ladder = state.ladder
    val body = state.bodyScan
    // A check that started by itself at a red light is not "parked work": the driver is under way and is not asked to wait for it.
    val parkedWork = ladder is LadderProgress.Running || (body is BodyScanProgress.Running && !body.auto) || state.faults.status == FaultsStatus.READING
    val health = when {
        !identifyDone -> row(GuidedStep.HEALTH_CHECK, StepState.PENDING, StepSentence.HEALTH_LATER)
        ladder is LadderProgress.Running ->
            row(GuidedStep.HEALTH_CHECK, StepState.ACTIVE, StepSentence.HEALTH_LEARNING, arg = ladder.label, fraction = ladder.fraction, canSkip = true)
        body is BodyScanProgress.Running && !body.auto ->
            row(GuidedStep.HEALTH_CHECK, StepState.ACTIVE, StepSentence.HEALTH_RUNNING, arg = body.label, fraction = body.fraction, canSkip = true)
        state.faults.status == FaultsStatus.READING -> row(GuidedStep.HEALTH_CHECK, StepState.ACTIVE, StepSentence.HEALTH_RUNNING)
        ladder is LadderProgress.Flagged -> row(GuidedStep.HEALTH_CHECK, StepState.DONE, StepSentence.HEALTH_FLAGGED, arg = ladder.reason)
        ladder is LadderProgress.Stopped -> row(GuidedStep.HEALTH_CHECK, StepState.DONE, StepSentence.HEALTH_STOPPED, arg = ladder.level)
        else -> row(GuidedStep.HEALTH_CHECK, StepState.DONE, StepSentence.HEALTH_OK)
    }

    val healthDone = health.state == StepState.DONE && !parkedWork
    val stage = state.driveStage
    val ready = when {
        !healthDone -> row(GuidedStep.READY_TO_DRIVE, StepState.PENDING, StepSentence.READY_LATER)
        stage == DriveStage.NOT_STARTED -> row(GuidedStep.READY_TO_DRIVE, StepState.ACTIVE, StepSentence.READY_NOW)
        else -> row(GuidedStep.READY_TO_DRIVE, StepState.DONE, StepSentence.READY_DONE)
    }

    val driveDetected = when {
        stage == DriveStage.NOT_STARTED && healthDone -> row(GuidedStep.DRIVE_DETECTED, StepState.PENDING, StepSentence.DRIVE_WAITING)
        stage == DriveStage.NOT_STARTED -> row(GuidedStep.DRIVE_DETECTED, StepState.PENDING, StepSentence.DRIVE_LATER)
        stage == DriveStage.DRIVING -> row(GuidedStep.DRIVE_DETECTED, StepState.ACTIVE, StepSentence.DRIVE_UNDER_WAY)
        else -> row(GuidedStep.DRIVE_DETECTED, StepState.DONE, StepSentence.DRIVE_STARTED)
    }

    val ended = when {
        stage != DriveStage.ENDED -> row(GuidedStep.DRIVE_ENDED, StepState.PENDING, StepSentence.REPORT_LATER)
        state.driveTruth == null -> row(GuidedStep.DRIVE_ENDED, StepState.ACTIVE, StepSentence.REPORT_BUILDING)
        else -> row(GuidedStep.DRIVE_ENDED, StepState.DONE, StepSentence.REPORT_READY)
    }

    return GuidedFlow(listOf(adapter, ignition, answers, identify, health, ready, driveDetected, ended))
}

private fun row(
    step: GuidedStep,
    state: StepState,
    sentence: StepSentence,
    arg: String? = null,
    fraction: Float? = null,
    canRetry: Boolean = false,
    canSkip: Boolean = false,
) = GuidedStepItem(step, state, StepDetail(sentence, arg, fraction), canRetry, canSkip)

private val INFO_STAGES = setOf(ProbeStage.VEHICLE_INFO, ProbeStage.BENCHMARK, ProbeStage.DERIVED_FACTS)
