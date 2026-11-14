// WiredTransport.kt: TCP server for the Windows helper over adb forward (loopback), USB tethering, or the phone's Wi-Fi hotspot.
package dev.padlink.transport.wired

import android.util.Log
import dev.padlink.input.PenReport
import dev.padlink.settings.SettingsStore
import dev.padlink.transport.DropReason
import dev.padlink.transport.HostSize
import dev.padlink.transport.LinkDrop
import dev.padlink.transport.LinkIssue
import dev.padlink.transport.LinkState
import dev.padlink.transport.LinkStatus
import dev.padlink.transport.Outbox
import dev.padlink.transport.Outgoing
import dev.padlink.transport.PairingRequest
import dev.padlink.transport.Transport
import dev.padlink.transport.TransportStack
import dev.padlink.wire.FrameReader
import dev.padlink.wire.WireCodec
import dev.padlink.wire.WireFormatException
import dev.padlink.wire.WireFrame
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which network path the helper server listens on. */
enum class WiredMode { ADB, TETHER, HOTSPOT }

/**
 * Helper transport: the phone is the TCP server on [WireCodec.PORT]. ADB mode binds loopback (the
 * helper uses `adb forward`); tethering mode binds the USB tether interface and hotspot mode binds
 * the Wi-Fi hotspot interface, and both require pairing.
 */
