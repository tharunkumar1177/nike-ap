// BluetoothSupport.kt: adapter access, runtime permission checks, bonded hosts, and adapter/ACL broadcasts.
package dev.padlink.transport.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/** A paired computer that can be selected as the Bluetooth target. */
data class BtHost(val address: String, val name: String)

/** Shared Bluetooth helpers used by both HID stacks and the UI. */
object BluetoothSupport {

    /** Returns the default adapter, or null when the device has no Bluetooth. */
    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Runtime permissions the Bluetooth transports need on this API level. */
    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        emptyArray()
    }

    /** True when BLUETOOTH_CONNECT is granted (always true before Android 12). */
    fun hasConnectPermission(context: Context): Boolean = granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    /** True when BLUETOOTH_ADVERTISE is granted (always true before Android 12). */
    fun hasAdvertisePermission(context: Context): Boolean = granted(context, Manifest.permission.BLUETOOTH_ADVERTISE)

    /** Checks one Android 12+ Bluetooth permission. */
    private fun granted(context: Context, permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Bonded devices sorted by name; empty without permission or adapter. */
    @SuppressLint("MissingPermission")
    fun bondedHosts(context: Context): List<BtHost> {
        val adapter = adapter(context) ?: return emptyList()
        if (!hasConnectPermission(context)) return emptyList()
        return try {
            adapter.bondedDevices.orEmpty()
                .map { BtHost(it.address, displayName(it)) }
                .sortedBy { it.name.lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** Human-readable device name, falling back to the address. */
    @SuppressLint("MissingPermission")
    fun displayName(device: BluetoothDevice): String = try {
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null
        alias ?: device.name ?: device.address
    } catch (_: SecurityException) {
        device.address
    }

    /** Resolves an address to a device, or null when invalid. */
    fun device(context: Context, address: String?): BluetoothDevice? {
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) return null
        return adapter(context)?.getRemoteDevice(address)
    }

    /** Extracts the device extra from a Bluetooth broadcast. */
    fun deviceExtra(intent: Intent): BluetoothDevice? =
        IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
}

/** Listens to adapter on/off and ACL link changes while a Bluetooth transport runs. */
class BluetoothEvents(
    private val context: Context,
    private val onAdapterState: (Int) -> Unit,
    private val onAcl: (BluetoothDevice, Boolean) -> Unit,
) {
    private val receiver = object : BroadcastReceiver() {
        /** Dispatches adapter and ACL broadcasts. */
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    onAdapterState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR))
                BluetoothDevice.ACTION_ACL_CONNECTED -> BluetoothSupport.deviceExtra(intent)?.let { onAcl(it, true) }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> BluetoothSupport.deviceExtra(intent)?.let { onAcl(it, false) }
            }
        }
    }
    private var registered = false

    /** Starts listening. */
    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    /** Stops listening. */
    fun unregister() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered by the system during teardown.
        }
        registered = false
    }
}
