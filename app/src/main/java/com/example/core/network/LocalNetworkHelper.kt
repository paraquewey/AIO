package com.example.core.network

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetworkHelper {

    data class LocalAddressCandidate(
        val interfaceName: String,
        val ipAddress: String,
        val priority: Int
    )

    fun getLocalWifiAndHotspotIps(): List<String> {
        val candidates = mutableListOf<LocalAddressCandidate>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet")) continue

                val priority = when {
                    name.startsWith("ap") || name.startsWith("swlan") -> 1
                    name.startsWith("wlan") -> 2
                    name.startsWith("rndis") || name.startsWith("usb") -> 3
                    name.startsWith("eth") -> 4
                    else -> 5
                }

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("10.89.0.")) continue
                        candidates.add(LocalAddressCandidate(name, ip, priority))
                    }
                }
            }
        }
        return candidates
            .sortedBy { it.priority }
            .map { it.ipAddress }
            .distinct()
    }

    fun getPrimaryShareIp(): String? = getLocalWifiAndHotspotIps().firstOrNull()
}
