package knightlink.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import knightlink.app.R
import knightlink.app.session.DriveTruthLine
import knightlink.app.session.driveTruthCard
import knightlink.app.ui.design.KlCard
import knightlink.app.ui.design.KlColors
import knightlink.app.ui.design.KlSectionTitle
import knightlink.app.ui.design.SecondaryButton
import knightlink.core.runtime.drivetruth.DriveTruthReport

/**
 * What the drive that just ended can say about itself: only figures the report holds, each with its method; what is missing is said in a note, never
 * shown as zero. "Share" hands the same lines to the share sheet as text.
 */
@Composable
fun DriveTruthCard(report: DriveTruthReport, onShare: (String) -> Unit) {
    val resources = LocalResources.current
    val model = remember(report) { driveTruthCard(report) }
    KlCard {
        KlSectionTitle(stringResource(R.string.dt_title))
        for (line in model.lines) {
            val text = line.text(resources)
            when (line) {
                is DriveTruthLine.Cost -> Text(text, style = MaterialTheme.typography.headlineMedium, color = KlColors.OnSurface)
                is DriveTruthLine.Method -> Text(text, style = MaterialTheme.typography.labelMedium, color = KlColors.OnSurfaceMuted)
                else -> Text(text, style = MaterialTheme.typography.bodyLarge, color = KlColors.OnSurface)
            }
        }
        for (note in model.notes) {
            Text(stringResource(note.textRes()), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
        }
        SecondaryButton(stringResource(R.string.dt_share), { onShare(model.shareText(resources)) })
    }
}
