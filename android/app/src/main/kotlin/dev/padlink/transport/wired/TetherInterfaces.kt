// TetherInterfaces.kt: finds the IPv4 address of the USB tethering interface the wired server binds to.
package dev.padlink.transport.wired

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** Lookup of USB tether interfaces (`rndis*`, `ncm*`, `usb*`). */
object TetherInterfaces {

    private val NAME = Regex("^(rndis|ncm|usb)\\d*$")

    /** True when [name] is a USB tethering interface name. */
    fun isTetherName(name: String): Boolean = NAME.matches(name)

    /** IPv4 address of the first up USB tether interface, or null when tethering is off. */
    fun findAddress(): InetAddress? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: SocketException) {
            return null
        }
        return interfaces
            .filter { isTetherName(it.name) && runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .sortedBy { it.name }
            .firstNotNullOfOrNull { nif -> nif.inetAddresses.toList().firstOrNull { it is Inet4Address } }
    }
}
