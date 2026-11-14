// TransportRouter.kt: owns the single active transport, follows connection settings, and falls back from Classic to BLE.
package dev.padlink.transport

import android.content.Context
import dev.padlink.input.MouseReport
import dev.padlink.input.PenReport
import dev.padlink.settings.BtMode
import dev.padlink.settings.ConnectionSettings
import dev.padlink.settings.SettingsStore
import dev.padlink.settings.TransportKind
import dev.padlink.transport.bt.BleHidTransport
import dev.padlink.transport.bt.ClassicHidTransport
import dev.padlink.transport.wired.WiredMode
import dev.padlink.transport.wired.WiredTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Starts, stops, and swaps transports on the main thread; mirrors the active link state. */
class TransportRouter(private val context: Context, private val settings: SettingsStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(LinkState.IDLE)

    /** Link state of the active transport. */
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private var current: Transport? = null
    private var stateJob: Job? = null
    private var settingsJob: Job? = null
    private var activeConnection: ConnectionSettings? = null
    private var adoptedAddress: String? = null
    private var classicUnavailable = false

    /** Begins following connection settings; idempotent. */
    fun activate() {
        if (settingsJob != null) return
        settingsJob = scope.launch {
            settings.settings.map { it.connection }.distinctUntilChanged().collect { onConnectionSettings(it) }
        }
    }

    /** Stops the active transport and stops following settings. */
    fun deactivate() {
        settingsJob?.cancel()
        settingsJob = null
        stopCurrent()
        activeConnection = null
        _state.value = LinkState.IDLE
    }

    /** Rebuilds the active transport, for example after permissions were granted. */
    fun restart() {
        val connection = settings.settings.value.connection
        if (settingsJob == null) return
        classicUnavailable = false
        switchTo(connection)
    }

    /** Applies a settings change unless it only records a host that connected by itself. */
    private fun onConnectionSettings(connection: ConnectionSettings) {
        val previous = activeConnection
        if (previous != null && connection.btTarget == adoptedAddress &&
            previous.copy(btTarget = connection.btTarget) == connection
        ) {
            activeConnection = connection
            return
        }
        if (previous == null || previous.transport != connection.transport || previous.btMode != connection.btMode) {
            classicUnavailable = false
        }
        switchTo(connection)
    }

    /** Stops the current transport and starts the one the settings select. */
    private fun switchTo(connection: ConnectionSettings) {
        stopCurrent()
        activeConnection = connection
        startTransport(build(connection), connection)
    }

    /** Creates the transport for [connection]. */
    private fun build(connection: ConnectionSettings): Transport = when (connection.transport) {
        TransportKind.BLUETOOTH -> when (connection.btMode) {
            BtMode.CLASSIC -> ClassicHidTransport(context, connection.btTarget, ::adoptHost)
            BtMode.BLE -> BleHidTransport(context, connection.btTarget, ::adoptHost)
            BtMode.AUTO -> if (classicUnavailable) {
                BleHidTransport(context, connection.btTarget, ::adoptHost)
            } else {
                ClassicHidTransport(context, connection.btTarget, ::adoptHost)
            }
        }
        TransportKind.USB_ADB -> WiredTransport(WiredMode.ADB, settings)
        TransportKind.USB_TETHER -> WiredTransport(WiredMode.TETHER, settings)
        TransportKind.WIFI_HOTSPOT -> WiredTransport(WiredMode.HOTSPOT, settings)
    }

    /** Starts [transport], mirrors its state, and watches for an automatic BLE fallback. */
    private fun startTransport(transport: Transport, connection: ConnectionSettings) {
        current = transport
        stateJob = scope.launch {
            transport.state.collect { s ->
                _state.value = s
                if (shouldFallBack(transport, connection, s)) scope.launch { fallBackToBle(connection) }
            }
        }
        transport.start()
    }

    /** True when automatic mode should replace a failed Classic transport with BLE. */
    private fun shouldFallBack(transport: Transport, connection: ConnectionSettings, s: LinkState): Boolean =
        transport is ClassicHidTransport &&
            connection.transport == TransportKind.BLUETOOTH &&
            connection.btMode == BtMode.AUTO &&
            s.status == LinkStatus.UNAVAILABLE &&
            (s.issue == LinkIssue.HID_PROFILE_UNAVAILABLE || s.issue == LinkIssue.HID_REGISTRATION_FAILED)

    /** Swaps a failed Classic transport for BLE. */
    private fun fallBackToBle(connection: ConnectionSettings) {
        if (current !is ClassicHidTransport || activeConnection != connection) return
        classicUnavailable = true
        switchTo(connection)
    }

    /** Stops and forgets the current transport. */
    private fun stopCurrent() {
        stateJob?.cancel()
        stateJob = null
        current?.stop()
        current = null
    }

    /** Remembers a host that connected on its own as the Bluetooth target without restarting. */
    private fun adoptHost(address: String) {
        if (settings.settings.value.connection.btTarget == address) return
        adoptedAddress = address
        settings.putString(SettingsStore.KEY_BT_TARGET, address)
    }

    /** Sends a mouse report on the active transport. */
    fun sendMouse(report: MouseReport) {
        current?.send(Outgoing.Mouse(report))
    }

    /** Sends a pen report on the active transport. */
    fun sendPen(report: PenReport) {
        current?.send(Outgoing.Pen(report))
    }

    /** Retries the active transport's connection. */
    fun retry() {
        current?.retry()
    }

    /** Answers a wired pairing request. */
    fun respondToPairing(approve: Boolean) {
        current?.respondToPairing(approve)
    }
}
