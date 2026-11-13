// TetherInterfacesTest.kt: checks which interface names count as USB tethering.
package dev.padlink.transport.wired

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
}
