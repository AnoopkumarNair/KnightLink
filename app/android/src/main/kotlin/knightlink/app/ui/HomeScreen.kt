package knightlink.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import knightlink.app.R
import knightlink.app.ble.LastAdapter
import knightlink.app.session.FaultsStatus
import knightlink.app.session.RecordingsSummary
import knightlink.app.session.UiState
import knightlink.app.ui.design.KlColors
import knightlink.app.ui.design.KlSpacing
import knightlink.app.ui.design.QuietButton

private enum class Tab { SCAN, CAR, FAULTS, LIVE }

private enum class Page { TABS, SETTINGS, DEVELOPER }

/**
 * The app's shell: a calm header (the title opens the developer page on a long press), four tabs (Scan, Car, Faults, Live) and Settings. The friend sees
 * the guided Scan flow first and the car summary second; raw data, ECUs, rates and the prototype's cards are on the developer page only.
 */
@Composable
fun HomeScreen(
    state: UiState,
    recordings: RecordingsSummary,
    lastAdapter: LastAdapter?,
    actions: ConnectionActions,
    onShareLatest: () -> Unit,
    onShareLog: () -> Unit,
    modifier: Modifier = Modifier,
    driveData: DriveDataActions = DriveDataActions(locationAllowed = false, onAllowLocation = {}),
    voice: VoiceActions = VoiceActions.Unavailable,
    talk: TalkActions = TalkActions.NONE,
    app: AppActions = AppActions(),
) {
    var tab by rememberSaveable { mutableStateOf(Tab.SCAN) }
    var page by rememberSaveable { mutableStateOf(Page.TABS) }
    var consent by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = page != Page.TABS) { page = Page.TABS }

    val requestShare = { consent = true }
    if (consent) {
        FindingsConsentDialog(
            onConfirm = {
                consent = false
                app.onShareFindings()
            },
            onDismiss = { consent = false },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Header(
                page = page,
                demo = app.demo?.active == true,
                onSettings = { page = if (page == Page.SETTINGS) Page.TABS else Page.SETTINGS },
                onDeveloper = { page = Page.DEVELOPER },
                onBack = { page = Page.TABS },
            )
        },
        bottomBar = { if (page == Page.TABS) TabBar(tab, state) { tab = it } },
    ) { inner ->
        val content = Modifier.padding(inner).let { if (page == Page.TABS) it else it.navigationBarsPadding() }
        when (page) {
            Page.TABS -> when (tab) {
                Tab.SCAN -> ScanScreen(state, lastAdapter, actions, app, content)
                Tab.CAR -> SummaryScreen(state, app, requestShare, content)
                Tab.FAULTS -> FaultsScreen(state, actions.onRefreshFaults, content)
                Tab.LIVE -> LiveScreen(state, content)
            }
            Page.SETTINGS -> SettingsScreen(state, actions, app, driveData, voice, talk, requestShare, { page = Page.DEVELOPER }, content)
            Page.DEVELOPER ->
                DeveloperScreen(state, recordings, lastAdapter, actions, onShareLatest, onShareLog, content, driveData, voice, talk)
        }
    }
}

@Composable
private fun Header(page: Page, demo: Boolean, onSettings: () -> Unit, onDeveloper: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = KlSpacing.Gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val longPressHint = stringResource(R.string.title_hint)
            Text(
                stringResource(
                    when (page) {
                        Page.TABS -> R.string.app_name
                        Page.SETTINGS -> R.string.settings_title
                        Page.DEVELOPER -> R.string.dev_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                color = KlColors.OnSurface,
                modifier = Modifier
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { onDeveloper() }) }
                    .semantics {
                        heading()
                        onLongClick(label = longPressHint) {
                            onDeveloper()
                            true
                        }
                    },
            )
            if (page == Page.TABS) {
                QuietButton(stringResource(R.string.nav_settings), onSettings)
            } else {
                QuietButton(stringResource(R.string.dev_back), onBack)
            }
        }
        if (demo) {
            Text(
                stringResource(R.string.demo_banner),
                style = MaterialTheme.typography.labelMedium,
                color = KlColors.Attention,
                modifier = Modifier.padding(horizontal = KlSpacing.Gutter, vertical = KlSpacing.Xs),
            )
        }
    }
}

@Composable
private fun TabBar(selected: Tab, state: UiState, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = KlColors.Surface, contentColor = KlColors.OnSurface) {
        for (tab in Tab.entries) {
            val label = stringResource(tab.labelRes())
            val badge = if (tab == Tab.FAULTS && state.faults.status == FaultsStatus.READY) state.faults.items.size.takeIf { it > 0 } else null
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    BadgedBox(badge = { if (badge != null) Badge { Text(badge.toString()) } }) {
                        // A plain mark: the label carries the meaning, so the glyph is hidden from a screen reader.
                        Box(Modifier.clearAndSetSemantics { }) { Text(tab.glyph(), style = MaterialTheme.typography.titleLarge) }
                    }
                },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.semantics { if (badge != null) contentDescription = "$label, $badge" },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = KlColors.OnAccent,
                    selectedTextColor = KlColors.Accent,
                    indicatorColor = KlColors.Accent,
                    unselectedIconColor = KlColors.OnSurfaceMuted,
                    unselectedTextColor = KlColors.OnSurfaceMuted,
                ),
            )
        }
    }
}

private fun Tab.labelRes(): Int = when (this) {
    Tab.SCAN -> R.string.nav_scan
    Tab.CAR -> R.string.nav_car
    Tab.FAULTS -> R.string.nav_faults
    Tab.LIVE -> R.string.nav_live
}

private fun Tab.glyph(): String = when (this) {
    Tab.SCAN -> "◎"
    Tab.CAR -> "▣"
    Tab.FAULTS -> "!"
    Tab.LIVE -> "◔"
}
