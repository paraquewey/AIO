package com.example.vpn.tun

import android.os.ParcelFileDescriptor
import com.example.core.logger.AppLogger
import com.example.core.stats.StatisticsManager
import com.example.domain.models.TunnelProfile
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import com.example.engines.adapter.UdpRelaySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TunPacketRouter(
    private val tunFd: ParcelFileDescriptor,
    private val profile: TunnelProfile,
    private val dnsConfig: DnsManager.ResolvedDnsConfig,
    private val engine: TunnelEngine,
    private val dnsManager: DnsManager,
    private val statisticsManager: StatisticsManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val dnsSemaphore = Semaphore(24)
    private val running = AtomicBoolean(false)
    private val outputStream = FileOutputStream(tunFd.fileDescriptor)
    private val inputStream = FileInputStream(tunFd.fileDescriptor)
    private val tcpSessions = ConcurrentHashMap<TcpFlowKey, TcpSession>()
    private val udpFlowClients = ConcurrentHashMap<UdpFlowKey, UdpClientEndpoints>()

    @Volatile
    private var udpRelaySession: UdpRelaySession? = null
    private var udpReceiverJob: Job? = null
    private var readLoopJob: Job? = null
    private val seqSeed = AtomicLong(1_000_000L)

    data class TcpFlowKey(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int
    )

    data class UdpFlowKey(
        val clientIp: String,
        val clientPort: Int
    )

    data class UdpClientEndpoints(
        val clientIpBytes: ByteArray,
        val clientPort: Int
    )

    private inner class TcpSession(
        val key: TcpFlowKey,
        val clientIpBytes: ByteArray,
        val serverIpBytes: ByteArray,
        val initialClientSeq: Long,
        val initialServerSeq: Long,
        var clientNextSeq: Long,
        var mySeq: Long
    ) {
        val streamReady = CompletableDeferred<TunnelStream>()
        val outboundChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        val seqLock = Any()
        val outOfOrder = TreeMap<Long, ByteArray>()

        @Volatile
        var stream: TunnelStream? = null

        @Volatile
        var connected: Boolean = false

        @Volatile
        var closed: Boolean = false

        @Volatile
        var clientFinReceived: Boolean = false

        @Volatile
        var serverFinSent: Boolean = false

        @Volatile
        var clientWindow: Int = 65535

        var connectorJob: Job? = null
        var writerJob: Job? = null
        var readerJob: Job? = null

        fun close() {
            if (closed) return
            closed = true
            outboundChannel.close()
            connectorJob?.cancel()
            writerJob?.cancel()
            readerJob?.cancel()
            if (!streamReady.isCompleted) {
                streamReady.cancel()
            }
            runCatching { stream?.close() }
            stream = null
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val socksPort = engine.getLocalSocksPort()
        if (socksPort != null) {
            AppLogger.info(TAG, "SSH Port Forwarding (direct-tcpip / SOCKS5 127.0.0.1:$socksPort) listo")
        }
        AppLogger.info(
            TAG,
            "DNS Forwarding activo sobre túnel (DNS1 -> ${dnsConfig.primaryDns} / DNS2 -> ${dnsConfig.secondaryDns}, modo=${dnsConfig.dnsMode.name})"
        )

        scope.launch {
            val relay = runCatching { engine.openUdpRelay() }.getOrNull()
            if (!running.get()) {
                runCatching { relay?.close() }
                return@launch
            }
            udpRelaySession = relay
            if (relay != null) {
                startUdpRelayReceiver(relay)
            }
        }

        readLoopJob = scope.launch {
            val buffer = ByteArray(maxOf(profile.mtu + 128, 4096))
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

    private fun startUdpRelayReceiver(relay: UdpRelaySession) {
        udpReceiverJob?.cancel()
        udpReceiverJob = scope.launch {
            val buf = ByteArray(maxOf(profile.mtu + 256, 4096))
            while (isActive && running.get()) {
                val pkt = try {
                    relay.receiveUdpPacket(buf)
                } catch (_: Throwable) {
                    null
                } ?: break

                val clientIp = pkt.clientIp ?: continue
                val clientPort = pkt.clientPort ?: continue
                val client = udpFlowClients[UdpFlowKey(clientIp, clientPort)] ?: continue
                if (pkt.length <= 0) continue

                val remoteIpBytes = IpPacketParser.ipStringToBytes(pkt.remoteIp)
                val replyPacket = IpPacketParser.buildIpv4UdpPacket(
                    srcIpBytes = remoteIpBytes,
                    dstIpBytes = client.clientIpBytes,
                    srcPort = pkt.remotePort,
                    dstPort = client.clientPort,
                    payload = buf,
                    payloadLength = pkt.length
                )
                statisticsManager.recordBytesReceived(pkt.length.toLong())
                statisticsManager.recordPacketForwarded()
                writePacketToTun(replyPacket)
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
                if (!running.get()) return@launch
                dnsSemaphore.withPermit {
                    if (!running.get()) return@withPermit
                    val dnsResp = dnsManager.resolveWireQueryThroughTunnel(
                        queryBytes = payload,
                        dnsConfig = dnsConfig,
                        engine = engine,
                        udpRelay = udpRelaySession
                    ) ?: return@withPermit
                    if (!running.get()) return@withPermit
                    statisticsManager.recordDnsQuery()
                    statisticsManager.recordBytesReceived(dnsResp.size.toLong())
                    statisticsManager.recordPacketForwarded()
                    val replyPacket = IpPacketParser.buildIpv4UdpPacket(
                        srcIpBytes = ip.dstIpBytes,
                        dstIpBytes = ip.srcIpBytes,
                        srcPort = 53,
                        dstPort = udp.srcPort,
                        payload = dnsResp
                    )
                    writePacketToTun(replyPacket)
                }
            }
        } else {
            val relay = udpRelaySession ?: return
            val flowKey = UdpFlowKey(ip.srcIp, udp.srcPort)
            udpFlowClients.putIfAbsent(flowKey, UdpClientEndpoints(ip.srcIpBytes, udp.srcPort))
            scope.launch {
                runCatching {
                    relay.sendUdpPacket(
                        srcClientIp = ip.srcIp,
                        srcClientPort = udp.srcPort,
                        destIp = ip.dstIp,
                        destPort = udp.dstPort,
                        payload = payload,
                        isDns = false
                    )
                }.onFailure { err ->
                    AppLogger.warnRateLimited(
                        TAG,
                        "udp_relay_send",
                        "Error enviando UDP vía UDPGW (${ip.dstIp}:${udp.dstPort}): ${err.message}"
                    )
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
            val existing = tcpSessions[key]
            if (existing != null && !existing.closed && existing.initialClientSeq == tcp.seqNum) {
                // Retransmitted SYN while connecting or established: do NOT tear down tunnel channel!
                if (existing.connected) {
                    scope.launch {
                        val synAck = IpPacketParser.buildIpv4TcpPacket(
                            srcIpBytes = existing.serverIpBytes,
                            dstIpBytes = existing.clientIpBytes,
                            srcPort = key.dstPort,
                            dstPort = key.srcPort,
                            seqNum = existing.initialServerSeq,
                            ackNum = (existing.initialClientSeq + 1L) and 0xFFFFFFFFL,
                            flags = IpPacketParser.TCP_SYN or IpPacketParser.TCP_ACK,
                            mssOption = calculateTunMss()
                        )
                        writePacketToTun(synAck)
                    }
                }
                return
            }

            tcpSessions.remove(key)?.close()
            val initialMySeq = seqSeed.addAndGet(65536L) and 0xFFFFFFFFL
            val initialAck = (tcp.seqNum + 1L) and 0xFFFFFFFFL
            val session = TcpSession(
                key = key,
                clientIpBytes = ip.srcIpBytes,
                serverIpBytes = ip.dstIpBytes,
                initialClientSeq = tcp.seqNum,
                initialServerSeq = initialMySeq,
                clientNextSeq = initialAck,
                mySeq = (initialMySeq + 1L) and 0xFFFFFFFFL
            ).apply {
                clientWindow = tcp.windowSize.coerceAtLeast(4096)
            }
            tcpSessions[key] = session
            statisticsManager.setActiveTcpConnections(tcpSessions.size)

            session.connectorJob = scope.launch {
                try {
                    val tunnelStream = engine.openTcpTunnel(ip.dstIp, tcp.dstPort)
                    if (session.closed || !running.get()) {
                        tunnelStream.close()
                        return@launch
                    }
                    session.stream = tunnelStream
                    session.connected = true
                    session.streamReady.complete(tunnelStream)

                    val synAck = IpPacketParser.buildIpv4TcpPacket(
                        srcIpBytes = session.serverIpBytes,
                        dstIpBytes = session.clientIpBytes,
                        srcPort = key.dstPort,
                        dstPort = key.srcPort,
                        seqNum = session.initialServerSeq,
                        ackNum = initialAck,
                        flags = IpPacketParser.TCP_SYN or IpPacketParser.TCP_ACK,
                        mssOption = calculateTunMss()
                    )
                    writePacketToTun(synAck)
                    startOutboundWriter(session)
                    startTunnelReader(session, tunnelStream)
                } catch (e: Throwable) {
                    session.streamReady.completeExceptionally(e)
                    AppLogger.debug(TAG, "TCP ${ip.dstIp}:${tcp.dstPort} falló: ${e.message}")
                    val rst = IpPacketParser.buildIpv4TcpPacket(
                        srcIpBytes = ip.dstIpBytes,
                        dstIpBytes = ip.srcIpBytes,
                        srcPort = tcp.dstPort,
                        dstPort = tcp.srcPort,
                        seqNum = 0L,
                        ackNum = initialAck,
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

        session.clientWindow = tcp.windowSize

        if (tcp.payloadLength > 0) {
            val payload = buffer.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
            var shouldAck = false
            synchronized(session.seqLock) {
                val expected = session.clientNextSeq
                val diff = seqDiff(tcp.seqNum, expected)
                when {
                    diff == 0 -> {
                        enqueueInOrder(session, payload)
                        drainOutOfOrderBuffer(session)
                        shouldAck = true
                    }
                    diff < 0 -> {
                        val endDiff = seqDiff((tcp.seqNum + payload.size.toLong()) and 0xFFFFFFFFL, expected)
                        if (endDiff > 0) {
                            val overlap = (-diff).coerceAtLeast(0)
                            if (overlap < payload.size) {
                                enqueueInOrder(session, payload.copyOfRange(overlap, payload.size))
                                drainOutOfOrderBuffer(session)
                            }
                        }
                        shouldAck = true
                    }
                    diff in 1..65536 -> {
                        if (session.outOfOrder.size < 32) {
                            session.outOfOrder[tcp.seqNum] = payload
                        }
                        shouldAck = true
                    }
                }
            }

            if (shouldAck) {
                val ackNumber = synchronized(session.seqLock) { session.clientNextSeq }
                scope.launch {
                    val ackPkt = IpPacketParser.buildIpv4TcpPacket(
                        srcIpBytes = session.serverIpBytes,
                        dstIpBytes = session.clientIpBytes,
                        srcPort = key.dstPort,
                        dstPort = key.srcPort,
                        seqNum = session.mySeq,
                        ackNum = ackNumber,
                        flags = IpPacketParser.TCP_ACK
                    )
                    writePacketToTun(ackPkt)
                }
            }
        }

        if (isFin) {
            val ackNumber = synchronized(session.seqLock) {
                val expectedEnd = (tcp.seqNum + tcp.payloadLength.toLong()) and 0xFFFFFFFFL
                if (seqDiff(expectedEnd, session.clientNextSeq) == 0) {
                    session.clientNextSeq = (session.clientNextSeq + 1L) and 0xFFFFFFFFL
                }
                session.clientNextSeq
            }
            session.clientFinReceived = true
            session.outboundChannel.close()
            scope.launch {
                val finAck = IpPacketParser.buildIpv4TcpPacket(
                    srcIpBytes = session.serverIpBytes,
                    dstIpBytes = session.clientIpBytes,
                    srcPort = key.dstPort,
                    dstPort = key.srcPort,
                    seqNum = session.mySeq,
                    ackNum = ackNumber,
                    flags = IpPacketParser.TCP_ACK
                )
                writePacketToTun(finAck)
                if (session.serverFinSent) {
                    closeSession(key)
                }
            }
        } else if (isAck && session.serverFinSent && tcp.payloadLength == 0) {
            // Final ACK from client acknowledging our server FIN
            if (session.clientFinReceived || seqDiff(tcp.ackNum, session.mySeq) >= 0) {
                closeSession(key)
            }
        }
    }

    private fun enqueueInOrder(session: TcpSession, data: ByteArray) {
        if (data.isEmpty()) return
        session.clientNextSeq = (session.clientNextSeq + data.size.toLong()) and 0xFFFFFFFFL
        session.outboundChannel.trySend(data)
    }

    private fun drainOutOfOrderBuffer(session: TcpSession) {
        while (session.outOfOrder.isNotEmpty()) {
            val nextSeq = session.clientNextSeq
            val buffered = session.outOfOrder.remove(nextSeq) ?: break
            enqueueInOrder(session, buffered)
        }
    }

    private fun startOutboundWriter(session: TcpSession) {
        session.writerJob = scope.launch {
            val stream = runCatching { session.streamReady.await() }.getOrNull() ?: return@launch
            try {
                for (chunk in session.outboundChannel) {
                    if (session.closed || !running.get()) break
                    stream.outputStream.write(chunk)
                    stream.outputStream.flush()
                    statisticsManager.recordBytesSent(chunk.size.toLong())
                }
                if (session.clientFinReceived && !session.closed) {
                    // Half-close outbound stream so server receives EOF while keeping readerJob alive!
                    stream.halfCloseOutput()
                }
            } catch (_: Throwable) {
                if (!session.closed) sendRstAndClose(session)
            }
        }
    }

    private fun startTunnelReader(session: TcpSession, stream: TunnelStream) {
        val mss = calculateTunMss()
        session.readerJob = scope.launch {
            val readBuf = ByteArray(mss)
            try {
                while (isActive && running.get() && !session.closed) {
                    val n = stream.inputStream.read(readBuf)
                    if (n == -1) {
                        session.serverFinSent = true
                        val ackNum = synchronized(session.seqLock) { session.clientNextSeq }
                        val finPkt = IpPacketParser.buildIpv4TcpPacket(
                            srcIpBytes = session.serverIpBytes,
                            dstIpBytes = session.clientIpBytes,
                            srcPort = session.key.dstPort,
                            dstPort = session.key.srcPort,
                            seqNum = session.mySeq,
                            ackNum = ackNum,
                            flags = IpPacketParser.TCP_FIN or IpPacketParser.TCP_ACK
                        )
                        session.mySeq = (session.mySeq + 1L) and 0xFFFFFFFFL
                        writePacketToTun(finPkt)
                        if (session.clientFinReceived) {
                            closeSession(session.key)
                        }
                        break
                    }
                    if (n > 0) {
                        statisticsManager.recordBytesReceived(n.toLong())
                        val ackNum = synchronized(session.seqLock) { session.clientNextSeq }
                        val dataPkt = IpPacketParser.buildIpv4TcpPacket(
                            srcIpBytes = session.serverIpBytes,
                            dstIpBytes = session.clientIpBytes,
                            srcPort = session.key.dstPort,
                            dstPort = session.key.srcPort,
                            seqNum = session.mySeq,
                            ackNum = ackNum,
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
        val ackNum = synchronized(session.seqLock) { session.clientNextSeq }
        val rst = IpPacketParser.buildIpv4TcpPacket(
            srcIpBytes = session.serverIpBytes,
            dstIpBytes = session.clientIpBytes,
            srcPort = session.key.dstPort,
            dstPort = session.key.srcPort,
            seqNum = session.mySeq,
            ackNum = ackNum,
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

    private fun calculateTunMss(): Int = (profile.mtu - 60).coerceIn(512, 1400)

    private fun seqDiff(a: Long, b: Long): Int = ((a - b) and 0xFFFFFFFFL).toInt()

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        readLoopJob?.cancel()
        udpReceiverJob?.cancel()
        tcpSessions.values.forEach { it.close() }
        tcpSessions.clear()
        udpFlowClients.clear()
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
