// TetherInterfacesTest.kt: checks which interface names count as USB tethering or Wi-Fi hotspot.
package dev.padlink.transport.wired

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TetherInterfacesTest {

    /** RNDIS, NCM, and generic USB gadget interfaces are tether interfaces. */
    @Test
    fun acceptsUsbTetherNames() {
        listOf("rndis0", "ncm0", "usb0", "rndis").forEach { assertTrue(it, TetherInterfaces.isTetherName(it)) }
    }

    /** Wi-Fi, cellular, loopback, and lookalike names are not. */
    @Test
    fun rejectsOtherNames() {
        listOf("wlan0", "rmnet_data0", "lo", "swlan0", "usbnet", "p2p0").forEach {
            assertFalse(it, TetherInterfaces.isTetherName(it))
        }
    }

    /** Samsung `swlan0`, `ap0`, `softap0`, and secondary `wlan1`+ are hotspot interfaces. */
    @Test
    fun acceptsHotspotNames() {
        listOf("swlan0", "ap0", "softap0", "wlan1", "wlan2").forEach {
            assertTrue(it, TetherInterfaces.isHotspotName(it))
        }
    }

    /** The phone's own Wi-Fi client `wlan0`, USB tether, cellular, loopback, and P2P interfaces are not. */
    @Test
    fun rejectsNonHotspotNames() {
        listOf("wlan0", "wlan", "rndis0", "ncm0", "rmnet_data0", "lo", "p2p0", "aware_data0").forEach {
            assertFalse(it, TetherInterfaces.isHotspotName(it))
        }
    }

    /** IPv4-mapped IPv6 addresses become plain IPv4, so loopback and tether checks see the real address. */
    @Test
    fun unmapsIpv4MappedAddresses() {
        val mapped = InetAddress.getByAddress(ByteArray(10) + byteArrayOf(-1, -1, 127, 0, 0, 1))
        val plain = TetherInterfaces.unmap(mapped)
        assertTrue(plain is Inet4Address)
        assertTrue(plain.isLoopbackAddress)
        val tether = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 42, 129.toByte()))
        assertEquals(tether, TetherInterfaces.unmap(tether))
        val v6 = InetAddress.getByName("fe80::1")
        assertEquals(v6, TetherInterfaces.unmap(v6))
    }
}
