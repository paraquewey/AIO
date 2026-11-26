package com.example

import com.example.core.logger.AppLogger
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
}
