package com.example.vpn.tun

import android.os.ParcelFileDescriptor
import com.example.core.logger.AppLogger
import com.example.core.stats.StatisticsManager
import com.example.domain.models.TunnelProfile
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import com.example.engines.adapter.UdpRelaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TunPacketRouter(
    private val tunFd: ParcelFileDescriptor,
    private val profile: TunnelProfile,
    private val engine: TunnelEngine,
    private val dnsManager: DnsManager,
    private val statisticsManager: StatisticsManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val running = AtomicBoolean(false)
    private val outputStream = FileOutputStream(tunFd.fileDescriptor)
    private val inputStream = FileInputStream(tunFd.fileDescriptor)
    private val tcpSessions = ConcurrentHashMap<TcpFlowKey, TcpSession>()
    private var udpRelaySession: UdpRelaySession? = null
    private var readLoopJob: Job? = null
    private val seqSeed = AtomicLong(1_000_000L)

    data class TcpFlowKey(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int
    )

    private inner class TcpSession(
        val key: TcpFlowKey,
        val clientIpBytes: ByteArray,
        val serverIpBytes: ByteArray,
        var clientNextSeq: Long,
        var mySeq: Long
    ) {
        @Volatile
        var stream: TunnelStream? = null
        @Volatile
        var closed: Boolean = false
        var readerJob: Job? = null
        val sendLock = Any()

        fun close() {
            if (closed) return
            closed = true
            readerJob?.cancel()
            runCatching { stream?.close() }
            stream = null
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            udpRelaySession = runCatching { engine.openUdpRelay() }.getOrNull()
        }
        readLoopJob = scope.launch {
            val buffer = ByteArray(maxOf(profile.mtu + 64, 4096))
            AppLogger.info(TAG, "Router TUN iniciado (MTU=${profile.mtu})")
            while (isActive && running.get()) {
                val bytesRead = try {
                    inputStream.read(buffer)
                } catch (e: IOException) {
                    if (running.get()) AppLogger.warn(TAG, "Lectura TUN finalizada: ${e.message}")
                    break
                }
                if (bytesRead <= 0) continue
                statisticsManager.recordPacketForwarded()
                val ip = IpPacketParser.parseIpv4(buffer, bytesRead) ?: continue
                when (ip.protocol) {
                    IpPacketParser.PROTO_UDP -> handleUdpPacket(buffer, ip)
                    IpPacketParser.PROTO_TCP -> handleTcpPacket(buffer, ip)
                }
            }
        }
    }

    private fun handleUdpPacket(buffer: ByteArray, ip: IpPacketParser.Ipv4Packet) {
        val udp = IpPacketParser.parseUdp(buffer, ip) ?: return
        if (udp.payloadLength <= 0) return
        val payload = buffer.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        statisticsManager.recordBytesSent(payload.size.toLong())

        if (udp.dstPort == 53) {
            scope.launch {
                val dnsResp = dnsManager.resolveWireQuery(payload, profile) ?: return@launch
                statisticsManager.recordDnsQuery()
                statisticsManager.recordBytesReceived(dnsResp.size.toLong())
                val replyPacket = IpPacketParser.buildIpv4UdpPacket(
                    srcIpBytes = ip.dstIpBytes,
                    dstIpBytes = ip.srcIpBytes,
                    srcPort = 53,
                    dstPort = udp.srcPort,
                    payload = dnsResp
                )
                writePacketToTun(replyPacket)
            }
        } else {
            val relay = udpRelaySession ?: return
            scope.launch {
                runCatching {
                    relay.sendDatagram(ip.dstIp, udp.dstPort, payload)
                }
            }
        }
    }

    private fun handleTcpPacket(buffer: ByteArray, ip: IpPacketParser.Ipv4Packet) {
        val tcp = IpPacketParser.parseTcp(buffer, ip) ?: return
        val key = TcpFlowKey(ip.srcIp, tcp.srcPort, ip.dstIp, tcp.dstPort)
        val isSyn = (tcp.flags and IpPacketParser.TCP_SYN) != 0
        val isAck = (tcp.flags and IpPacketParser.TCP_ACK) != 0
        val isRst = (tcp.flags and IpPacketParser.TCP_RST) != 0
        val isFin = (tcp.flags and IpPacketParser.TCP_FIN) != 0

        if (isSyn && !isAck) {
            // New outbound TCP connection through the VPN TUN
            tcpSessions.remove(key)?.close()
            val initialMySeq = seqSeed.addAndGet(65536L)
            val session = TcpSession(
                key = key,
                clientIpBytes = ip.srcIpBytes,
                serverIpBytes = ip.dstIpBytes,
                clientNextSeq = (tcp.seqNum + 1L) and 0xFFFFFFFFL,
                mySeq = initialMySeq
            )
            tcpSessions[key] = session
            statisticsManager.setActiveTcpConnections(tcpSessions.size)

            scope.launch {
                try {
                    val tunnelStream = engine.openTcpTunnel(ip.dstIp, tcp.dstPort)
                    if (session.closed || !running.get()) {
                        tunnelStream.close()
                        return@launch
                    }
                    session.stream = tunnelStream
                    val synAck = IpPacketParser.buildIpv4TcpPacket(
                        srcIpBytes = session.serverIpBytes,
                        dstIpBytes = session.clientIpBytes,
                        srcPort = key.dstPort,
                        dstPort = key.srcPort,
                        seqNum = session.mySeq,
                        ackNum = session.clientNextSeq,
                        flags = IpPacketParser.TCP_SYN or IpPacketParser.TCP_ACK
                    )
                    session.mySeq = (session.mySeq + 1L) and 0xFFFFFFFFL
                    writePacketToTun(synAck)
                    startTunnelReader(session, tunnelStream)
                } catch (e: Throwable) {
                    AppLogger.debug(TAG, "TCP ${ip.dstIp}:${tcp.dstPort} falló: ${e.message}")
                    val rst = IpPacketParser.buildIpv4TcpPacket(
                        srcIpBytes = ip.dstIpBytes,
                        dstIpBytes = ip.srcIpBytes,
                        srcPort = tcp.dstPort,
                        dstPort = tcp.srcPort,
                        seqNum = 0L,
                        ackNum = (tcp.seqNum + 1L) and 0xFFFFFFFFL,
                        flags = IpPacketParser.TCP_RST or IpPacketParser.TCP_ACK
                    )
                    writePacketToTun(rst)
                    closeSession(key)
                }
            }
            return
        }

        val session = tcpSessions[key] ?: return
        if (isRst) {
            closeSession(key)
            return
        }

        if (tcp.payloadLength > 0) {
            val payload = buffer.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
            val expectedSeq = session.clientNextSeq
            if (tcp.seqNum == expectedSeq) {
                session.clientNextSeq = (tcp.seqNum + payload.size.toLong()) and 0xFFFFFFFFL
                scope.launch {
                    val stream = session.stream ?: return@launch
                    try {
                        synchronized(session.sendLock) {
                            stream.outputStream.write(payload)
                            stream.outputStream.flush()
                        }
                        statisticsManager.recordBytesSent(payload.size.toLong())
                        val ackPkt = IpPacketParser.buildIpv4TcpPacket(
                            srcIpBytes = session.serverIpBytes,
                            dstIpBytes = session.clientIpBytes,
                            srcPort = key.dstPort,
                            dstPort = key.srcPort,
                            seqNum = session.mySeq,
                            ackNum = session.clientNextSeq,
                            flags = IpPacketParser.TCP_ACK
                        )
                        writePacketToTun(ackPkt)
                    } catch (_: Throwable) {
                        sendRstAndClose(session)
                    }
                }
            } else {
                // Re-send duplicate ACK for expected sequence
                scope.launch {
                    val dupAck = IpPacketParser.buildIpv4TcpPacket(
                        srcIpBytes = session.serverIpBytes,
                        dstIpBytes = session.clientIpBytes,
                        srcPort = key.dstPort,
                        dstPort = key.srcPort,
                        seqNum = session.mySeq,
                        ackNum = session.clientNextSeq,
                        flags = IpPacketParser.TCP_ACK
                    )
                    writePacketToTun(dupAck)
                }
            }
        }

        if (isFin) {
            session.clientNextSeq = (session.clientNextSeq + 1L) and 0xFFFFFFFFL
            scope.launch {
                val finAck = IpPacketParser.buildIpv4TcpPacket(
                    srcIpBytes = session.serverIpBytes,
                    dstIpBytes = session.clientIpBytes,
                    srcPort = key.dstPort,
                    dstPort = key.srcPort,
                    seqNum = session.mySeq,
                    ackNum = session.clientNextSeq,
                    flags = IpPacketParser.TCP_FIN or IpPacketParser.TCP_ACK
                )
                session.mySeq = (session.mySeq + 1L) and 0xFFFFFFFFL
                writePacketToTun(finAck)
                closeSession(key)
            }
        }
    }

    private fun startTunnelReader(session: TcpSession, stream: TunnelStream) {
        val mss = (profile.mtu - 60).coerceIn(512, 1400)
        session.readerJob = scope.launch {
            val readBuf = ByteArray(mss)
            try {
                while (isActive && running.get() && !session.closed) {
                    val n = stream.inputStream.read(readBuf)
                    if (n == -1) {
                        val finPkt = IpPacketParser.buildIpv4TcpPacket(
                            srcIpBytes = session.serverIpBytes,
                            dstIpBytes = session.clientIpBytes,
                            srcPort = session.key.dstPort,
                            dstPort = session.key.srcPort,
                            seqNum = session.mySeq,
                            ackNum = session.clientNextSeq,
                            flags = IpPacketParser.TCP_FIN or IpPacketParser.TCP_ACK
                        )
                        session.mySeq = (session.mySeq + 1L) and 0xFFFFFFFFL
                        writePacketToTun(finPkt)
                        closeSession(session.key)
                        break
                    }
                    if (n > 0) {
                        statisticsManager.recordBytesReceived(n.toLong())
                        val dataPkt = IpPacketParser.buildIpv4TcpPacket(
                            srcIpBytes = session.serverIpBytes,
                            dstIpBytes = session.clientIpBytes,
                            srcPort = session.key.dstPort,
                            dstPort = session.key.srcPort,
                            seqNum = session.mySeq,
                            ackNum = session.clientNextSeq,
                            flags = IpPacketParser.TCP_PSH or IpPacketParser.TCP_ACK,
                            payload = readBuf,
                            payloadLength = n
                        )
                        session.mySeq = (session.mySeq + n.toLong()) and 0xFFFFFFFFL
                        writePacketToTun(dataPkt)
                    }
                }
            } catch (_: Throwable) {
                if (!session.closed) sendRstAndClose(session)
            }
        }
    }

    private suspend fun sendRstAndClose(session: TcpSession) {
        val rst = IpPacketParser.buildIpv4TcpPacket(
            srcIpBytes = session.serverIpBytes,
            dstIpBytes = session.clientIpBytes,
            srcPort = session.key.dstPort,
            dstPort = session.key.srcPort,
            seqNum = session.mySeq,
            ackNum = session.clientNextSeq,
            flags = IpPacketParser.TCP_RST or IpPacketParser.TCP_ACK
        )
        writePacketToTun(rst)
        closeSession(session.key)
    }

    private fun closeSession(key: TcpFlowKey) {
        tcpSessions.remove(key)?.close()
        statisticsManager.setActiveTcpConnections(tcpSessions.size)
    }

    private suspend fun writePacketToTun(packet: ByteArray) {
        if (!running.get()) return
        writeMutex.withLock {
            runCatching {
                outputStream.write(packet)
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        readLoopJob?.cancel()
        tcpSessions.values.forEach { it.close() }
        tcpSessions.clear()
        statisticsManager.setActiveTcpConnections(0)
        runCatching { udpRelaySession?.close() }
        udpRelaySession = null
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        scope.cancel()
        AppLogger.info(TAG, "Router TUN detenido y recursos liberados.")
    }

    companion object {
        private const val TAG = "TunPacketRouter"
    }
}
