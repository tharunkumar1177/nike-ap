// Transport.kt: transport interface and the shared connection state model.
package dev.padlink.transport

import dev.padlink.input.MouseReport
import dev.padlink.input.PenReport
import kotlinx.coroutines.flow.StateFlow

/** Link status values from "Connection state model" in architecture.md. */
enum class LinkStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    PROFILE_NOT_CONNECTED,
    PAIRING_REQUEST,
    UNAVAILABLE,
}

/** Why a transport is idle or unavailable; the UI maps each to guidance text. */
enum class LinkIssue {
    NO_TARGET,
    BLUETOOTH_UNSUPPORTED,
    BLUETOOTH_OFF,
    PERMISSION_MISSING,
    HID_PROFILE_UNAVAILABLE,
    HID_REGISTRATION_FAILED,
    BLE_ADVERTISING_FAILED,
    WAITING_FOR_HELPER,
    TETHER_OFF,
    HOTSPOT_OFF,
    PORT_IN_USE,
}

/** Pending approval of a wired helper over tethering. */
data class PairingRequest(val code: String, val hostName: String)

/** Why the last wired helper connection ended. */
enum class DropReason {
    /** Connected, but nothing arrived for the 3 s link timeout. */
    HELPER_SILENT,
    /** The helper closed the connection or said Bye. */
    HELPER_CLOSED,
    /** The helper connected but sent no Hello within 3 s. */
    NO_HELLO,
    /** Another helper connection took over. */
    REPLACED,
    /** Bytes on the wire did not follow the protocol. */
    PROTOCOL_ERROR,
    /** The socket failed. */
    NETWORK_ERROR,
}

/** The last wired disconnect, shown while waiting for the helper to return. */
data class LinkDrop(val reason: DropReason, val hostName: String, val wasConnected: Boolean)

/** Pixel size of the computer screen the pen targets, reported by the wired helper. */
data class HostSize(val width: Int, val height: Int)

/** Which concrete stack is running, for display. */
enum class TransportStack { NONE, CLASSIC, BLE, USB_ADB, USB_TETHER, WIFI_HOTSPOT }

/** Snapshot of one transport's link. */
data class LinkState(
    val status: LinkStatus,
    val stack: TransportStack = TransportStack.NONE,
    val targetName: String? = null,
    val issue: LinkIssue? = null,
    val pairing: PairingRequest? = null,
    val hostSize: HostSize? = null,
    val lastDrop: LinkDrop? = null,
    /** Address the helper server listens on (hotspot mode), shown while waiting for the helper. */
    val listenAddress: String? = null,
    /** False while waiting when no computer has opened a connection to the server since it started. */
    val helperReached: Boolean = true,
) {
    companion object {
        val IDLE = LinkState(LinkStatus.IDLE)
    }
}

/** Item queued for delivery on a transport's sender thread. */
sealed interface Outgoing {
    /** A relative mouse report. */
    data class Mouse(val report: MouseReport) : Outgoing

    /** An absolute pen report. */
    data class Pen(val report: PenReport) : Outgoing
}

/** One way of delivering input to a computer. Exactly one is active at a time. */
interface Transport {
    /** Current link state. */
    val state: StateFlow<LinkState>

    /** Starts the stack and begins connecting. */
    fun start()

    /** Releases held input where possible and stops the stack. */
    fun stop()

    /** Queues a report for delivery; dropped silently while not connected. */
    fun send(item: Outgoing)

    /** Retries the connection after a failure the user acknowledged. */
    fun retry() {}

    /** Answers a pending pairing request. */
    fun respondToPairing(approve: Boolean) {}
}
