package knightlink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import knightlink.app.R
import knightlink.app.ble.LastAdapter
import knightlink.app.session.CarProfile
import knightlink.app.session.ConnectionStatus
import knightlink.app.session.FuelType
import knightlink.app.session.GarageStatus
import knightlink.app.session.GuidedStep
import knightlink.app.session.GuidedStepItem
import knightlink.app.session.StepState
import knightlink.app.session.Transmission
import knightlink.app.session.UiState
import knightlink.app.session.guidedFlow
import knightlink.app.ui.design.KlCard
import knightlink.app.ui.design.KlColors
import knightlink.app.ui.design.KlSectionTitle
import knightlink.app.ui.design.KlSpacing
import knightlink.app.ui.design.KlTextField
import knightlink.app.ui.design.LONG_WAIT_SECONDS
import knightlink.app.ui.design.PrimaryButton
import knightlink.app.ui.design.QuietButton
import knightlink.app.ui.design.SecondaryButton
import knightlink.app.ui.design.StepItem
import knightlink.core.vehicle.UNKNOWN_MAKE
import knightlink.core.vehicle.VehicleIdentity
import kotlinx.coroutines.delay

/** What the friend-facing screens can do besides connecting; one object so a screen's signature does not grow with every feature. */
class AppActions(
    /** Keeps what the driver told about the car (and its odometer). */
    val onSaveProfile: (CarProfile) -> Unit = {},
    val onFuelPrice: (String) -> Unit = {},
    /** Opens the consent text, then makes and shares the findings file. */
    val onShareFindings: () -> Unit = {},
    /** Hands plain text to the share sheet (the Drive Truth card). */
    val onShareText: (String) -> Unit = {},
    val appVersion: String = "",
    /** Non-null only in debug builds: plays a made-up drive through the screens. */
    val demo: DemoActions? = null,
    /** The system clock, for the date an odometer was noted. */
    val now: () -> Long = { System.currentTimeMillis() },
)

class DemoActions(val active: Boolean, val onStart: () -> Unit, val onLeave: () -> Unit)

/**
 * The guided connect flow: one big live sentence, eight ticking steps, "You can drive now" when it is time, the new-car or welcome-back card, and
 * the Drive Truth card when a drive has ended. Nothing here says hex, an ECU id or a VIN.
 */
