// WireFrame.kt: frames and big-endian codec of the wired protocol ("Wired protocol" in architecture.md).
package dev.padlink.wire

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Thrown when bytes on the wire violate the framing or payload layout. */
class WireFormatException(message: String) : Exception(message)

/** One frame of the wired protocol. */
sealed interface WireFrame {
    /** Helper introduction: protocol [version], 16-byte [token] as lowercase hex, display [name]. */
    data class Hello(val version: Int, val tokenHex: String, val name: String) : WireFrame

    /** Phone answer to Hello. */
    data class Welcome(val version: Int, val status: Int) : WireFrame

    /** Six-digit code the helper displays during tethering approval. */
    data class PairCode(val code: Int) : WireFrame

    /** Pixel size of the helper's pen target monitor. */
    data class HostInfo(val width: Int, val height: Int) : WireFrame

    /** Relative mouse input; deltas are i16. */
    data class Mouse(val buttons: Int, val dx: Int, val dy: Int, val wheel: Int, val pan: Int) : WireFrame

    /** Absolute pen input. */
    data class Pen(
        val flags: Int,
        val x: Int,
        val y: Int,
        val pressure: Int,
        val tiltX: Int,
        val tiltY: Int,
        val twist: Int,
    ) : WireFrame

    /** Keepalive request carrying a u32 [nonce]. */
    data class Ping(val nonce: Long) : WireFrame

    /** Keepalive answer echoing [nonce]. */
    data class Pong(val nonce: Long) : WireFrame

    /** Orderly close. */
    data object Bye : WireFrame

    /** Frame of a type this version does not know; ignored by receivers. */
    data class Unknown(val type: Int) : WireFrame
}

/** Encoder and decoder for [WireFrame]. */
object WireCodec {
    const val VERSION = 1
    const val PORT = 47800
    const val MAX_LEN = 512
    const val TOKEN_BYTES = 16

    const val STATUS_OK = 0
    const val STATUS_PAIRING = 1
    const val STATUS_REJECTED = 2
    const val STATUS_VERSION_MISMATCH = 3

    const val TYPE_HELLO = 0x01
    const val TYPE_WELCOME = 0x02
    const val TYPE_PAIR_CODE = 0x03
    const val TYPE_HOST_INFO = 0x04
    const val TYPE_MOUSE = 0x10
    const val TYPE_PEN = 0x11
    const val TYPE_PING = 0x20
    const val TYPE_PONG = 0x21
    const val TYPE_BYE = 0x30

    /** Encodes [frame] including its length prefix. */
    fun encode(frame: WireFrame): ByteArray {
        val payload = ByteBuffer.allocate(MAX_LEN).order(ByteOrder.BIG_ENDIAN)
        val type = when (frame) {
            is WireFrame.Hello -> {
                val token = hexToBytes(frame.tokenHex)
                require(token.size == TOKEN_BYTES) { "token must be $TOKEN_BYTES bytes" }
                val name = frame.name.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
                payload.put(frame.version.toByte()).put(token).put(name.size.toByte()).put(name)
                TYPE_HELLO
            }
            is WireFrame.Welcome -> {
                payload.put(frame.version.toByte()).put(frame.status.toByte())
                TYPE_WELCOME
            }
            is WireFrame.PairCode -> {
                payload.putInt(frame.code)
                TYPE_PAIR_CODE
            }
            is WireFrame.HostInfo -> {
                payload.putShort(u16(frame.width)).putShort(u16(frame.height))
                TYPE_HOST_INFO
            }
            is WireFrame.Mouse -> {
                payload.put(frame.buttons.toByte())
                    .putShort(i16(frame.dx)).putShort(i16(frame.dy))
                    .putShort(i16(frame.wheel)).putShort(i16(frame.pan))
                TYPE_MOUSE
            }
            is WireFrame.Pen -> {
                payload.put(frame.flags.toByte())
                    .putShort(u16(frame.x)).putShort(u16(frame.y)).putShort(u16(frame.pressure))
                    .put(frame.tiltX.coerceIn(-128, 127).toByte()).put(frame.tiltY.coerceIn(-128, 127).toByte())
                    .putShort(u16(frame.twist))
                TYPE_PEN
            }
            is WireFrame.Ping -> {
                payload.putInt(frame.nonce.toInt())
                TYPE_PING
            }
            is WireFrame.Pong -> {
                payload.putInt(frame.nonce.toInt())
                TYPE_PONG
            }
            WireFrame.Bye -> TYPE_BYE
            is WireFrame.Unknown -> throw IllegalArgumentException("cannot encode unknown frame type ${frame.type}")
        }
        val bodyLen = payload.position()
        val out = ByteBuffer.allocate(3 + bodyLen).order(ByteOrder.BIG_ENDIAN)
        out.putShort((1 + bodyLen).toShort()).put(type.toByte()).put(payload.array(), 0, bodyLen)
        return out.array()
    }

