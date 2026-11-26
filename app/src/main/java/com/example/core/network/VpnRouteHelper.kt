package com.example.core.network

import java.net.InetAddress

object VpnRouteHelper {

    private const val IPV4_MASK_32 = 0xFFFFFFFFL

    data class CidrBlock(
        val addressLong: Long,
        val prefixLength: Int
    ) {
        init {
            require(prefixLength in 0..32) { "Prefijo CIDR IPv4 inválido: $prefixLength" }
        }

        val maskedAddressLong: Long
            get() = addressLong and maskForPrefix(prefixLength)

        val address: String
            get() = longToIpv4(maskedAddressLong)

        fun normalized(): CidrBlock = CidrBlock(maskedAddressLong, prefixLength)

        fun contains(ipLong: Long): Boolean {
            val unsignedIp = ipLong and IPV4_MASK_32
            if (prefixLength == 0) return true
            val mask = maskForPrefix(prefixLength)
            return (unsignedIp and mask) == maskedAddressLong
        }

        override fun toString(): String = "$address/$prefixLength"
    }

    /**
     * Ranges to exclude via VpnService.Builder.excludeRoute() on Android 13+ (API 33+).
     * Loopback (127.0.0.0/8) is listed for completeness and host-matching, but skipped
     * before calling VpnService.Builder because AOSP rejects loopback addresses with "Bad address".
     */
    val EXCLUDED_LAN_CGNAT_CIDRS: List<CidrBlock> = listOf(
        cidr("10.0.0.0", 8),       // Private RFC 1918
        cidr("172.16.0.0", 12),    // Private RFC 1918
        cidr("192.168.0.0", 16),   // Private LAN / Hotspot
        cidr("100.64.0.0", 10),    // CGNAT / Tailscale / USB-Wi-Fi tethering
        cidr("127.0.0.0", 8),      // Loopback (OS handles natively; rejected by VpnService.Builder)
        cidr("169.254.0.0", 16),   // Link-local
        cidr("224.0.0.0", 4)       // Multicast
    )

    /**
     * Additional reserved ranges excluded from Android <= 12 inverse routes so we never pass
     * 0.0.0.0/8 or 240.0.0.0/4 (broadcast/reserved) to VpnService.Builder.addRoute().
     */
    val ANDROID_12_EXCLUDED_CIDRS: List<CidrBlock> = EXCLUDED_LAN_CGNAT_CIDRS + listOf(
        cidr("0.0.0.0", 8),        // "This" network (cannot be non-/0 routed on some kernels)
        cidr("240.0.0.0", 4)       // Reserved / 255.255.255.255 limited broadcast
    )

    fun maskForPrefix(prefixLength: Int): Long {
        require(prefixLength in 0..32) { "Prefijo CIDR IPv4 fuera de rango (0..32): $prefixLength" }
        return if (prefixLength == 0) 0L else (IPV4_MASK_32 shl (32 - prefixLength)) and IPV4_MASK_32
    }

    /**
     * Returns false for prefixes that AOSP VpnService.Builder explicitly rejects with
     * IllegalArgumentException("Bad address"), such as 127.0.0.0/8 loopback or non-zero-masked CIDRs.
     */
    fun canPassToVpnBuilder(cidr: CidrBlock, isExcludeRoute: Boolean = false): Boolean {
        if (cidr.prefixLength !in 0..32) return false
        val masked = cidr.maskedAddressLong
        if ((cidr.addressLong and IPV4_MASK_32) != masked) return false
        val firstOctet = ((masked ushr 24) and 0xFFL).toInt()
        // AOSP VpnService.Builder.check() throws IllegalArgumentException("Bad address") on loopback
        if (firstOctet == 127) return false
        if (isExcludeRoute && cidr.prefixLength == 0) return false
        // Avoid passing 0.0.0.0/N (N > 0) as a route/exclude if some Android builds treat 0.0.0.0 as unspecified
        if (masked == 0L && cidr.prefixLength > 0) return false
        return true
    }

