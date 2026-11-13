// ClassicHidTransport.kt: Classic Bluetooth HID device (BluetoothHidDevice) carrying mouse and pen reports.
package dev.padlink.transport.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.padlink.hid.HidDescriptor
import dev.padlink.hid.HidDescriptorParser
import dev.padlink.hid.ReportEncoder
import dev.padlink.hid.WheelCarry
import dev.padlink.input.PenReport
import dev.padlink.transport.LinkIssue
import dev.padlink.transport.LinkState
import dev.padlink.transport.LinkStatus
import dev.padlink.transport.Outbox
import dev.padlink.transport.Outgoing
import dev.padlink.transport.Transport
import dev.padlink.transport.TransportStack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Registers the combined HID descriptor with the system HID Device profile and connects to the
 * selected host. Runtime permissions are checked in [start]; all state changes run on the main thread.
 */
@SuppressLint("MissingPermission")
class ClassicHidTransport(
    private val context: Context,
    private val targetAddress: String?,
    private val onHostConnected: (String) -> Unit,
) : Transport {

    private val _state = MutableStateFlow(LinkState(LinkStatus.IDLE, TransportStack.CLASSIC))
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val reportSizes = HidDescriptorParser.parse(HidDescriptor.DESCRIPTOR).inputBytes
    private val carry = WheelCarry()
    private val outbox = Outbox("classic", ::deliver)
    private val events = BluetoothEvents(context, ::onAdapterState, ::onAcl)
    private val adapter: BluetoothAdapter? = BluetoothSupport.adapter(context)

    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private var running = false
    private var aclUp = false
    private var target: BluetoothDevice? = null

    @Volatile
    private var connected: BluetoothDevice? = null

    private val proxyTimeout = Runnable { fail(LinkIssue.HID_PROFILE_UNAVAILABLE) }
    private val profileCheck = Runnable { checkProfileAfterConnect() }
    private val retryConnect = Runnable { connectTarget() }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        /** Receives the HID Device proxy and registers the app. */
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            main.post {
                main.removeCallbacks(proxyTimeout)
                if (!running) {
                    adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
                    return@post
                }
                hid = proxy as BluetoothHidDevice
                register()
            }
        }

        /** The Bluetooth process restarted; the registration is gone. */
        override fun onServiceDisconnected(profile: Int) {
            main.post {
                hid = null
                registered = false
                connected = null
                if (running) fail(LinkIssue.HID_PROFILE_UNAVAILABLE)
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        /** Registration result; on success connect to the target host. */
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            main.post { onRegistration(pluggedDevice, registered) }
        }

        /** Tracks the HID connection to a host. */
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            main.post { onHidConnection(device, state) }
        }

        /** Answers GET_REPORT with an idle input report of the right size. */
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val size = reportSizes[id.toInt()]
            val proxy = hid ?: return
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && size != null) {
                proxy.replyReport(device, type, id, ReportEncoder.idle(size))
            } else {
                proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            }
        }

        /** Acknowledges SET_REPORT; the descriptor declares no output or feature reports. */
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        /** The host removed the virtual cable; treat as a disconnect. */
        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            main.post { onHidConnection(device, BluetoothProfile.STATE_DISCONNECTED) }
        }
    }

    /** Validates prerequisites and requests the HID Device profile proxy. */
    override fun start() {
        if (running) return
        running = true
        events.register()
        begin()
    }

    /** Runs the prerequisite checks and asks for the profile proxy. */
    private fun begin() {
        val adapter = adapter ?: return fail(LinkIssue.BLUETOOTH_UNSUPPORTED)
        if (!BluetoothSupport.hasConnectPermission(context)) return fail(LinkIssue.PERMISSION_MISSING)
        if (!adapter.isEnabled) return fail(LinkIssue.BLUETOOTH_OFF)
        target = BluetoothSupport.device(context, targetAddress)
        setState(LinkStatus.CONNECTING)
        if (!adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)) {
            return fail(LinkIssue.HID_PROFILE_UNAVAILABLE)
        }
        main.postDelayed(proxyTimeout, PROXY_TIMEOUT_MS)
    }

    /** Registers the SDP record and descriptor with the profile. */
    private fun register() {
        val proxy = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            SDP_NAME, SDP_DESCRIPTION, SDP_PROVIDER, HidDescriptor.SUBCLASS_COMBO, HidDescriptor.DESCRIPTOR,
        )
        val qosOut = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 32, 2000, -1, -1,
        )
        val ok = try {
            proxy.registerApp(sdp, null, qosOut, callbackExecutor, hidCallback)
        } catch (_: SecurityException) {
            false
        }
        if (!ok) fail(LinkIssue.HID_REGISTRATION_FAILED)
    }

    /** Handles registration changes; losing registration to another app is a failure. */
    private fun onRegistration(plugged: BluetoothDevice?, isRegistered: Boolean) {
        if (!running) return
        val wasRegistered = registered
        registered = isRegistered
        when {
            isRegistered && !wasRegistered -> {
                if (target == null && plugged != null) target = plugged
                connectTarget()
            }
            !isRegistered -> {
                connected = null
                fail(LinkIssue.HID_REGISTRATION_FAILED)
            }
        }
    }

    /** Connects to the target host, or waits for any host to connect when none is selected. */
    private fun connectTarget() {
        main.removeCallbacks(retryConnect)
        main.removeCallbacks(profileCheck)
        if (!running || !registered) return
        if (connected != null) return
        val device = target ?: return setState(LinkStatus.IDLE, issue = LinkIssue.NO_TARGET)
        setState(LinkStatus.CONNECTING, BluetoothSupport.displayName(device))
        val started = try {
            hid?.connect(device) == true
        } catch (_: SecurityException) {
            false
        }
        main.postDelayed(profileCheck, if (started) PROFILE_CHECK_MS else RETRY_MS)
    }

    /** After a connect attempt: report a missing HID profile, or retry. */
    private fun checkProfileAfterConnect() {
        if (!running || connected != null) return
        val device = target ?: return
        if (aclUp) {
            setState(LinkStatus.PROFILE_NOT_CONNECTED, BluetoothSupport.displayName(device))
        } else {
            setState(LinkStatus.DISCONNECTED, BluetoothSupport.displayName(device))
        }
        main.postDelayed(retryConnect, RETRY_MS)
    }

    /** Updates the connection from profile callbacks. */
    private fun onHidConnection(device: BluetoothDevice, state: Int) {
        if (!running) return
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                main.removeCallbacks(profileCheck)
                main.removeCallbacks(retryConnect)
                connected = device
                target = device
                carry.reset()
                setState(LinkStatus.CONNECTED, BluetoothSupport.displayName(device))
                onHostConnected(device.address)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (connected?.address != device.address && target?.address != device.address) return
                connected = null
                outbox.clear()
                setState(LinkStatus.DISCONNECTED, BluetoothSupport.displayName(device))
                main.postDelayed(retryConnect, RETRY_MS)
            }
        }
    }

    /** Tracks the ACL link of the target for "connection needs update" detection. */
    private fun onAcl(device: BluetoothDevice, up: Boolean) {
        main.post {
            if (device.address == target?.address) aclUp = up
        }
    }

    /** Restarts when the adapter turns on and reports when it turns off. */
    private fun onAdapterState(state: Int) {
        main.post {
            if (!running) return@post
            when (state) {
                BluetoothAdapter.STATE_ON -> if (hid == null) begin()
                BluetoothAdapter.STATE_OFF -> {
                    connected = null
                    registered = false
                    hid = null
                    fail(LinkIssue.BLUETOOTH_OFF)
                }
            }
        }
    }

    /** Queues a report while a host is connected. */
    override fun send(item: Outgoing) {
        if (connected != null) outbox.offer(item)
    }

    /** Sends one queued item on the outbox thread. */
    private fun deliver(item: Outgoing): Boolean {
        val device = connected ?: return false
        val proxy = hid ?: return false
        return when (item) {
            is Outgoing.Mouse -> ReportEncoder.mouse(item.report, carry).all {
                proxy.sendReport(device, HidDescriptor.ID_MOUSE, it)
            }
            is Outgoing.Pen -> proxy.sendReport(device, HidDescriptor.ID_PEN, ReportEncoder.pen(item.report))
        }
    }

    /** Retries after the user taps Connect on the info card. */
    override fun retry() {
        main.post {
            if (!running) return@post
            if (hid == null) begin() else connectTarget()
        }
    }

    /** Releases held input, unregisters, and frees the proxy. */
    override fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(proxyTimeout)
        main.removeCallbacks(profileCheck)
        main.removeCallbacks(retryConnect)
        events.unregister()
        outbox.close()
        val proxy = hid
        val device = connected
        try {
            if (proxy != null && device != null) {
                proxy.sendReport(device, HidDescriptor.ID_MOUSE, ReportEncoder.idle(MOUSE_REPORT_SIZE))
                proxy.sendReport(device, HidDescriptor.ID_PEN, ReportEncoder.pen(PenReport.OUT_OF_RANGE))
            }
            if (proxy != null && registered) proxy.unregisterApp()
            if (proxy != null) adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        } catch (_: SecurityException) {
            // Permission revoked while running; the system tears the registration down itself.
        }
        hid = null
        registered = false
        connected = null
        callbackExecutor.shutdown()
        _state.value = LinkState(LinkStatus.IDLE, TransportStack.CLASSIC)
    }

    /** Publishes UNAVAILABLE with [issue]. */
    private fun fail(issue: LinkIssue) {
        main.removeCallbacks(proxyTimeout)
        _state.value = LinkState(LinkStatus.UNAVAILABLE, TransportStack.CLASSIC, targetName(), issue)
    }

    /** Publishes a status for the target. */
    private fun setState(status: LinkStatus, name: String? = targetName(), issue: LinkIssue? = null) {
        _state.value = LinkState(status, TransportStack.CLASSIC, name, issue)
    }

    /** Display name of the current target, if any. */
    private fun targetName(): String? = target?.let { BluetoothSupport.displayName(it) }

    companion object {
        const val SDP_NAME = "PadLink"
        const val SDP_DESCRIPTION = "Android trackpad and pen"
        const val SDP_PROVIDER = "PadLink"
        private const val MOUSE_REPORT_SIZE = 5
        private const val PROXY_TIMEOUT_MS = 3_000L
        private const val PROFILE_CHECK_MS = 8_000L
        private const val RETRY_MS = 5_000L
    }
}
