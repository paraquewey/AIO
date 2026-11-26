package com.example

import android.util.Base64
import com.example.data.import_export.ConfigParserAndExporter
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.SshAuthMethod
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConfigParserAndExporterTest {

    @Test
    fun `export json excludes secrets by default and re-imports cleanly`() {
        val profile = TunnelProfile(
            name = "Edge SSH",
            protocol = TunnelProtocol.SSH,
            host = "vpn.example.org",
            port = 2222,
            username = "ops",
            category = "Trabajo",
            tags = listOf("ssh", "prod")
        )
        val secrets = ProfileSecrets(password = "SuperSecret123!")

        val exported = ConfigParserAndExporter.exportProfilesToJson(
            profiles = listOf(profile to secrets),
            includeSecrets = false
        )
        assertFalse(exported.contains("SuperSecret123!"))

        val parsed = ConfigParserAndExporter.importFromText(exported)
        assertTrue(parsed.isSuccess)
        val entries = parsed.getOrThrow()
        assertEquals(1, entries.size)
        assertEquals("Edge SSH", entries[0].profile.name)
        assertEquals("vpn.example.org", entries[0].profile.host)
        assertEquals(2222, entries[0].profile.port)
        assertEquals("", entries[0].secrets.password)
    }

    @Test
    fun `export json with opt-in preserves secrets`() {
        val profile = TunnelProfile(
            name = "Proxy Auth",
            protocol = TunnelProtocol.HTTPS_PROXY,
            host = "proxy.example.net",
            port = 8443,
            username = "alice"
        )
        val secrets = ProfileSecrets(password = "OptInPass99")

        val exported = ConfigParserAndExporter.exportProfilesToJson(
            profiles = listOf(profile to secrets),
            includeSecrets = true
        )
        val parsed = ConfigParserAndExporter.importFromText(exported).getOrThrow()
        assertEquals("OptInPass99", parsed[0].secrets.password)
    }

    @Test
    fun `parse ssh uri with jump host and private key auth`() {
        val uri = "ssh://admin@target.internal:22?jump=bastion@jump.example.com:2222&auth=key#Internal%20Node"
        val entries = ConfigParserAndExporter.importFromText(uri).getOrThrow()
        assertEquals(1, entries.size)
        val p = entries[0].profile
        assertEquals("Internal Node", p.name)
        assertEquals(TunnelProtocol.SSH_JUMP, p.protocol)
        assertEquals("target.internal", p.host)
        assertEquals(22, p.port)
        assertTrue(p.jumpHostEnabled)
        assertEquals("jump.example.com", p.jumpHost)
        assertEquals(2222, p.jumpPort)
        assertEquals("bastion", p.jumpUsername)
        assertEquals(SshAuthMethod.PRIVATE_KEY, p.authMethod)
    }

    @Test
    fun `parse socks5, https, vless and vmess uris`() {
        val vmessJson = """{"v":"2","ps":"Demo VMess","add":"vm.example.com","port":"443","id":"00000000-0000-0000-0000-000000000000","tls":"tls","sni":"vm.example.com"}"""
        val vmessB64 = Base64.encodeToString(vmessJson.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val payload = """
            socks5://u:p@198.51.100.10:1080#SocksNode
            https://proxy.example.com:443#HttpsNode
            vless://uuid@edge.example.com:443?security=reality&sni=cdn.example.com#RealityNode
            vmess://$vmessB64
        """.trimIndent()

        val entries = ConfigParserAndExporter.importFromText(payload).getOrThrow()
        assertEquals(4, entries.size)
        assertEquals(TunnelProtocol.SOCKS5, entries[0].profile.protocol)
        assertEquals(TunnelProtocol.HTTPS_PROXY, entries[1].profile.protocol)
        assertEquals(TunnelProtocol.REALITY, entries[2].profile.protocol)
        assertEquals(TunnelProtocol.VMESS, entries[3].profile.protocol)
        assertEquals("Demo VMess", entries[3].profile.name)
    }

    @Test
    fun `parse sing-box json and clash yaml subset`() {
        val singBox = """
            {
              "outbounds": [
                {"type": "direct", "tag": "direct"},
                {"type": "socks", "tag": "SB Socks", "server": "203.0.113.5", "server_port": 1080}
              ]
            }
        """.trimIndent()
        val sbEntries = ConfigParserAndExporter.importFromText(singBox).getOrThrow()
        assertEquals(1, sbEntries.size)
        assertEquals(TunnelProtocol.SOCKS5, sbEntries[0].profile.protocol)
        assertEquals("203.0.113.5", sbEntries[0].profile.host)

        val clash = """
            proxies:
              - name: Clash-Socks
                type: socks5
                server: 192.0.2.20
                port: 1080
        """.trimIndent()
        val clashEntries = ConfigParserAndExporter.importFromText(clash).getOrThrow()
        assertEquals(1, clashEntries.size)
        assertEquals("Clash-Socks", clashEntries[0].profile.name)
    }

    @Test
    fun `reject invalid port, blank host, and empty payloads`() {
        assertTrue(ConfigParserAndExporter.importFromText("").isFailure)
        assertTrue(ConfigParserAndExporter.importFromText("socks5://badhost:99999").isFailure)
        assertTrue(ConfigParserAndExporter.importFromText("not-a-valid-uri").isFailure)
    }
}
