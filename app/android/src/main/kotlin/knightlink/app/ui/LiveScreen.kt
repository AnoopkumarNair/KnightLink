package knightlink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import knightlink.app.R
import knightlink.app.session.LiveValue
import knightlink.app.session.UiState
import knightlink.app.ui.design.BigFigure
import knightlink.app.ui.design.EmptyState
import knightlink.app.ui.design.KlCard
import knightlink.app.ui.design.KlColors
import knightlink.app.ui.design.KlSpacing
import knightlink.core.runtime.CaptureMode

/** The few live values a friend wants, big and calm. Each says where it came from, or that the car does not report it. */
private class LiveSlot(val signalId: String, val label: Int, val method: Int)

private val CORE_SLOTS = listOf(
    LiveSlot("vehicle.speed", R.string.live_speed, R.string.live_method_speed),
    LiveSlot("engine.rpm", R.string.live_rpm, R.string.live_method_rpm),
    LiveSlot("engine.coolant_temp", R.string.live_coolant, R.string.live_method_coolant),
    LiveSlot("fuel.level_pct", R.string.live_fuel, R.string.live_method_fuel),
)

/** Shown only when the car reports them: nobody is told "not reported" about a figure they never asked for. */
private val EXTRA_SLOTS = listOf(
    LiveSlot("engine.load_pct", R.string.live_load, R.string.live_method_load),
    LiveSlot("fuel.rate_lph", R.string.live_rate, R.string.live_method_rate),
)

@Composable
fun LiveScreen(state: UiState, modifier: Modifier = Modifier) {
    if (!state.isActive || (state.liveValues.isEmpty() && state.vehicle == null)) {
        Column(modifier.fillMaxSize().padding(KlSpacing.Gutter)) {
            EmptyState(stringResource(R.string.live_empty_title), stringResource(R.string.live_empty_body))
        }
        return
    }
    val byId = state.liveValues.associateBy { it.signalId }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(KlSpacing.Gutter),
        verticalArrangement = Arrangement.spacedBy(KlSpacing.L),
    ) {
        if (state.captureMode == CaptureMode.PASSIVE) {
            Text(
                stringResource(R.string.capture_live_paused),
                style = MaterialTheme.typography.titleMedium,
                color = KlColors.Accent,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        for (slot in CORE_SLOTS + EXTRA_SLOTS.filter { it.signalId in byId }) {
            KlCard { Figure(slot, byId[slot.signalId], probed = state.vehicle != null) }
        }
    }
}

@Composable
private fun Figure(slot: LiveSlot, value: LiveValue?, probed: Boolean) {
    val label = stringResource(slot.label)
    when {
        value != null -> BigFigure(
            label = label,
            value = value.valueText,
            unit = value.unit,
            method = if (value.stale) stringResource(R.string.live_method_stale, value.ageSeconds) else stringResource(slot.method),
            dim = value.stale,
        )
        // Before the probe has finished a missing value only means "not yet", so nothing claims the car lacks it.
        probed -> BigFigure(label, null, "", stringResource(R.string.live_method_missing))
        else -> BigFigure(label, null, "", stringResource(R.string.live_empty_title))
    }
}
