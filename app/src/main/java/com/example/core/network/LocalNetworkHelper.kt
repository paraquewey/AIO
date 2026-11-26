package com.example.core.network

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetworkHelper {

    data class LocalAddressCandidate(
        val interfaceName: String,
        val categoryLabel: String,
        val ipAddress: String,
        val priority: Int
    )

    fun getActiveInterfaceAddresses(): List<LocalAddressCandidate> {
        val candidates = mutableListOf<LocalAddressCandidate>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.startsWith("ppp") || name.startsWith("rmnet") || name.startsWith("ccmni")) continue

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("10.89.0.")) continue
                        val isCgnat = VpnRouteHelper.ipv4ToLongOrNull(ip)?.let {
                            val b0 = ((it ushr 24) and 0xFFL).toInt()
                            val b1 = ((it ushr 16) and 0xFFL).toInt()
                            b0 == 100 && b1 in 64..127
                        } ?: false

                        // Only expose tun* interfaces if they carry a CGNAT/Tailscale address (100.64.0.0/10)
                        if (name.startsWith("tun") && !isCgnat) continue

                        val (priority, label) = when {
                            name.startsWith("ap") || name.startsWith("swlan") -> 1 to "Hotspot"
                            name.startsWith("wlan") -> 2 to "Wi-Fi"
                            name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> 3 to "USB / Tethering"
                            isCgnat -> 4 to "CGNAT / Red"
                            name.startsWith("eth") -> 5 to "Ethernet"
                            else -> 6 to "Red local"
                        }
                        candidates.add(
                            LocalAddressCandidate(
                                interfaceName = iface.name,
                                categoryLabel = label,
                                ipAddress = ip,
                                priority = priority
                            )
                        )
                    }
                }
            }
        }
        return candidates
            .sortedWith(compareBy<LocalAddressCandidate> { it.priority }.thenBy { it.ipAddress })
            .distinctBy { it.ipAddress }
    }

    fun getLocalWifiAndHotspotIps(): List<String> =
        getActiveInterfaceAddresses().map { it.ipAddress }

    fun getPrimaryShareIp(): String? = getLocalWifiAndHotspotIps().firstOrNull()
}