class WiredTransport(
    private val mode: WiredMode,
    private val settings: SettingsStore,
) : Transport {

    private val stack = when (mode) {
        WiredMode.ADB -> TransportStack.USB_ADB
        WiredMode.TETHER -> TransportStack.USB_TETHER
        WiredMode.HOTSPOT -> TransportStack.WIFI_HOTSPOT
    }
    private val offIssue = if (mode == WiredMode.HOTSPOT) LinkIssue.HOTSPOT_OFF else LinkIssue.TETHER_OFF
    private val _state = MutableStateFlow(LinkState(LinkStatus.CONNECTING, stack, issue = LinkIssue.WAITING_FOR_HELPER))
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val outbox = Outbox("wired", ::deliver)
    private val sessionLock = Any()
    private val random = SecureRandom()

    @Volatile private var running = false
    @Volatile private var session: Session? = null
    private val servers: MutableSet<ServerSocket> = CopyOnWriteArraySet()
    @Volatile private var serverIssue: LinkIssue? = null
    @Volatile private var pendingDecision: ArrayBlockingQueue<Boolean>? = null
    @Volatile private var lastDrop: LinkDrop? = null
    @Volatile private var listenAddress: String? = null
    @Volatile private var helperReached = false
    private val serverThreads = mutableListOf<Thread>()

    /** One accepted helper connection with serialized writes. */
    private inner class Session(val socket: Socket) {
        private val out = BufferedOutputStream(socket.getOutputStream())

        @Volatile var open = true
        @Volatile var name: String = ""
        @Volatile var hostSize: HostSize? = null
        @Volatile var replaced = false

        /** Writes [frames] and flushes; closes the session and returns false on failure. */
        fun write(vararg frames: WireFrame): Boolean = synchronized(this) {
            if (!open) return false
            try {
                for (f in frames) out.write(WireCodec.encode(f))
                out.flush()
                true
            } catch (_: IOException) {
                close()
                false
            }
        }

        /** Closes the socket; idempotent. */
        fun close() {
            open = false
            runCatching { socket.close() }
        }
    }

    /**
     * Starts the accept loops. ADB mode listens on loopback. Tethering mode listens on the tether
     * interface address and also on loopback, so a helper that arrives through `adb forward` works too.
     * Hotspot mode listens only on the hotspot interface address.
     */
    override fun start() {
        if (running) return
        running = true
        val loopback = InetAddress.getLoopbackAddress()
        when (mode) {
            WiredMode.ADB -> startLoop("wired-server") { serverLoop({ loopback }, reportIssues = true) }
            WiredMode.TETHER -> {
                startLoop("wired-server") { serverLoop(TetherInterfaces::findAddress, reportIssues = true) }
                startLoop("wired-adb") { serverLoop({ loopback }, reportIssues = false) }
            }
            WiredMode.HOTSPOT ->
                startLoop("wired-hotspot") { serverLoop(TetherInterfaces::findHotspotAddress, reportIssues = true) }
        }
    }

    /** Runs [body] on a named daemon thread tracked for [stop]. */
    private fun startLoop(name: String, body: () -> Unit) {
        serverThreads += Thread(body, name).apply {
            isDaemon = true
            start()
        }
    }

    /** Releases held input on the helper, says Bye, and closes the server. */
    override fun stop() {
        running = false
        pendingDecision?.offer(false)
        val s = synchronized(sessionLock) { session.also { session = null } }
        val open = servers.toList()
        servers.clear()
        Thread({
            s?.write(
                WireFrame.Mouse(0, 0, 0, 0, 0),
                penFrame(PenReport.OUT_OF_RANGE),
                WireFrame.Bye,
            )
            s?.close()
            open.forEach { runCatching { it.close() } }
        }, "wired-stop").start()
        serverThreads.forEach { it.interrupt() }
        serverThreads.clear()
        outbox.close()
        _state.value = LinkState(LinkStatus.IDLE, stack)
    }

    /** Queues a report while a helper is connected. */
    override fun send(item: Outgoing) {
        if (session != null) outbox.offer(item)
    }

    /** Answers the pending tethering approval. */
    override fun respondToPairing(approve: Boolean) {
        pendingDecision?.offer(approve)
    }

    /**
     * Binds the address [addressOf] returns and accepts helpers, rebinding when that address
     * changes. Only the primary loop ([reportIssues]) drives the interface-off and port-in-use hints.
     */
    private fun serverLoop(addressOf: () -> InetAddress?, reportIssues: Boolean) {
        while (running) {
            val address = addressOf()
            if (address == null) {
                if (reportIssues) {
                    serverIssue = offIssue
                    listenAddress = null
                    publishCurrent()
                }
                if (!sleepQuietly(RESCAN_MS)) return
                continue
            }
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = RESCAN_MS.toInt()
                    bind(InetSocketAddress(address, WireCodec.PORT), BACKLOG)
                }
            } catch (e: IOException) {
                Log.w(TAG, "cannot listen on ${address.hostAddress}", e)
                if (reportIssues) {
                    serverIssue = LinkIssue.PORT_IN_USE
                    publishCurrent()
                }
                if (!sleepQuietly(RESCAN_MS)) return
                continue
            }
            servers += server
            if (!running) runCatching { server.close() }
            if (reportIssues) {
                serverIssue = null
                if (mode == WiredMode.HOTSPOT) listenAddress = address.hostAddress
                publishCurrent()
            }
            Log.i(TAG, "listening on ${address.hostAddress}:${WireCodec.PORT}")
            try {
                while (running) {
                    try {
                        val socket = server.accept()
                        Thread({ handleClient(socket) }, "wired-client").apply {
                            isDaemon = true
                            start()
                        }
                    } catch (_: SocketTimeoutException) {
                        if (addressOf() != address) break
                    }
                }
            } catch (_: IOException) {
                // Closed by stop().
            } finally {
                runCatching { server.close() }
                servers -= server
            }
        }
    }

    /** Runs the handshake, optional pairing, keepalive, and receive loop for one helper. */
    private fun handleClient(socket: Socket) {
        Log.i(TAG, "connection from ${socket.inetAddress.hostAddress} to ${socket.localAddress.hostAddress}")
        if (!helperReached) {
            helperReached = true
            publishCurrent()
        }
        val s = try {
            socket.tcpNoDelay = true
            socket.soTimeout = LINK_TIMEOUT_MS
            Session(socket)
        } catch (_: IOException) {
            runCatching { socket.close() }
            return
        }
        var connected = false
        var reason: DropReason? = null
        try {
            val reader = FrameReader(socket.getInputStream())
            val hello = try {
                reader.read()
            } catch (_: SocketTimeoutException) {
                reason = DropReason.NO_HELLO
                return
            }
            if (hello !is WireFrame.Hello) {
                reason = if (hello == null) DropReason.HELPER_CLOSED else DropReason.PROTOCOL_ERROR
                return
            }
            if (hello.version != WireCodec.VERSION) {
                s.write(WireFrame.Welcome(WireCodec.VERSION, WireCodec.STATUS_VERSION_MISMATCH))
                return
            }
            s.name = hello.name.ifBlank { socket.inetAddress.hostAddress ?: "" }
            val viaAdb = socket.localAddress?.let(TetherInterfaces::unmap)?.isLoopbackAddress == true
            if (!viaAdb && !settings.isHelperApproved(hello.tokenHex) && !pair(s, hello.tokenHex)) {
                return
            }
            if (!running || !s.write(WireFrame.Welcome(WireCodec.VERSION, WireCodec.STATUS_OK))) return
            val previous = synchronized(sessionLock) {
                session.also { session = s }
            }
            previous?.let {
                it.replaced = true
                it.close()
            }
            connected = true
            lastDrop = null
            Log.i(TAG, "helper ${s.name} connected from ${socket.inetAddress.hostAddress}")
            outbox.clear()
            publishCurrent()
            startPinger(s)
            reason = receive(s, reader)
        } catch (_: SocketTimeoutException) {
            reason = DropReason.HELPER_SILENT
        } catch (e: IOException) {
            reason = if (s.replaced) DropReason.REPLACED else DropReason.NETWORK_ERROR
            if (!s.replaced) Log.w(TAG, "helper ${s.name} socket error", e)
        } catch (e: WireFormatException) {
            reason = DropReason.PROTOCOL_ERROR
            Log.w(TAG, "helper ${s.name} protocol error", e)
        } finally {
            s.close()
            if (reason != null && running) {
                Log.i(TAG, "helper ${s.name} dropped: $reason (connected=$connected)")
                if (reason != DropReason.REPLACED) lastDrop = LinkDrop(reason, s.name, connected)
            }
            val wasActive = synchronized(sessionLock) {
                (session === s).also { if (it) session = null }
            }
            if (wasActive) outbox.clear()
            if (running && (wasActive || session == null)) publishCurrent()
        }
    }

    /** Processes helper frames until Bye or close; the 3 s link timeout surfaces as an exception. */
    private fun receive(s: Session, reader: FrameReader): DropReason {
        while (running && s.open) {
            when (val frame = reader.read() ?: return DropReason.HELPER_CLOSED) {
                is WireFrame.HostInfo -> if (frame.width > 0 && frame.height > 0) {
                    s.hostSize = HostSize(frame.width, frame.height)
                    if (session === s) publishCurrent()
                }
                is WireFrame.Ping -> s.write(WireFrame.Pong(frame.nonce))
                WireFrame.Bye -> return DropReason.HELPER_CLOSED
                else -> Unit
            }
        }
        return if (s.replaced) DropReason.REPLACED else DropReason.NETWORK_ERROR
    }

    /**
     * Shows the approval code and waits for the user's answer while watching for the helper to
     * disconnect. Returns true when approved; sends Welcome status 2 otherwise.
     */
    private fun pair(s: Session, tokenHex: String): Boolean {
        val decisions = ArrayBlockingQueue<Boolean>(1)
        synchronized(sessionLock) {
            if (pendingDecision != null) {
                s.write(WireFrame.Welcome(WireCodec.VERSION, WireCodec.STATUS_REJECTED))
                return false
            }
            pendingDecision = decisions
        }
        try {
            val code = random.nextInt(1_000_000)
            if (!s.write(WireFrame.Welcome(WireCodec.VERSION, WireCodec.STATUS_PAIRING), WireFrame.PairCode(code))) {
                return false
            }
            _state.value = LinkState(
                LinkStatus.PAIRING_REQUEST,
                stack,
                targetName = s.name,
                pairing = PairingRequest("%06d".format(code), s.name),
            )
            val approved = awaitDecision(s, decisions)
            if (approved) {
                settings.approveHelper(tokenHex)
            } else {
                s.write(WireFrame.Welcome(WireCodec.VERSION, WireCodec.STATUS_REJECTED))
            }
            return approved
        } finally {
            synchronized(sessionLock) { pendingDecision = null }
            publishCurrent()
        }
    }

    /** Polls for the user's answer; a silent helper is expected, so any byte or EOF ends pairing. */
    private fun awaitDecision(s: Session, decisions: ArrayBlockingQueue<Boolean>): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PAIRING_TIMEOUT_MS)
        val input = s.socket.getInputStream()
        s.socket.soTimeout = PAIRING_POLL_MS
        try {
            while (running && System.nanoTime() < deadline) {
                decisions.poll(PAIRING_POLL_MS.toLong(), TimeUnit.MILLISECONDS)?.let { return it }
                try {
                    input.read()
                    return false
                } catch (_: SocketTimeoutException) {
                    // Helper still connected and silent.
                }
            }
            return false
        } catch (_: InterruptedException) {
            return false
        } finally {
            runCatching { s.socket.soTimeout = LINK_TIMEOUT_MS }
        }
    }

    /** Sends Ping every second while [s] stays open. */
    private fun startPinger(s: Session) {
        Thread({
            var nonce = 0L
            while (s.open && running) {
                if (!sleepQuietly(PING_INTERVAL_MS)) return@Thread
                if (!s.write(WireFrame.Ping(nonce))) return@Thread
                nonce = (nonce + 1) and 0xFFFFFFFFL
            }
        }, "wired-ping").apply {
            isDaemon = true
            start()
        }
    }

    /** Publishes the state implied by the current session and server status. */
    private fun publishCurrent() {
        if (!running) return
        if (pendingDecision != null && _state.value.status == LinkStatus.PAIRING_REQUEST) return
        val s = session
        _state.value = when {
            s != null -> LinkState(LinkStatus.CONNECTED, stack, targetName = s.name, hostSize = s.hostSize)
            serverIssue != null -> LinkState(LinkStatus.UNAVAILABLE, stack, issue = serverIssue)
            else -> LinkState(
                LinkStatus.CONNECTING,
                stack,
                issue = LinkIssue.WAITING_FOR_HELPER,
                lastDrop = lastDrop,
                listenAddress = listenAddress,
                helperReached = helperReached,
            )
        }
    }

    /** Sender-thread delivery of one report as one or more frames. */
    private fun deliver(item: Outgoing): Boolean {
        val s = session ?: return false
        return when (item) {
            is Outgoing.Mouse -> {
                val r = item.report
                s.write(*WireCodec.mouseFrames(r.buttons, r.dx, r.dy, r.wheel, r.pan).toTypedArray())
            }
            is Outgoing.Pen -> s.write(penFrame(item.report))
        }
    }

    /** Converts a pen report to its wire frame. */
    private fun penFrame(r: PenReport): WireFrame.Pen =
        WireFrame.Pen(r.flags, r.x, r.y, r.pressure, r.tiltX, r.tiltY, r.twist)

    /** Sleeps [ms]; returns false when interrupted. */
    private fun sleepQuietly(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        false
    }

    private companion object {
        const val TAG = "PadLinkWired"
        const val BACKLOG = 2
        const val RESCAN_MS = 2_000L
        const val LINK_TIMEOUT_MS = 3_000
        const val PING_INTERVAL_MS = 1_000L
        const val PAIRING_TIMEOUT_MS = 60_000L
        const val PAIRING_POLL_MS = 250
    }
}
