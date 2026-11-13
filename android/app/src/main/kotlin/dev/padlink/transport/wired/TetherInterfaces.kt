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

    /** Converts an IPv4-mapped IPv6 address (`::ffff:a.b.c.d`) to plain IPv4; other addresses pass through. */
    fun unmap(address: InetAddress): InetAddress {
        val b = address.address
        if (b.size != 16) return address
        for (i in 0 until 10) if (b[i] != 0.toByte()) return address
        if (b[10] != 0xFF.toByte() || b[11] != 0xFF.toByte()) return address
        return InetAddress.getByAddress(b.copyOfRange(12, 16))
    }

    /** True when [address] belongs to an up USB tether interface. */
    fun isTetherAddress(address: InetAddress): Boolean {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: SocketException) {
            return false
        }
        val wanted = unmap(address)
        return interfaces.any { nif ->
            isTetherName(nif.name) && runCatching { nif.isUp }.getOrDefault(false) &&
                nif.inetAddresses.toList().any { unmap(it) == wanted }
        }
    }

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
