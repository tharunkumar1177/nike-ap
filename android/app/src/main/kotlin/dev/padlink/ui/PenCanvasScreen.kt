// PenCanvasScreen.kt: full-screen landscape S Pen tablet surface with status, close, and Samsung hint.
package dev.padlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.padlink.R
import dev.padlink.input.PenController
import dev.padlink.settings.SettingsStore
import dev.padlink.ui.theme.PadColors

/** Pen canvas: the whole screen is the tablet; fingers still work as the trackpad. */
@Composable
fun PenCanvasScreen(viewModel: MainViewModel, onClose: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val session = remember { PadSession(context, viewModel.router) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings, link.hostSize) { session.apply(settings, link.hostSize) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.release() }
    KeepScreenOn(true)

    Box(Modifier.fillMaxSize().background(PadColors.PenCanvas)) {
        TrackpadSurface(
            session = session,
            hint = stringResource(R.string.pen_canvas_hint),
            showTabletArea = showsTabletFrame(settings.pen.mode),
            tabletAspect = PenController.aspectFor(settings.pen.aspect, link.hostSize),
            tabletAreaPercent = settings.pen.areaPercent,
            excludeAllGestures = true,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(PadColors.PenCanvas.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, stringResource(R.string.close_pen_canvas), tint = PadColors.OnTrackpad)
            }
            Text(
                "${link.targetName ?: stringResource(R.string.app_name)}  ${stringResource(statusText(link.status))}",
                color = PadColors.OnTrackpad,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 4.dp),
            )
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.open_settings), tint = PadColors.OnTrackpad)
            }
        }
        if (DisplayTuning.isSamsung() && !settings.samsungHintDismissed) {
            Column(Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(max = 560.dp)) {
                SamsungHintCard(onDismiss = { viewModel.store.putBoolean(SettingsStore.KEY_SAMSUNG_HINT_DISMISSED, true) })
            }
        }
    }

    if (showSettings) {
        SettingsSheet(settings, viewModel.store, initialTab = 1, onDismiss = { showSettings = false })
    }
    link.pairing?.let { request -> PairingDialog(request, viewModel::respondToPairing) }
}
