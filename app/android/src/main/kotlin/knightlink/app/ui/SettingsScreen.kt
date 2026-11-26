package knightlink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import knightlink.app.R
import knightlink.app.session.UiState
import knightlink.app.ui.design.KlCard
import knightlink.app.ui.design.KlColors
import knightlink.app.ui.design.KlSectionTitle
import knightlink.app.ui.design.KlSpacing
import knightlink.app.ui.design.PrimaryButton
import knightlink.app.ui.design.QuietButton
import knightlink.app.ui.design.SecondaryButton
import knightlink.app.voice.AiMode
import knightlink.app.voice.AiSettings
import knightlink.app.voice.CloudProvider

data class AiActions(
    val settings: AiSettings = AiSettings(),
    val onMode: (AiMode) -> Unit = {},
    val onProvider: (CloudProvider) -> Unit = {},
    val onSaveApiKey: (String) -> Unit = {},
    val onSideloadPack: () -> Unit = {},
)

/**
 * Settings: the voice (spoken updates are off until switched on), the fuel price, the deep scan, location, sharing the car's findings, and the way into
 * the developer page. The push-to-talk card of the prototype lives here too.
 */
@Composable
fun SettingsScreen(
    state: UiState,
    actions: ConnectionActions,
    app: AppActions,
    driveData: DriveDataActions,
    voice: VoiceActions,
    talk: TalkActions,
    ai: AiActions,
    onRequestShare: () -> Unit,
    onOpenDeveloper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(KlSpacing.Gutter),
        verticalArrangement = Arrangement.spacedBy(KlSpacing.L),
    ) {
        KlSectionTitle(stringResource(R.string.settings_voice))
        Text(stringResource(R.string.settings_voice_hint), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
        VoiceCard(voice)
        TalkCard(talk)
        KnightPackSideloadCard(voice.activePersonaName, ai.onSideloadPack)
        AiModeCard(ai)

        KlSectionTitle(stringResource(R.string.settings_trip))
        FuelPriceCard(state.fuelPrice, app.onFuelPrice)

        KlSectionTitle(stringResource(R.string.settings_scanning))
        KlCard {
            Row(
                Modifier.fillMaxWidth().heightIn(min = KlSpacing.Touch),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KlSpacing.M),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.deep_scan_label), style = MaterialTheme.typography.bodyLarge, color = KlColors.OnSurface)
                    Text(stringResource(R.string.deep_scan_hint), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
                }
                Switch(checked = state.deepScan, onCheckedChange = actions.onDeepScan)
            }
            SettingSwitch(stringResource(R.string.auto_body_label), stringResource(R.string.auto_body_hint), state.autoBodyCheck, actions.onAutoBodyCheck)
            SettingSwitch(
                stringResource(R.string.cluster_deep_label),
                stringResource(R.string.cluster_deep_hint),
                state.clusterDeepCheck,
                actions.onClusterDeepCheck,
            )
        }

        KlSectionTitle(stringResource(R.string.settings_sharing))
        KlCard {
            Text(stringResource(R.string.findings_intro), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
            PrimaryButton(stringResource(R.string.findings_button), onRequestShare)
        }

        KlSectionTitle(stringResource(R.string.settings_phone))
        KlCard {
            Text(
                stringResource(if (driveData.locationAllowed) R.string.settings_location_on else R.string.settings_location_off),
                style = MaterialTheme.typography.bodyMedium,
                color = KlColors.OnSurfaceMuted,
            )
            if (!driveData.locationAllowed) SecondaryButton(stringResource(R.string.action_allow_location), driveData.onAllowLocation)
            if (!actions.backgroundAllowed) {
                Text(stringResource(R.string.background_explanation), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
                SecondaryButton(stringResource(R.string.action_allow_background), actions.onAllowBackground)
            }
        }

        KlSectionTitle(stringResource(R.string.settings_about))
        KlCard {
            Text(stringResource(R.string.settings_version, app.appVersion), style = MaterialTheme.typography.bodyLarge, color = KlColors.OnSurface)
            Text(stringResource(R.string.settings_developer_hint), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
            QuietButton(stringResource(R.string.settings_developer), onOpenDeveloper)
        }
    }
}

/** What goes into the findings file and what does not, said before anything is made; one tap more makes the file and opens the share sheet. */
@Composable
fun FindingsConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.findings_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(KlSpacing.M)) {
                Text(stringResource(R.string.findings_intro), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.findings_included_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.findings_included), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.findings_excluded_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.findings_excluded), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = KlSpacing.Touch)) { Text(stringResource(R.string.findings_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = KlSpacing.Touch)) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One switch with its label and a line that says what it does. The switch is a toggle: a double tap only switches it back. */
@Composable
private fun SettingSwitch(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = KlSpacing.Touch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KlSpacing.M),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = KlColors.OnSurface)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun KnightPackSideloadCard(activeName: String, onSideload: () -> Unit) {
    KlCard {
        Text(stringResource(R.string.knightpack_sideload_title), style = MaterialTheme.typography.titleMedium, color = KlColors.OnSurface)
        Text(activeName, style = MaterialTheme.typography.bodyMedium, color = KlColors.Accent)
        SecondaryButton(stringResource(R.string.knightpack_sideload_button), onSideload)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiModeCard(ai: AiActions) {
    var keyDialog by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    if (keyDialog) {
        AlertDialog(
            onDismissRequest = { keyDialog = false },
            title = { Text(stringResource(R.string.ai_key_set)) },
            text = {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(ai.settings.provider.label) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ai.onSaveApiKey(keyInput)
                        keyInput = ""
                        keyDialog = false
                    },
                ) { Text(stringResource(R.string.readings_end_save)) }
            },
            dismissButton = { TextButton(onClick = { keyDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    KlCard {
        Text(stringResource(R.string.ai_mode_title), style = MaterialTheme.typography.titleMedium, color = KlColors.OnSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KlSpacing.S)) {
            AiMode.entries.forEach { mode ->
                FilterChip(
                    selected = ai.settings.mode == mode,
                    onClick = { ai.onMode(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        Text(ai.settings.mode.summary, style = MaterialTheme.typography.bodySmall, color = KlColors.OnSurfaceMuted)
        if (ai.settings.mode == AiMode.CLOUD_AI || ai.settings.mode == AiMode.HYBRID_AI) {
            Text(stringResource(R.string.ai_provider_title), style = MaterialTheme.typography.titleSmall, color = KlColors.OnSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KlSpacing.S)) {
                CloudProvider.entries.forEach { provider ->
                    FilterChip(
                        selected = ai.settings.provider == provider,
                        onClick = { ai.onProvider(provider) },
                        label = { Text(provider.label) },
                    )
                }
            }
            Text(
                if (ai.settings.hasCloudKey) stringResource(R.string.ai_key_saved) else stringResource(R.string.ai_key_missing),
                style = MaterialTheme.typography.bodySmall,
                color = if (ai.settings.hasCloudKey) KlColors.Good else KlColors.OnSurfaceMuted,
            )
            SecondaryButton(stringResource(R.string.ai_key_set), onClick = { keyDialog = true })
        }
    }
}
