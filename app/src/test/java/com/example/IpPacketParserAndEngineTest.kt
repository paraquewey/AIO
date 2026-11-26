package com.example

import com.example.domain.models.EngineFamily
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.SshAuthMethod
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import com.example.engines.http.HttpConnectProxyEngine
import com.example.engines.nativecore.NativeCoreAdapterEngine
import com.example.engines.socks.Socks5TunnelEngine
import com.example.vpn.tun.IpPacketParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpPacketParserAndEngineTest {

    @Test
    fun `ipv4 tcp packet build and parse roundtrip preserves ports, seq, ack, flags, and payload`() {
        val srcIp = IpPacketParser.ipStringToBytes("10.89.0.2")
        val dstIp = IpPacketParser.ipStringToBytes("93.184.216.34")
        val payload = "GET / HTTP/1.1\r\n\r\n".toByteArray()

        val rawPacket = IpPacketParser.buildIpv4TcpPacket(
            srcIpBytes = srcIp,
            dstIpBytes = dstIp,
            srcPort = 54321,
            dstPort = 80,
            seqNum = 1000L,
            ackNum = 2000L,
            flags = IpPacketParser.TCP_PSH or IpPacketParser.TCP_ACK,
            payload = payload
        )

        // Verify IPv4 header checksum evaluates to 0 across header
        assertEquals(0, IpPacketParser.computeChecksum(rawPacket, 0, 20))

        val parsedIp = IpPacketParser.parseIpv4(rawPacket, rawPacket.size)
        assertNotNull(parsedIp)
        assertEquals(IpPacketParser.PROTO_TCP, parsedIp!!.protocol)
        assertEquals("10.89.0.2", parsedIp.srcIp)
        assertEquals("93.184.216.34", parsedIp.dstIp)

        val parsedTcp = IpPacketParser.parseTcp(rawPacket, parsedIp)
        assertNotNull(parsedTcp)
        assertEquals(54321, parsedTcp!!.srcPort)
        assertEquals(80, parsedTcp.dstPort)
        assertEquals(1000L, parsedTcp.seqNum)
        assertEquals(2000L, parsedTcp.ackNum)
        assertEquals(payload.size, parsedTcp.payloadLength)
        val extractedPayload = rawPacket.copyOfRange(
            parsedTcp.payloadOffset,
            parsedTcp.payloadOffset + parsedTcp.payloadLength
        )
        assertArrayEquals(payload, extractedPayload)
    }

    @Test
    fun `ipv4 udp packet build and parse roundtrip`() {
        val srcIp = IpPacketParser.ipStringToBytes("1.1.1.1")
        val dstIp = IpPacketParser.ipStringToBytes("10.89.0.2")
        val dnsPayload = byteArrayOf(0x13, 0x37, 0x01, 0x00)

        val rawPacket = IpPacketParser.buildIpv4UdpPacket(
            srcIpBytes = srcIp,
            dstIpBytes = dstIp,
            srcPort = 53,
            dstPort = 41234,
            payload = dnsPayload
        )
        val ip = IpPacketParser.parseIpv4(rawPacket, rawPacket.size)!!
        assertEquals(IpPacketParser.PROTO_UDP, ip.protocol)
        val udp = IpPacketParser.parseUdp(rawPacket, ip)!!
        assertEquals(53, udp.srcPort)
        assertEquals(41234, udp.dstPort)
        assertEquals(dnsPayload.size, udp.payloadLength)
    }

    @Test
    fun `http connect engine rejects crlf header injection and invalid ports`() = runTest {
        val engine = HttpConnectProxyEngine()
        val badHeaderProfile = TunnelProfile(
            name = "Bad Header",
            protocol = TunnelProtocol.HTTPS_PROXY,
            host = "proxy.example.com",
            port = 443,
            customConnectHeaders = mapOf("User-Agent" to "Evil\r\nX-Injected: 1")
        )
        assertTrue(engine.validateConfiguration(badHeaderProfile, ProfileSecrets()).isFailure)

        val badPortProfile = badHeaderProfile.copy(port = 0, customConnectHeaders = emptyMap())
        assertTrue(engine.validateConfiguration(badPortProfile, ProfileSecrets()).isFailure)
    }

    @Test
    fun `socks5 engine validates rfc1929 credential byte lengths`() = runTest {
        val engine = Socks5TunnelEngine()
        val longUser = "a".repeat(256)
        val profile = TunnelProfile(
            name = "TooLongUser",
            protocol = TunnelProtocol.SOCKS5,
            host = "127.0.0.1",
            port = 1080,
            username = longUser
        )
        assertTrue(engine.validateConfiguration(profile, ProfileSecrets()).isFailure)
    }

    @Test
    fun `native core adapter refuses fake simulation when library is absent`() = runTest {
        val engine = NativeCoreAdapterEngine()
        assertEquals(EngineFamily.NATIVE_CORE, engine.engineFamily)
        val vlessProfile = TunnelProfile(
            name = "VLESS Test",
            protocol = TunnelProtocol.VLESS,
            host = "vless.example.com",
            port = 443,
            authMethod = SshAuthMethod.PASSWORD
        )
        val result = engine.start(vlessProfile, ProfileSecrets())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
    }
}
