package com.example

import com.example.data.security.SshHostKeyStore
import com.example.domain.models.AppSettings
import com.example.domain.models.DnsMode
import com.example.domain.models.EngineFamily
import com.example.domain.models.EngineTuningConfig
import com.example.domain.models.NetworkPreset
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelStatistics
import com.example.engines.adapter.EngineContext
import com.example.engines.adapter.EngineStatus
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import android.graphics.Bitmap
import android.graphics.Color
import com.example.engines.ssh.DynamicChannelLimiter
import com.example.engines.ssh.SshTunnelEngine
import com.example.ui.theme.computeBitmapAverageLuminance
import com.example.ui.theme.computeEffectiveBackgroundLuminance
import com.example.vpn.tun.DnsManager
import com.example.vpn.tun.DynamicBoundedPacketQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpaceGlassV2NetworkTest {

    @Test
    fun `dynamic channel limiter bounds permits and updates limit live`() {
        val limiter = DynamicChannelLimiter(initialLimit = 8)
        assertEquals(8, limiter.getLimit())
        repeat(8) { limiter.acquire(1_000L) }
        assertEquals(8, limiter.getActiveCount())

        limiter.setLimit(32)
        assertEquals(32, limiter.getLimit())
        limiter.acquire(1_000L)
        assertEquals(9, limiter.getActiveCount())

        repeat(9) { limiter.release() }
        assertEquals(0, limiter.getActiveCount())
    }

    @Test
    fun `ssh engine applies live tuning concurrency limits`() {
        val store = SshHostKeyStore(RuntimeEnvironment.getApplication())
        val engine = SshTunnelEngine(store)
        assertEquals(24, engine.currentConcurrencyLimit())

        engine.updateTuning(EngineTuningConfig(maxConcurrentSshChannels = 48, sshWindowKb = 512))
        assertEquals(48, engine.currentConcurrencyLimit())

        engine.updateTuning(EngineTuningConfig(maxConcurrentSshChannels = 100))
        assertEquals(64, engine.currentConcurrencyLimit())
    }

    @Test
    fun `network presets configure engine tuning and security rules cleanly`() {
        val base = AppSettings()
        val speed = base.applyNetworkPreset(NetworkPreset.MAX_SPEED)
        assertEquals(32, speed.transferBufferKb)
        assertEquals(512, speed.sshWindowKb)
        assertEquals(32, speed.maxConcurrentSshChannels)
        assertEquals(20, speed.sshKeepAliveSeconds)

        val privacy = base.applyNetworkPreset(NetworkPreset.MAX_SECURITY_PRIVACY)
        assertEquals(DnsMode.DNS_OVER_HTTPS, privacy.defaultDnsMode)
        assertTrue(privacy.strictTlsVerification)
        assertTrue(privacy.strictKillSwitch)
        assertFalse(privacy.bypassLan)
        assertFalse(privacy.wifiProxyShareEnabled)

        val optimal = privacy.restoreOptimalEngineDefaults()
        assertEquals(NetworkPreset.BALANCED, optimal.networkPreset)
        assertEquals(16, optimal.transferBufferKb)
        assertEquals(256, optimal.sshWindowKb)
        assertEquals(24, optimal.maxConcurrentSshChannels)
        assertTrue(optimal.bypassLan)
        assertFalse(optimal.strictKillSwitch)
    }

    @Test
    fun `dns manager stale-while-revalidate returns stale entry immediately and refreshes asynchronously`() = runBlocking {
        val dnsManager = DnsManager()
        val query = dnsManager.buildSampleDnsQuery("example.com")
        // Patch txId in query to 0xCAFE so we also verify txId rewriting on SWR cache hit
        val queryWithTx = query.copyOf().apply {
            this[0] = 0xCA.toByte()
            this[1] = 0xFE.toByte()
        }
        val staleResp = buildValidDnsAResponse(query, "93.184.216.34", ttlSec = 120)
        val freshResp = buildValidDnsAResponse(query, "93.184.216.35", ttlSec = 300)

        val dnsConfig = DnsManager.ResolvedDnsConfig(
            dnsMode = DnsMode.PLAIN_DNS,
            primaryDns = "1.1.1.1",
            secondaryDns = "1.0.0.1",
            dohUrl = "https://cloudflare-dns.com/dns-query"
        )
        val scopeKey = "${dnsConfig.dnsMode.name}:${dnsConfig.primaryDns}:${dnsConfig.dohUrl}"
        // Seed entry expired 5 seconds ago (well inside 24h STALE_GRACE_MS)
        dnsManager.seedCacheEntryForTesting(
            queryBytes = query,
            responseBytes = staleResp,
            scopeKey = scopeKey,
            expiresAtMs = System.currentTimeMillis() - 5_000L
        )

        val upstreamCalls = AtomicInteger(0)
        val fakeEngine = object : TunnelEngine {
            override val engineFamily = EngineFamily.SSH
            override val engineName = "FakeSshEngine"
            override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.RUNNING)
            override suspend fun validateConfiguration(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun initialize(context: EngineContext) = Unit
            override suspend fun start(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun stop() = Unit
            override fun release() = Unit
            override suspend fun openTcpTunnel(targetHost: String, targetPort: Int): TunnelStream {
                upstreamCalls.incrementAndGet()
                val framed = ByteArrayOutputStream().apply {
                    write((freshResp.size ushr 8) and 0xFF)
                    write(freshResp.size and 0xFF)
                    write(freshResp)
                }.toByteArray()
                return object : TunnelStream {
                    override val inputStream: InputStream = ByteArrayInputStream(framed)
                    override val outputStream: OutputStream = ByteArrayOutputStream()
                    override fun close() = Unit
                }
            }
            override fun getStatistics() = TunnelStatistics()
        }

        val immediate = dnsManager.resolveWireQueryThroughTunnel(
            queryBytes = queryWithTx,
            dnsConfig = dnsConfig,
            engine = fakeEngine,
            bypassCache = false
        )
        assertNotNull(immediate)
        assertEquals(0xCA.toByte(), immediate!![0])
        assertEquals(0xFE.toByte(), immediate[1])
        assertEquals("93.184.216.34", dnsManager.extractFirstARecordIp(immediate))

        // Wait briefly for SWR background refresh coroutine to update cache
        var refreshedIp: String? = null
        repeat(20) {
            delay(40L)
            val next = dnsManager.resolveWireQueryThroughTunnel(
                queryBytes = queryWithTx,
                dnsConfig = dnsConfig,
                engine = fakeEngine,
                bypassCache = false
            )
            val ip = next?.let { dnsManager.extractFirstARecordIp(it) }
            if (ip == "93.184.216.35") {
                refreshedIp = ip
                return@repeat
            }
        }
        assertEquals(1, upstreamCalls.get())
        assertEquals("93.184.216.35", refreshedIp)
        dnsManager.close()
    }

    @Test
    fun `dynamic bounded packet queue resizes capacity live within bounds`() {
        val queue = DynamicBoundedPacketQueue(initialCapacity = 64)
        assertEquals(64, queue.capacity())
        repeat(40) { idx -> queue.put(byteArrayOf(idx.toByte())) }
        assertEquals(40, queue.size())

        queue.setCapacity(192)
        assertEquals(192, queue.capacity())

        queue.setCapacity(10) // clamps to min 32
        assertEquals(32, queue.capacity())

        val first = queue.take()
        assertEquals(0.toByte(), first[0])
        assertEquals(39, queue.size())
    }

    @Test
    fun `dns manager respects smartDnsCacheEnabled false by always hitting upstream`() = runBlocking {
        val dnsManager = DnsManager(initialSmartCacheEnabled = false)
        assertFalse(dnsManager.isSmartCacheEnabled())

        val query = dnsManager.buildSampleDnsQuery("example.com")
        val freshResp = buildValidDnsAResponse(query, "203.0.113.9", ttlSec = 300)
        val dnsConfig = DnsManager.ResolvedDnsConfig(
            dnsMode = DnsMode.PLAIN_DNS,
            primaryDns = "1.1.1.1",
            secondaryDns = "1.0.0.1",
            dohUrl = "https://cloudflare-dns.com/dns-query"
        )
        val scopeKey = "${dnsConfig.dnsMode.name}:${dnsConfig.primaryDns}:${dnsConfig.dohUrl}"
        dnsManager.seedCacheEntryForTesting(
            queryBytes = query,
            responseBytes = buildValidDnsAResponse(query, "198.51.100.1", ttlSec = 300),
            scopeKey = scopeKey,
            expiresAtMs = System.currentTimeMillis() + 120_000L
        )

        val upstreamCalls = AtomicInteger(0)
        val fakeEngine = object : TunnelEngine {
            override val engineFamily = EngineFamily.SSH
            override val engineName = "FakeSshEngine"
            override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.RUNNING)
            override suspend fun validateConfiguration(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun initialize(context: EngineContext) = Unit
            override suspend fun start(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun stop() = Unit
            override fun release() = Unit
            override suspend fun openTcpTunnel(targetHost: String, targetPort: Int): TunnelStream {
                upstreamCalls.incrementAndGet()
                val framed = ByteArrayOutputStream().apply {
                    write((freshResp.size ushr 8) and 0xFF)
                    write(freshResp.size and 0xFF)
                    write(freshResp)
                }.toByteArray()
                return object : TunnelStream {
                    override val inputStream: InputStream = ByteArrayInputStream(framed)
                    override val outputStream: OutputStream = ByteArrayOutputStream()
                    override fun close() = Unit
                }
            }
            override fun getStatistics() = TunnelStatistics()
        }

        val r1 = dnsManager.resolveWireQueryThroughTunnel(query, dnsConfig, fakeEngine, bypassCache = false)
        val r2 = dnsManager.resolveWireQueryThroughTunnel(query, dnsConfig, fakeEngine, bypassCache = false)
        assertEquals("203.0.113.9", dnsManager.extractFirstARecordIp(r1!!))
        assertEquals("203.0.113.9", dnsManager.extractFirstARecordIp(r2!!))
        assertEquals(2, upstreamCalls.get())

        dnsManager.updateTuning(EngineTuningConfig(smartDnsCacheEnabled = true))
        assertTrue(dnsManager.isSmartCacheEnabled())
        dnsManager.close()
    }

    @Test
    fun `background luminance computation accurately distinguishes dark and bright wallpapers with dimmer`() {
        val darkBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(12, 16, 28))
        }
        val brightBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(245, 248, 252))
        }

        val darkLum = computeBitmapAverageLuminance(darkBmp)
        val brightLum = computeBitmapAverageLuminance(brightBmp)
        assertTrue(darkLum < 0.2f)
        assertTrue(brightLum > 0.9f)

        val brightNoDim = computeEffectiveBackgroundLuminance(
            hasCustomBg = true,
            rawBgLuminance = brightLum,
            dimmerAlpha = 0f,
            useLightTheme = false
        )
        val brightHeavyDim = computeEffectiveBackgroundLuminance(
            hasCustomBg = true,
            rawBgLuminance = brightLum,
            dimmerAlpha = 0.70f,
            useLightTheme = false
        )
        assertTrue(brightNoDim >= 0.5f)
        assertTrue(brightHeavyDim < 0.5f)

        darkBmp.recycle()
        brightBmp.recycle()
    }

    private fun buildValidDnsAResponse(query: ByteArray, ip: String, ttlSec: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(query[0].toInt() and 0xFF)
        out.write(query[1].toInt() and 0xFF)
        out.write(0x81)
        out.write(0x80) // Standard query response, NOERROR
        out.write(0x00)
        out.write(0x01) // QDCOUNT = 1
        out.write(0x00)
        out.write(0x01) // ANCOUNT = 1
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        // Question section copy
        out.write(query, 12, query.size - 12)
        // Answer section: compression pointer 0xC00C
        out.write(0xC0); out.write(0x0C)
        out.write(0x00); out.write(0x01) // TYPE A
        out.write(0x00); out.write(0x01) // CLASS IN
        out.write((ttlSec ushr 24) and 0xFF)
        out.write((ttlSec ushr 16) and 0xFF)
        out.write((ttlSec ushr 8) and 0xFF)
        out.write(ttlSec and 0xFF)
        out.write(0x00); out.write(0x04) // RDLENGTH = 4
        ip.split('.').forEach { out.write(it.toInt() and 0xFF) }
        return out.toByteArray()
    }
}