    /** Decodes a frame body ([type] plus [payload], without the length prefix). */
    fun decode(type: Int, payload: ByteArray): WireFrame {
        val b = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        /** Fails unless the payload holds at least [n] bytes. */
        fun need(n: Int) {
            if (payload.size < n) throw WireFormatException("type 0x%02x needs %d bytes, got %d".format(type, n, payload.size))
        }
        return when (type) {
            TYPE_HELLO -> {
                need(1 + TOKEN_BYTES + 1)
                val version = b.get().toInt() and 0xFF
                val token = ByteArray(TOKEN_BYTES).also { b.get(it) }
                val nameLen = b.get().toInt() and 0xFF
                need(1 + TOKEN_BYTES + 1 + nameLen)
                val name = ByteArray(nameLen).also { b.get(it) }
                WireFrame.Hello(version, bytesToHex(token), String(name, Charsets.UTF_8))
            }
            TYPE_WELCOME -> {
                need(2)
                WireFrame.Welcome(b.get().toInt() and 0xFF, b.get().toInt() and 0xFF)
            }
            TYPE_PAIR_CODE -> {
                need(4)
                WireFrame.PairCode(b.int)
            }
            TYPE_HOST_INFO -> {
                need(4)
                WireFrame.HostInfo(b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF)
            }
            TYPE_MOUSE -> {
                need(9)
                WireFrame.Mouse(
                    b.get().toInt() and 0xFF,
                    b.short.toInt(), b.short.toInt(), b.short.toInt(), b.short.toInt(),
                )
            }
            TYPE_PEN -> {
                need(11)
                WireFrame.Pen(
                    flags = b.get().toInt() and 0xFF,
                    x = b.short.toInt() and 0xFFFF,
                    y = b.short.toInt() and 0xFFFF,
                    pressure = b.short.toInt() and 0xFFFF,
                    tiltX = b.get().toInt(),
                    tiltY = b.get().toInt(),
                    twist = b.short.toInt() and 0xFFFF,
                )
            }
            TYPE_PING -> {
                need(4)
                WireFrame.Ping(b.int.toLong() and 0xFFFFFFFFL)
            }
            TYPE_PONG -> {
                need(4)
                WireFrame.Pong(b.int.toLong() and 0xFFFFFFFFL)
            }
            TYPE_BYE -> WireFrame.Bye
            else -> WireFrame.Unknown(type)
        }
    }

    /** Decodes one complete frame including its length prefix. */
    fun decodeFrame(bytes: ByteArray): WireFrame {
        if (bytes.size < 3) throw WireFormatException("frame shorter than 3 bytes")
        val len = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (len < 1 || len > MAX_LEN || bytes.size != 2 + len) throw WireFormatException("bad length $len")
        return decode(bytes[2].toInt() and 0xFF, bytes.copyOfRange(3, bytes.size))
    }

    /** Splits a mouse report into frames whose fields each fit i16. */
    fun mouseFrames(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int): List<WireFrame.Mouse> {
        val frames = ArrayList<WireFrame.Mouse>(1)
        var rx = dx
        var ry = dy
        var rw = wheel
        var rp = pan
        do {
            val fx = rx.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val fy = ry.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val fw = rw.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val fp = rp.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            frames += WireFrame.Mouse(buttons, fx, fy, fw, fp)
            rx -= fx
            ry -= fy
            rw -= fw
            rp -= fp
        } while (rx != 0 || ry != 0 || rw != 0 || rp != 0)
        return frames
    }

    /** Lowercase hex of [bytes]. */
    fun bytesToHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (v in bytes) append("%02x".format(v.toInt() and 0xFF))
    }

    /** Parses lowercase or uppercase hex into bytes. */
    fun hexToBytes(hex: String): ByteArray {
        if (hex.length % 2 != 0) throw WireFormatException("odd hex length")
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) throw WireFormatException("bad hex digit")
            ((hi shl 4) or lo).toByte()
        }
    }

    /** Saturates to i16 and returns the raw short. */
    private fun i16(v: Int): Short = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    /** Saturates to u16 and returns the raw short. */
    private fun u16(v: Int): Short = v.coerceIn(0, 0xFFFF).toShort()
}

/** Reads length-prefixed frames from a blocking stream. */
class FrameReader(input: InputStream) {

    private val data = DataInputStream(input)

    /** Reads the next frame; returns null on a clean end of stream before a frame starts. */
    fun read(): WireFrame? {
        val hi = data.read()
        if (hi < 0) return null
        val lo = data.read()
        if (lo < 0) throw EOFException("truncated length")
        val len = (hi shl 8) or lo
        if (len < 1 || len > WireCodec.MAX_LEN) throw WireFormatException("bad length $len")
        val body = ByteArray(len)
        data.readFully(body)
        return WireCodec.decode(body[0].toInt() and 0xFF, body.copyOfRange(1, len))
    }
}
