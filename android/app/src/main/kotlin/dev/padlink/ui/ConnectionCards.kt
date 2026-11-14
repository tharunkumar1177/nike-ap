// ConnectionCards.kt: connection guidance card, Samsung settings hint, and wired pairing dialog.
package dev.padlink.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.padlink.R
import dev.padlink.settings.BtMode
import dev.padlink.settings.ConnectionSettings
import dev.padlink.settings.TransportKind
import dev.padlink.transport.DropReason
import dev.padlink.transport.LinkIssue
import dev.padlink.transport.LinkState
import dev.padlink.transport.LinkStatus
import dev.padlink.transport.PairingRequest

/** Action a guidance card offers. */
private enum class CardAction { RETRY, BLUETOOTH_SETTINGS, PERMISSIONS, USE_BLE, TETHER_SETTINGS }

/** Content of one guidance card. */
private data class Guidance(@StringRes val title: Int, @StringRes val body: Int, val actions: List<CardAction>)

/** Chooses guidance for the current link, or null when everything is fine. */
private fun guidanceFor(link: LinkState, connection: ConnectionSettings): Guidance? = when (link.status) {
    LinkStatus.CONNECTED, LinkStatus.PAIRING_REQUEST -> null
    LinkStatus.PROFILE_NOT_CONNECTED -> Guidance(
        R.string.card_profile_title, R.string.card_profile_body,
        listOf(CardAction.RETRY, CardAction.BLUETOOTH_SETTINGS),
    )
    LinkStatus.DISCONNECTED -> Guidance(
        R.string.card_disconnected_title, R.string.card_disconnected_body, listOf(CardAction.RETRY),
    )
    LinkStatus.IDLE -> if (link.issue == LinkIssue.NO_TARGET) {
        Guidance(R.string.card_no_target_title, R.string.card_no_target_body, listOf(CardAction.BLUETOOTH_SETTINGS))
    } else {
        null
    }
    LinkStatus.CONNECTING -> when {
        link.issue != LinkIssue.WAITING_FOR_HELPER -> null
        connection.transport == TransportKind.USB_ADB ->
            Guidance(R.string.card_adb_title, R.string.card_adb_body, emptyList())
        else -> Guidance(R.string.card_tether_title, R.string.card_tether_body, emptyList())
    }
    LinkStatus.UNAVAILABLE -> when (link.issue) {
        LinkIssue.BLUETOOTH_OFF -> Guidance(
            R.string.card_bt_off_title, R.string.card_bt_off_body, listOf(CardAction.BLUETOOTH_SETTINGS),
        )
        LinkIssue.PERMISSION_MISSING -> Guidance(
            R.string.card_permission_title, R.string.card_permission_body, listOf(CardAction.PERMISSIONS),
        )
        LinkIssue.BLUETOOTH_UNSUPPORTED -> Guidance(
            R.string.card_bt_unsupported_title, R.string.card_bt_unsupported_body, emptyList(),
        )
        LinkIssue.HID_PROFILE_UNAVAILABLE, LinkIssue.HID_REGISTRATION_FAILED -> Guidance(
            R.string.card_hid_title, R.string.card_hid_body,
            if (connection.btMode == BtMode.CLASSIC) listOf(CardAction.USE_BLE, CardAction.RETRY) else listOf(CardAction.RETRY),
        )
        LinkIssue.BLE_ADVERTISING_FAILED -> Guidance(
            R.string.card_ble_title, R.string.card_ble_body, listOf(CardAction.RETRY),
        )
        LinkIssue.TETHER_OFF -> Guidance(
            R.string.card_tether_off_title, R.string.card_tether_off_body, listOf(CardAction.TETHER_SETTINGS),
        )
        LinkIssue.PORT_IN_USE -> Guidance(
            R.string.card_port_title, R.string.card_port_body, emptyList(),
        )
        else -> null
    }
}

/** Text for why the last wired connection ended. */
@StringRes
private fun dropText(reason: DropReason): Int = when (reason) {
    DropReason.HELPER_SILENT -> R.string.drop_helper_silent
    DropReason.HELPER_CLOSED -> R.string.drop_helper_closed
    DropReason.NO_HELLO -> R.string.drop_no_hello
    DropReason.REPLACED -> R.string.drop_replaced
    DropReason.PROTOCOL_ERROR -> R.string.drop_protocol_error
    DropReason.NETWORK_ERROR -> R.string.drop_network_error
}

/** Card explaining why input is not reaching the computer and how to fix it. */
@Composable
fun ConnectionInfoCard(
    link: LinkState,
    connection: ConnectionSettings,
    onRetry: () -> Unit,
    onRequestPermissions: () -> Unit,
    onUseBle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val guidance = guidanceFor(link, connection) ?: return
    val context = LocalContext.current
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(guidance.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(guidance.body), style = MaterialTheme.typography.bodyMedium)
            link.lastDrop?.takeIf { link.issue == LinkIssue.WAITING_FOR_HELPER }?.let { drop ->
                Text(
                    stringResource(
                        if (drop.wasConnected) R.string.drop_connected else R.string.drop_attempt,
                        drop.hostName,
                        stringResource(dropText(drop.reason)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (guidance.actions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    for (action in guidance.actions) {
                        TextButton(onClick = {
                            when (action) {
                                CardAction.RETRY -> onRetry()
                                CardAction.BLUETOOTH_SETTINGS -> openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
                                CardAction.PERMISSIONS -> onRequestPermissions()
                                CardAction.USE_BLE -> onUseBle()
                                CardAction.TETHER_SETTINGS -> openTetherSettings(context)
                            }
                        }) {
                            Text(
                                stringResource(
                                    when (action) {
                                        CardAction.RETRY -> R.string.action_connect
                                        CardAction.BLUETOOTH_SETTINGS -> R.string.action_bluetooth_settings
                                        CardAction.PERMISSIONS -> R.string.action_grant
                                        CardAction.USE_BLE -> R.string.action_use_ble
                                        CardAction.TETHER_SETTINGS -> R.string.action_tether_settings
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Hint about Samsung features that steal S Pen and edge input; dismissible. */
@Composable
fun SamsungHintCard(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.samsung_hint_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.samsung_hint_body), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_got_it)) }
            }
        }
    }
}

/** Approval dialog for a wired helper over tethering, showing the code both sides display. */
@Composable
fun PairingDialog(request: PairingRequest, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(stringResource(R.string.pairing_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.pairing_body, request.hostName))
                Text(
                    request.code.chunked(3).joinToString(" "),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.pairing_approve)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.pairing_deny)) } },
    )
}

/** Opens a system settings screen, ignoring devices that lack it. */
private fun openSettings(context: Context, action: String) {
    try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Settings screen missing on this build; the card text still explains the step.
    }
}

/** Opens the tethering screen, falling back to the general wireless settings. */
private fun openTetherSettings(context: Context) {
    val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(tether)
    } catch (_: ActivityNotFoundException) {
        openSettings(context, Settings.ACTION_WIRELESS_SETTINGS)
    } catch (_: SecurityException) {
        openSettings(context, Settings.ACTION_WIRELESS_SETTINGS)
    }
}
