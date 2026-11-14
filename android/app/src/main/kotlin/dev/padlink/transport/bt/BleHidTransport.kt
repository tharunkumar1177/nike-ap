// BleHidTransport.kt: BLE HID over GATT (HOGP) peripheral carrying mouse and pen reports.
package dev.padlink.transport.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * GATT server exposing HID (0x1812), Battery (0x180F), and Device Information (0x180A) services
 * with the combined descriptor. Runtime permissions are checked in [start].
 */
@SuppressLint("MissingPermission")
class BleHidTransport(
    private val context: Context,
    private val targetAddress: String?,
    private val onHostConnected: (String) -> Unit,
) : Transport {

    private val _state = MutableStateFlow(LinkState(LinkStatus.IDLE, TransportStack.BLE))
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val reportSizes = HidDescriptorParser.parse(HidDescriptor.DESCRIPTOR).inputBytes
    private val carry = WheelCarry()
    private val outbox = Outbox("ble", ::deliver)
    private val events = BluetoothEvents(context, ::onAdapterState) { _, _ -> }
    private val notifyDone = Semaphore(0)

    private var server: BluetoothGattServer? = null
    private var advertising = false
    private var running = false
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private val reportChars = HashMap<Int, BluetoothGattCharacteristic>()
    private var batteryChar: BluetoothGattCharacteristic? = null
    private val staticValues = HashMap<UUID, ByteArray>()
    private val descriptorValues = HashMap<BluetoothGattDescriptor, ByteArray>()
    private val subscriptions = ConcurrentHashMap<String, MutableSet<BluetoothGattCharacteristic>>()
    private val clientLinks = HashMap<String, BluetoothGatt>()
    private var lastBatteryNotify = 0L

    @Volatile
    private var active: BluetoothDevice? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        /** Advertising is running. */
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
        }

        /** Advertising failed for a reason other than already running. */
        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                advertising = true
                return
            }
            main.post { if (active == null) fail(LinkIssue.BLE_ADVERTISING_FAILED) }
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        /** Requests the shortest connection interval once the client link to the host is up. */
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        /** Continues adding services one at a time, then advertises. */
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post { addNextService() }
        }

        /** Tracks host connections. */
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post { onHostLink(device, newState == BluetoothProfile.STATE_CONNECTED) }
        }

        /** Serves characteristic reads, including offset reads of the long report map. */
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            respondRead(device, requestId, offset, characteristicValue(characteristic))
        }

        /** Accepts Protocol Mode and HID Control Point writes. */
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        /** Serves Report Reference and per-host CCCD values. */
        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val value = if (descriptor.uuid == CCCD) {
                val on = subscriptions[device.address]?.contains(descriptor.characteristic) == true
                if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            } else {
                descriptorValues[descriptor] ?: ByteArray(0)
            }
            respondRead(device, requestId, offset, value)
        }

        /** Records notification subscriptions; the first report subscription marks the host connected. */
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor.uuid == CCCD) {
                val characteristic = descriptor.characteristic
                val enabled = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                val set = subscriptions.getOrPut(device.address) { ConcurrentHashMap.newKeySet() }
                if (enabled) set.add(characteristic) else set.remove(characteristic)
                main.post { onSubscriptionChanged(device) }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        /** Unblocks the sender after each notification. */
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifyDone.release()
        }
    }

    /** Validates prerequisites, opens the GATT server, and adds services. */
    override fun start() {
        if (running) return
        running = true
        events.register()
        begin()
    }

    /** Runs prerequisite checks and builds the GATT database. */
    private fun begin() {
        val adapter = adapter ?: return fail(LinkIssue.BLUETOOTH_UNSUPPORTED)
        if (!BluetoothSupport.hasConnectPermission(context) || !BluetoothSupport.hasAdvertisePermission(context)) {
            return fail(LinkIssue.PERMISSION_MISSING)
        }
        if (!adapter.isEnabled) return fail(LinkIssue.BLUETOOTH_OFF)
        if (adapter.bluetoothLeAdvertiser == null) return fail(LinkIssue.BLE_ADVERTISING_FAILED)
        val gattServer = manager?.openGattServer(context, serverCallback) ?: return fail(LinkIssue.BLE_ADVERTISING_FAILED)
        server = gattServer
        setState(LinkStatus.CONNECTING)
        pendingServices.clear()
        pendingServices += buildHidService()
        pendingServices += buildBatteryService()
        pendingServices += buildDeviceInfoService()
        addNextService()
    }

    /** Adds the next queued service, or starts advertising when all are added. */
    private fun addNextService() {
        if (!running) return
        val gattServer = server ?: return
        val next = pendingServices.removeFirstOrNull()
        if (next != null) {
            if (!gattServer.addService(next)) fail(LinkIssue.BLE_ADVERTISING_FAILED)
            return
        }
        startAdvertising()
        connectTarget()
    }

    /** HID service: information, report map, control point, protocol mode, one report per input ID. */
    private fun buildHidService(): BluetoothGattService {
        val service = BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(readOnly(HID_INFORMATION, byteArrayOf(0x11, 0x01, 0x00, 0x02)))
        service.addCharacteristic(readOnly(REPORT_MAP, HidDescriptor.DESCRIPTOR))
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                HID_CONTROL_POINT,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            ),
        )
        val protocolMode = BluetoothGattCharacteristic(
            PROTOCOL_MODE,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )
        staticValues[PROTOCOL_MODE] = byteArrayOf(0x01)
        service.addCharacteristic(protocolMode)
        reportChars.clear()
        for (id in INPUT_REPORT_IDS) {
            val report = BluetoothGattCharacteristic(
                REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            )
            report.addDescriptor(cccd())
            val reference = BluetoothGattDescriptor(REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED)
            descriptorValues[reference] = byteArrayOf(id.toByte(), REPORT_TYPE_INPUT)
            report.addDescriptor(reference)
            service.addCharacteristic(report)
            reportChars[id] = report
        }
        return service
    }

    /** Battery service with the real battery level. */
    private fun buildBatteryService(): BluetoothGattService {
        val service = BluetoothGattService(BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val level = BluetoothGattCharacteristic(
            BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        level.addDescriptor(cccd())
        service.addCharacteristic(level)
        batteryChar = level
        return service
    }

    /** Device Information service with manufacturer name and PnP ID. */
    private fun buildDeviceInfoService(): BluetoothGattService {
        val service = BluetoothGattService(DEVICE_INFO_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(readOnly(MANUFACTURER_NAME, ClassicHidTransport.SDP_PROVIDER.toByteArray()))
        service.addCharacteristic(readOnly(PNP_ID, PNP_ID_VALUE))
        return service
    }

    /** Encrypted read-only characteristic with a fixed value. */
    private fun readOnly(uuid: UUID, value: ByteArray): BluetoothGattCharacteristic {
        staticValues[uuid] = value
        return BluetoothGattCharacteristic(
            uuid, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        )
    }

    /** Client Characteristic Configuration descriptor. */
    private fun cccd() = BluetoothGattDescriptor(
        CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
    )

    /** Current value for a characteristic read. */
    private fun characteristicValue(characteristic: BluetoothGattCharacteristic): ByteArray = when {
        characteristic.uuid == BATTERY_LEVEL -> byteArrayOf(batteryLevel().toByte())
        characteristic.uuid == REPORT -> {
            val id = reportChars.entries.firstOrNull { it.value === characteristic }?.key
            ReportEncoder.idle(reportSizes[id] ?: 0)
        }
        else -> staticValues[characteristic.uuid] ?: ByteArray(0)
    }

    /** Sends a read response honoring [offset] for long reads. */
    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray) {
        val gattServer = server ?: return
        if (offset > value.size) {
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
        } else {
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }
    }

    /** Starts connectable, low-latency advertising of the HID service. */
    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return fail(LinkIssue.BLE_ADVERTISING_FAILED)
        if (advertising) return
        val name = try {
            adapter?.name.orEmpty()
        } catch (_: SecurityException) {
            ""
        }
        val nameInScanResponse = name.length > MAX_NAME_IN_ADVERTISEMENT
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(HID_SERVICE))
            .setIncludeDeviceName(!nameInScanResponse)
            .build()
        val scanResponse = if (nameInScanResponse) AdvertiseData.Builder().setIncludeDeviceName(true).build() else null
        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (_: SecurityException) {
            fail(LinkIssue.PERMISSION_MISSING)
        }
    }

    /** Stops advertising if running. */
    private fun stopAdvertising() {
        if (!advertising) return
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: SecurityException) {
            // Permission revoked; the stack stops advertising with the process.
        }
        advertising = false
    }

    /** Asks the stack to reconnect to the selected bonded host. */
    private fun connectTarget() {
        val device = BluetoothSupport.device(context, targetAddress)
        if (device == null) {
            setState(LinkStatus.IDLE, issue = LinkIssue.NO_TARGET)
            return
        }
        setState(LinkStatus.CONNECTING, BluetoothSupport.displayName(device))
        server?.connect(device, false)
    }

    /** Handles a host connecting or disconnecting at the GATT level. */
    private fun onHostLink(device: BluetoothDevice, connected: Boolean) {
        if (!running) return
        if (connected) {
            if (!clientLinks.containsKey(device.address)) {
                device.connectGatt(context, false, clientCallback)?.let { clientLinks[device.address] = it }
            }
            return
        }
        subscriptions.remove(device.address)
        clientLinks.remove(device.address)?.close()
        if (active?.address == device.address) {
            active = null
            outbox.clear()
            notifyDone.release()
            setState(LinkStatus.DISCONNECTED, BluetoothSupport.displayName(device))
            advertising = false
            startAdvertising()
        }
    }

    /** Makes a host active once it subscribes to a report and matches the target (if any). */
    private fun onSubscriptionChanged(device: BluetoothDevice) {
        if (!running) return
        val subscribed = !subscriptions[device.address].isNullOrEmpty()
        val matchesTarget = targetAddress == null || targetAddress == device.address
        if (subscribed && matchesTarget && active?.address != device.address) {
            active = device
            carry.reset()
            setState(LinkStatus.CONNECTED, BluetoothSupport.displayName(device))
            onHostConnected(device.address)
        }
    }

    /** Restarts when the adapter turns on and reports when it turns off. */
    private fun onAdapterState(state: Int) {
        main.post {
            if (!running) return@post
            when (state) {
                BluetoothAdapter.STATE_ON -> if (server == null) begin()
                BluetoothAdapter.STATE_OFF -> {
                    teardownServer()
                    fail(LinkIssue.BLUETOOTH_OFF)
                }
            }
        }
    }

    /** Queues a report while a host is subscribed. */
    override fun send(item: Outgoing) {
        if (active != null) outbox.offer(item)
    }

    /** Sends one queued item as notifications on the outbox thread. */
    private fun deliver(item: Outgoing): Boolean {
        val device = active ?: return false
        val ok = when (item) {
            is Outgoing.Mouse -> ReportEncoder.mouse(item.report, carry).all { notify(device, HidDescriptor.ID_MOUSE, it) }
            is Outgoing.Pen -> notify(device, HidDescriptor.ID_PEN, ReportEncoder.pen(item.report))
        }
        maybeNotifyBattery(device)
        return ok
    }

    /** Notifies one report and waits for the stack to accept the next. */
    private fun notify(device: BluetoothDevice, reportId: Int, payload: ByteArray): Boolean {
        val characteristic = reportChars[reportId] ?: return false
        return notifyCharacteristic(device, characteristic, payload)
    }

    /** Sends a notification with the API-appropriate call and waits for onNotificationSent. */
    @Suppress("DEPRECATION")
    private fun notifyCharacteristic(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        val gattServer = server ?: return false
        notifyDone.drainPermits()
        val sent = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer.notifyCharacteristicChanged(device, characteristic, false, payload) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = payload
                gattServer.notifyCharacteristicChanged(device, characteristic, false)
            }
        } catch (_: SecurityException) {
            false
        }
        if (!sent) return false
        return notifyDone.tryAcquire(NOTIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Pushes the battery level at most every five minutes while input flows. */
    private fun maybeNotifyBattery(device: BluetoothDevice) {
        val now = System.currentTimeMillis()
        if (now - lastBatteryNotify < BATTERY_INTERVAL_MS) return
        val characteristic = batteryChar ?: return
        if (subscriptions[device.address]?.contains(characteristic) != true) return
        lastBatteryNotify = now
        notifyCharacteristic(device, characteristic, byteArrayOf(batteryLevel().toByte()))
    }

    /** Battery percentage from the system. */
    private fun batteryLevel(): Int =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.coerceIn(0, 100) ?: 100

    /** Retries after the user taps Connect. */
    override fun retry() {
        main.post {
            if (!running) return@post
            if (server == null) begin() else connectTarget()
        }
    }

    /** Releases held input, stops advertising, and closes the GATT server. */
    override fun stop() {
        if (!running) return
        running = false
        events.unregister()
        outbox.close()
        val device = active
        if (device != null) {
            notify(device, HidDescriptor.ID_MOUSE, ReportEncoder.idle(MOUSE_REPORT_SIZE))
            notify(device, HidDescriptor.ID_PEN, ReportEncoder.pen(PenReport.OUT_OF_RANGE))
        }
        teardownServer()
        _state.value = LinkState(LinkStatus.IDLE, TransportStack.BLE)
    }

    /** Closes advertising, client links, and the GATT server. */
    private fun teardownServer() {
        stopAdvertising()
        clientLinks.values.forEach { it.close() }
        clientLinks.clear()
        val gattServer = server
        active?.let { gattServer?.cancelConnection(it) }
        active = null
        subscriptions.clear()
        gattServer?.close()
        server = null
        notifyDone.release()
    }

    /** Publishes UNAVAILABLE with [issue]. */
    private fun fail(issue: LinkIssue) {
        _state.value = LinkState(LinkStatus.UNAVAILABLE, TransportStack.BLE, targetName(), issue)
    }

    /** Publishes a status. */
    private fun setState(status: LinkStatus, name: String? = targetName(), issue: LinkIssue? = null) {
        _state.value = LinkState(status, TransportStack.BLE, name, issue)
    }

    /** Display name of the target host. */
    private fun targetName(): String? = BluetoothSupport.device(context, targetAddress)?.let { BluetoothSupport.displayName(it) }

    companion object {
        private val INPUT_REPORT_IDS = intArrayOf(
            HidDescriptor.ID_KEYBOARD, HidDescriptor.ID_MOUSE, HidDescriptor.ID_CONSUMER, HidDescriptor.ID_PEN,
        )
        private const val REPORT_TYPE_INPUT: Byte = 0x01
        private const val MOUSE_REPORT_SIZE = 5
        private const val MAX_NAME_IN_ADVERTISEMENT = 22
        private const val NOTIFY_TIMEOUT_MS = 100L
        private const val BATTERY_INTERVAL_MS = 300_000L

        /** USB-IF vendor source, vendor 0x1209 (pid.codes), product 0x0001, version 0x0100, little-endian. */
        private val PNP_ID_VALUE = byteArrayOf(0x02, 0x09, 0x12, 0x01, 0x00, 0x00, 0x01)

        /** Expands a 16-bit SIG UUID. */
        private fun uuid16(value: Int): UUID =
            UUID.fromString(String.format(Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", value))

        val HID_SERVICE: UUID = uuid16(0x1812)
        val HID_INFORMATION: UUID = uuid16(0x2A4A)
        val REPORT_MAP: UUID = uuid16(0x2A4B)
        val HID_CONTROL_POINT: UUID = uuid16(0x2A4C)
        val REPORT: UUID = uuid16(0x2A4D)
        val PROTOCOL_MODE: UUID = uuid16(0x2A4E)
        val BATTERY_SERVICE: UUID = uuid16(0x180F)
        val BATTERY_LEVEL: UUID = uuid16(0x2A19)
        val DEVICE_INFO_SERVICE: UUID = uuid16(0x180A)
        val MANUFACTURER_NAME: UUID = uuid16(0x2A29)
        val PNP_ID: UUID = uuid16(0x2A50)
        val CCCD: UUID = uuid16(0x2902)
        val REPORT_REFERENCE: UUID = uuid16(0x2908)
    }
}
