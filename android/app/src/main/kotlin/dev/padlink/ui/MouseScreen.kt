// MouseScreen.kt: the Mouse screen (header with transport picker, guidance card, trackpad, scroll strip, buttons).
package dev.padlink.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings as SystemSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.padlink.R
import dev.padlink.input.PenController
import dev.padlink.settings.BtMode
import dev.padlink.settings.ConnectionSettings
import dev.padlink.settings.PenMode
import dev.padlink.settings.TransportKind
import dev.padlink.transport.LinkState
import dev.padlink.transport.LinkStatus
import dev.padlink.transport.bt.BtHost

/** Mouse screen matching the original layout, with transport selection in the header. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MouseScreen(viewModel: MainViewModel, onRequestPermissions: () -> Unit, onOpenPenCanvas: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val session = remember { PadSession(context, viewModel.router) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings, link.hostSize) { session.apply(settings, link.hostSize) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.release() }
    KeepScreenOn(settings.mouse.keepScreenOn)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.title_mouse), maxLines = 1)
                            TransportPicker(link, settings.connection, hosts, viewModel)
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenPenCanvas) {
                            Icon(Icons.Outlined.Draw, stringResource(R.string.open_pen_canvas))
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.open_settings))
                        }
                    },
                )
                if (link.status == LinkStatus.CONNECTING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                } else {
                    Box(Modifier.height(4.dp))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionInfoCard(
                link = link,
                connection = settings.connection,
                onRetry = viewModel::retry,
                onRequestPermissions = onRequestPermissions,
                onUseBle = { viewModel.setBtMode(BtMode.BLE) },
            )
            val m = settings.mouse
            if (m.buttonsTop) MouseButtons(session, m.visibleButtons)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m.scrollBarLeft) ScrollStrip(session)
                TrackpadSurface(
                    session = session,
                    hint = stringResource(R.string.trackpad_hint),
                    showTabletArea = false,
                    tabletAspect = PenController.aspectFor(settings.pen.aspect, link.hostSize),
                    tabletAreaPercent = settings.pen.areaPercent,
                    excludeAllGestures = false,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
                if (m.scrollBarRight) ScrollStrip(session)
            }
            if (m.buttonsBottom) MouseButtons(session, m.visibleButtons)
        }
    }

    if (showSettings) {
        SettingsSheet(settings, viewModel.store, onDismiss = { showSettings = false })
    }
    link.pairing?.let { request -> PairingDialog(request, viewModel::respondToPairing) }
}

/** Keeps the screen awake while [enabled] and this composable is shown. */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/** Header subtitle showing target and status; opens the transport menu. */
@Composable
private fun TransportPicker(
    link: LinkState,
    connection: ConnectionSettings,
    hosts: List<BtHost>,
    viewModel: MainViewModel,
) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val target = when (connection.transport) {
        TransportKind.BLUETOOTH -> link.targetName
            ?: hosts.firstOrNull { it.address == connection.btTarget }?.name
            ?: stringResource(R.string.transport_bluetooth)
        TransportKind.USB_ADB -> link.targetName ?: stringResource(R.string.transport_usb_adb)
        TransportKind.USB_TETHER -> link.targetName ?: stringResource(R.string.transport_usb_tether)
    }
    Box {
        Row(
            Modifier.clickable {
                viewModel.refreshHosts()
                open = true
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$target  ${stringResource(statusText(link.status))}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, stringResource(R.string.choose_transport))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.transport_bluetooth),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (hosts.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.no_paired_computers)) },
                    onClick = {},
                    enabled = false,
                )
            }
            for (host in hosts) {
                val selected = connection.transport == TransportKind.BLUETOOTH && connection.btTarget == host.address
                DropdownMenuItem(
                    text = { Text(host.name) },
                    leadingIcon = { Icon(Icons.Outlined.Bluetooth, null) },
                    trailingIcon = { if (selected) Icon(Icons.Filled.Check, null) },
                    onClick = {
                        open = false
                        viewModel.selectBluetooth(host)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pair_new_computer)) },
                onClick = {
                    open = false
                    try {
                        context.startActivity(Intent(SystemSettings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                        // No Bluetooth settings screen on this device.
                    }
                },
            )
            HorizontalDivider()
            for ((kind, label) in listOf(
                TransportKind.USB_ADB to R.string.transport_usb_adb,
                TransportKind.USB_TETHER to R.string.transport_usb_tether,
            )) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { Icon(Icons.Outlined.Usb, null) },
                    trailingIcon = { if (connection.transport == kind) Icon(Icons.Filled.Check, null) },
                    onClick = {
                        open = false
                        viewModel.selectWired(kind)
                    },
                )
            }
        }
    }
}

/** Header status word for a link status. */
fun statusText(status: LinkStatus): Int = when (status) {
    LinkStatus.IDLE -> R.string.status_no_device
    LinkStatus.CONNECTING -> R.string.status_connecting
    LinkStatus.CONNECTED -> R.string.status_connected
    LinkStatus.DISCONNECTED, LinkStatus.PROFILE_NOT_CONNECTED -> R.string.status_disconnected
    LinkStatus.PAIRING_REQUEST -> R.string.status_pairing
    LinkStatus.UNAVAILABLE -> R.string.status_unavailable
}

/** True when the pen tablet frame should be drawn for [mode]. */
fun showsTabletFrame(mode: PenMode): Boolean = mode == PenMode.TABLET
