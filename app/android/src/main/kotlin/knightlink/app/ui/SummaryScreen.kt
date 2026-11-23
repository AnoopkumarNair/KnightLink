package knightlink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import knightlink.app.R
import knightlink.app.session.CoverageItem
import knightlink.app.session.GarageStatus
import knightlink.app.session.HealthVerdict
import knightlink.app.session.UiState
import knightlink.app.session.coverageReport
import knightlink.app.session.healthVerdict
import knightlink.app.session.readinessLine
import knightlink.app.ui.design.EmptyState
import knightlink.app.ui.design.KlCard
import knightlink.app.ui.design.KlColors
import knightlink.app.ui.design.KlGauge
import knightlink.app.ui.design.KlRow
import knightlink.app.ui.design.KlSectionTitle
import knightlink.app.ui.design.KlSpacing
import knightlink.app.ui.design.KlTextField
import knightlink.app.ui.design.PrimaryButton
import knightlink.app.ui.design.SecondaryButton
import knightlink.app.ui.design.StatusChip
import knightlink.app.ui.design.Tone
import knightlink.core.vehicle.SupportTier
import knightlink.core.vehicle.UNKNOWN_MAKE

/**
 * The car at a glance, in the order a friend wants it: who the car is, a health verdict with the fault count, the emission checks, the fuel gauge,
 * the distance, what the app could and could not read, the fuel price, the last drive, and the way to share the findings. Every figure says where it
 * came from or that the car does not report it.
 */
