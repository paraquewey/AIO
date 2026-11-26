package com.example.vpn.tun

import com.example.core.logger.AppLogger
import com.example.domain.models.AppSettings
import com.example.domain.models.DnsMode
import com.example.domain.models.TunnelProfile
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult

class DnsManager(
    private val protectSocket: (Socket) -> Boolean = { true },
    private val protectDatagramSocket: (DatagramSocket) -> Boolean = { true }
) {
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
        val errorSummary: String? = null
    )

    private data class DohEndpointTarget(
        val connectIp: String,
        val port: Int,
        val sniHost: String,
        val hostHeader: String,
        val path: String
    )

    // Used ONLY for disconnected-mode direct probes (never for user TUN traffic when VPN is active)
    private val protectedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DNS_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DNS_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .socketFactory(object : SocketFactory() {
                override fun createSocket(): Socket = Socket().also { protectSocket(it) }
                override fun createSocket(host: String?, port: Int): Socket =
                    Socket(host, port).also { protectSocket(it) }
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                    Socket(host, port, localHost, localPort).also { protectSocket(it) }
                override fun createSocket(host: InetAddress?, port: Int): Socket =
                    Socket(host, port).also { protectSocket(it) }
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                    Socket(address, port, localAddress, localPort).also { protectSocket(it) }
            })
            .build()
    }

    /**
     * Resolves active DNS queries coming from tun0 strictly through the active TunnelEngine
     * using DNS-over-HTTPS (over tunnel TCP+TLS) and/or DNS-over-TCP (RFC 1035 2-byte framing)
     * with automatic fallback from primaryDns -> secondaryDns.
     * NEVER bypasses the VPN via VpnService.protect() or direct UDP.
     */
    suspend fun resolveWireQueryThroughTunnel(
        queryBytes: ByteArray,
        dnsConfig: ResolvedDnsConfig,
        engine: TunnelEngine
    ): ByteArray? = withContext(Dispatchers.IO) {
        when (dnsConfig.dnsMode) {
            DnsMode.DNS_OVER_HTTPS -> {
                val dohAttempt = resolveViaTunnelDohWithDetails(queryBytes, dnsConfig, engine)
                if (dohAttempt.responseBytes != null) {
                    dohAttempt.responseBytes
                } else {
                    resolveViaTunnelTcpWithFallback(
                        queryBytes = queryBytes,
                        primaryDns = dnsConfig.primaryDns,
                        secondaryDns = dnsConfig.secondaryDns,
                        engine = engine
                    ).responseBytes
                }
            }
            DnsMode.PLAIN_DNS, DnsMode.SYSTEM_DEFAULT -> {
                resolveViaTunnelTcpWithFallback(
                    queryBytes = queryBytes,
                    primaryDns = dnsConfig.primaryDns,
                    secondaryDns = dnsConfig.secondaryDns,
                    engine = engine
                ).responseBytes
            }
        }
    }

    suspend fun resolveViaTunnelTcpWithFallback(
        queryBytes: ByteArray,
        primaryDns: String,
        secondaryDns: String,
        engine: TunnelEngine
    ): DnsResolutionResult = withContext(Dispatchers.IO) {
        val primary = primaryDns.trim().ifBlank { DEFAULT_PRIMARY_DNS }
        val secondary = secondaryDns.trim()

        val first = resolveViaTunnelTcp(queryBytes, primary, engine)
        if (first != null) {
            return@withContext DnsResolutionResult(
                responseBytes = first,
                serverLabel = "$primary:53 (TCP)",
                usedFallback = false
            )
        }

        if (secondary.isNotBlank() && secondary != primary) {
            val second = resolveViaTunnelTcp(queryBytes, secondary, engine)
            if (second != null) {
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

    suspend fun resolveViaTunnelDohWithDetails(
        queryBytes: ByteArray,
        dnsConfig: ResolvedDnsConfig,
        engine: TunnelEngine,
        timeoutMs: Long = DNS_QUERY_TIMEOUT_MS
    ): DnsResolutionResult = withContext(Dispatchers.IO) {
        val target = resolveDohTarget(dnsConfig, engine)
            ?: return@withContext DnsResolutionResult(
                responseBytes = null,
                serverLabel = dnsConfig.dohUrl,
                errorSummary = "URL DoH inválida o sin resolución IP previa"
            )

        val candidates = buildList {
            add(target)
            // Secondary IP fallback when using matching DoH provider or direct IP DoH
            if (dnsConfig.secondaryDns.isNotBlank() && dnsConfig.secondaryDns != target.connectIp &&
                isSameDohProvider(target.sniHost, dnsConfig.secondaryDns)
            ) {
                add(target.copy(connectIp = dnsConfig.secondaryDns))
            }
        }

        var lastError: String? = null
        candidates.forEachIndexed { idx, candidate ->
            try {
                val resp = withTimeoutOrNull(timeoutMs) {
                    executeDohOverTunnelStream(queryBytes, candidate, engine)
                }
                if (resp != null && resp.size >= 12) {
                    return@withContext DnsResolutionResult(
                        responseBytes = resp,
                        serverLabel = "https://${candidate.hostHeader}${candidate.path} vía ${candidate.connectIp}",
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
            serverLabel = dnsConfig.dohUrl,
            usedFallback = candidates.size > 1,
            errorSummary = lastError ?: "DoH inaccesible dentro del túnel"
        )
    }

    private suspend fun resolveDohTarget(
        dnsConfig: ResolvedDnsConfig,
        engine: TunnelEngine
    ): DohEndpointTarget? {
        val rawUrl = dnsConfig.dohUrl.ifBlank { DEFAULT_DOH_URL }
        val customDnsSelected = dnsConfig.primaryDns != DEFAULT_PRIMARY_DNS &&
            dnsConfig.primaryDns != DEFAULT_SECONDARY_DNS
        val usingUntouchedDefaultDohUrl = rawUrl.equals(DEFAULT_DOH_URL, ignoreCase = true)

        // If user set custom DNS IP (e.g. 94.140.14.15 or 8.8.8.8) and left default DoH URL untouched,
        // map to the chosen DNS provider or direct IP so user's DNS choice is always honored.
        if (customDnsSelected && usingUntouchedDefaultDohUrl) {
            val mappedHost = WELL_KNOWN_IP_TO_DOH_HOST[dnsConfig.primaryDns]
            if (mappedHost != null) {
                val connectIp = WELL_KNOWN_DOH_HOST_TO_IPS[mappedHost]?.firstOrNull() ?: dnsConfig.primaryDns
                return DohEndpointTarget(
                    connectIp = connectIp,
                    port = 443,
                    sniHost = mappedHost,
                    hostHeader = mappedHost,
                    path = "/dns-query"
                )
            }
            return DohEndpointTarget(
                connectIp = dnsConfig.primaryDns,
                port = 443,
                sniHost = dnsConfig.primaryDns,
                hostHeader = dnsConfig.primaryDns,
                path = "/dns-query"
            )
        }

        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port in 1..65535) uri.port else 443
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/dns-query"
        val querySuffix = uri.rawQuery?.let { "?$it" } ?: ""
        val fullPath = "$path$querySuffix"

        val directIp = when {
            isValidIpv4(host) -> host
            host.equals("cloudflare-dns.com", ignoreCase = true) &&
                (dnsConfig.primaryDns == "1.1.1.1" || dnsConfig.primaryDns == "1.0.0.1") -> dnsConfig.primaryDns
            else -> WELL_KNOWN_DOH_HOST_TO_IPS[host.lowercase()]?.firstOrNull()
                ?: resolveHostnameToIpv4ViaTunnelTcp(host, dnsConfig, engine)
        } ?: return null

        val hostHeader = if (port == 443) host else "$host:$port"
        return DohEndpointTarget(
            connectIp = directIp,
            port = port,
            sniHost = host,
            hostHeader = hostHeader,
            path = fullPath
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
            engine = engine
        ).responseBytes ?: return null
        return extractFirstARecordIp(resp)
    }

    private suspend fun executeDohOverTunnelStream(
        queryBytes: ByteArray,
        target: DohEndpointTarget,
        engine: TunnelEngine
    ): ByteArray {
        var stream: TunnelStream? = null
        try {
            stream = engine.openTcpTunnel(target.connectIp, target.port)
            val sslContext = SSLContext.getDefault()
            val sslEngine = sslContext.createSSLEngine(target.sniHost, target.port).apply {
                useClientMode = true
                val params = sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                if (!isValidIpv4(target.sniHost)) {
                    params.serverNames = listOf(SNIHostName(target.sniHost))
                }
                sslParameters = params
            }

            performTlsHandshake(sslEngine, stream)

            val httpReq = buildString {
                append("POST ").append(target.path).append(" HTTP/1.1\r\n")
                append("Host: ").append(target.hostHeader).append("\r\n")
                append("Content-Type: application/dns-message\r\n")
                append("Accept: application/dns-message\r\n")
                append("Content-Length: ").append(queryBytes.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)

            val fullReq = ByteArray(httpReq.size + queryBytes.size)
            System.arraycopy(httpReq, 0, fullReq, 0, httpReq.size)
            System.arraycopy(queryBytes, 0, fullReq, httpReq.size, queryBytes.size)

            val rawResponse = sendAndReceiveTlsApplicationData(sslEngine, stream, fullReq)
            return parseHttpDnsResponseBody(rawResponse)
        } finally {
            runCatching { stream?.close() }
        }
    }

    private fun performTlsHandshake(sslEngine: javax.net.ssl.SSLEngine, stream: TunnelStream) {
        sslEngine.beginHandshake()
        val session = sslEngine.session
        var appOut = ByteBuffer.allocate(0)
        var netOut = ByteBuffer.allocate(session.packetBufferSize.coerceAtLeast(16_384))
        var netIn = ByteBuffer.allocate(session.packetBufferSize.coerceAtLeast(16_384))
        var appIn = ByteBuffer.allocate(session.applicationBufferSize.coerceAtLeast(16_384))

        while (true) {
            when (sslEngine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> return

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = sslEngine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = sslEngine.delegatedTask
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val res = sslEngine.wrap(appOut, netOut)
                    if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        netOut = ByteBuffer.allocate(sslEngine.session.packetBufferSize.coerceAtLeast(netOut.capacity() * 2))
                        continue
                    }
                    netOut.flip()
                    if (netOut.hasRemaining()) {
                        val bytes = ByteArray(netOut.remaining())
                        netOut.get(bytes)
                        stream.outputStream.write(bytes)
                        stream.outputStream.flush()
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    netIn.flip()
                    val res = sslEngine.unwrap(netIn, appIn)
                    netIn.compact()
                    when (res.status) {
                        SSLEngineResult.Status.OK -> Unit
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            appIn = ByteBuffer.allocate(sslEngine.session.applicationBufferSize.coerceAtLeast(appIn.capacity() * 2))
                        }
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            if (!netIn.hasRemaining()) {
                                val bigger = ByteBuffer.allocate(netIn.capacity() * 2)
                                netIn.flip()
                                bigger.put(netIn)
                                netIn = bigger
                            }
                            val temp = ByteArray(netIn.remaining().coerceAtMost(8192))
                            val read = stream.inputStream.read(temp)
                            if (read == -1) throw EOFException("EOF durante handshake TLS DoH.")
                            netIn.put(temp, 0, read)
                        }
                        SSLEngineResult.Status.CLOSED -> throw IOException("Sesión TLS DoH cerrada durante handshake.")
                    }
                }

                else -> throw IOException("Estado TLS inesperado: ${sslEngine.handshakeStatus}")
            }
        }
    }

    private fun sendAndReceiveTlsApplicationData(
        sslEngine: javax.net.ssl.SSLEngine,
        stream: TunnelStream,
        payload: ByteArray
    ): ByteArray {
        val appOut = ByteBuffer.wrap(payload)
        var netOut = ByteBuffer.allocate(sslEngine.session.packetBufferSize.coerceAtLeast(16_384))
        while (appOut.hasRemaining()) {
            netOut.clear()
            val res = sslEngine.wrap(appOut, netOut)
            if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                continue
            }
            netOut.flip()
            if (netOut.hasRemaining()) {
                val outBytes = ByteArray(netOut.remaining())
                netOut.get(outBytes)
                stream.outputStream.write(outBytes)
            }
        }
        stream.outputStream.flush()

        val collected = ByteArrayOutputStream()
        var netIn = ByteBuffer.allocate(sslEngine.session.packetBufferSize.coerceAtLeast(16_384))
        var appIn = ByteBuffer.allocate(sslEngine.session.applicationBufferSize.coerceAtLeast(16_384))
        val temp = ByteArray(8192)

        while (true) {
            val read = stream.inputStream.read(temp)
            if (read == -1) break
            if (netIn.remaining() < read) {
                val bigger = ByteBuffer.allocate((netIn.position() + read).coerceAtLeast(netIn.capacity() * 2))
                netIn.flip()
                bigger.put(netIn)
                netIn = bigger
            }
            netIn.put(temp, 0, read)

            var needMoreBytes = false
            while (!needMoreBytes) {
                netIn.flip()
                appIn.clear()
                val res = sslEngine.unwrap(netIn, appIn)
                netIn.compact()
                appIn.flip()
                if (appIn.hasRemaining()) {
                    val chunk = ByteArray(appIn.remaining())
                    appIn.get(chunk)
                    collected.write(chunk)
                }
                when (res.status) {
                    SSLEngineResult.Status.OK -> {
                        if (netIn.position() == 0) needMoreBytes = true
                    }
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        appIn = ByteBuffer.allocate(sslEngine.session.applicationBufferSize.coerceAtLeast(appIn.capacity() * 2))
                    }
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                        needMoreBytes = true
                    }
                    SSLEngineResult.Status.CLOSED -> {
                        return collected.toByteArray()
                    }
                }
            }
            if (isCompleteHttpResponse(collected.toByteArray())) break
        }
        return collected.toByteArray()
    }

    private fun isCompleteHttpResponse(raw: ByteArray): Boolean {
        val sepIdx = indexOfCrlfCrlf(raw)
        if (sepIdx < 0) return false
        val headerStr = String(raw, 0, sepIdx, StandardCharsets.ISO_8859_1)
        val contentLength = headerStr.lines()
            .firstOrNull { it.lowercase().startsWith("content-length:") }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
        if (contentLength != null) {
            return raw.size >= sepIdx + 4 + contentLength
        }
        if (headerStr.lowercase().contains("transfer-encoding: chunked")) {
            val bodyBytes = raw.copyOfRange(sepIdx + 4, raw.size)
            val bodyText = String(bodyBytes, StandardCharsets.ISO_8859_1)
            return bodyText.contains("\r\n0\r\n\r\n") || bodyText.startsWith("0\r\n\r\n")
        }
        return false
    }

    private fun parseHttpDnsResponseBody(raw: ByteArray): ByteArray {
        val sepIdx = indexOfCrlfCrlf(raw)
        if (sepIdx < 0) throw IOException("Respuesta HTTP DoH incompleta")
        val headerStr = String(raw, 0, sepIdx, StandardCharsets.ISO_8859_1)
        val lines = headerStr.lines()
        val statusLine = lines.firstOrNull().orEmpty()
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        if (statusCode !in 200..299) {
            throw IOException("DoH HTTP $statusCode ($statusLine)")
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
        val isChunked = lines.any { it.lowercase().contains("transfer-encoding: chunked") }
        val bodyRaw = raw.copyOfRange(bodyStart, raw.size)
        return if (isChunked) decodeChunkedBody(bodyRaw) else bodyRaw
    }

    private fun decodeChunkedBody(chunked: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = 0
        while (pos < chunked.size) {
            val lineEnd = indexOfCrlf(chunked, pos)
            if (lineEnd < 0) break
            val sizeHex = String(chunked, pos, lineEnd - pos, StandardCharsets.ISO_8859_1)
                .substringBefore(';').trim()
            val chunkSize = sizeHex.toIntOrNull(16) ?: break
            pos = lineEnd + 2
            if (chunkSize == 0) break
            val available = (chunked.size - pos).coerceAtMost(chunkSize)
            out.write(chunked, pos, available)
            pos += available + 2
        }
        return out.toByteArray()
    }

    /**
     * Direct DoH probe ONLY for disconnected-mode Diagnostics (never used when VPN tunnel is active).
     */
    fun resolveViaDohDirect(queryBytes: ByteArray, dohUrl: String): ByteArray? {
        val url = dohUrl.ifBlank { DEFAULT_DOH_URL }
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-message")
                .post(queryBytes.toRequestBody("application/dns-message".toMediaType()))
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

    /**
     * Queries public exit IP through the active TunnelEngine over HTTP/1.1 using api.ipify.org.
     * Returns null if unreachable—never invents or guesses an IP address.
     */
    suspend fun fetchExitIpThroughTunnel(engine: TunnelEngine): String? = withContext(Dispatchers.IO) {
        runCatching {
            withTimeoutOrNull(4_000L) {
                engine.openTcpTunnel("api.ipify.org", 80).use { stream ->
                    val req = "GET / HTTP/1.1\r\nHost: api.ipify.org\r\nConnection: close\r\nUser-Agent: AIOTunnelVPN/1.0\r\n\r\n"
                    stream.outputStream.write(req.toByteArray(StandardCharsets.US_ASCII))
                    stream.outputStream.flush()
                    val raw = stream.inputStream.readBytes()
                    val text = String(raw, StandardCharsets.UTF_8)
                    val body = text.substringAfter("\r\n\r\n", "").trim()
                    if (body.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$|^[0-9a-fA-F:]+$"""))) {
                        body
                    } else null
                }
            }
        }.getOrNull()
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
            pos += 4 // QTYPE + QCLASS
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

    private fun indexOfCrlf(data: ByteArray, start: Int): Int {
        for (i in start..(data.size - 2)) {
            if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte()) return i
        }
        return -1
    }

    private fun isSameDohProvider(sniHost: String, secondaryIp: String): Boolean {
        val mapped = WELL_KNOWN_IP_TO_DOH_HOST[secondaryIp] ?: return false
        return mapped.equals(sniHost, ignoreCase = true)
    }

    companion object {
        private const val TAG = "DnsManager"
        const val DNS_QUERY_TIMEOUT_MS = 2_500L
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
            "dns.adguard-dns.com" to listOf("94.140.14.140", "94.140.14.141"),
            "dns.adguard.com" to listOf("94.140.14.140", "94.140.14.141"),
            "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112")
        )

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
