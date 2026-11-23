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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import knightlink.app.R
import knightlink.app.session.FAULT_KIND_ORDER
import knightlink.app.session.FaultItem
import knightlink.app.session.FaultsStatus
import knightlink.app.session.UiState
import knightlink.app.ui.design.EmptyState
import knightlink.app.ui.design.ErrorState
import knightlink.app.ui.design.KlCard
import knightlink.app.ui.design.KlColors
import knightlink.app.ui.design.KlSpacing
import knightlink.app.ui.design.SecondaryButton
import knightlink.app.ui.design.StatusChip
import knightlink.app.ui.design.Tone
import knightlink.core.scan.FaultKind

/**
 * The fault codes the car reported: the code, what the generic list says it is (or "no description available"), and how it is stored. Neutral on
 * purpose: no colour of alarm and no claim about how serious a code is, because that needs the car maker's own documents.
 */
@Composable
fun FaultsScreen(state: UiState, onReadAgain: () -> Unit, modifier: Modifier = Modifier) {
    val faults = state.faults
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(KlSpacing.Gutter),
        verticalArrangement = Arrangement.spacedBy(KlSpacing.L),
    ) {
        when (faults.status) {
            FaultsStatus.NOT_READ ->
                EmptyState(stringResource(R.string.faults_not_read_title), stringResource(R.string.faults_not_read_body))
            FaultsStatus.READING ->
                EmptyState(stringResource(R.string.faults_title), stringResource(R.string.faults_reading))
            FaultsStatus.FAILED -> ErrorState(
                stringResource(R.string.faults_failed_title),
                stringResource(R.string.faults_failed_body),
                stringResource(R.string.faults_read_again),
                onReadAgain,
            )
            FaultsStatus.READY -> {
                KlCard {
                    Text(
                        if (faults.items.isEmpty()) {
                            stringResource(R.string.faults_none)
                        } else {
                            pluralStringResource(R.plurals.verdict_codes_title, faults.items.size, faults.items.size)
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = KlColors.OnSurface,
                    )
                    Text(
                        when (faults.readiness?.milOn) {
                            true -> stringResource(R.string.faults_lamp_on)
                            false -> stringResource(R.string.faults_lamp_off)
                            null -> stringResource(R.string.faults_lamp_unknown)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = KlColors.OnSurfaceMuted,
                    )
                    Text(
                        stringResource(if (faults.items.isEmpty()) R.string.faults_screen_none_body else R.string.faults_neutral),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KlColors.OnSurfaceMuted,
                    )
                }
                for (item in faults.items.sortedBy { FAULT_KIND_ORDER.indexOf(it.kind) }) FaultCard(item)
            }
        }
        if (faults.status != FaultsStatus.FAILED) {
            SecondaryButton(stringResource(R.string.faults_read_again), onReadAgain, enabled = faults.status != FaultsStatus.READING)
        }
    }
}

@Composable
private fun FaultCard(item: FaultItem) {
    val resources = LocalResources.current
    val kind = stringResource(item.kind.labelRes())
    val description = item.description ?: stringResource(R.string.fault_no_description)
    KlCard(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription =
                resources.getString(R.string.faults_item_description, item.code, description, kind)
        },
    ) {
        Text(item.code, style = MaterialTheme.typography.titleLarge, color = KlColors.OnSurface, fontFamily = FontFamily.Monospace)
        Text(
            description,
            style = MaterialTheme.typography.bodyLarge,
            color = if (item.description == null) KlColors.OnSurfaceMuted else KlColors.OnSurface,
        )
        StatusChip(kind, Tone.NEUTRAL)
    }
}

private fun FaultKind.labelRes(): Int = when (this) {
    FaultKind.CONFIRMED -> R.string.faults_kind_confirmed
    FaultKind.PENDING -> R.string.faults_kind_pending
    FaultKind.PERMANENT -> R.string.faults_kind_permanent
}