@Composable
fun ScanScreen(
    state: UiState,
    lastAdapter: LastAdapter?,
    actions: ConnectionActions,
    app: AppActions,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val flow = remember(state) { guidedFlow(state) }
    val idle = !state.isActive
    var editing by rememberSaveable { mutableStateOf(false) }
    // The end readings: asked for in a dialog on Disconnect, and in a card once the drive is over, while the drive's start has no end yet.
    var askEndOnDisconnect by rememberSaveable { mutableStateOf(false) }
    var endPromptSkippedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    val needsEnd = state.dashboard.needsEnd(app.now())
    if (askEndOnDisconnect) {
        EndReadingsPrompt(
            state.dashboard,
            dialog = true,
            onSave = { odo, dte ->
                askEndOnDisconnect = false
                actions.onDashboard(odo, dte, true)
                actions.onDisconnect()
            },
            onSkip = {
                askEndOnDisconnect = false
                endPromptSkippedAt = state.dashboard.startSavedAtMs
                actions.onDisconnect()
            },
        )
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(KlSpacing.Gutter),
        verticalArrangement = Arrangement.spacedBy(KlSpacing.L),
    ) {
        val headline = flow.headline
        Text(
            headline.detail.text(resources),
            style = MaterialTheme.typography.headlineMedium,
            color = if (headline.state == StepState.FAILED) KlColors.Problem else KlColors.OnSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        if (flow.canDriveNow) DriveNowCard()

        if (idle && needsEnd && endPromptSkippedAt != state.dashboard.startSavedAtMs) {
            EndReadingsPrompt(
                state.dashboard,
                dialog = false,
                onSave = { odo, dte -> actions.onDashboard(odo, dte, true) },
                onSkip = { endPromptSkippedAt = state.dashboard.startSavedAtMs },
            )
        }

        val linkConnecting = state.connection is ConnectionStatus.Connecting ||
            state.connection == ConnectionStatus.Initialising ||
            state.connection is ConnectionStatus.Reconnecting ||
            state.connection is ConnectionStatus.Recovering
        if (!idle && !linkConnecting) ParkedCheckCard(state, actions.onRunParkedCheckAgain, actions.onSkipLadder)

        if (idle) {
            IdleActions(lastAdapter, actions, app.demo)
        } else if (!actions.backgroundAllowed) {
            KlCard {
                Text(stringResource(R.string.background_explanation), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
                SecondaryButton(stringResource(R.string.action_allow_background), actions.onAllowBackground)
            }
        }

        KlCard {
            for (item in flow.items) StepRow(item, state, actions)
        }

        CarCard(state, editing, { editing = it }, app)

        if (state.twinReady) {
            KlCard {
                KlSectionTitle(stringResource(R.string.twin_ready_title))
                Text(stringResource(R.string.twin_ready_body), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
                PrimaryButton(stringResource(R.string.twin_ready_export), app.onShareFindings)
            }
        }

        state.driveTruth?.let { DriveTruthCard(it, app.onShareText) }

        if (!idle && state.driveModes.isNotEmpty()) DriveModeCard(state.driveModes, state.drive, recording = true, onMode = actions.onDriveMode)
        DriveReadingsCard(state.dashboard, actions.onDashboard)

        if (!idle) {
            SecondaryButton(
                stringResource(R.string.action_disconnect),
                { if (needsEnd && app.demo?.active != true) askEndOnDisconnect = true else actions.onDisconnect() },
            )
        }
        app.demo?.takeIf { it.active }?.let {
            SecondaryButton(stringResource(R.string.demo_drive_leave), it.onLeave)
        }
    }
}

@Composable
private fun DriveNowCard() {
    KlCard {
        Text(stringResource(R.string.scan_drive_now_title), style = MaterialTheme.typography.titleLarge, color = KlColors.Good)
        Text(stringResource(R.string.scan_drive_now_body), style = MaterialTheme.typography.bodyLarge, color = KlColors.OnSurface)
    }
}

@Composable
private fun IdleActions(lastAdapter: LastAdapter?, actions: ConnectionActions, demo: DemoActions?) {
    KlCard {
        KlSectionTitle(stringResource(R.string.scan_idle_title))
        Text(stringResource(R.string.scan_idle_body), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
        if (lastAdapter != null) {
            PrimaryButton(
                lastAdapter.name?.let { stringResource(R.string.action_connect_last, it) } ?: stringResource(R.string.action_connect_last_unnamed),
                { actions.onConnectLast(lastAdapter) },
            )
            SecondaryButton(stringResource(R.string.action_choose_another), actions.onChooseAdapter)
        } else {
            PrimaryButton(stringResource(R.string.action_connect_bluetooth), actions.onChooseAdapter)
        }
        if (demo != null) SecondaryButton(stringResource(R.string.demo_drive), demo.onStart)
    }
}

@Composable
private fun StepRow(item: GuidedStepItem, state: UiState, actions: ConnectionActions) {
    val resources = LocalResources.current
    val stalled by rememberStalled(item.step, item.state)
    val sentence = item.detail.text(resources) + if (stalled) " " + stringResource(R.string.step_still_working) else ""
    StepItem(
        number = item.step.ordinal + 1,
        title = stringResource(item.step.titleRes()),
        sentence = sentence,
        state = item.state.visual(),
        stateWord = stringResource(item.state.wordRes()),
        progress = item.detail.fraction,
        animate = !stalled,
    ) {
        if (item.canRetry) QuietButton(stringResource(R.string.action_retry), retryFor(item.step, state, actions))
        if (item.canSkip) QuietButton(stringResource(R.string.action_skip), actions.onSkipLadder)
    }
}

/** Retry on the adapter step means starting from the connect button again; anywhere else it asks the session to negotiate and probe again. */
private fun retryFor(step: GuidedStep, state: UiState, actions: ConnectionActions): () -> Unit =
    if (step == GuidedStep.ADAPTER && state.isActive) actions.onDisconnect else actions.onRetry

/** True once a step has been active for [LONG_WAIT_SECONDS]: it then stops animating and says it is still working. */
@Composable
private fun rememberStalled(step: GuidedStep, state: StepState) =
    produceState(false, step, state) {
        value = false
        if (state == StepState.ACTIVE) {
            delay(LONG_WAIT_SECONDS * MILLIS)
            value = true
        }
    }

private const val MILLIS = 1000L

/** For a new car, the questions; for a known one, the welcome-back with what is on file and an Edit. */
@Composable
private fun CarCard(state: UiState, editing: Boolean, setEditing: (Boolean) -> Unit, app: AppActions) {
    val identity = state.identity ?: return
    when (val garage = state.garage) {
        GarageStatus.Unknown -> Unit
        GarageStatus.New -> KlCard {
            KlSectionTitle(stringResource(R.string.car_new_title))
            Text(stringResource(R.string.car_new_intro), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
            CarProfileForm(identity, state.modelChoices, null, app) { app.onSaveProfile(it) }
        }
        is GarageStatus.Known -> KlCard {
            if (editing) {
                KlSectionTitle(stringResource(R.string.car_new_title))
                CarProfileForm(identity, state.modelChoices, garage.profile, app, onCancel = { setEditing(false) }) {
                    app.onSaveProfile(it)
                    setEditing(false)
                }
            } else {
                Welcome(garage.profile) { setEditing(true) }
            }
        }
    }
}

@Composable
private fun Welcome(profile: CarProfile, onEdit: () -> Unit) {
    KlSectionTitle(stringResource(R.string.scan_welcome, profile.displayName))
    val details = listOfNotNull(
        profile.fuelType?.let { stringResource(it.labelRes()) },
        profile.transmission?.let { stringResource(it.labelRes()) },
    ).joinToString(", ")
    if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodyLarge, color = KlColors.OnSurface)
    Text(
        profile.odometerKm?.let { stringResource(R.string.scan_odometer_known, "%,d".format(it)) } ?: stringResource(R.string.scan_odometer_unknown),
        style = MaterialTheme.typography.bodyMedium,
        color = KlColors.OnSurfaceMuted,
    )
    QuietButton(stringResource(R.string.scan_edit), onEdit)
}

/**
 * Make (prefilled from the VIN's make), model (a picker of the usual ones for the make, or free text), an optional name, fuel, gearbox and the
 * odometer. Saving needs only a make; everything else may stay empty.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarProfileForm(
    identity: VehicleIdentity,
    models: List<String>,
    initial: CarProfile?,
    app: AppActions,
    onCancel: (() -> Unit)? = null,
    onSave: (CarProfile) -> Unit,
) {
    var make by rememberSaveable { mutableStateOf(initial?.make ?: identity.make.takeIf { it != UNKNOWN_MAKE }.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(initial?.model ?: identity.model.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(initial?.nickname.orEmpty()) }
    var fuel by rememberSaveable { mutableStateOf(initial?.fuelType?.tag) }
    var gearbox by rememberSaveable { mutableStateOf(initial?.transmission?.tag) }
    var odometer by rememberSaveable { mutableStateOf(initial?.odometerKm?.toString().orEmpty()) }
    var tank by rememberSaveable { mutableStateOf(initial?.tankCapacityL?.toInt()?.toString().orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(KlSpacing.M)) {
        KlTextField(make, { make = it }, stringResource(R.string.car_make))
        if (models.isNotEmpty()) {
            Text(stringResource(R.string.car_model_pick), style = MaterialTheme.typography.labelMedium, color = KlColors.OnSurfaceMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KlSpacing.S)) {
                for (choice in models) FilterChip(selected = model == choice, onClick = { model = choice }, label = { Text(choice) })
            }
        }
        KlTextField(model, { model = it }, stringResource(R.string.car_model))
        KlTextField(name, { name = it }, stringResource(R.string.car_name))
        Text(stringResource(R.string.car_fuel), style = MaterialTheme.typography.labelMedium, color = KlColors.OnSurfaceMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KlSpacing.S)) {
            for (type in FuelType.entries) {
                FilterChip(selected = fuel == type.tag, onClick = {
                    fuel = type.tag
                }, label = { Text(stringResource(type.labelRes())) })
            }
        }
        Text(stringResource(R.string.car_gearbox), style = MaterialTheme.typography.labelMedium, color = KlColors.OnSurfaceMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(KlSpacing.S)) {
            for (type in Transmission.entries) {
                FilterChip(selected = gearbox == type.tag, onClick = { gearbox = type.tag }, label = { Text(stringResource(type.labelRes())) })
            }
        }
        KlTextField(odometer, { odometer = it.filter(Char::isDigit).take(ODOMETER_DIGITS) }, stringResource(R.string.car_odometer), number = true)
        KlTextField(tank, { tank = it.filter { c -> c.isDigit() || c == '.' }.take(TANK_DIGITS) }, stringResource(R.string.car_tank_litres), number = true)
        PrimaryButton(
            stringResource(R.string.car_save),
            {
                onSave(
                    CarProfile(
                        make = make.trim().ifBlank { identity.make },
                        model = model.trim().ifBlank { null },
                        nickname = name.trim().ifBlank { null },
                        fuelType = FuelType.fromTag(fuel),
                        transmission = Transmission.fromTag(gearbox),
                        odometerKm = odometer.toLongOrNull(),
                        odometerAtMs = if (odometer.isBlank()) 0 else app.now(),
                        tankCapacityL = tank.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 },
                    ),
                )
            },
            enabled = make.isNotBlank() || identity.make != UNKNOWN_MAKE,
        )
        if (onCancel != null) SecondaryButton(stringResource(R.string.car_cancel_edit), onCancel)
    }
}

private const val ODOMETER_DIGITS = 7
private const val TANK_DIGITS = 5
