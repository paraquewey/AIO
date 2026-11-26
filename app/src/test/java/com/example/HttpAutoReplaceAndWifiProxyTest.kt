package com.example

import com.example.core.logger.AppLogger
import com.example.core.network.VpnRouteHelper
import com.example.core.stats.StatisticsManager
import com.example.domain.models.EngineFamily
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import com.example.domain.models.TunnelStatistics
import com.example.engines.adapter.EngineContext
import com.example.engines.adapter.EngineStatus
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import com.example.engines.http.RawTcpPayloadExecutor
import com.example.engines.proxy.WifiShareProxyServer
import com.example.vpn.tun.DnsManager
import org.junit.Assert.assertFalse
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class HttpAutoReplaceAndWifiProxyTest {

    @Test
    fun `http 503 auto-replace drains headers without consuming SSH-2_0 banner`() {
        AppLogger.clear()
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            server.use { ss ->
                ss.accept().use { client ->
                    val input = client.getInputStream()
                    val buf = ByteArray(1024)
                    input.read(buf)
                    val responseWithBanner =
                        "HTTP/1.1 503 Service Unavailable\r\nServer: proxy-edge\r\nContent-Length: 42\r\n\r\n" +
                            "SSH-2.0-OpenSSH_9.6p1 Ubuntu\r\n"
                    client.getOutputStream().write(responseWithBanner.toByteArray(StandardCharsets.ISO_8859_1))
                    client.getOutputStream().flush()
                }
            }
        }

        val profile = TunnelProfile(
            name = "AutoReplace 503",
            protocol = TunnelProtocol.SSH,
            host = "127.0.0.1",
            port = server.localPort,
            rawHttpPayload = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]"
        )

        val sock = RawTcpPayloadExecutor.connectRawSocket(
            host = "127.0.0.1",
            port = server.localPort,
            useTls = false,
            connectTimeoutMs = 3_000,
            readTimeoutMs = 3_000
        )
        sock.use { s ->
            val bufferedIn = BufferedInputStream(s.getInputStream())
            val status = RawTcpPayloadExecutor.executeRawPayloadHandshake(
                socket = s,
                bufferedIn = bufferedIn,
                profile = profile,
                secrets = ProfileSecrets(),
                baseReadTimeoutMs = 3_000
            )
            assertTrue(status.contains("Auto-Replace 503"))
            val remainingBanner = String(bufferedIn.readBytes(), StandardCharsets.ISO_8859_1)
            assertEquals("SSH-2.0-OpenSSH_9.6p1 Ubuntu\r\n", remainingBanner)
        }
        serverThread.join(2_000)

        assertTrue(
            AppLogger.logs.value.any {
                it.message.contains("HTTP/1.1 503 recibido -> Auto-Replace a 200 OK")
            }
        )
        AppLogger.clear()
    }

    @Test
    fun `chained split payload accepts multiple 403 and 502 responses then stops before SSH banner`() {
        AppLogger.clear()
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            server.use { ss ->
                ss.accept().use { client ->
                    val input = client.getInputStream()
                    val buf = ByteArray(1024)
                    val acc = ByteArrayOutputStream()
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        acc.write(buf, 0, n)
                        val text = acc.toString(StandardCharsets.ISO_8859_1.name())
                        if (text.contains("PUT /step2")) break
                    }
                    val resp =
                        "HTTP/1.1 403 Forbidden\r\nConnection: keep-alive\r\n\r\n" +
                            "HTTP/1.1 502 Bad Gateway\r\nProxy-Connection: keep-alive\r\n\r\n" +
                            "SSH-2.0-dropbear_2022.83\r\n"
                    client.getOutputStream().write(resp.toByteArray(StandardCharsets.ISO_8859_1))
                    client.getOutputStream().flush()
                }
            }
        }

        val profile = TunnelProfile(
            name = "Split AutoReplace",
            protocol = TunnelProtocol.SSH,
            host = "127.0.0.1",
            port = server.localPort,
            rawHttpPayload = "GET /step1 HTTP/1.1[crlf]Host: [host][crlf][crlf][split]" +
                "PUT /step2 HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]"
        )

        val sock = RawTcpPayloadExecutor.connectRawSocket(
            host = "127.0.0.1",
            port = server.localPort,
            useTls = false,
            connectTimeoutMs = 3_000,
            readTimeoutMs = 3_000
        )
        sock.use { s ->
            val bufferedIn = BufferedInputStream(s.getInputStream())
            val status = RawTcpPayloadExecutor.executeRawPayloadHandshake(
                socket = s,
                bufferedIn = bufferedIn,
                profile = profile,
                secrets = ProfileSecrets(),
                baseReadTimeoutMs = 3_000
            )
            assertTrue(status.contains("Auto-Replace 502"))
            val remainingBanner = String(bufferedIn.readBytes(), StandardCharsets.ISO_8859_1)
            assertEquals("SSH-2.0-dropbear_2022.83\r\n", remainingBanner)
        }
        serverThread.join(2_000)

        val logs = AppLogger.logs.value.map { it.message }
        assertTrue(logs.any { it.contains("HTTP/1.1 403 recibido -> Auto-Replace a 200 OK") })
        assertTrue(logs.any { it.contains("HTTP/1.1 502 recibido -> Auto-Replace a 200 OK") })
        AppLogger.clear()
    }

    @Test
    fun `wifi share proxy server handles both HTTP CONNECT and SOCKS5 through tunnel`() = runTest {
        val stats = StatisticsManager()
        val proxyServer = WifiShareProxyServer(
            listenPort = 0,
            openTunnel = { targetHost, targetPort ->
                val payload = "TUNNELED:$targetHost:$targetPort".toByteArray(StandardCharsets.UTF_8)
                object : TunnelStream {
                    override val inputStream: InputStream = ByteArrayInputStream(payload)
                    override val outputStream: OutputStream = ByteArrayOutputStream()
                    override fun close() = Unit
                }
            },
            statisticsManager = stats
        )
        val port = proxyServer.start()
        assertTrue(port > 0)

        // 1. Test HTTP CONNECT client
        Socket(InetAddress.getByName("127.0.0.1"), port).use { client ->
            client.soTimeout = 2_000
            val out = client.getOutputStream()
            val inp = client.getInputStream()
            out.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()

            val response = String(inp.readBytes(), StandardCharsets.UTF_8)
            assertTrue(response.startsWith("HTTP/1.1 200 Connection Established"))
            assertTrue(response.contains("TUNNELED:example.com:443"))
        }

        // 2. Test SOCKS5 client on the exact same port
        Socket(InetAddress.getByName("127.0.0.1"), port).use { client ->
            client.soTimeout = 2_000
            val out = client.getOutputStream()
            val inp = DataInputStream(client.getInputStream())
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            assertEquals(0x05, inp.readUnsignedByte())
            assertEquals(0x00, inp.readUnsignedByte())

            val hostBytes = "dns.google".toByteArray(StandardCharsets.UTF_8)
            val req = ByteArrayOutputStream().apply {
                write(0x05); write(0x01); write(0x00); write(0x03)
                write(hostBytes.size); write(hostBytes)
                write(0x00); write(53)
            }.toByteArray()
            out.write(req)
            out.flush()

            assertEquals(0x05, inp.readUnsignedByte())
            assertEquals(0x00, inp.readUnsignedByte())
            inp.readUnsignedByte(); inp.readUnsignedByte()
            val bndIp = ByteArray(4)
            inp.readFully(bndIp)
            inp.readUnsignedShort()
            val data = String(inp.readBytes(), StandardCharsets.UTF_8)
            assertEquals("TUNNELED:dns.google:53", data)
        }

        proxyServer.close()
    }

    @Test
    fun `exit ip lookup parses country flag emoji and location over TunnelEngine`() = runTest {
        assertEquals("🇦🇷", DnsManager.countryCodeToFlagEmoji("AR"))
        assertEquals("🇺🇸", DnsManager.countryCodeToFlagEmoji("us"))
        assertEquals("🇧🇷", DnsManager.countryCodeToFlagEmoji("BR"))
        assertEquals("🌐", DnsManager.countryCodeToFlagEmoji(null))

        val jsonBody = """{"status":"success","country":"Argentina","countryCode":"AR","city":"Buenos Aires","query":"181.46.10.22"}"""
        val httpResp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${jsonBody.length}\r\n\r\n$jsonBody"

        val fakeEngine = object : TunnelEngine {
            override val engineFamily = EngineFamily.SSH
            override val engineName = "FakeGeoEngine"
            override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.RUNNING)
            override suspend fun validateConfiguration(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun initialize(context: EngineContext) = Unit
            override suspend fun start(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun stop() = Unit
            override fun getStatistics() = TunnelStatistics()
            override fun release() = Unit
            override suspend fun openTcpTunnel(targetHost: String, targetPort: Int): TunnelStream {
                return object : TunnelStream {
                    override val inputStream: InputStream = ByteArrayInputStream(httpResp.toByteArray(StandardCharsets.UTF_8))
                    override val outputStream: OutputStream = ByteArrayOutputStream()
                    override fun close() = Unit
                }
            }
        }

        val dnsMgr = DnsManager()
        val info = dnsMgr.fetchExitIpInfoThroughTunnel(fakeEngine)
        assertNotNull(info)
        assertEquals("181.46.10.22", info?.ip)
        assertEquals("Argentina", info?.country)
        assertEquals("AR", info?.countryCode)
        assertEquals("Buenos Aires", info?.city)
    }

    @Test
    fun `statistics manager computes peak speeds and resets peaks on new session`() {
        val statsMgr = StatisticsManager()
        statsMgr.recordBytesReceived(5_000L)
        statsMgr.recordBytesSent(2_000L)

        // Invoke publishSnapshot via reflection or start/stop session behavior
        val publish = StatisticsManager::class.java.getDeclaredMethod("publishSnapshot", Boolean::class.javaPrimitiveType)
        publish.isAccessible = true
        publish.invoke(statsMgr, false)

        assertEquals(5_000L, statsMgr.stats.value.peakDownloadBytesPerSec)
        assertEquals(2_000L, statsMgr.stats.value.peakUploadBytesPerSec)

        // Lower subsequent interval must preserve higher peak
        statsMgr.recordBytesReceived(1_000L)
        statsMgr.recordBytesSent(500L)
        publish.invoke(statsMgr, false)
        assertEquals(1_000L, statsMgr.stats.value.downloadBytesPerSec)
        assertEquals(5_000L, statsMgr.stats.value.peakDownloadBytesPerSec)
        assertEquals(2_000L, statsMgr.stats.value.peakUploadBytesPerSec)

        // Starting a fresh session resets peaks cleanly
        statsMgr.startSession()
        assertEquals(0L, statsMgr.stats.value.peakDownloadBytesPerSec)
        assertEquals(0L, statsMgr.stats.value.peakUploadBytesPerSec)
        statsMgr.stopSession()
    }

    @Test
    fun `vpn route helper excludes LAN, CGNAT, loopback, multicast, and masks all CIDR host bits`() {
        val allowed = VpnRouteHelper.computeAllowedPublicIpv4Routes()
        assertTrue(allowed.isNotEmpty())

        // Every emitted CIDR MUST have 0 host bits and pass VpnService.Builder checks
        allowed.forEach { cidr ->
            val mask = VpnRouteHelper.maskForPrefix(cidr.prefixLength)
            assertEquals(
                "Host bits must be zero for $cidr",
                cidr.addressLong and mask,
                cidr.addressLong
            )
            assertTrue(
                "Emitted route $cidr must pass VpnService.Builder validation",
                VpnRouteHelper.canPassToVpnBuilder(cidr, isExcludeRoute = false)
            )
        }

        // Loopback 127.0.0.0/8 must be rejected from VpnService.Builder calls (Android throws Bad address)
        assertFalse(
            VpnRouteHelper.canPassToVpnBuilder(
                VpnRouteHelper.cidr("127.0.0.0", 8),
                isExcludeRoute = true
            )
        )
        assertTrue(
            VpnRouteHelper.canPassToVpnBuilder(
                VpnRouteHelper.cidr("100.64.0.0", 10),
                isExcludeRoute = true
            )
        )

        // Masking unmasked host IPs into canonical network CIDRs
        assertEquals("192.168.1.0/24", VpnRouteHelper.cidr("192.168.1.55", 24).toString())
        assertEquals("100.64.0.0/10", VpnRouteHelper.cidr("100.85.227.228", 10).toString())
        assertEquals("0.0.0.0/0", VpnRouteHelper.cidr("192.168.1.55", 0).toString())

        // IPv4 sanitization strips whitespace & :port and rejects invalid/loopback/multicast DNS targets
        assertEquals("94.140.14.15", VpnRouteHelper.sanitizeIpv4Address("  94.140.14.15:53 "))
        assertEquals("1.1.1.1", VpnRouteHelper.sanitizeIpv4Address("[1.1.1.1]"))
        assertEquals(null, VpnRouteHelper.sanitizeIpv4Address("127.0.0.1"))
        assertEquals(null, VpnRouteHelper.sanitizeIpv4Address("224.0.0.1"))
        assertEquals(null, VpnRouteHelper.sanitizeIpv4Address("999.1.1.1"))

        fun isRoutedToVpn(ip: String): Boolean {
            val ipLong = VpnRouteHelper.ipv4ToLongOrNull(ip)!!
            return allowed.any { it.contains(ipLong) }
        }

        // Local, Tethering, CGNAT, Unspecified, Multicast, and Reserved IPs MUST be excluded
        listOf(
            "0.0.0.1", "10.0.0.1", "10.255.255.254",
            "100.64.0.1", "100.85.227.228", "100.127.255.254",
            "127.0.0.1", "169.254.10.20",
            "172.16.0.1", "172.31.255.254",
            "192.168.0.1", "192.168.43.1",
            "224.0.0.251", "239.255.255.250", "255.255.255.255"
        ).forEach { localIp ->
            assertFalse("Local/CGNAT/Reserved IP $localIp should NOT enter TUN", isRoutedToVpn(localIp))
            assertTrue("isLocalOrCgnatHost($localIp) must be true", VpnRouteHelper.isLocalOrCgnatHost(localIp))
        }

        // Public IPs including CGNAT boundary neighbors MUST enter TUN
        listOf(
            "1.1.1.1", "8.8.8.8", "9.255.255.255", "11.0.0.1",
            "100.63.255.255", "100.128.0.1",
            "172.15.255.255", "172.32.0.1",
            "192.167.255.255", "192.169.0.1",
            "223.255.255.254"
        ).forEach { publicIp ->
            assertTrue("Public IP $publicIp MUST enter TUN", isRoutedToVpn(publicIp))
            assertFalse("isLocalOrCgnatHost($publicIp) must be false", VpnRouteHelper.isLocalOrCgnatHost(publicIp))
        }

        // Verify exact non-overlapping mathematical complement of 2^32
        val totalAllowed = allowed.sumOf { 1L shl (32 - it.prefixLength) }
        val totalExcluded = VpnRouteHelper.ANDROID_12_EXCLUDED_CIDRS.sumOf { 1L shl (32 - it.prefixLength) }
        assertEquals(1L shl 32, totalAllowed + totalExcluded)
    }

    @Test
    fun `wifi proxy handles plain HTTP forwarding, local LAN bypass protect(), and 256KB downloads`() = runTest {
        // Local HTTP server simulating e.g. 100.85.227.228:8181/share on loopback
        val localServer = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val largePayload = ByteArray(262_144) { idx -> ('A'.code + (idx % 26)).toByte() }
        val receivedHeadRef = java.util.concurrent.atomic.AtomicReference("")
        val localServerThread = thread(isDaemon = true) {
            localServer.use { ss ->
                ss.accept().use { client ->
                    val raw = ByteArrayOutputStream()
                    val buf = ByteArray(2048)
                    val inp = client.getInputStream()
                    while (true) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        raw.write(buf, 0, n)
                        if (raw.toString(StandardCharsets.ISO_8859_1.name()).contains("\r\n\r\n")) break
                    }
                    receivedHeadRef.set(raw.toString(StandardCharsets.ISO_8859_1.name()))
                    val out = client.getOutputStream()
                    val header = "HTTP/1.1 200 OK\r\nContent-Length: ${largePayload.size}\r\nConnection: close\r\n\r\n"
                    out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
                    out.write(largePayload)
                    out.flush()
                }
            }
        }

        val remoteTunnelCalls = AtomicInteger(0)
        val localSocketProtected = AtomicBoolean(false)
        val proxyServer = WifiShareProxyServer(
            listenPort = 0,
            openTunnel = { _, _ ->
                remoteTunnelCalls.incrementAndGet()
                error("Should not hit remote SSH tunnel for local bypass IP!")
            },
            protectSocket = {
                localSocketProtected.set(true)
                true
            },
            isBypassLanEnabled = { true }
        )
        val proxyPort = proxyServer.start()

        Socket(InetAddress.getByName("127.0.0.1"), proxyPort).use { client ->
            client.soTimeout = 5_000
            val out = client.getOutputStream()
            val req = "GET http://127.0.0.1:${localServer.localPort}/share?file=big HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${localServer.localPort}\r\n" +
                "Proxy-Connection: keep-alive\r\n" +
                "Proxy-Authorization: Basic dXNlcjpwYXNz\r\n\r\n"
            out.write(req.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()

            val fullResp = client.getInputStream().readBytes()
            val sep = "\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
            val headerEnd = fullResp.indices.first { idx ->
                idx + sep.size <= fullResp.size &&
                    sep.indices.all { j -> fullResp[idx + j] == sep[j] }
            }
            val bodyBytes = fullResp.copyOfRange(headerEnd + sep.size, fullResp.size)
            assertEquals(largePayload.size, bodyBytes.size)
            assertTrue(largePayload.contentEquals(bodyBytes))
        }

        localServerThread.join(3_000)
        proxyServer.close()

        val forwardedHead = receivedHeadRef.get()
        assertTrue(forwardedHead.startsWith("GET /share?file=big HTTP/1.1\r\n"))
        assertTrue(forwardedHead.contains("Connection: keep-alive\r\n"))
        assertFalse(forwardedHead.contains("Proxy-Connection:", ignoreCase = true))
        assertFalse(forwardedHead.contains("Proxy-Authorization:", ignoreCase = true))
        assertTrue("Local bypass socket must be protected via VpnService.protect()", localSocketProtected.get())
        assertEquals("Local LAN/CGNAT target must bypass remote SSH tunnel", 0, remoteTunnelCalls.get())
    }
}
