package com.example.core.network

import java.net.InetAddress

object VpnRouteHelper {

    data class CidrBlock(
        val addressLong: Long,
        val prefixLength: Int
    ) {
        val address: String
            get() = longToIpv4(addressLong)

        fun contains(ipLong: Long): Boolean {
            if (prefixLength == 0) return true
            val mask = (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
            return (ipLong and mask) == (addressLong and mask)
        }

        override fun toString(): String = "$address/$prefixLength"
    }

    val EXCLUDED_LAN_CGNAT_CIDRS: List<CidrBlock> = listOf(
        cidr("10.0.0.0", 8),
        cidr("100.64.0.0", 10),     // CGNAT / Tailscale / USB-Wi-Fi tethering
        cidr("127.0.0.0", 8),      // Loopback
        cidr("169.254.0.0", 16),   // Link-local
        cidr("172.16.0.0", 12),    // Private RFC 1918
        cidr("192.168.0.0", 16)    // Private LAN / Hotspot
    )

    /**
     * Computes the exact inverse CIDR list of [excluded] inside 0.0.0.0/0
     * for Android 12 and lower where VpnService.Builder.excludeRoute() is unavailable.
     */
    fun computeAllowedPublicIpv4Routes(
        excluded: List<CidrBlock> = EXCLUDED_LAN_CGNAT_CIDRS
    ): List<CidrBlock> {
        var current = listOf(CidrBlock(0L, 0))
        for (ex in excluded) {
            val next = ArrayList<CidrBlock>(current.size + 16)
            for (block in current) {
                subtractBlock(block, ex, next)
            }
            current = next
        }
        return current
    }

    private fun subtractBlock(
        base: CidrBlock,
        exclude: CidrBlock,
        out: MutableList<CidrBlock>
    ) {
        if (!overlaps(base, exclude)) {
            out.add(base)
            return
        }
        if (exclude.prefixLength <= base.prefixLength) {
            // exclude covers base completely
            return
        }
        val nextPrefix = base.prefixLength + 1
        val bit = 1L shl (32 - nextPrefix)
        val left = CidrBlock(base.addressLong, nextPrefix)
        val right = CidrBlock(base.addressLong or bit, nextPrefix)
        subtractBlock(left, exclude, out)
        subtractBlock(right, exclude, out)
    }

    private fun overlaps(a: CidrBlock, b: CidrBlock): Boolean {
        val minPrefix = minOf(a.prefixLength, b.prefixLength)
        if (minPrefix == 0) return true
        val mask = (0xFFFFFFFFL shl (32 - minPrefix)) and 0xFFFFFFFFL
        return (a.addressLong and mask) == (b.addressLong and mask)
    }

    fun cidr(ip: String, prefixLength: Int): CidrBlock {
        val parsed = ipv4ToLongOrNull(ip)
            ?: throw IllegalArgumentException("IPv4 inválida en CIDR: $ip")
        val mask = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        return CidrBlock(parsed and mask, prefixLength)
    }

    fun ipv4ToLongOrNull(ip: String): Long? {
        val clean = ip.trim()
        val parts = clean.split('.')
        if (parts.size != 4) return null
        var acc = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            acc = (acc shl 8) or octet.toLong()
        }
        return acc and 0xFFFFFFFFL
    }

    fun longToIpv4(value: Long): String = buildString {
        append((value ushr 24) and 0xFFL).append('.')
        append((value ushr 16) and 0xFFL).append('.')
        append((value ushr 8) and 0xFFL).append('.')
        append(value and 0xFFL)
    }

    fun isLocalOrCgnatIpv4(ip: String): Boolean {
        val addr = ipv4ToLongOrNull(ip) ?: return false
        val b0 = ((addr ushr 24) and 0xFFL).toInt()
        val b1 = ((addr ushr 16) and 0xFFL).toInt()
        return when {
            b0 == 0 -> true                                // 0.0.0.0/8
            b0 == 10 -> true                               // 10.0.0.0/8
            b0 == 127 -> true                              // 127.0.0.0/8
            b0 == 169 && b1 == 254 -> true                 // 169.254.0.0/16
            b0 == 172 && b1 in 16..31 -> true              // 172.16.0.0/12
            b0 == 192 && b1 == 168 -> true                 // 192.168.0.0/16
            b0 == 100 && b1 in 64..127 -> true             // 100.64.0.0/10 (CGNAT)
            else -> false
        }
    }

    fun isLocalOrCgnatHost(host: String): Boolean {
        val clean = host.trim().removePrefix("[").substringBefore("]")
        if (clean.isEmpty()) return false
        val lower = clean.lowercase()
        if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".localhost")) {
            return true
        }
        if (isLocalOrCgnatIpv4(clean)) {
            return true
        }
        if (clean.contains(':')) {
            val normalized = lower.substringBefore('%')
            if (normalized == "::1" || normalized == "::") return true
            if (normalized.startsWith("fe80:") || normalized.startsWith("fc") || normalized.startsWith("fd")) {
                return true
            }
            return runCatching {
                val addr = InetAddress.getByName(clean)
                addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress
            }.getOrDefault(false)
        }
        return false
    }
}