@Composable
fun SummaryScreen(state: UiState, app: AppActions, onRequestShare: () -> Unit, modifier: Modifier = Modifier) {
    val identity = state.identity
    if (identity == null) {
        Column(modifier.fillMaxSize().padding(KlSpacing.Gutter)) {
            EmptyState(stringResource(R.string.summary_empty_title), stringResource(R.string.summary_empty_body))
        }
        return
    }
    val coverage = remember(state) { coverageReport(state) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(KlSpacing.Gutter),
        verticalArrangement = Arrangement.spacedBy(KlSpacing.L),
    ) {
        KlCard {
            val name = listOfNotNull(identity.make.takeIf { it != UNKNOWN_MAKE }, identity.model, identity.year?.toString()).joinToString(" ")
            val title =
                (state.garage as? GarageStatus.Known)?.profile?.nickname?.takeIf { it.isNotBlank() }
                    ?: name.ifBlank { stringResource(R.string.summary_empty_title) }
            Text(title, style = MaterialTheme.typography.titleLarge, color = KlColors.OnSurface)
            if (title != name && name.isNotBlank()) Text(name, style = MaterialTheme.typography.bodyLarge, color = KlColors.OnSurfaceMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(KlSpacing.S)) {
                StatusChip(
                    stringResource(if (identity.tier == SupportTier.FULL) R.string.summary_support_full else R.string.summary_support_basic),
                    if (identity.tier == SupportTier.FULL) Tone.GOOD else Tone.NEUTRAL,
                )
                identity.vinLast4?.let { StatusChip(stringResource(R.string.identity_vin_end, it), Tone.NEUTRAL) }
            }
            identity.protocol?.let {
                Text(stringResource(R.string.summary_protocol, it), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
            }
        }

        HealthCard(state)
        FuelAndDistanceCard(state)
        CoverageCard(coverage)
        FuelPriceCard(state.fuelPrice, app.onFuelPrice)
        state.driveTruth?.let { DriveTruthCard(it, app.onShareText) }
        PrimaryButton(stringResource(R.string.findings_button), onRequestShare)
    }
}

@Composable
private fun HealthCard(state: UiState) {
    val verdict = healthVerdict(state.faults)
    val readiness = readinessLine(state.faults.readiness)
    KlCard {
        when (verdict.kind) {
            HealthVerdict.Kind.NOT_CHECKED -> {
                Text(stringResource(R.string.verdict_not_checked_title), style = MaterialTheme.typography.headlineMedium, color = KlColors.OnSurface)
                Text(stringResource(R.string.verdict_not_checked_body), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
            }
            HealthVerdict.Kind.ALL_CLEAR -> {
                Row(horizontalArrangement = Arrangement.spacedBy(KlSpacing.M)) {
                    Text(
                        stringResource(R.string.verdict_clear_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = KlColors.Good,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip("0", Tone.GOOD)
                }
                Text(stringResource(R.string.verdict_clear_body), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
            }
            HealthVerdict.Kind.CODES_STORED -> {
                Row(horizontalArrangement = Arrangement.spacedBy(KlSpacing.M)) {
                    Text(
                        pluralStringResource(R.plurals.verdict_codes_title, verdict.faultCount, verdict.faultCount),
                        style = MaterialTheme.typography.headlineMedium,
                        color = KlColors.OnSurface,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(verdict.faultCount.toString(), Tone.ATTENTION)
                }
                if (verdict.lampOn) Text(stringResource(R.string.verdict_lamp_on), style = MaterialTheme.typography.bodyLarge, color = KlColors.Attention)
                Text(stringResource(R.string.verdict_codes_body), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
            }
        }
        if (verdict.kind != HealthVerdict.Kind.NOT_CHECKED) {
            if (readiness != null) {
                Text(
                    stringResource(R.string.readiness_line, readiness.ready, readiness.total),
                    style = MaterialTheme.typography.titleMedium,
                    color = KlColors.OnSurface,
                )
                Text(stringResource(R.string.readiness_method), style = MaterialTheme.typography.labelMedium, color = KlColors.OnSurfaceMuted)
            } else {
                Text(stringResource(R.string.readiness_none), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
            }
        }
    }
}

@Composable
private fun FuelAndDistanceCard(state: UiState) {
    val level = state.liveValues.firstOrNull { it.signalId == "fuel.level_pct" }?.valueText?.toDoubleOrNull()?.toInt()?.coerceIn(0, PERCENT_MAX)
    val profile = (state.garage as? GarageStatus.Known)?.profile
    val odometer = state.liveValues.firstOrNull { it.signalId.contains("odometer") }?.let { "${it.valueText} ${it.unit}".trim() }
    val typed = profile?.odometerKm ?: state.dashboard.odometerStartKm
    KlCard {
        KlSectionTitle(stringResource(R.string.fuel_level_title))
        if (level != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(KlSpacing.M)) {
                Text(stringResource(R.string.fuel_level_value, level), style = MaterialTheme.typography.displayLarge, color = KlColors.OnSurface)
            }
            KlGauge(
                level / PERCENT_MAX.toFloat(),
                stringResource(R.string.fuel_level_description, level),
                tone = if (level <=
                    LOW_FUEL_PERCENT
                ) {
                    Tone.ATTENTION
                } else {
                    Tone.ACCENT
                },
            )
            Text(stringResource(R.string.fuel_level_method), style = MaterialTheme.typography.labelMedium, color = KlColors.OnSurfaceMuted)
        } else {
            Text(stringResource(R.string.not_reported), style = MaterialTheme.typography.bodyLarge, color = KlColors.OnSurfaceMuted)
        }
        KlSectionTitle(stringResource(R.string.distance_title), Modifier.padding(top = KlSpacing.S))
        when {
            odometer != null -> Text(odometer, style = MaterialTheme.typography.titleLarge, color = KlColors.OnSurface)
            typed != null -> Text(
                stringResource(R.string.distance_from_you, "%,d".format(typed)),
                style = MaterialTheme.typography.bodyLarge,
                color = KlColors.OnSurface,
            )
            else -> Text(stringResource(R.string.not_reported), style = MaterialTheme.typography.bodyLarge, color = KlColors.OnSurfaceMuted)
        }
    }
}

@Composable
private fun CoverageCard(items: List<CoverageItem>) {
    val resources = LocalResources.current
    KlCard {
        KlSectionTitle(stringResource(R.string.coverage_section))
        Text(stringResource(R.string.coverage_section_hint), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
        for (item in items) {
            val title = stringResource(item.domain.titleRes())
            val word = stringResource(item.state.wordRes())
            val note = item.note?.let { stringResource(it.wordRes()) }
            KlRow(
                title = title,
                subtitle = note,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = if (note == null) {
                        resources.getString(R.string.cov_row_description, title, word)
                    } else {
                        resources.getString(R.string.cov_row_description_note, title, word, note)
                    }
                },
                trailing = { StatusChip(word, item.state.tone()) },
            )
        }
    }
}

/** The fuel price per litre, empty until the driver types one; kept on the phone. Shown on the summary and in the settings. */
@Composable
fun FuelPriceCard(price: Double?, onSave: (String) -> Unit) {
    var text by rememberSaveable(price) { mutableStateOf(price?.let { formatPrice(it) }.orEmpty()) }
    val invalid = text.isNotBlank() && knightlink.app.session.FuelPriceStore.parse(text) == null
    KlCard {
        KlSectionTitle(stringResource(R.string.fuel_price_title))
        Text(stringResource(R.string.fuel_price_hint), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
        KlTextField(
            text,
            { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(PRICE_CHARS) },
            stringResource(R.string.fuel_price_field),
            number = true,
            prefix = "₹",
            supporting = if (invalid) {
                stringResource(R.string.fuel_price_invalid)
            } else {
                price?.let {
                    stringResource(R.string.fuel_price_saved, formatPrice(it))
                }
            },
        )
        SecondaryButton(stringResource(R.string.fuel_price_save), { onSave(text) }, enabled = !invalid)
    }
}

private fun formatPrice(price: Double): String = if (price ==
    price.toLong().toDouble()
) {
    price.toLong().toString()
} else {
    "%.2f".format(java.util.Locale.ROOT, price)
}

private const val PERCENT_MAX = 100
private const val LOW_FUEL_PERCENT = 15
private const val PRICE_CHARS = 7
