package com.example

import com.example.core.logger.AppLogger
import com.example.data.import_export.ConfigParserAndExporter
import com.example.domain.models.AppSettings
import com.example.domain.models.DnsMode
import com.example.domain.models.EngineFamily
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import com.example.domain.models.TunnelStatistics
import com.example.engines.adapter.EngineContext
import com.example.engines.adapter.EngineStatus
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import com.example.engines.http.HttpConnectProxyEngine
import com.example.vpn.tun.DnsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class DnsAndHttpTunnelTest {

    @Test
    fun `resolveActiveDnsConfig inherits global dns1 and dns2 unless profile overrides`() {
        val globalSettings = AppSettings(
            defaultDnsMode = DnsMode.DNS_OVER_HTTPS,
            defaultDnsServer = "94.140.14.15",
            defaultDnsServerSecondary = "94.140.15.15"
        )
        val defaultProfile = TunnelProfile(
            name = "Inherit",
            protocol = TunnelProtocol.HTTP_PROXY,
            host = "proxy.example.com",
            port = 8080
        )
        val inherited = DnsManager.resolveActiveDnsConfig(defaultProfile, globalSettings)
        assertEquals("94.140.14.15", inherited.primaryDns)
        assertEquals("94.140.15.15", inherited.secondaryDns)

        val customProfile = defaultProfile.copy(
            useCustomDns = true,
            dnsServer = "8.8.8.8",
            dnsServerSecondary = "8.8.4.4"
        )
        val overridden = DnsManager.resolveActiveDnsConfig(customProfile, globalSettings)
        assertEquals("8.8.8.8", overridden.primaryDns)
        assertEquals("8.8.4.4", overridden.secondaryDns)
    }

    @Test
    fun `dns over tcp routes strictly inside TunnelEngine without protect and falls back to dns2`() = runTest {
        val protectCalled = AtomicInteger(0)
        val dnsMgr = DnsManager(
            protectSocket = {
                protectCalled.incrementAndGet()
                true
            },
            protectDatagramSocket = {
                protectCalled.incrementAndGet()
                true
            }
        )

        val expectedDnsReply = byteArrayOf(
            0x13, 0x37, 0x81.toByte(), 0x80.toByte(),
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x61, 0x00, 0x00, 0x01, 0x00, 0x01
        )
        val attemptedTargets = mutableListOf<String>()

        val fakeEngine = object : TunnelEngine {
            override val engineFamily = EngineFamily.HTTP_CONNECT
            override val engineName = "FakeTunnel"
            override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.RUNNING)
            override suspend fun validateConfiguration(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun initialize(context: EngineContext) = Unit
            override suspend fun start(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun stop() = Unit
            override fun getStatistics() = TunnelStatistics()
            override fun release() = Unit

            override suspend fun openTcpTunnel(targetHost: String, targetPort: Int): TunnelStream {
                attemptedTargets.add("$targetHost:$targetPort")
                if (targetHost == "94.140.14.15") {
                    throw IOException("Primary DNS unreachable")
                }
                val outBuf = ByteArrayOutputStream()
                val framedReply = ByteArray(2 + expectedDnsReply.size).apply {
                    this[0] = ((expectedDnsReply.size ushr 8) and 0xFF).toByte()
                    this[1] = (expectedDnsReply.size and 0xFF).toByte()
                    System.arraycopy(expectedDnsReply, 0, this, 2, expectedDnsReply.size)
                }
                val inStream = ByteArrayInputStream(framedReply)
                return object : TunnelStream {
                    override val inputStream: InputStream = inStream
                    override val outputStream: OutputStream = outBuf
                    override fun close() = Unit
                }
            }
        }

        val config = DnsManager.ResolvedDnsConfig(
            dnsMode = DnsMode.PLAIN_DNS,
            primaryDns = "94.140.14.15",
            secondaryDns = "94.140.15.15",
            dohUrl = "https://cloudflare-dns.com/dns-query"
        )
        val query = dnsMgr.buildSampleDnsQuery("example.com")
        val result = dnsMgr.resolveViaTunnelTcpWithFallback(
            queryBytes = query,
            primaryDns = config.primaryDns,
            secondaryDns = config.secondaryDns,
            engine = fakeEngine
        )

        assertEquals(0, protectCalled.get())
        assertEquals(listOf("94.140.14.15:53", "94.140.15.15:53"), attemptedTargets)
        assertTrue(result.usedFallback)
        assertNotNull(result.responseBytes)
        assertArrayEquals(expectedDnsReply, result.responseBytes)
    }

    @Test
    fun `app logger rate limiting prevents dns error flood`() {
        AppLogger.clear()
        repeat(25) {
            AppLogger.warnRateLimited("DnsManager", "flood_test_key", "Timeout DNS-over-TCP (94.140.14.15:53)")
        }
        assertEquals(1, AppLogger.logs.value.size)
        AppLogger.clear()
    }

    @Test
    fun `http proxy engine builds custom GET websocket request and accepts 101 switching protocols without tls`() = runTest {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val receivedRequest = StringBuilder()
        val serverThread = thread(isDaemon = true) {
            server.use { ss ->
                ss.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                    while (true) {
                        val line = reader.readLine() ?: break
                        receivedRequest.append(line).append('\n')
                        if (line.isEmpty()) break
                    }
                    val resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
                    client.getOutputStream().write(resp.toByteArray(StandardCharsets.ISO_8859_1))
                    client.getOutputStream().flush()
                }
            }
        }

        val profile = TunnelProfile(
            name = "WS Proxy",
            protocol = TunnelProtocol.HTTP_PROXY,
            host = "127.0.0.1",
            port = server.localPort,
            tlsEnabled = false,
            httpRequestMethod = "GET",
            httpRequestPath = "/ws",
            upgradeWebSocket = true,
            customConnectHeaders = mapOf("Host" to "cdn.example.net", "User-Agent" to "AIOTest/1.0")
        )

        val (latencyMs, statusLine) = HttpConnectProxyEngine.probeHttpEndpoint(profile, ProfileSecrets())
        serverThread.join(2_000)

        assertTrue(latencyMs >= 1L)
        assertTrue(statusLine.startsWith("HTTP/1.1 101"))
        val reqText = receivedRequest.toString()
        assertTrue(reqText.contains("GET /ws HTTP/1.1"))
        assertTrue(reqText.contains("Host: cdn.example.net"))
        assertTrue(reqText.contains("Upgrade: websocket"))
        assertTrue(reqText.contains("Connection: Upgrade"))
    }

    @Test
    fun `config import preserves backward compatibility for older json without secondary dns`() {
        val legacyProfileJson = """
            {
              "name": "Legacy",
              "protocol": "HTTP_PROXY",
              "host": "proxy.legacy.net",
              "port": 8080,
              "dnsServer": "8.8.8.8"
            }
        """.trimIndent()
        val imported = ConfigParserAndExporter.importFromText(legacyProfileJson)
        assertTrue(imported.isSuccess)
        val profile = imported.getOrThrow().first().profile
        assertEquals("8.8.8.8", profile.dnsServer)
        assertEquals("1.0.0.1", profile.dnsServerSecondary)
        assertEquals("CONNECT", profile.httpRequestMethod)
        assertFalse(profile.upgradeWebSocket)
    }
}
