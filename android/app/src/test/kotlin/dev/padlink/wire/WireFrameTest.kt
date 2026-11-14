// WireFrameTest.kt: checks the Kotlin wired codec against the shared golden vectors.
package dev.padlink.wire

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireFrameTest {

    /** One parsed line of protocol/test-vectors.txt. */
    private data class Vector(val fields: Map<String, String>, val hex: String) {
        /** Integer field value. */
        fun int(key: String): Int = fields.getValue(key).toInt()

        /** Long field value. */
        fun long(key: String): Long = fields.getValue(key).toLong()
    }

    /** Loads the golden vectors; Gradle runs unit tests from the module directory. */
    private fun vectors(): List<Vector> {
        val file = File("../../protocol/test-vectors.txt")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val fields = line.split(' ').associate { part ->
                    val eq = part.indexOf('=')
                    part.substring(0, eq) to part.substring(eq + 1)
                }
                Vector(fields - "hex", fields.getValue("hex"))
            }
    }

    /** Builds the frame a vector describes. */
    private fun frameOf(v: Vector): WireFrame = when (v.fields.getValue("frame")) {
        "hello" -> WireFrame.Hello(v.int("version"), v.fields.getValue("token"), v.fields.getValue("name"))
        "welcome" -> WireFrame.Welcome(v.int("version"), v.int("status"))
        "pair_code" -> WireFrame.PairCode(v.int("code"))
        "host_info" -> WireFrame.HostInfo(v.int("width"), v.int("height"))
        "mouse" -> WireFrame.Mouse(v.int("buttons"), v.int("dx"), v.int("dy"), v.int("wheel"), v.int("pan"))
        "pen" -> WireFrame.Pen(
            v.int("flags"), v.int("x"), v.int("y"), v.int("pressure"),
            v.int("tilt_x"), v.int("tilt_y"), v.int("twist"),
        )
        "ping" -> WireFrame.Ping(v.long("nonce"))
        "pong" -> WireFrame.Pong(v.long("nonce"))
        "bye" -> WireFrame.Bye
        else -> error("unknown vector ${v.fields}")
    }

    /** Every vector encodes to its hex. */
    @Test
    fun encodesGoldenVectors() {
        val all = vectors()
        assertTrue(all.size >= 10)
        for (v in all) {
            assertEquals(v.fields.toString(), v.hex, WireCodec.bytesToHex(WireCodec.encode(frameOf(v))))
        }
    }

    /** Every vector's hex decodes back to the described frame. */
    @Test
    fun decodesGoldenVectors() {
        for (v in vectors()) {
            assertEquals(v.fields.toString(), frameOf(v), WireCodec.decodeFrame(WireCodec.hexToBytes(v.hex)))
        }
    }

    /** The stream reader returns consecutive frames and null at end of stream. */
    @Test
    fun readerStreamsFrames() {
        val bytes = WireCodec.encode(WireFrame.Ping(7)) + WireCodec.encode(WireFrame.Bye)
        val reader = FrameReader(ByteArrayInputStream(bytes))
        assertEquals(WireFrame.Ping(7), reader.read())
        assertEquals(WireFrame.Bye, reader.read())
        assertNull(reader.read())
    }

    /** Unknown frame types decode to Unknown so receivers can skip them. */
    @Test
    fun unknownTypeIsSkippable() {
        assertEquals(WireFrame.Unknown(0x7F), WireCodec.decodeFrame(WireCodec.hexToBytes("00027f00")))
    }

    /** Oversized lengths are rejected. */
    @Test(expected = WireFormatException::class)
    fun rejectsOversizedLength() {
        FrameReader(ByteArrayInputStream(WireCodec.hexToBytes("0201" + "00".repeat(4)))).read()
    }

    /** Mouse values beyond i16 split into several frames that sum to the original. */
    @Test
    fun splitsLargeMouseDeltas() {
        val frames = WireCodec.mouseFrames(1, 70000, -40000, 0, 0)
        assertEquals(3, frames.size)
        assertEquals(70000, frames.sumOf { it.dx })
        assertEquals(-40000, frames.sumOf { it.dy })
        assertTrue(frames.all { it.buttons == 1 })
    }
}
