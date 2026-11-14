// MainViewModel.kt: exposes settings, link state, and bonded hosts to the Compose screens.
package dev.padlink.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.padlink.PadLinkApp
import dev.padlink.settings.BtMode
import dev.padlink.settings.Settings
import dev.padlink.settings.SettingsStore
import dev.padlink.settings.TransportKind
import dev.padlink.transport.LinkState
import dev.padlink.transport.TransportRouter
import dev.padlink.transport.bt.BluetoothSupport
import dev.padlink.transport.bt.BtHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Screen state holder backed by the process-wide settings store and router. */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PadLinkApp

    /** Persistent settings store. */
    val store: SettingsStore = app.settings

    /** Transport router used by input sessions. */
    val router: TransportRouter = app.router

    /** Current settings. */
    val settings: StateFlow<Settings> = store.settings

    /** Active link state. */
    val link: StateFlow<LinkState> = router.state

    private val _hosts = MutableStateFlow<List<BtHost>>(emptyList())

    /** Paired computers available as Bluetooth targets. */
    val hosts: StateFlow<List<BtHost>> = _hosts.asStateFlow()

    /** Reloads the bonded device list. */
    fun refreshHosts() {
        _hosts.value = BluetoothSupport.bondedHosts(app)
    }

    /** Selects Bluetooth with [host] as the target. */
    fun selectBluetooth(host: BtHost) = store.selectTransport(TransportKind.BLUETOOTH, host.address)

    /** Selects a helper transport (USB or Wi-Fi hotspot). */
    fun selectWired(kind: TransportKind) = store.selectTransport(kind)

    /** Changes the Bluetooth stack preference. */
    fun setBtMode(mode: BtMode) = store.putString(SettingsStore.KEY_BT_MODE, mode.key)

    /** Retries the active connection. */
    fun retry() = router.retry()

    /** Answers a wired pairing request. */
    fun respondToPairing(approve: Boolean) = router.respondToPairing(approve)

    /** Restarts the transport after permissions changed and refreshes hosts. */
    fun onPermissionsChanged() {
        refreshHosts()
        router.restart()
    }
}
