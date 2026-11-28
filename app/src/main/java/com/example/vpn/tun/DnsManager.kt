package com.example.vpn.tun

import com.example.core.logger.AppLogger
import com.example.domain.models.AppSettings
import com.example.domain.models.DnsMode
import com.example.domain.models.TunnelProfile
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import com.example.engines.adapter.UdpRelaySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

class DnsManager(
    private val protectSocket: (Socket) -> Boolean = { true },
    private val protectDatagramSocket: (DatagramSocket) -> Boolean = { true },
    internal val dohSslSocketFactoryOverride: SSLSocketFactory? = null,
    internal val dohTrustManagerOverride: X509TrustManager? = null
) : Closeable {
    data class ResolvedDnsConfig(
        val dnsMode: DnsMode,
        val primaryDns: String,
        val secondaryDns: String,
        val dohUrl: String
    )

    data class DnsResolutionResult(
        val responseBytes: ByteArray?,
        val serverLabel: String,
        val usedFallback: Boolean = false,
        val errorSummary: String? = null,
        val fromCache: Boolean = false
    )

    data class TunnelExitProbeResult(
        val ttfbMs: Long,
        val statusCode: Int,
        val endpointLabel: String,
        val exitIp: String? = null
    )

    private data class DohEndpointTarget(
        val url: String,
        val connectIp: String,
        val port: Int,
        val hostHeader: String
    )

    private data class CachedDnsEntry(
        val responseBytes: ByteArray,
        val expiresAtMs: Long,
        @Volatile var lastAccessedOrder: Long
    )

    private data class CacheLookupResult(
        val cacheKey: String,
        val bytes: ByteArray,
        val isStale: Boolean
    )

    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val refreshingKeys = ConcurrentHashMap.newKeySet<String>()
    private val cacheAccessSeq = AtomicLong(0L)
    private val swrScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridgeLock = Any()

    @Volatile
    private var activeBridge: TunnelSocketBridge? = null

    @Volatile
    private var tunnelHttpClient: OkHttpClient? = null

    @Volatile
    private var tunnelProbesClient: OkHttpClient? = null

    // Used ONLY for disconnected-mode direct probes (never for user TUN traffic when VPN is active)
    private val protectedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(DNS_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DNS_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .socketFactory(object : SocketFactory() {
                override fun createSocket(): Socket = Socket().apply { tcpNoDelay = true }.also { protectSocket(it) }
                override fun createSocket(host: String?, port: Int): Socket =
                    Socket(host, port).apply { tcpNoDelay = true }.also { protectSocket(it) }
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                    Socket(host, port, localHost, localPort).apply { tcpNoDelay = true }.also { protectSocket(it) }
                override fun createSocket(host: InetAddress?, port: Int): Socket =
                    Socket(host, port).apply { tcpNoDelay = true }.also { protectSocket(it) }
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                    Socket(address, port, localAddress, localPort).apply { tcpNoDelay = true }.also { protectSocket(it) }
            })
            .hostnameVerifier(HostnameVerifier { hostname, session ->
                verifyDohHostnameOrIpSan(hostname, session)
            })
            .build()
    }

    fun clearCache() {
        dnsCache.clear()
    }

    fun cacheSize(): Int = dnsCache.size

    private fun getOrCreateTunnelDohClient(engine: TunnelEngine): OkHttpClient {
        synchronized(bridgeLock) {
            val currentBridge = activeBridge
            if (currentBridge != null && currentBridge.engine === engine) {
                tunnelHttpClient?.let { return it }
            }
            closeTunnelClientsLocked()

            val bridge = TunnelSocketBridge(engine)
            val socketFactory = bridge.createSocketFactory()
            val tunnelDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    resolveHostnameSynchronouslyForBridge(hostname, engine)
            }
            val verifier = HostnameVerifier { hostname, session ->
                verifyDohHostnameOrIpSan(hostname, session)
            }

            val dohClient = OkHttpClient.Builder()
                .socketFactory(socketFactory)
                .dns(tunnelDns)
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
                .connectTimeout(DNS_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(DNS_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(DNS_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .hostnameVerifier(verifier)
                .apply {
                    if (dohSslSocketFactoryOverride != null && dohTrustManagerOverride != null) {
                        sslSocketFactory(dohSslSocketFactoryOverride, dohTrustManagerOverride)
                    }
                }
                .build()

            val probeClient = OkHttpClient.Builder()
                .socketFactory(socketFactory)
                .dns(tunnelDns)
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
                .connectTimeout(5_000L, TimeUnit.MILLISECONDS)
                .readTimeout(5_000L, TimeUnit.MILLISECONDS)
                .writeTimeout(5_000L, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .hostnameVerifier(verifier)
                .build()

            activeBridge = bridge
            tunnelHttpClient = dohClient
            tunnelProbesClient = probeClient
            return dohClient
        }
    }

    private fun getOrCreateTunnelProbeClient(engine: TunnelEngine): OkHttpClient {
        getOrCreateTunnelDohClient(engine)
        return synchronized(bridgeLock) {
            tunnelProbesClient ?: getOrCreateTunnelDohClient(engine)
        }
    }

    private fun resolveHostnameSynchronouslyForBridge(hostname: String, engine: TunnelEngine): List<InetAddress> {
        val clean = hostname.trim()
        if (isValidIpv4(clean)) {
            return listOf(ipv4ToInetAddress(clean, clean))
        }
        val knownIps = WELL_KNOWN_DOH_HOST_TO_IPS[clean.lowercase()]
            ?: WELL_KNOWN_PROBE_HOST_TO_IPS[clean.lowercase()]
        if (!knownIps.isNullOrEmpty()) {
            return knownIps.map { ip -> ipv4ToInetAddress(clean, ip) }
        }
        val resolved = kotlinx.coroutines.runBlocking {
            resolveHostnameToIpv4ViaTunnelTcp(
                hostname = clean,
                dnsConfig = ResolvedDnsConfig(
                    dnsMode = DnsMode.PLAIN_DNS,
                    primaryDns = DEFAULT_PRIMARY_DNS,
                    secondaryDns = DEFAULT_SECONDARY_DNS,
                    dohUrl = DEFAULT_DOH_URL
                ),
                engine = engine
            )
        }
        if (resolved != null && isValidIpv4(resolved)) {
            return listOf(ipv4ToInetAddress(clean, resolved))
        }
        throw IOException("No se pudo resolver '$clean' dentro del túnel sin DNS del sistema.")
    }

    private fun ipv4ToInetAddress(hostname: String, ipLiteral: String): InetAddress {
        val parts = ipLiteral.trim().split('.')
        val raw = ByteArray(4) { idx -> (parts[idx].toInt() and 0xFF).toByte() }
        return InetAddress.getByAddress(hostname, raw)
    }

    private fun closeTunnelClientsLocked() {
        runCatching { tunnelHttpClient?.connectionPool?.evictAll() }
        runCatching { tunnelProbesClient?.connectionPool?.evictAll() }
        tunnelHttpClient = null
        tunnelProbesClient = null
        runCatching { activeBridge?.close() }
        activeBridge = null
    }

    override fun close() {
        swrScope.cancel()
        refreshingKeys.clear()
        synchronized(bridgeLock) {
            closeTunnelClientsLocked()
        }
    }

    /**
     * Resolves active DNS queries coming from tun0 strictly through the active TunnelEngine:
     *  - Checks thread-safe LRU+TTL in-memory cache first (< 2 ms).
     *  - DNS_OVER_HTTPS -> DoH inside tunnel TLS+TCP (HTTP/2 keep-alive), falling back to DNS-over-TCP and UDPGW.
     *  - PLAIN_DNS -> DNS-over-TCP (RFC 1035 2-byte framing) with primaryDns -> secondaryDns fallback,
     *    falling back to UDPGW if TCP/53 is blocked.
     *  - SYSTEM_DEFAULT -> Active interception (NEVER passive bypass): tries BadVPN UDPGW if connected,
     *    then DNS-over-TCP (RFC 1035) via direct-tcpip -> primaryDns/secondaryDns.
     * NEVER bypasses the VPN via VpnService.protect() or direct UDP.
     */
    suspend fun resolveWireQueryThroughTunnel(
        queryBytes: ByteArray,
        dnsConfig: ResolvedDnsConfig,
        engine: TunnelEngine,
        udpRelay: UdpRelaySession? = null,
        bypassCache: Boolean = false
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (queryBytes.size < 12) return@withContext null

        val cacheScopeKey = "${dnsConfig.dnsMode.name}:${dnsConfig.primaryDns}:${dnsConfig.dohUrl}"
        if (!bypassCache) {
            lookupCachedResponseWithStale(queryBytes, cacheScopeKey)?.let { hit ->
                if (hit.isStale) {
                    scheduleBackgroundCacheRefresh(
                        cacheKey = hit.cacheKey,
                        cacheScopeKey = cacheScopeKey,
                        queryBytes = queryBytes.copyOf(),
                        dnsConfig = dnsConfig,
                        engine = engine,
                        udpRelay = udpRelay
                    )
                }
                return@withContext hit.bytes
            }
        }

        val resolved = queryUpstreamByMode(queryBytes, dnsConfig, engine, udpRelay)

        if (resolved != null && !bypassCache) {
            storeCachedResponse(queryBytes, resolved, cacheScopeKey)
        }
        resolved
    }

    private suspend fun queryUpstreamByMode(
        queryBytes: ByteArray,
        dnsConfig: ResolvedDnsConfig,
        engine: TunnelEngine,
        udpRelay: UdpRelaySession?
    ): ByteArray? = when (dnsConfig.dnsMode) {
        DnsMode.DNS_OVER_HTTPS -> {
            resolveViaTunnelDohWithDetails(queryBytes, dnsConfig, engine, bypassCache = true).responseBytes
                ?: resolveViaTunnelTcpWithFallback(
                    queryBytes = queryBytes,
                    primaryDns = dnsConfig.primaryDns,
                    secondaryDns = dnsConfig.secondaryDns,
                    engine = engine,
                    bypassCache = true
                ).responseBytes
                ?: resolveViaUdpgwWithFallback(queryBytes, dnsConfig, udpRelay)
        }
        DnsMode.PLAIN_DNS -> {
            resolveViaTunnelTcpWithFallback(
                queryBytes = queryBytes,
                primaryDns = dnsConfig.primaryDns,
                secondaryDns = dnsConfig.secondaryDns,
                engine = engine,
                bypassCache = true
            ).responseBytes
                ?: resolveViaUdpgwWithFallback(queryBytes, dnsConfig, udpRelay)
        }
        DnsMode.SYSTEM_DEFAULT -> {
            resolveViaUdpgwWithFallback(queryBytes, dnsConfig, udpRelay)
                ?: resolveViaTunnelTcpWithFallback(
                    queryBytes = queryBytes,
                    primaryDns = dnsConfig.primaryDns,
                    secondaryDns = dnsConfig.secondaryDns,
                    engine = engine,
                    bypassCache = true
                ).responseBytes
        }
    }

    private fun scheduleBackgroundCacheRefresh(
        cacheKey: String,
        cacheScopeKey: String,
        queryBytes: ByteArray,
        dnsConfig: ResolvedDnsConfig,
        engine: TunnelEngine,
        udpRelay: UdpRelaySession?
    ) {
        if (!refreshingKeys.add(cacheKey)) return
        swrScope.launch {
            try {
                val refreshed = queryUpstreamByMode(queryBytes, dnsConfig, engine, udpRelay)
                if (refreshed != null) {
                    storeCachedResponse(queryBytes, refreshed, cacheScopeKey)
                }
            } catch (_: Throwable) {
            } finally {
                refreshingKeys.remove(cacheKey)
            }
        }
    }

    internal fun seedCacheEntryForTesting(
        queryBytes: ByteArray,
        responseBytes: ByteArray,
        scopeKey: String,
        expiresAtMs: Long
    ) {
        val qKey = buildDnsCacheKey(queryBytes, scopeKey) ?: return
        dnsCache[qKey] = CachedDnsEntry(
            responseBytes = responseBytes.copyOf(),
            expiresAtMs = expiresAtMs,
            lastAccessedOrder = cacheAccessSeq.incrementAndGet()
        )
    }

    private fun lookupCachedResponseWithStale(
        queryBytes: ByteArray,
        cacheScopeKey: String
    ): CacheLookupResult? {
        val qKey = buildDnsCacheKey(queryBytes, cacheScopeKey) ?: return null
        val entry = dnsCache[qKey] ?: return null
        val now = System.currentTimeMillis()
        if (now >= entry.expiresAtMs + STALE_GRACE_MS) {
            dnsCache.remove(qKey, entry)
            return null
        }
        entry.lastAccessedOrder = cacheAccessSeq.incrementAndGet()
        val copy = entry.responseBytes.copyOf()
        if (copy.size >= 2) {
            copy[0] = queryBytes[0]
            copy[1] = queryBytes[1]
        }
        return CacheLookupResult(
            cacheKey = qKey,
            bytes = copy,
            isStale = now >= entry.expiresAtMs
        )
    }

    private fun lookupCachedResponse(queryBytes: ByteArray, cacheScopeKey: String): ByteArray? =
        lookupCachedResponseWithStale(queryBytes, cacheScopeKey)?.bytes

    private fun storeCachedResponse(queryBytes: ByteArray, responseBytes: ByteArray, cacheScopeKey: String) {
        if (responseBytes.size < 12) return
        val rcode = responseBytes[3].toInt() and 0x0F
        val anCount = ((responseBytes[6].toInt() and 0xFF) shl 8) or (responseBytes[7].toInt() and 0xFF)
        if (rcode != 0 || anCount <= 0) return

        val qKey = buildDnsCacheKey(queryBytes, cacheScopeKey) ?: return
        val ttlSec = extractMinDnsTtlSeconds(responseBytes).coerceIn(MIN_CACHE_TTL_SECONDS, MAX_CACHE_TTL_SECONDS)
        val now = System.currentTimeMillis()
        val entry = CachedDnsEntry(
            responseBytes = responseBytes.copyOf(),
            expiresAtMs = now + (ttlSec * 1_000L),
            lastAccessedOrder = cacheAccessSeq.incrementAndGet()
        )
        if (dnsCache.size >= MAX_DNS_CACHE_ENTRIES && !dnsCache.containsKey(qKey)) {
            evictCacheEntries(now)
        }
        dnsCache[qKey] = entry
    }

    private fun evictCacheEntries(nowMs: Long) {
        dnsCache.entries.removeIf { it.value.expiresAtMs + STALE_GRACE_MS <= nowMs }
        while (dnsCache.size >= MAX_DNS_CACHE_ENTRIES) {
            val lruKey = dnsCache.entries.minByOrNull { it.value.lastAccessedOrder }?.key ?: break
            dnsCache.remove(lruKey)
        }
    }

    internal fun buildDnsCacheKey(queryBytes: ByteArray, scopeKey: String): String? {
        if (queryBytes.size < 13) return null
        val qdCount = ((queryBytes[4].toInt() and 0xFF) shl 8) or (queryBytes[5].toInt() and 0xFF)
        if (qdCount != 1) return null
        val nameBuilder = StringBuilder()
        var pos = 12
        var hops = 0
        while (pos < queryBytes.size && hops++ < 64) {
            val len = queryBytes[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if ((len and 0xC0) != 0) return null // Queries should not start with compression pointer
            pos++
            if (pos + len > queryBytes.size) return null
            if (nameBuilder.isNotEmpty()) nameBuilder.append('.')
            nameBuilder.append(String(queryBytes, pos, len, StandardCharsets.US_ASCII).lowercase())
            pos += len
        }
        if (pos + 4 > queryBytes.size) return null
        val qType = ((queryBytes[pos].toInt() and 0xFF) shl 8) or (queryBytes[pos + 1].toInt() and 0xFF)
        val qClass = ((queryBytes[pos + 2].toInt() and 0xFF) shl 8) or (queryBytes[pos + 3].toInt() and 0xFF)
        return "$scopeKey|${nameBuilder}|$qType|$qClass"
    }

    internal fun extractMinDnsTtlSeconds(dnsResponse: ByteArray): Long {
        if (dnsResponse.size < 12) return MIN_CACHE_TTL_SECONDS
        val qdCount = ((dnsResponse[4].toInt() and 0xFF) shl 8) or (dnsResponse[5].toInt() and 0xFF)
        val anCount = ((dnsResponse[6].toInt() and 0xFF) shl 8) or (dnsResponse[7].toInt() and 0xFF)
        if (anCount <= 0) return MIN_CACHE_TTL_SECONDS

        var pos = 12
        repeat(qdCount) {
            pos = skipDnsName(dnsResponse, pos)
            if (pos < 0 || pos + 4 > dnsResponse.size) return MIN_CACHE_TTL_SECONDS
            pos += 4
        }

        var minTtl = Long.MAX_VALUE
        repeat(anCount) {
            pos = skipDnsName(dnsResponse, pos)
            if (pos < 0 || pos + 10 > dnsResponse.size) return MIN_CACHE_TTL_SECONDS
            val ttl = ((dnsResponse[pos + 4].toLong() and 0xFF) shl 24) or
                ((dnsResponse[pos + 5].toLong() and 0xFF) shl 16) or
                ((dnsResponse[pos + 6].toLong() and 0xFF) shl 8) or
                (dnsResponse[pos + 7].toLong() and 0xFF)
            val rdLength = ((dnsResponse[pos + 8].toInt() and 0xFF) shl 8) or (dnsResponse[pos + 9].toInt() and 0xFF)
            pos += 10
            if (pos + rdLength > dnsResponse.size) return MIN_CACHE_TTL_SECONDS
            minTtl = minOf(minTtl, ttl)
            pos += rdLength
        }
        return if (minTtl == Long.MAX_VALUE) MIN_CACHE_TTL_SECONDS else minTtl.coerceAtLeast(MIN_CACHE_TTL_SECONDS)
    }

    private suspend fun resolveViaUdpgwWithFallback(
        queryBytes: ByteArray,
        dnsConfig: ResolvedDnsConfig,
        udpRelay: UdpRelaySession?
    ): ByteArray? {
        if (udpRelay == null || !udpRelay.isDnsCapable) return null
        val primary = dnsConfig.primaryDns.trim().ifBlank { DEFAULT_PRIMARY_DNS }
        val first = udpRelay.resolveDnsQuery(primary, queryBytes, DNS_QUERY_TIMEOUT_MS)
        if (first != null && first.size >= 12) return first

        val secondary = dnsConfig.secondaryDns.trim()
        if (secondary.isNotBlank() && secondary != primary) {
            val second = udpRelay.resolveDnsQuery(secondary, queryBytes, DNS_QUERY_TIMEOUT_MS)
            if (second != null && second.size >= 12) return second
        }
        return null
    }

    suspend fun resolveViaTunnelTcpWithFallback(
        queryBytes: ByteArray,
        primaryDns: String,
        secondaryDns: String,
        engine: TunnelEngine,
        bypassCache: Boolean = false
    ): DnsResolutionResult = withContext(Dispatchers.IO) {
        val primary = primaryDns.trim().ifBlank { DEFAULT_PRIMARY_DNS }
        val secondary = secondaryDns.trim()
        val scopeKey = "TCP:$primary:$secondary"

        if (!bypassCache) {
            lookupCachedResponse(queryBytes, scopeKey)?.let { cached ->
                return@withContext DnsResolutionResult(
                    responseBytes = cached,
                    serverLabel = "$primary:53 (Caché local)",
                    usedFallback = false,
                    fromCache = true
                )
            }
        }

        val first = resolveViaTunnelTcp(queryBytes, primary, engine)
        if (first != null) {
            if (!bypassCache) storeCachedResponse(queryBytes, first, scopeKey)
            return@withContext DnsResolutionResult(
                responseBytes = first,
                serverLabel = "$primary:53 (TCP)",
                usedFallback = false
            )
        }

        if (secondary.isNotBlank() && secondary != primary) {
            val second = resolveViaTunnelTcp(queryBytes, secondary, engine)
            if (second != null) {
                if (!bypassCache) storeCachedResponse(queryBytes, second, scopeKey)
                return@withContext DnsResolutionResult(
                    responseBytes = second,
                    serverLabel = "$secondary:53 (TCP fallback)",
                    usedFallback = true
                )
            }
            return@withContext DnsResolutionResult(
                responseBytes = null,
                serverLabel = "$primary / $secondary",
                usedFallback = true,
                errorSummary = "Timeout o rechazo DNS-over-TCP en $primary y respaldo $secondary"
            )
        }

        DnsResolutionResult(
            responseBytes = null,
            serverLabel = "$primary:53",
            usedFallback = false,
            errorSummary = "Timeout o rechazo DNS-over-TCP en $primary:53"
        )
    }

    /**
     * RFC 1035 Section 4.2.2 DNS-over-TCP strictly through TunnelEngine.openTcpTunnel(dnsIp, 53).
     */
    suspend fun resolveViaTunnelTcp(
        queryBytes: ByteArray,
        dnsIp: String,
        engine: TunnelEngine,
        timeoutMs: Long = DNS_QUERY_TIMEOUT_MS
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (queryBytes.size < 12) return@withContext null
        try {
            withTimeoutOrNull(timeoutMs) {
                var stream: TunnelStream? = null
                try {
                    stream = engine.openTcpTunnel(dnsIp, 53)
                    val out = stream.outputStream
                    val framed = ByteArray(2 + queryBytes.size)
                    framed[0] = ((queryBytes.size ushr 8) and 0xFF).toByte()
                    framed[1] = (queryBytes.size and 0xFF).toByte()
                    System.arraycopy(queryBytes, 0, framed, 2, queryBytes.size)
                    out.write(framed)
                    out.flush()

                    val lenBytes = readExactly(stream.inputStream, 2)
                    val respLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
                    if (respLen !in 12..65535) {
                        throw IOException("Longitud DNS-over-TCP inválida: $respLen")
                    }
                    readExactly(stream.inputStream, respLen)
                } finally {
                    runCatching { stream?.close() }
                }
            }.also { result ->
                if (result == null) {
                    AppLogger.warnRateLimited(
                        TAG,
                        "dns_tcp_timeout_$dnsIp",
                        "Timeout DNS-over-TCP ($dnsIp:53, ${timeoutMs} ms)"
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            AppLogger.warnRateLimited(
                TAG,
                "dns_tcp_err_$dnsIp",
                "Error DNS-over-TCP ($dnsIp:53): ${err.message ?: err.javaClass.simpleName}"
            )
            null
        }
    }

    /**
     * RFC 8484 DoH POST (application/dns-message) over dedicated OkHttp TLS HTTP/2+HTTP/1.1 client
     * bridged through [TunnelSocketBridge] with single sequential socket reader & IP SAN validation.
     */
    suspend fun resolveViaTunnelDohWithDetails(
        queryBytes: ByteArray,
        dnsConfig: ResolvedDnsConfig,
        engine: TunnelEngine,
        timeoutMs: Long = DNS_QUERY_TIMEOUT_MS,
        bypassCache: Boolean = false
    ): DnsResolutionResult = withContext(Dispatchers.IO) {
        val scopeKey = "DOH:${dnsConfig.primaryDns}:${dnsConfig.dohUrl}"
        if (!bypassCache) {
            lookupCachedResponse(queryBytes, scopeKey)?.let { cached ->
                return@withContext DnsResolutionResult(
                    responseBytes = cached,
                    serverLabel = "DoH caché local (< 2 ms)",
                    usedFallback = false,
                    fromCache = true
                )
            }
        }

        val candidates = resolveDohTargets(dnsConfig, engine)
        if (candidates.isEmpty()) {
            return@withContext DnsResolutionResult(
                responseBytes = null,
                serverLabel = dnsConfig.dohUrl,
                errorSummary = "URL DoH inválida o sin resolución IP previa"
            )
        }

        val client = getOrCreateTunnelDohClient(engine)
        var lastError: String? = null

        candidates.forEachIndexed { idx, candidate ->
            try {
                val resp = withTimeoutOrNull(timeoutMs) {
                    executeDohOkHttpPost(client, candidate, queryBytes)
                }
                if (resp != null && resp.size >= 12) {
                    if (!bypassCache) storeCachedResponse(queryBytes, resp, scopeKey)
                    return@withContext DnsResolutionResult(
                        responseBytes = resp,
                        serverLabel = candidate.url,
                        usedFallback = idx > 0
                    )
                }
                val errMsg = "Timeout DoH (${timeoutMs} ms) hacia ${candidate.connectIp}"
                lastError = errMsg
                AppLogger.warnRateLimited(TAG, "dns_doh_timeout_${candidate.connectIp}", errMsg)
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                lastError = err.message ?: err.javaClass.simpleName
                AppLogger.warnRateLimited(
                    TAG,
                    "dns_doh_err_${candidate.connectIp}",
                    "Error DoH en túnel (${candidate.connectIp}): $lastError"
                )
            }
        }

        DnsResolutionResult(
            responseBytes = null,
            serverLabel = candidates.first().url,
            usedFallback = candidates.size > 1,
            errorSummary = lastError ?: "DoH inaccesible dentro del túnel"
        )
    }

    private fun executeDohOkHttpPost(
        client: OkHttpClient,
        target: DohEndpointTarget,
        queryBytes: ByteArray
    ): ByteArray {
        val request = Request.Builder()
            .url(target.url)
            .header("Accept", DOH_MEDIA_TYPE)
            .header("Host", target.hostHeader)
            .post(queryBytes.toRequestBody(DOH_MEDIA_TYPE.toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} (${response.message}) desde ${target.url}")
            }
            val bodyBytes = response.body?.bytes()
                ?: throw IOException("Respuesta DoH vacía desde ${target.url}")
            if (bodyBytes.size < 12) {
                throw IOException("Respuesta DNS DoH truncada (${bodyBytes.size} bytes)")
            }
            return bodyBytes
        }
    }

    private suspend fun resolveDohTargets(
        dnsConfig: ResolvedDnsConfig,
        engine: TunnelEngine
    ): List<DohEndpointTarget> {
        val rawUrl = dnsConfig.dohUrl.trim().ifBlank { DEFAULT_DOH_URL }
        val usingDefaultCloudflareUrl = rawUrl.equals(DEFAULT_DOH_URL, ignoreCase = true)

        // When user relies on default DoH URL and sets custom DNS IP (e.g. 94.140.14.15 / 94.140.15.15),
        // route directly via https://<DNS_IP>/dns-query (RFC 8484 + IP SAN verified) with secondary fallback.
        if (usingDefaultCloudflareUrl && isValidIpv4(dnsConfig.primaryDns)) {
            val list = mutableListOf(
                DohEndpointTarget(
                    url = "https://${dnsConfig.primaryDns}/dns-query",
                    connectIp = dnsConfig.primaryDns,
                    port = 443,
                    hostHeader = dnsConfig.primaryDns
                )
            )
            if (isValidIpv4(dnsConfig.secondaryDns) && dnsConfig.secondaryDns != dnsConfig.primaryDns) {
                list.add(
                    DohEndpointTarget(
                        url = "https://${dnsConfig.secondaryDns}/dns-query",
                        connectIp = dnsConfig.secondaryDns,
                        port = 443,
                        hostHeader = dnsConfig.secondaryDns
                    )
                )
            }
            return list
        }

        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return emptyList()
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return emptyList()
        val port = if (uri.port in 1..65535) uri.port else 443
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/dns-query"
        val querySuffix = uri.rawQuery?.let { "?$it" } ?: ""
        val fullUrl = "https://$host${if (port == 443) "" else ":$port"}$path$querySuffix"

        val resolvedIp = when {
            isValidIpv4(host) -> host
            else -> WELL_KNOWN_DOH_HOST_TO_IPS[host.lowercase()]?.firstOrNull()
                ?: resolveHostnameToIpv4ViaTunnelTcp(host, dnsConfig, engine)
        } ?: return emptyList()

        val hostHeader = if (port == 443) host else "$host:$port"
        return listOf(
            DohEndpointTarget(
                url = fullUrl,
                connectIp = resolvedIp,
                port = port,
                hostHeader = hostHeader
            )
        )
    }

    private suspend fun resolveHostnameToIpv4ViaTunnelTcp(
        hostname: String,
        dnsConfig: ResolvedDnsConfig,
        engine: TunnelEngine
    ): String? {
        val query = buildSampleDnsQuery(hostname)
        val resp = resolveViaTunnelTcpWithFallback(
            queryBytes = query,
            primaryDns = dnsConfig.primaryDns,
            secondaryDns = dnsConfig.secondaryDns,
            engine = engine,
            bypassCache = true
        ).responseBytes ?: return null
        return extractFirstARecordIp(resp)
    }

    /**
     * Measures lightweight TTFB (HTTP HEAD / 204 without body download) over a pooled/warm
     * tunnel connection, excluding cold SSH channel setup or full JSON body transfers.
     */
    suspend fun probeTunnelExitTtfb(
        engine: TunnelEngine,
        cachedExitIp: String? = null
    ): TunnelExitProbeResult? = withContext(Dispatchers.IO) {
        val client = getOrCreateTunnelProbeClient(engine)
        val candidates = listOf(
            "http://cp.cloudflare.com/generate_204",
            "http://connectivitycheck.gstatic.com/generate_204"
        )

        for (probeUrl in candidates) {
            val result = runCatching {
                withTimeoutOrNull(4_000L) {
                    // Warm up connection if pool has no idle socket so TTFB reflects real round-trip latency
                    if (client.connectionPool.idleConnectionCount() == 0) {
                        val warmReq = Request.Builder()
                            .url(probeUrl)
                            .head()
                            .header("Connection", "keep-alive")
                            .build()
                        runCatching { client.newCall(warmReq).execute().close() }
                    }

                    var reqSentNs = 0L
                    var respFirstByteNs = 0L
                    val ttfbListener = object : EventListener() {
                        override fun requestHeadersEnd(call: Call, request: Request) {
                            reqSentNs = System.nanoTime()
                        }

                        override fun responseHeadersStart(call: Call) {
                            if (respFirstByteNs == 0L) {
                                respFirstByteNs = System.nanoTime()
                            }
                        }
                    }

                    val ttfbCallClient = client.newBuilder()
                        .eventListener(ttfbListener)
                        .build()

                    val req = Request.Builder()
                        .url(probeUrl)
                        .head()
                        .header("Connection", "keep-alive")
                        .build()

                    val fallbackStartNs = System.nanoTime()
                    ttfbCallClient.newCall(req).execute().use { resp ->
                        val fallbackEndNs = System.nanoTime()
                        if (resp.code !in 100..499) {
                            throw IOException("HTTP ${resp.code}")
                        }
                        val elapsedNs = if (reqSentNs > 0L && respFirstByteNs >= reqSentNs) {
                            respFirstByteNs - reqSentNs
                        } else {
                            fallbackEndNs - fallbackStartNs
                        }
                        val ttfbMs = TimeUnit.NANOSECONDS.toMillis(elapsedNs).coerceAtLeast(1L)
                        TunnelExitProbeResult(
                            ttfbMs = ttfbMs,
                            statusCode = resp.code,
                            endpointLabel = probeUrl,
                            exitIp = cachedExitIp
                        )
                    }
                }
            }.getOrNull()

            if (result != null) {
                return@withContext result
            }
        }
        null
    }

    /**
     * Direct DoH probe ONLY for disconnected-mode Diagnostics (never used when VPN tunnel is active).
     */
    fun resolveViaDohDirect(queryBytes: ByteArray, dohUrl: String): ByteArray? {
        val url = dohUrl.ifBlank { DEFAULT_DOH_URL }
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", DOH_MEDIA_TYPE)
                .post(queryBytes.toRequestBody(DOH_MEDIA_TYPE.toMediaType()))
                .build()
            protectedHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.warnRateLimited(TAG, "direct_doh_http_$url", "DoH directo HTTP ${response.code} desde $url")
                    return null
                }
                response.body?.bytes()
            }
        }.onFailure { err ->
            AppLogger.warnRateLimited(TAG, "direct_doh_err_$url", "DoH directo sin túnel ($url): ${err.message}")
        }.getOrNull()
    }

    /**
     * Direct UDP probe ONLY for disconnected-mode Diagnostics (never used when VPN tunnel is active).
     */
    fun resolveViaProtectedUdpWithFallback(
        queryBytes: ByteArray,
        primaryDns: String,
        secondaryDns: String
    ): DnsResolutionResult {
        val first = resolveViaProtectedUdp(queryBytes, primaryDns)
        if (first != null) {
            return DnsResolutionResult(first, "$primaryDns:53 (UDP directo)", usedFallback = false)
        }
        if (secondaryDns.isNotBlank() && secondaryDns != primaryDns) {
            val second = resolveViaProtectedUdp(queryBytes, secondaryDns)
            if (second != null) {
                return DnsResolutionResult(second, "$secondaryDns:53 (UDP directo fallback)", usedFallback = true)
            }
        }
        return DnsResolutionResult(
            responseBytes = null,
            serverLabel = "$primaryDns / $secondaryDns",
            errorSummary = "Sin salida UDP directa"
        )
    }

    fun resolveViaProtectedUdp(queryBytes: ByteArray, dnsIp: String): ByteArray? {
        return runCatching {
            val socket = DatagramSocket()
            if (!protectDatagramSocket(socket)) {
                socket.close()
                return null
            }
            socket.use { sock ->
                sock.soTimeout = DNS_QUERY_TIMEOUT_MS.toInt()
                val addr = InetAddress.getByName(dnsIp)
                sock.send(DatagramPacket(queryBytes, queryBytes.size, addr, 53))
                val respBuf = ByteArray(4096)
                val respPacket = DatagramPacket(respBuf, respBuf.size)
                sock.receive(respPacket)
                respBuf.copyOf(respPacket.length)
            }
        }.onFailure { err ->
            AppLogger.warnRateLimited(
                TAG,
                "direct_udp_$dnsIp",
                "DNS UDP directo sin túnel ($dnsIp): ${err.message}"
            )
        }.getOrNull()
    }

    data class ExitIpInfo(
        val ip: String,
        val country: String? = null,
        val countryCode: String? = null,
        val city: String? = null,
        val isp: String? = null,
        val asn: String? = null
    )

    /**
     * Queries public exit IP + country/city/ISP/ASN through the active TunnelEngine.
     * Uses pooled HTTP client over [TunnelSocketBridge] or fallback direct TCP stream.
     */
    suspend fun fetchExitIpInfoThroughTunnel(engine: TunnelEngine): ExitIpInfo? = withContext(Dispatchers.IO) {
        val probeClient = getOrCreateTunnelProbeClient(engine)

        val fromIpApi = runCatching {
            withTimeoutOrNull(4_500L) {
                val req = Request.Builder()
                    .url("http://ip-api.com/json/?fields=status,country,countryCode,city,isp,as,query")
                    .header("Accept", "application/json")
                    .header("User-Agent", "AIOTunnelVPN/1.0")
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    parseIpApiJson(body)
                }
            }
        }.getOrNull()
        if (fromIpApi != null) return@withContext fromIpApi

        // Direct fallback for lightweight mock engines in unit tests that return raw HTTP over openTcpTunnel
        val fromDirectIpApi = runCatching {
            withTimeoutOrNull(4_000L) {
                engine.openTcpTunnel("ip-api.com", 80).use { stream ->
                    val req = "GET /json/?fields=status,country,countryCode,city,isp,as,query HTTP/1.1\r\n" +
                        "Host: ip-api.com\r\n" +
                        "Accept: application/json\r\n" +
                        "Connection: close\r\n" +
                        "User-Agent: AIOTunnelVPN/1.0\r\n\r\n"
                    stream.outputStream.write(req.toByteArray(StandardCharsets.US_ASCII))
                    stream.outputStream.flush()
                    val raw = stream.inputStream.readBytes()
                    val bodyBytes = decodeHttpBody(raw)
                    parseIpApiJson(String(bodyBytes, StandardCharsets.UTF_8))
                }
            }
        }.getOrNull()
        if (fromDirectIpApi != null) return@withContext fromDirectIpApi

        val fromIpWho = runCatching {
            withTimeoutOrNull(5_000L) {
                val req = Request.Builder()
                    .url("https://ipwho.is/")
                    .header("Accept", "application/json")
                    .header("User-Agent", "AIOTunnelVPN/1.0")
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    parseIpWhoJson(body)
                }
            }
        }.getOrNull()
        if (fromIpWho != null) return@withContext fromIpWho

        val ipOnly = runCatching {
            withTimeoutOrNull(4_000L) {
                val req = Request.Builder()
                    .url("http://api.ipify.org/")
                    .header("User-Agent", "AIOTunnelVPN/1.0")
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string()?.trim().orEmpty()
                    if (isValidIpLiteral(body)) body else null
                }
            }
        }.getOrNull()
        ipOnly?.let { ExitIpInfo(ip = it) }
    }

    suspend fun fetchExitIpThroughTunnel(engine: TunnelEngine): String? =
        fetchExitIpInfoThroughTunnel(engine)?.ip

    private fun decodeHttpBody(raw: ByteArray): ByteArray {
        val sepIdx = indexOfCrlfCrlf(raw)
        if (sepIdx < 0) throw IOException("Respuesta HTTP incompleta")
        val headerStr = String(raw, 0, sepIdx, StandardCharsets.ISO_8859_1)
        val lines = headerStr.lines()
        val statusLine = lines.firstOrNull().orEmpty()
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        if (statusCode !in 200..299) {
            throw IOException("HTTP $statusCode ($statusLine)")
        }
        val bodyStart = sepIdx + 4
        val contentLength = lines
            .firstOrNull { it.lowercase().startsWith("content-length:") }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
        if (contentLength != null && raw.size >= bodyStart + contentLength) {
            return raw.copyOfRange(bodyStart, bodyStart + contentLength)
        }
        val bodyRaw = raw.copyOfRange(bodyStart, raw.size)
        return bodyRaw
    }

    fun buildSampleDnsQuery(domain: String = "example.com"): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x13)
        out.write(0x37) // Transaction ID
        out.write(0x01)
        out.write(0x00) // Standard recursive query
        out.write(0x00)
        out.write(0x01) // QDCOUNT = 1
        out.write(0x00)
        out.write(0x00) // ANCOUNT = 0
        out.write(0x00)
        out.write(0x00) // NSCOUNT = 0
        out.write(0x00)
        out.write(0x00) // ARCOUNT = 0
        for (label in domain.split('.').filter { it.isNotEmpty() }) {
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00) // Root label
        out.write(0x00)
        out.write(0x01) // QTYPE = A
        out.write(0x00)
        out.write(0x01) // QCLASS = IN
        return out.toByteArray()
    }

    internal fun extractFirstARecordIp(dnsResponse: ByteArray): String? {
        if (dnsResponse.size < 12) return null
        val qdCount = ((dnsResponse[4].toInt() and 0xFF) shl 8) or (dnsResponse[5].toInt() and 0xFF)
        val anCount = ((dnsResponse[6].toInt() and 0xFF) shl 8) or (dnsResponse[7].toInt() and 0xFF)
        if (anCount <= 0) return null

        var pos = 12
        repeat(qdCount) {
            pos = skipDnsName(dnsResponse, pos)
            if (pos < 0 || pos + 4 > dnsResponse.size) return null
            pos += 4
        }

        repeat(anCount) {
            pos = skipDnsName(dnsResponse, pos)
            if (pos < 0 || pos + 10 > dnsResponse.size) return null
            val rType = ((dnsResponse[pos].toInt() and 0xFF) shl 8) or (dnsResponse[pos + 1].toInt() and 0xFF)
            val rdLength = ((dnsResponse[pos + 8].toInt() and 0xFF) shl 8) or (dnsResponse[pos + 9].toInt() and 0xFF)
            pos += 10
            if (pos + rdLength > dnsResponse.size) return null
            if (rType == 1 && rdLength == 4) {
                return "${dnsResponse[pos].toInt() and 0xFF}.${dnsResponse[pos + 1].toInt() and 0xFF}." +
                    "${dnsResponse[pos + 2].toInt() and 0xFF}.${dnsResponse[pos + 3].toInt() and 0xFF}"
            }
            pos += rdLength
        }
        return null
    }

    private fun skipDnsName(buf: ByteArray, start: Int): Int {
        var pos = start
        while (pos < buf.size) {
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if ((len and 0xC0) == 0xC0) {
                return if (pos + 2 <= buf.size) pos + 2 else -1
            }
            pos += 1 + len
        }
        return -1
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read == -1) throw EOFException("EOF inesperado leyendo DNS-over-TCP ($offset/$count bytes).")
            offset += read
        }
        return buf
    }

    private fun indexOfCrlfCrlf(data: ByteArray): Int {
        for (i in 0..(data.size - 4)) {
            if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() && data[i + 3] == '\n'.code.toByte()
            ) return i
        }
        return -1
    }

    companion object {
        private const val TAG = "DnsManager"
        private const val DOH_MEDIA_TYPE = "application/dns-message"
        const val DNS_QUERY_TIMEOUT_MS = 2_500L
        const val MIN_CACHE_TTL_SECONDS = 60L
        const val MAX_CACHE_TTL_SECONDS = 3_600L
        const val STALE_GRACE_MS = 86_400_000L // 24 hours stale-while-revalidate grace window
        const val MAX_DNS_CACHE_ENTRIES = 512
        const val DEFAULT_PRIMARY_DNS = "1.1.1.1"
        const val DEFAULT_SECONDARY_DNS = "1.0.0.1"
        const val DEFAULT_DOH_URL = "https://cloudflare-dns.com/dns-query"

        private val WELL_KNOWN_IP_TO_DOH_HOST = mapOf(
            "1.1.1.1" to "cloudflare-dns.com",
            "1.0.0.1" to "cloudflare-dns.com",
            "8.8.8.8" to "dns.google",
            "8.8.4.4" to "dns.google",
            "94.140.14.15" to "dns.adguard-dns.com",
            "94.140.15.15" to "dns.adguard-dns.com",
            "94.140.14.140" to "dns.adguard-dns.com",
            "94.140.14.141" to "dns.adguard-dns.com",
            "9.9.9.9" to "dns.quad9.net",
            "149.112.112.112" to "dns.quad9.net"
        )

        private val WELL_KNOWN_DOH_HOST_TO_IPS = mapOf(
            "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
            "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
            "dns.adguard-dns.com" to listOf("94.140.14.15", "94.140.15.15"),
            "dns.adguard.com" to listOf("94.140.14.15", "94.140.15.15"),
            "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112")
        )

        private val WELL_KNOWN_PROBE_HOST_TO_IPS = mapOf(
            "cp.cloudflare.com" to listOf("1.1.1.1", "1.0.0.1"),
            "connectivitycheck.gstatic.com" to listOf("142.250.190.99"),
            "api.ipify.org" to listOf("104.26.13.205")
        )

        /**
         * Verifies TLS hostname or IP SAN (e.g. 94.140.14.15 / 94.140.15.15 / 1.1.1.1)
         * after X.509 CA chain validation has succeeded.
         */
        fun verifyDohHostnameOrIpSan(hostOrIp: String, session: SSLSession): Boolean {
            val clean = hostOrIp.trim().removePrefix("[").substringBefore("]")
            if (clean.isEmpty()) return false

            if (runCatching { HttpsURLConnection.getDefaultHostnameVerifier().verify(clean, session) }.getOrDefault(false)) {
                return true
            }

            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
                ?: return false

            val sans = runCatching { leaf.subjectAlternativeNames }.getOrNull().orEmpty()
            if (isValidIpv4(clean)) {
                val targetBytes = runCatching { InetAddress.getByName(clean).address }.getOrNull()
                val ipMatched = sans.any { entry ->
                    val type = entry.getOrNull(0) as? Int
                    val value = entry.getOrNull(1) as? String
                    if (type == 7 && value != null && targetBytes != null) {
                        runCatching { InetAddress.getByName(value).address.contentEquals(targetBytes) }.getOrDefault(false)
                    } else {
                        false
                    }
                }
                if (ipMatched) return true

                // If a trusted CA certificate for a known DoH IP only lists its DNS SAN (e.g. dns.adguard-dns.com)
                val mappedDnsHost = WELL_KNOWN_IP_TO_DOH_HOST[clean]
                if (mappedDnsHost != null) {
                    return sans.any { entry ->
                        val type = entry.getOrNull(0) as? Int
                        val value = entry.getOrNull(1) as? String
                        type == 2 && value != null && matchesDnsName(mappedDnsHost, value)
                    }
                }
            } else {
                val dnsMatched = sans.any { entry ->
                    val type = entry.getOrNull(0) as? Int
                    val value = entry.getOrNull(1) as? String
                    type == 2 && value != null && matchesDnsName(clean, value)
                }
                if (dnsMatched) return true
            }
            return false
        }

        private fun matchesDnsName(host: String, pattern: String): Boolean {
            val h = host.lowercase()
            val p = pattern.lowercase()
            if (h == p) return true
            if (p.startsWith("*.")) {
                val suffix = p.substring(1) // ".domain.com"
                return h.endsWith(suffix) && h.indexOf('.') == h.length - suffix.length
            }
            return false
        }

        @Serializable
        private data class IpApiResponse(
            val status: String = "",
            val country: String = "",
            val countryCode: String = "",
            val city: String = "",
            val isp: String = "",
            @kotlinx.serialization.SerialName("as")
            val asn: String = "",
            val query: String = ""
        )

        @Serializable
        private data class IpWhoConnection(
            val asn: Long? = null,
            val isp: String = ""
        )

        @Serializable
        private data class IpWhoResponse(
            val success: Boolean = false,
            val ip: String = "",
            val country: String = "",
            val country_code: String = "",
            val city: String = "",
            val connection: IpWhoConnection? = null
        )

        private val jsonParser = Json { ignoreUnknownKeys = true }

        fun countryCodeToFlagEmoji(countryCode: String?): String {
            val code = countryCode?.trim()?.uppercase().orEmpty()
            if (code.length != 2 || !code[0].isLetter() || !code[1].isLetter()) return "🌐"
            val first = 0x1F1E6 + (code[0].code - 'A'.code)
            val second = 0x1F1E6 + (code[1].code - 'A'.code)
            return String(Character.toChars(first)) + String(Character.toChars(second))
        }

        internal fun parseIpApiJson(jsonText: String): ExitIpInfo? = runCatching {
            val obj = jsonParser.decodeFromString<IpApiResponse>(jsonText)
            if (!obj.status.equals("success", ignoreCase = true)) return null
            val ip = obj.query.trim()
            if (!isValidIpLiteral(ip)) return null
            ExitIpInfo(
                ip = ip,
                country = obj.country.trim().ifBlank { null },
                countryCode = obj.countryCode.trim().ifBlank { null },
                city = obj.city.trim().ifBlank { null },
                isp = obj.isp.trim().ifBlank { null },
                asn = obj.asn.trim().ifBlank { null }
            )
        }.getOrNull()

        internal fun parseIpWhoJson(jsonText: String): ExitIpInfo? = runCatching {
            val obj = jsonParser.decodeFromString<IpWhoResponse>(jsonText)
            if (!obj.success) return null
            val ip = obj.ip.trim()
            if (!isValidIpLiteral(ip)) return null
            ExitIpInfo(
                ip = ip,
                country = obj.country.trim().ifBlank { null },
                countryCode = obj.country_code.trim().ifBlank { null },
                city = obj.city.trim().ifBlank { null },
                isp = obj.connection?.isp?.trim()?.ifBlank { null },
                asn = obj.connection?.asn?.let { "AS$it" }
            )
        }.getOrNull()

        internal fun isValidIpLiteral(value: String): Boolean =
            value.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$|^[0-9a-fA-F:]+$"""))

        fun isValidIpv4(ip: String): Boolean {
            val parts = ip.trim().split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                val num = part.toIntOrNull() ?: return@all false
                num in 0..255
            }
        }

        fun resolveActiveDnsConfig(
            profile: TunnelProfile?,
            settings: AppSettings
        ): ResolvedDnsConfig {
            val globalPrimary = settings.defaultDnsServer.trim().takeIf { isValidIpv4(it) } ?: DEFAULT_PRIMARY_DNS
            val globalSecondary = settings.defaultDnsServerSecondary.trim().takeIf { isValidIpv4(it) } ?: DEFAULT_SECONDARY_DNS
            val globalDoh = settings.defaultDohUrl.trim().ifBlank { DEFAULT_DOH_URL }

            if (profile == null) {
                return ResolvedDnsConfig(
                    dnsMode = settings.defaultDnsMode,
                    primaryDns = globalPrimary,
                    secondaryDns = globalSecondary,
                    dohUrl = globalDoh
                )
            }

            val profilePrimaryValid = profile.dnsServer.trim().takeIf { isValidIpv4(it) }
            val profileSecondaryValid = profile.dnsServerSecondary.trim().takeIf { isValidIpv4(it) }

            val effectivePrimary = if (profilePrimaryValid != null &&
                (profile.useCustomDns || profilePrimaryValid != DEFAULT_PRIMARY_DNS)
            ) {
                profilePrimaryValid
            } else {
                globalPrimary
            }

            val effectiveSecondary = if (profileSecondaryValid != null &&
                (profile.useCustomDns || profileSecondaryValid != DEFAULT_SECONDARY_DNS)
            ) {
                profileSecondaryValid
            } else {
                globalSecondary
            }

            val profileDoh = profile.dohUrl.trim()
            val effectiveDoh = if (profileDoh.isNotBlank() &&
                (profile.useCustomDns || profileDoh != DEFAULT_DOH_URL)
            ) {
                profileDoh
            } else {
                globalDoh
            }

            return ResolvedDnsConfig(
                dnsMode = profile.dnsMode,
                primaryDns = effectivePrimary,
                secondaryDns = effectiveSecondary,
                dohUrl = effectiveDoh
            )
        }
    }
}
