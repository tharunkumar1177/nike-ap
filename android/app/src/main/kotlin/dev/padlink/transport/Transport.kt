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
    PORT_IN_USE,
}

/** Pending approval of a wired helper over tethering. */
data class PairingRequest(val code: String, val hostName: String)

/** Pixel size of the computer screen the pen targets, reported by the wired helper. */
data class HostSize(val width: Int, val height: Int)

/** Which concrete stack is running, for display. */
enum class TransportStack { NONE, CLASSIC, BLE, USB_ADB, USB_TETHER }

/** Snapshot of one transport's link. */
data class LinkState(
    val status: LinkStatus,
    val stack: TransportStack = TransportStack.NONE,
    val targetName: String? = null,
    val issue: LinkIssue? = null,
    val pairing: PairingRequest? = null,
    val hostSize: HostSize? = null,
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
