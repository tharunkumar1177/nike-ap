// TetherInterfaces.kt: finds the IPv4 address of the USB tethering or Wi-Fi hotspot interface the wired server binds to.
package dev.padlink.transport.wired

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** Lookup of USB tether interfaces (`rndis*`, `ncm*`, `usb*`) and Wi-Fi hotspot interfaces. */
object TetherInterfaces {

    private val NAME = Regex("^(rndis|ncm|usb)\\d*$")

    /** `wlan0` is the phone's own Wi-Fi client interface, so only `wlan1` and up count. */
    private val HOTSPOT_NAME = Regex("^(swlan\\d*|softap\\d*|ap\\d*|wlan[1-9]\\d*)$")

    /** True when [name] is a USB tethering interface name. */
    fun isTetherName(name: String): Boolean = NAME.matches(name)

    /** True when [name] is a Wi-Fi hotspot (soft AP) interface name. */
    fun isHotspotName(name: String): Boolean = HOTSPOT_NAME.matches(name)

    /** Converts an IPv4-mapped IPv6 address (`::ffff:a.b.c.d`) to plain IPv4; other addresses pass through. */
    fun unmap(address: InetAddress): InetAddress {
        val b = address.address
        if (b.size != 16) return address
        for (i in 0 until 10) if (b[i] != 0.toByte()) return address
        if (b[10] != 0xFF.toByte() || b[11] != 0xFF.toByte()) return address
        return InetAddress.getByAddress(b.copyOfRange(12, 16))
    }

    /** IPv4 address of the first up USB tether interface, or null when tethering is off. */
    fun findAddress(): InetAddress? = firstAddress(::isTetherName)

    /** IPv4 address of the first up Wi-Fi hotspot interface, or null when the hotspot is off. */
    fun findHotspotAddress(): InetAddress? = firstAddress(::isHotspotName)

    /** IPv4 address of the first up, non-loopback interface whose name satisfies [matches]. */
    private fun firstAddress(matches: (String) -> Boolean): InetAddress? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: SocketException) {
            return null
        }
        return interfaces
            .filter { matches(it.name) && runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .sortedBy { it.name }
            .firstNotNullOfOrNull { nif -> nif.inetAddresses.toList().firstOrNull { it is Inet4Address } }
    }
}
