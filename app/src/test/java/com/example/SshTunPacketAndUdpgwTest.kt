package com.example

import com.example.domain.models.DnsMode
import com.example.domain.models.EngineFamily
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelStatistics
import com.example.engines.adapter.EngineContext
import com.example.engines.adapter.EngineStatus
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import com.example.engines.ssh.BadVpnUdpgwClient
import com.example.engines.ssh.LocalSocks5Server
import com.example.vpn.tun.DnsManager
import com.example.vpn.tun.IpPacketParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class SshTunPacketAndUdpgwTest {

    @Test
    fun `badvpn udpgw encodes and decodes ipv4 frames with little endian length and big endian port`() {
        val dnsPayload = byteArrayOf(0x13, 0x37, 0x01, 0x00, 0x00, 0x01)
        val encoded = BadVpnUdpgwClient.encodeDataFrame(
            flags = BadVpnUdpgwClient.FLAG_DNS,
            connId = 42,
            destIp = "94.140.14.15",
            destPort = 53,
            payload = dnsPayload
        )
        // Frame body = 1 (flags) + 2 (connId) + 4 (ip) + 2 (port) + 6 (payload) = 15 bytes
        assertEquals(17, encoded.size)
        assertEquals(15, encoded[0].toInt() and 0xFF)
        assertEquals(0, encoded[1].toInt() and 0xFF)
        assertEquals(BadVpnUdpgwClient.FLAG_DNS, encoded[2].toInt() and 0xFF)
        assertEquals(42, encoded[3].toInt() and 0xFF)
        assertEquals(0, encoded[4].toInt() and 0xFF)
        // Port 53 big-endian = 0x00, 0x35
        assertEquals(0x00, encoded[9].toInt() and 0xFF)
        assertEquals(0x35, encoded[10].toInt() and 0xFF)

        val decoded = BadVpnUdpgwClient.readFrame(ByteArrayInputStream(encoded))
        assertNotNull(decoded)
        assertEquals(BadVpnUdpgwClient.FLAG_DNS, decoded!!.flags)
        assertEquals(42, decoded.connId)
        assertEquals("94.140.14.15", decoded.remoteIp)
        assertEquals(53, decoded.remotePort)
        assertArrayEquals(dnsPayload, decoded.payload)
    }

    @Test
    fun `badvpn udpgw client multiplexes dns and general udp flows bidirectionally`() = runTest {
        val clientToServer = PipedOutputStream()
        val serverFromClient = PipedInputStream(clientToServer, 8192)
        val serverToClient = PipedOutputStream()
        val clientFromServer = PipedInputStream(serverToClient, 8192)

        val fakeServerThread = thread(isDaemon = true) {
            try {
                while (true) {
                    val req = BadVpnUdpgwClient.readFrame(serverFromClient) ?: break
                    if ((req.flags and BadVpnUdpgwClient.FLAG_KEEPALIVE) != 0) continue
                    val replyPayload = if ((req.flags and BadVpnUdpgwClient.FLAG_DNS) != 0) {
                        byteArrayOf(req.payload[0], req.payload[1], 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
                    } else {
                        "pong:${String(req.payload, StandardCharsets.UTF_8)}".toByteArray(StandardCharsets.UTF_8)
                    }
                    val respFrame = BadVpnUdpgwClient.encodeDataFrame(
                        flags = req.flags,
                        connId = req.connId,
                        destIp = req.remoteIp,
                        destPort = req.remotePort,
                        payload = replyPayload
                    )
                    serverToClient.write(respFrame)
                    serverToClient.flush()
                }
            } catch (_: Throwable) {
            }
        }

        val udpgw = BadVpnUdpgwClient {
            object : TunnelStream {
                override val inputStream: InputStream = clientFromServer
                override val outputStream: OutputStream = clientToServer
                override fun close() {
                    runCatching { clientToServer.close() }
                    runCatching { serverToClient.close() }
                }
            }
        }
        assertTrue(udpgw.connectInitial())

        val dnsReply = udpgw.resolveDnsQuery("94.140.14.15", byteArrayOf(0x22, 0x33, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0), 2_000L)
        assertNotNull(dnsReply)
        assertEquals(0x22, dnsReply!![0].toInt() and 0xFF)
        assertEquals(0x33, dnsReply[1].toInt() and 0xFF)

        udpgw.sendUdpPacket(
            srcClientIp = "10.89.0.2",
            srcClientPort = 51515,
            destIp = "8.8.4.4",
            destPort = 443,
            payload = "quic-hello".toByteArray(StandardCharsets.UTF_8),
            isDns = false
        )
        val recvBuf = ByteArray(1024)
        val inbound = udpgw.receiveUdpPacket(recvBuf)
        assertNotNull(inbound)
        assertEquals("10.89.0.2", inbound!!.clientIp)
        assertEquals(51515, inbound.clientPort)
        assertEquals("8.8.4.4", inbound.remoteIp)
        assertEquals(443, inbound.remotePort)
        assertEquals("pong:quic-hello", String(recvBuf, 0, inbound.length, StandardCharsets.UTF_8))

        udpgw.close()
        fakeServerThread.join(1_000L)
    }

    @Test
    fun `local socks5 server forwards connect requests through ssh direct-tcpip stream`() = runTest {
        val socksServer = LocalSocks5Server { targetHost, targetPort ->
            assertEquals("93.184.216.34", targetHost)
            assertEquals(443, targetPort)
            val reply = "HTTP/1.1 200 OK\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
            val sink = ByteArrayOutputStream()
            val src = ByteArrayInputStream(reply)
            object : TunnelStream {
                override val inputStream: InputStream = src
                override val outputStream: OutputStream = sink
                override fun close() = Unit
            }
        }

        val port = socksServer.start()
        assertTrue(port > 0)

        Socket(InetAddress.getByName("127.0.0.1"), port).use { sock ->
            sock.soTimeout = 2_000
            val out = sock.getOutputStream()
            val inp = DataInputStream(sock.getInputStream())

            // Greeting: SOCKS5, 1 method: NO_AUTH
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            assertEquals(0x05, inp.readUnsignedByte())
            assertEquals(0x00, inp.readUnsignedByte())

            // CONNECT 93.184.216.34:443
            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 93, 184.toByte(), 216.toByte(), 34, 0x01, 0xBB.toByte()))
            out.flush()
            assertEquals(0x05, inp.readUnsignedByte())
            assertEquals(0x00, inp.readUnsignedByte())
            inp.readUnsignedByte() // RSV
            inp.readUnsignedByte() // ATYP
            val bndIp = ByteArray(4)
            inp.readFully(bndIp)
            inp.readUnsignedShort()

            val banner = inp.readBytes()
            assertEquals("HTTP/1.1 200 OK\r\n\r\n", String(banner, StandardCharsets.US_ASCII))
        }
        socksServer.close()
    }

    @Test
    fun `system default dns mode actively intercepts via tunnel instead of bypassing`() = runTest {
        val dnsMgr = DnsManager()
        val expectedReply = byteArrayOf(0x13, 0x37, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
        val dummyEngine = object : TunnelEngine {
            override val engineFamily = EngineFamily.SSH
            override val engineName = "FakeSsh"
            override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.RUNNING)
            override suspend fun validateConfiguration(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun initialize(context: EngineContext) = Unit
            override suspend fun start(profile: TunnelProfile, secrets: ProfileSecrets) = Result.success(Unit)
            override suspend fun stop() = Unit
            override fun getStatistics() = TunnelStatistics()
            override fun release() = Unit
            override suspend fun openTcpTunnel(targetHost: String, targetPort: Int): TunnelStream {
                val framed = ByteArray(2 + expectedReply.size).apply {
                    this[0] = ((expectedReply.size ushr 8) and 0xFF).toByte()
                    this[1] = (expectedReply.size and 0xFF).toByte()
                    System.arraycopy(expectedReply, 0, this, 2, expectedReply.size)
                }
                return object : TunnelStream {
                    override val inputStream: InputStream = ByteArrayInputStream(framed)
                    override val outputStream: OutputStream = ByteArrayOutputStream()
                    override fun close() = Unit
                }
            }
        }
        val config = DnsManager.ResolvedDnsConfig(
            dnsMode = DnsMode.SYSTEM_DEFAULT,
            primaryDns = "94.140.14.15",
            secondaryDns = "1.1.1.1",
            dohUrl = DnsManager.DEFAULT_DOH_URL
        )
        val resp = dnsMgr.resolveWireQueryThroughTunnel(
            queryBytes = dnsMgr.buildSampleDnsQuery("example.com"),
            dnsConfig = config,
            engine = dummyEngine,
            udpRelay = null
        )
        assertNotNull(resp)
        assertArrayEquals(expectedReply, resp)
    }

    @Test
    fun `ipv4 tcp syn-ack packet with mss option parses cleanly with 24 byte tcp header`() {
        val synAck = IpPacketParser.buildIpv4TcpPacket(
            srcIpBytes = IpPacketParser.ipStringToBytes("93.184.216.34"),
            dstIpBytes = IpPacketParser.ipStringToBytes("10.89.0.2"),
            srcPort = 443,
            dstPort = 52000,
            seqNum = 1000L,
            ackNum = 2001L,
            flags = IpPacketParser.TCP_SYN or IpPacketParser.TCP_ACK,
            mssOption = 1400
        )
        assertEquals(44, synAck.size)
        val ip = IpPacketParser.parseIpv4(synAck, synAck.size)!!
        val tcp = IpPacketParser.parseTcp(synAck, ip)!!
        assertEquals(24, tcp.headerLengthBytes)
        assertEquals(0, tcp.payloadLength)
        assertEquals(0x02, synAck[40].toInt() and 0xFF)
        assertEquals(0x04, synAck[41].toInt() and 0xFF)
        assertEquals(1400, ((synAck[42].toInt() and 0xFF) shl 8) or (synAck[43].toInt() and 0xFF))
    }
}