    /**
     * Computes the exact inverse CIDR list of [excluded] inside 0.0.0.0/0
     * for Android 12 and lower where VpnService.Builder.excludeRoute() is unavailable.
     * Every emitted CidrBlock is strictly normalized (host bits == 0).
     */
    fun computeAllowedPublicIpv4Routes(
        excluded: List<CidrBlock> = ANDROID_12_EXCLUDED_CIDRS
    ): List<CidrBlock> {
        var current = listOf(CidrBlock(0L, 0))
        for (exRaw in excluded) {
            val ex = exRaw.normalized()
            val next = ArrayList<CidrBlock>(current.size + 16)
            for (block in current) {
                subtractBlock(block.normalized(), ex, next)
            }
            current = next
        }
        return current
            .map { it.normalized() }
            .filter { canPassToVpnBuilder(it, isExcludeRoute = false) }
    }

    private fun subtractBlock(
        base: CidrBlock,
        exclude: CidrBlock,
        out: MutableList<CidrBlock>
    ) {
        if (!overlaps(base, exclude)) {
            out.add(base.normalized())
            return
        }
        if (exclude.prefixLength <= base.prefixLength) {
            // exclude covers base completely
            return
        }
        val nextPrefix = base.prefixLength + 1
        val bit = 1L shl (32 - nextPrefix)
        val nextMask = maskForPrefix(nextPrefix)
        val left = CidrBlock(base.maskedAddressLong and nextMask, nextPrefix)
        val right = CidrBlock((base.maskedAddressLong or bit) and nextMask, nextPrefix)
        subtractBlock(left, exclude, out)
        subtractBlock(right, exclude, out)
    }

    private fun overlaps(a: CidrBlock, b: CidrBlock): Boolean {
        val minPrefix = minOf(a.prefixLength, b.prefixLength)
        if (minPrefix == 0) return true
        val mask = maskForPrefix(minPrefix)
        return (a.maskedAddressLong and mask) == (b.maskedAddressLong and mask)
    }

    fun cidr(ip: String, prefixLength: Int): CidrBlock {
        require(prefixLength in 0..32) { "Prefijo CIDR inválido: $prefixLength" }
        val cleanIp = sanitizeIpv4Address(ip, allowLoopback = true, allowAnyLocal = true, allowMulticast = true)
            ?: throw IllegalArgumentException("IPv4 inválida en CIDR: $ip")
        val rawIp = ipv4ToLongOrNull(cleanIp)
            ?: throw IllegalArgumentException("IPv4 inválida en CIDR: $ip")
        val mask = maskForPrefix(prefixLength)
        val maskedIp = rawIp and mask
        return CidrBlock(maskedIp, prefixLength)
    }

    /**
     * Sanitizes an IPv4 string by trimming whitespace, stripping brackets or ":port",
     * and verifying 4 valid octets in 0..255.
     */
    fun sanitizeIpv4Address(
        raw: String,
        allowLoopback: Boolean = false,
        allowAnyLocal: Boolean = false,
        allowMulticast: Boolean = false
    ): String? {
        var clean = raw.trim().removePrefix("[").substringBefore("]").trim()
        if (clean.isEmpty()) return null
        // Strip ":port" if accidentally included (e.g., "1.1.1.1:53")
        val colonIdx = clean.indexOf(':')
        if (colonIdx > 0 && clean.indexOf(':', colonIdx + 1) < 0) {
            clean = clean.substring(0, colonIdx).trim()
        }
        val ipLong = ipv4ToLongOrNull(clean) ?: return null
        val firstOctet = ((ipLong ushr 24) and 0xFFL).toInt()
        if (!allowAnyLocal && firstOctet == 0) return null
        if (!allowLoopback && firstOctet == 127) return null
        if (!allowMulticast && firstOctet >= 224) return null
        return longToIpv4(ipLong)
    }

    fun ipv4ToLongOrNull(ip: String): Long? {
        val clean = ip.trim()
        val parts = clean.split('.')
        if (parts.size != 4) return null
        var acc = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            acc = (acc shl 8) or octet.toLong()
        }
        return acc and IPV4_MASK_32
    }

    fun longToIpv4(value: Long): String {
        val unsigned = value and IPV4_MASK_32
        return buildString {
            append((unsigned ushr 24) and 0xFFL).append('.')
            append((unsigned ushr 16) and 0xFFL).append('.')
            append((unsigned ushr 8) and 0xFFL).append('.')
            append(unsigned and 0xFFL)
        }
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
            b0 >= 224 -> true                              // Multicast & Reserved
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
