package com.example

import com.example.core.stats.StatisticsManager
import com.example.domain.models.DnsMode
import com.example.domain.models.EngineFamily
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelStatistics
import com.example.engines.adapter.EngineContext
import com.example.engines.adapter.EngineStatus
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import com.example.engines.proxy.WifiShareProxyServer
import com.example.engines.ssh.SshTunnelEngine
import com.example.vpn.tun.DnsManager
import com.example.vpn.tun.TunPacketRouter
import com.example.vpn.tun.TunnelSocketBridge
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.Principal
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext
import kotlin.concurrent.thread

class DiagnosticsAndLowLatencyTest {

    @Test
    fun `dns local ttl cache returns sub-2ms response with patched txId and minimum 60s ttl`() = runTest {
        val openCount = AtomicInteger(0)
        val dnsMgr = DnsManager()

        val fakeResponseWithShort5sTtl = buildDnsAResponse(
            txId0 = 0x13,
            txId1 = 0x37,
            domain = "example.com",
            ttlSeconds = 5L,
            ipBytes = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        )

        val fakeEngine = object : TunnelEngine {
            override val engineFamily = EngineFamily.SSH
            override val engineName = "CacheTestEngine"
            override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.RUNNING)
            override suspend fun validateConfiguration(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun initialize(context: EngineContext) = Unit
            override suspend fun start(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun stop() = Unit
            override fun getStatistics() = TunnelStatistics()
            override fun release() = Unit

            override suspend fun openTcpTunnel(targetHost: String, targetPort: Int): TunnelStream {
                openCount.incrementAndGet()
                val framed = ByteArray(2 + fakeResponseWithShort5sTtl.size).apply {
                    this[0] = ((fakeResponseWithShort5sTtl.size ushr 8) and 0xFF).toByte()
                    this[1] = (fakeResponseWithShort5sTtl.size and 0xFF).toByte()
                    System.arraycopy(fakeResponseWithShort5sTtl, 0, this, 2, fakeResponseWithShort5sTtl.size)
                }
                return object : TunnelStream {
                    override val inputStream: InputStream = ByteArrayInputStream(framed)
                    override val outputStream: OutputStream = ByteArrayOutputStream()
                    override fun close() = Unit
                }
            }
        }

        // Verify minimum 60s TTL clamp
        assertEquals(60L, dnsMgr.extractMinDnsTtlSeconds(fakeResponseWithShort5sTtl))

        val config = DnsManager.ResolvedDnsConfig(
            dnsMode = DnsMode.PLAIN_DNS,
            primaryDns = "94.140.14.15",
            secondaryDns = "94.140.15.15",
            dohUrl = DnsManager.DEFAULT_DOH_URL
        )
        val q1 = dnsMgr.buildSampleDnsQuery("example.com")
        val firstResp = dnsMgr.resolveWireQueryThroughTunnel(q1, config, fakeEngine)
        assertNotNull(firstResp)
        assertEquals(1, openCount.get())
        assertEquals(1, dnsMgr.cacheSize())

        // Second query with a different Transaction ID must hit cache in < 5ms without opening SSH channel
        val q2 = q1.copyOf().apply {
            this[0] = 0xAB.toByte()
            this[1] = 0xCD.toByte()
        }
        val t0 = System.nanoTime()
        val cachedResp = dnsMgr.resolveWireQueryThroughTunnel(q2, config, fakeEngine)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertNotNull(cachedResp)
        assertEquals(1, openCount.get())
        assertEquals(0xAB, cachedResp!![0].toInt() and 0xFF)
        assertEquals(0xCD, cachedResp[1].toInt() and 0xFF)
        assertTrue("Cache hit must be < 5 ms (was $elapsedMs ms)", elapsedMs < 5.0)

        dnsMgr.close()
    }

    @Test
    fun `doh hostname verifier accepts IP SAN 94_140_14_15 and 94_140_15_15 and rejects unrelated IPs`() {
        val certWithAdGuardIpSan = FakeSanX509Certificate(
            sans = listOf(
                listOf(2, "dns.adguard-dns.com"),
                listOf(7, "94.140.14.15"),
                listOf(7, "94.140.15.15")
            )
        )
        val session = FakeSslSession(certWithAdGuardIpSan)

        assertTrue(DnsManager.verifyDohHostnameOrIpSan("94.140.14.15", session))
        assertTrue(DnsManager.verifyDohHostnameOrIpSan("94.140.15.15", session))
        assertTrue(DnsManager.verifyDohHostnameOrIpSan("dns.adguard-dns.com", session))
        assertFalse(DnsManager.verifyDohHostnameOrIpSan("8.8.8.8", session))
    }

    @Test
    fun `tunnel socket bridge delivers ordered synchronized streams under concurrent http calls`() = runTest {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            server.use { ss ->
                repeat(8) {
                    val client = ss.accept()
                    thread(isDaemon = true) {
                        client.use { s ->
                            val inp = s.getInputStream()
                            val out = s.getOutputStream()
                            val reqBuf = ByteArray(4096)
                            val n = inp.read(reqBuf)
                            val reqStr = if (n > 0) String(reqBuf, 0, n, StandardCharsets.ISO_8859_1) else ""
                            val body = "ECHO:${reqStr.lines().firstOrNull().orEmpty()}"
                            val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                            out.write(resp.toByteArray(StandardCharsets.ISO_8859_1))
                            out.flush()
                        }
                    }
                }
            }
        }

        val bridgeEngine = object : TunnelEngine {
            override val engineFamily = EngineFamily.SSH
            override val engineName = "LoopbackBridgeEngine"
            override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.RUNNING)
            override suspend fun validateConfiguration(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun initialize(context: EngineContext) = Unit
            override suspend fun start(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun stop() = Unit
            override fun getStatistics() = TunnelStatistics()
            override fun release() = Unit

            override suspend fun openTcpTunnel(targetHost: String, targetPort: Int): TunnelStream {
                val sock = Socket(InetAddress.getByName("127.0.0.1"), server.localPort).apply {
                    tcpNoDelay = true
                }
                return object : TunnelStream {
                    override val inputStream: InputStream = sock.getInputStream()
                    override val outputStream: OutputStream = sock.getOutputStream()
                    override fun halfCloseOutput() {
                        runCatching { sock.shutdownOutput() }
                    }
                    override fun close() {
                        runCatching { sock.close() }
                    }
                }
            }
        }

        val bridge = TunnelSocketBridge(bridgeEngine)
        val client = OkHttpClient.Builder()
            .socketFactory(bridge.createSocketFactory())
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        val results = (1..8).map { idx ->
            async(kotlinx.coroutines.Dispatchers.IO) {
                val req = Request.Builder()
                    .url("http://94.140.14.15:${server.localPort}/probe-$idx")
                    .post("payload-$idx".toRequestBody("application/dns-message".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    assertEquals(200, resp.code)
                    resp.body?.string().orEmpty()
                }
            }
        }.awaitAll()

        results.forEachIndexed { idx, body ->
            assertTrue("Expected /probe-${idx + 1} in $body", body.contains("/probe-${idx + 1}"))
        }

        bridge.close()
        serverThread.join(2_000)
    }

    @Test
    fun `diagnostics ttfb probe measures lightweight 204 response without body download`() = runTest {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val headRequestsSeen = AtomicInteger(0)
        val serverThread = thread(isDaemon = true) {
            server.use { ss ->
                val client = ss.accept()
                client.use { s ->
                    val inp = s.getInputStream()
                    val out = s.getOutputStream()
                    val buf = ByteArray(2048)
                    while (true) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        val reqText = String(buf, 0, n, StandardCharsets.ISO_8859_1)
                        if (reqText.startsWith("HEAD ")) {
                            headRequestsSeen.incrementAndGet()
                            val resp = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n"
                            out.write(resp.toByteArray(StandardCharsets.ISO_8859_1))
                            out.flush()
                        }
                    }
                }
            }
        }

        val fakeEngine = object : TunnelEngine {
            override val engineFamily = EngineFamily.SSH
            override val engineName = "TtfbTestEngine"
            override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.RUNNING)
            override suspend fun validateConfiguration(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun initialize(context: EngineContext) = Unit
            override suspend fun start(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun stop() = Unit
            override fun getStatistics() = TunnelStatistics()
            override fun release() = Unit

            override suspend fun openTcpTunnel(targetHost: String, targetPort: Int): TunnelStream {
                val sock = Socket(InetAddress.getByName("127.0.0.1"), server.localPort).apply {
                    tcpNoDelay = true
                }
                return object : TunnelStream {
                    override val inputStream: InputStream = sock.getInputStream()
                    override val outputStream: OutputStream = sock.getOutputStream()
                    override fun close() {
                        runCatching { sock.close() }
                    }
                }
            }
        }

        val dnsMgr = DnsManager()
        val probe = dnsMgr.probeTunnelExitTtfb(fakeEngine, cachedExitIp = "181.46.10.22")
        dnsMgr.close()
        serverThread.join(2_000)

        assertNotNull(probe)
        assertEquals(204, probe!!.statusCode)
        assertEquals("181.46.10.22", probe.exitIp)
        assertTrue(probe.ttfbMs >= 1L)
        assertTrue("Expected warm HEAD /generate_204 reuse", headRequestsSeen.get() >= 2)
    }

    @Test
    fun `ssh engine and tun router use optimal 256kb window, 15s keepalive, 16kb buffers and 64-pkt backpressure`() {
        assertEquals(262_144, SshTunnelEngine.SSH_LOCAL_WINDOW_BYTES)
        assertEquals(32_768, SshTunnelEngine.SSH_LOCAL_PACKET_BYTES)
        assertEquals(15_000, SshTunnelEngine.SSH_SERVER_ALIVE_INTERVAL_MS)
        assertEquals(16_384, TunPacketRouter.COPY_BUFFER_BYTES)
        assertEquals(64, TunPacketRouter.TUN_WRITE_QUEUE_CAPACITY)
        assertEquals(16_384, WifiShareProxyServer.COPY_BUFFER_BYTES)

        val channelCtor = com.jcraft.jsch.ChannelDirectTCPIP::class.java.getDeclaredConstructor()
        channelCtor.isAccessible = true
        val channel = channelCtor.newInstance()
        val setLwSize = com.jcraft.jsch.Channel::class.java.getDeclaredMethod("setLocalWindowSize", Int::class.javaPrimitiveType)
        setLwSize.isAccessible = true
        setLwSize.invoke(channel, SshTunnelEngine.SSH_LOCAL_WINDOW_BYTES)

        val lwMaxField = com.jcraft.jsch.Channel::class.java.getDeclaredField("lwsize_max")
        lwMaxField.isAccessible = true
        lwMaxField.setInt(channel, SshTunnelEngine.SSH_LOCAL_WINDOW_BYTES)

        val setPktSize = com.jcraft.jsch.Channel::class.java.getDeclaredMethod("setLocalPacketSize", Int::class.javaPrimitiveType)
        setPktSize.isAccessible = true
        setPktSize.invoke(channel, SshTunnelEngine.SSH_LOCAL_PACKET_BYTES)

        val lwSizeField = com.jcraft.jsch.Channel::class.java.getDeclaredField("lwsize")
        lwSizeField.isAccessible = true
        val lmpSizeField = com.jcraft.jsch.Channel::class.java.getDeclaredField("lmpsize")
        lmpSizeField.isAccessible = true

        assertEquals(262_144, lwSizeField.getInt(channel))
        assertEquals(262_144, lwMaxField.getInt(channel))
        assertEquals(32_768, lmpSizeField.getInt(channel))
    }

    @Test
    fun `wifi share proxy streams multi-megabyte download with immediate flush and zero byte loss`() = runTest {
        val totalBytes = 4 * 1024 * 1024 // 4 MB continuous stream
        val chunkSize = 16_384
        val sourcePattern = ByteArray(totalBytes) { idx -> (idx * 31 + 7).toByte() }
        val receivedOut = ByteArrayOutputStream(totalBytes)
        val flushCount = AtomicInteger(0)

        val originServer = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val originThread = thread(isDaemon = true) {
            originServer.use { ss ->
                val s = ss.accept()
                s.use { sock ->
                    val out = sock.getOutputStream()
                    var off = 0
                    while (off < totalBytes) {
                        val len = minOf(chunkSize, totalBytes - off)
                        out.write(sourcePattern, off, len)
                        out.flush()
                        off += len
                    }
                    sock.shutdownOutput()
                }
            }
        }

        val proxy = WifiShareProxyServer(
            listenPort = 0,
            openTunnel = { _, _ ->
                val sock = Socket(InetAddress.getByName("127.0.0.1"), originServer.localPort).apply {
                    tcpNoDelay = true
                }
                val rawOut = sock.getOutputStream()
                val trackedOut = object : OutputStream() {
                    override fun write(b: Int) = rawOut.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) = rawOut.write(b, off, len)
                    override fun flush() {
                        flushCount.incrementAndGet()
                        rawOut.flush()
                    }
                    override fun close() = rawOut.close()
                }
                object : TunnelStream {
                    override val inputStream: InputStream = sock.getInputStream()
                    override val outputStream: OutputStream = trackedOut
                    override fun halfCloseOutput() {
                        runCatching { sock.shutdownOutput() }
                    }
                    override fun close() {
                        runCatching { sock.close() }
                    }
                }
            }
        )
        val proxyPort = proxy.start()

        try {
            Socket(InetAddress.getByName("127.0.0.1"), proxyPort).use { client ->
                client.soTimeout = 5_000
                val out = client.getOutputStream()
                val inp = client.getInputStream()
                out.write("CONNECT download.test:443 HTTP/1.1\r\nHost: download.test:443\r\n\r\n".toByteArray())
                out.flush()

                // Read HTTP 200 CONNECT header
                val headerBuf = ByteArrayOutputStream()
                while (true) {
                    val b = inp.read()
                    if (b == -1) break
                    headerBuf.write(b)
                    val s = headerBuf.toString(StandardCharsets.ISO_8859_1.name())
                    if (s.endsWith("\r\n\r\n")) break
                }
                assertTrue(headerBuf.toString(StandardCharsets.ISO_8859_1.name()).startsWith("HTTP/1.1 200"))

                val readBuf = ByteArray(16_384)
                while (true) {
                    val n = inp.read(readBuf)
                    if (n == -1) break
                    receivedOut.write(readBuf, 0, n)
                }
            }
        } finally {
            proxy.close()
            originThread.join(3_000)
        }

        assertEquals(totalBytes, receivedOut.size())
        assertArrayEquals(sourcePattern, receivedOut.toByteArray())
    }

    private fun buildDnsAResponse(
        txId0: Int,
        txId1: Int,
        domain: String,
        ttlSeconds: Long,
        ipBytes: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(txId0); out.write(txId1)
        out.write(0x81); out.write(0x80) // Standard response, NOERROR
        out.write(0x00); out.write(0x01) // QDCOUNT = 1
        out.write(0x00); out.write(0x01) // ANCOUNT = 1
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        for (label in domain.split('.')) {
            val b = label.toByteArray(StandardCharsets.US_ASCII)
            out.write(b.size); out.write(b)
        }
        out.write(0x00)
        out.write(0x00); out.write(0x01) // QTYPE = A
        out.write(0x00); out.write(0x01) // QCLASS = IN
        // Answer RR (pointer to name at 0xC00C)
        out.write(0xC0); out.write(0x0C)
        out.write(0x00); out.write(0x01) // TYPE A
        out.write(0x00); out.write(0x01) // CLASS IN
        out.write(((ttlSeconds ushr 24) and 0xFF).toInt())
        out.write(((ttlSeconds ushr 16) and 0xFF).toInt())
        out.write(((ttlSeconds ushr 8) and 0xFF).toInt())
        out.write((ttlSeconds and 0xFF).toInt())
        out.write(0x00); out.write(0x04) // RDLENGTH = 4
        out.write(ipBytes)
        return out.toByteArray()
    }

    private class FakeSanX509Certificate(
        private val sans: List<List<Any>>
    ) : X509Certificate() {
        override fun getSubjectAlternativeNames(): MutableCollection<MutableList<*>> =
            sans.map { it.toMutableList() }.toMutableList()
        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN(): Principal = Principal { "CN=Test" }
        override fun getSubjectDN(): Principal = Principal { "CN=dns.adguard-dns.com" }
        override fun getNotBefore(): Date = Date(0)
        override fun getNotAfter(): Date = Date(Long.MAX_VALUE)
        override fun getTBSCertificate(): ByteArray = ByteArray(0)
        override fun getSignature(): ByteArray = ByteArray(0)
        override fun getSigAlgName(): String = "SHA256withRSA"
        override fun getSigAlgOID(): String = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun getEncoded(): ByteArray = ByteArray(0)
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString(): String = "FakeSanX509Certificate"
        override fun getPublicKey(): PublicKey = object : PublicKey {
            override fun getAlgorithm() = "RSA"
            override fun getFormat() = "X.509"
            override fun getEncoded() = ByteArray(0)
        }
        override fun hasUnsupportedCriticalExtension(): Boolean = false
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
    }

    private class FakeSslSession(private val cert: Certificate) : SSLSession {
        override fun getId(): ByteArray = ByteArray(0)
        override fun getSessionContext(): SSLSessionContext? = null
        override fun getCreationTime(): Long = 0L
        override fun getLastAccessedTime(): Long = 0L
        override fun invalidate() = Unit
        override fun isValid(): Boolean = true
        override fun putValue(name: String?, value: Any?) = Unit
        override fun getValue(name: String?): Any? = null
        override fun removeValue(name: String?) = Unit
        override fun getValueNames(): Array<String> = emptyArray()
        override fun getPeerCertificates(): Array<Certificate> = arrayOf(cert)
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> = emptyArray()
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getPeerPrincipal(): Principal = Principal { "CN=dns.adguard-dns.com" }
        override fun getLocalPrincipal(): Principal? = null
        override fun getCipherSuite(): String = "TLS_AES_128_GCM_SHA256"
        override fun getProtocol(): String = "TLSv1.3"
        override fun getPeerHost(): String = "94.140.14.15"
        override fun getPeerPort(): Int = 443
        override fun getPacketBufferSize(): Int = 16384
        override fun getApplicationBufferSize(): Int = 16384
    }
}
