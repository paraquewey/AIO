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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
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
    private val dnsSemaphore = Semaphore(24)
    private val running = AtomicBoolean(false)
    private val outputStream = FileOutputStream(tunFd.fileDescriptor)
    private val inputStream = FileInputStream(tunFd.fileDescriptor)
    private val tcpSessions = ConcurrentHashMap<TcpFlowKey, TcpSession>()
    private val udpFlowClients = ConcurrentHashMap<UdpFlowKey, UdpClientEndpoints>()

    // Dual-tier QoS channels (Fast-Lane <= 160 bytes vs Standard Bulk > 160 bytes)
    private val priorityOutboundChannel = Channel<OutboundTask>(capacity = Channel.UNLIMITED)
    private val standardOutboundChannel = Channel<OutboundTask>(capacity = Channel.UNLIMITED)
    private val priorityTunWriteChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val standardTunWriteChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    @Volatile
    private var udpRelaySession: UdpRelaySession? = null
    private var udpReceiverJob: Job? = null
    private var readLoopJob: Job? = null
    private var tunWriterJob: Job? = null
    private val workerJobs = mutableListOf<Job>()
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

    private sealed interface OutboundTask {
        data class DnsQuery(
            val payload: ByteArray,
            val ip: IpPacketParser.Ipv4Packet,
            val udpSrcPort: Int
        ) : OutboundTask

        data class UdpForward(
            val relay: UdpRelaySession,
            val srcIp: String,
            val srcPort: Int,
            val dstIp: String,
            val dstPort: Int,
            val payload: ByteArray
        ) : OutboundTask

        data class OpenTcp(val session: TcpSession, val initialAck: Long) : OutboundTask

        data class FlushTcp(val session: TcpSession) : OutboundTask
    }

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
        val stateLock = Any()
        val streamWriteMutex = Mutex()
        val outOfOrder = TreeMap<Long, ByteArray>()
        val pendingOutbound = ArrayDeque<ByteArray>()
        val flushQueued = AtomicBoolean(false)

        @Volatile
        var stream: TunnelStream? = null

        @Volatile
        var connected: Boolean = false

        @Volatile
        var closed: Boolean = false

        @Volatile
        var clientFinReceived: Boolean = false

        @Volatile
        var halfClosedSent: Boolean = false

        @Volatile
        var serverFinSent: Boolean = false

        @Volatile
        var clientWindow: Int = 65535

        var readerJob: Job? = null

        fun close() {
            if (closed) return
            closed = true
            synchronized(stateLock) {
                outOfOrder.clear()
                pendingOutbound.clear()
            }
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
            "DNS Forwarding activo sobre túnel (QoS Fast-Lane <= $PRIORITY_PACKET_THRESHOLD_BYTES B, DNS1=${dnsConfig.primaryDns}, DNS2=${dnsConfig.secondaryDns}, modo=${dnsConfig.dnsMode.name})"
        )

        startTunWriterLoop()
        startPrioritizedOutboundWorkers()

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

    private fun startTunWriterLoop() {
        tunWriterJob?.cancel()
        tunWriterJob = scope.launch {
            while (isActive && running.get()) {
                val pkt = priorityTunWriteChannel.tryReceive().getOrNull() ?: select<ByteArray?> {
                    priorityTunWriteChannel.onReceiveCatching { it.getOrNull() }
                    standardTunWriteChannel.onReceiveCatching { it.getOrNull() }
                } ?: break

                runCatching {
                    outputStream.write(pkt)
                }
            }
        }
    }

    private fun enqueueTunWrite(packet: ByteArray, forcePriority: Boolean = false) {
        if (!running.get()) return
        if (forcePriority || packet.size <= PRIORITY_PACKET_THRESHOLD_BYTES) {
            priorityTunWriteChannel.trySend(packet)
        } else {
            standardTunWriteChannel.trySend(packet)
        }
    }

    private fun startPrioritizedOutboundWorkers() {
        workerJobs.forEach { it.cancel() }
        workerJobs.clear()
        repeat(OUTBOUND_WORKER_COUNT) {
            val job = scope.launch {
                while (isActive && running.get()) {
                    val task = priorityOutboundChannel.tryReceive().getOrNull() ?: select<OutboundTask?> {
                        priorityOutboundChannel.onReceiveCatching { it.getOrNull() }
                        standardOutboundChannel.onReceiveCatching { it.getOrNull() }
                    } ?: break

                    when (task) {
                        is OutboundTask.DnsQuery -> processDnsQuery(task)
                        is OutboundTask.UdpForward -> processUdpForward(task)
                        is OutboundTask.OpenTcp -> processOpenTcp(task.session, task.initialAck)
                        is OutboundTask.FlushTcp -> processFlushTcp(task.session)
                    }
                }
            }
            workerJobs.add(job)
        }
    }

    private suspend fun processDnsQuery(task: OutboundTask.DnsQuery) {
        if (!running.get()) return
        dnsSemaphore.withPermit {
            if (!running.get()) return@withPermit
            val dnsResp = dnsManager.resolveWireQueryThroughTunnel(
                queryBytes = task.payload,
                dnsConfig = dnsConfig,
                engine = engine,
                udpRelay = udpRelaySession
            ) ?: return@withPermit
            if (!running.get()) return@withPermit
            statisticsManager.recordDnsQuery()
            statisticsManager.recordBytesReceived(dnsResp.size.toLong())
            statisticsManager.recordPacketForwarded()
            val replyPacket = IpPacketParser.buildIpv4UdpPacket(
                srcIpBytes = task.ip.dstIpBytes,
                dstIpBytes = task.ip.srcIpBytes,
                srcPort = 53,
                dstPort = task.udpSrcPort,
                payload = dnsResp
            )
            enqueueTunWrite(replyPacket, forcePriority = true)
        }
    }

    private suspend fun processUdpForward(task: OutboundTask.UdpForward) {
        runCatching {
            task.relay.sendUdpPacket(
                srcClientIp = task.srcIp,
                srcClientPort = task.srcPort,
                destIp = task.dstIp,
                destPort = task.dstPort,
                payload = task.payload,
                isDns = false
            )
        }.onFailure { err ->
            AppLogger.warnRateLimited(
                TAG,
                "udp_relay_send",
                "Error enviando UDP vía UDPGW (${task.dstIp}:${task.dstPort}): ${err.message}"
            )
        }
    }

    private suspend fun processOpenTcp(session: TcpSession, initialAck: Long) {
        try {
            val tunnelStream = engine.openTcpTunnel(session.key.dstIp, session.key.dstPort)
            if (session.closed || !running.get()) {
                runCatching { tunnelStream.close() }
                return
            }
            session.stream = tunnelStream
            session.connected = true
            session.streamReady.complete(tunnelStream)

            val synAck = IpPacketParser.buildIpv4TcpPacket(
                srcIpBytes = session.serverIpBytes,
                dstIpBytes = session.clientIpBytes,
                srcPort = session.key.dstPort,
                dstPort = session.key.srcPort,
                seqNum = session.initialServerSeq,
                ackNum = initialAck,
                flags = IpPacketParser.TCP_SYN or IpPacketParser.TCP_ACK,
                mssOption = calculateTunMss()
            )
            enqueueTunWrite(synAck, forcePriority = true)
            startTunnelReader(session, tunnelStream)

            // Flush any early TCP payload that arrived before streamReady completed
            val hasPending = synchronized(session.stateLock) { session.pendingOutbound.isNotEmpty() || session.clientFinReceived }
            if (hasPending) {
                scheduleTcpFlush(session, isSmallPriority = true)
            }
        } catch (e: Throwable) {
            session.streamReady.completeExceptionally(e)
            AppLogger.debug(TAG, "TCP ${session.key.dstIp}:${session.key.dstPort} falló: ${e.message}")
            val rst = IpPacketParser.buildIpv4TcpPacket(
                srcIpBytes = session.serverIpBytes,
                dstIpBytes = session.clientIpBytes,
                srcPort = session.key.dstPort,
                dstPort = session.key.srcPort,
                seqNum = 0L,
                ackNum = initialAck,
                flags = IpPacketParser.TCP_RST or IpPacketParser.TCP_ACK
            )
            enqueueTunWrite(rst, forcePriority = true)
            closeSession(session.key)
        }
    }

    private suspend fun processFlushTcp(session: TcpSession) {
        val stream = runCatching { session.streamReady.await() }.getOrNull() ?: return
        if (session.closed || !running.get()) return
        try {
            session.streamWriteMutex.withLock {
                while (!session.closed && running.get()) {
                    session.flushQueued.set(false)
                    var totalWritten = 0L
                    while (!session.closed && running.get()) {
                        val chunk = synchronized(session.stateLock) {
                            session.pendingOutbound.pollFirst()
                        } ?: break
                        stream.outputStream.write(chunk)
                        totalWritten += chunk.size
                    }
                    if (totalWritten > 0L) {
                        stream.outputStream.flush()
                        statisticsManager.recordBytesSent(totalWritten)
                    }
                    if (session.clientFinReceived && !session.halfClosedSent && !session.closed) {
                        val stillEmpty = synchronized(session.stateLock) { session.pendingOutbound.isEmpty() }
                        if (stillEmpty) {
                            session.halfClosedSent = true
                            stream.halfCloseOutput()
                        }
                    }
                    val moreQueued = synchronized(session.stateLock) { session.pendingOutbound.isNotEmpty() }
                    if (!moreQueued) break
                }
            }
        } catch (_: Throwable) {
            if (!session.closed) sendRstAndClose(session)
        }
    }

    private fun scheduleTcpFlush(session: TcpSession, isSmallPriority: Boolean) {
        if (session.closed || !running.get()) return
        if (!session.flushQueued.compareAndSet(false, true)) return
        val task = OutboundTask.FlushTcp(session)
        if (isSmallPriority) {
            priorityOutboundChannel.trySend(task)
        } else {
            standardOutboundChannel.trySend(task)
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
                enqueueTunWrite(replyPacket)
            }
        }
    }

    private fun handleUdpPacket(buffer: ByteArray, ip: IpPacketParser.Ipv4Packet) {
        val udp = IpPacketParser.parseUdp(buffer, ip) ?: return
        if (udp.payloadLength <= 0) return
        val payload = buffer.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        statisticsManager.recordBytesSent(payload.size.toLong())

        if (udp.dstPort == 53) {
            // DNS always enters Fast-Lane priority queue
            priorityOutboundChannel.trySend(OutboundTask.DnsQuery(payload, ip, udp.srcPort))
        } else {
            val relay = udpRelaySession ?: return
            val flowKey = UdpFlowKey(ip.srcIp, udp.srcPort)
            udpFlowClients.putIfAbsent(flowKey, UdpClientEndpoints(ip.srcIpBytes, udp.srcPort))
            val task = OutboundTask.UdpForward(
                relay = relay,
                srcIp = ip.srcIp,
                srcPort = udp.srcPort,
                dstIp = ip.dstIp,
                dstPort = udp.dstPort,
                payload = payload
            )
            if (payload.size <= PRIORITY_PACKET_THRESHOLD_BYTES) {
                priorityOutboundChannel.trySend(task)
            } else {
                standardOutboundChannel.trySend(task)
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
                if (existing.connected) {
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
                    enqueueTunWrite(synAck, forcePriority = true)
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
            // TCP SYN handshakes always use Fast-Lane priority queue
            priorityOutboundChannel.trySend(OutboundTask.OpenTcp(session, initialAck))
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
            var shouldFlush = false
            var seqNumForAck = 0L
            var ackNumForAck = 0L

            synchronized(session.stateLock) {
                val expected = session.clientNextSeq
                val diff = seqDiff(tcp.seqNum, expected)
                when {
                    diff == 0 -> {
                        enqueueInOrderLocked(session, payload)
                        drainOutOfOrderBufferLocked(session)
                        shouldAck = true
                        shouldFlush = session.pendingOutbound.isNotEmpty()
                    }
                    diff < 0 -> {
                        val endDiff = seqDiff((tcp.seqNum + payload.size.toLong()) and 0xFFFFFFFFL, expected)
                        if (endDiff > 0) {
                            val overlap = (-diff).coerceAtLeast(0)
                            if (overlap < payload.size) {
                                enqueueInOrderLocked(session, payload.copyOfRange(overlap, payload.size))
                                drainOutOfOrderBufferLocked(session)
                                shouldFlush = session.pendingOutbound.isNotEmpty()
                            }
                        }
                        shouldAck = true
                    }
                    diff in 1..65536 -> {
                        if (session.outOfOrder.size < 64) {
                            session.outOfOrder[tcp.seqNum] = payload
                        }
                        shouldAck = true
                    }
                }
                if (shouldAck) {
                    seqNumForAck = session.mySeq
                    ackNumForAck = session.clientNextSeq
                }
            }

            if (shouldFlush) {
                scheduleTcpFlush(session, isSmallPriority = payload.size <= PRIORITY_PACKET_THRESHOLD_BYTES)
            }
            if (shouldAck) {
                val ackPkt = IpPacketParser.buildIpv4TcpPacket(
                    srcIpBytes = session.serverIpBytes,
                    dstIpBytes = session.clientIpBytes,
                    srcPort = key.dstPort,
                    dstPort = key.srcPort,
                    seqNum = seqNumForAck,
                    ackNum = ackNumForAck,
                    flags = IpPacketParser.TCP_ACK
                )
                enqueueTunWrite(ackPkt, forcePriority = true)
            }
        }

        if (isFin) {
            var seqNumForFinAck: Long
            var ackNumForFinAck: Long
            var bothFinDone = false
            synchronized(session.stateLock) {
                val expectedEnd = (tcp.seqNum + tcp.payloadLength.toLong()) and 0xFFFFFFFFL
                if (seqDiff(expectedEnd, session.clientNextSeq) == 0) {
                    session.clientNextSeq = (session.clientNextSeq + 1L) and 0xFFFFFFFFL
                }
                session.clientFinReceived = true
                seqNumForFinAck = session.mySeq
                ackNumForFinAck = session.clientNextSeq
                bothFinDone = session.serverFinSent && session.pendingOutbound.isEmpty()
            }
            scheduleTcpFlush(session, isSmallPriority = true)
            val finAck = IpPacketParser.buildIpv4TcpPacket(
                srcIpBytes = session.serverIpBytes,
                dstIpBytes = session.clientIpBytes,
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seqNum = seqNumForFinAck,
                ackNum = ackNumForFinAck,
                flags = IpPacketParser.TCP_ACK
            )
            enqueueTunWrite(finAck, forcePriority = true)
            if (bothFinDone) {
                closeSession(key)
            }
        } else if (isAck && session.serverFinSent && tcp.payloadLength == 0) {
            val shouldClose = synchronized(session.stateLock) {
                session.clientFinReceived || seqDiff(tcp.ackNum, session.mySeq) >= 0
            }
            if (shouldClose) {
                closeSession(key)
            }
        }
    }

    private fun enqueueInOrderLocked(session: TcpSession, data: ByteArray) {
        if (data.isEmpty()) return
        session.clientNextSeq = (session.clientNextSeq + data.size.toLong()) and 0xFFFFFFFFL
        session.pendingOutbound.addLast(data)
    }

    private fun drainOutOfOrderBufferLocked(session: TcpSession) {
        while (session.outOfOrder.isNotEmpty()) {
            val nextSeq = session.clientNextSeq
            val buffered = session.outOfOrder.remove(nextSeq) ?: break
            enqueueInOrderLocked(session, buffered)
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
                        var finPkt: ByteArray
                        var closeNow = false
                        synchronized(session.stateLock) {
                            session.serverFinSent = true
                            finPkt = IpPacketParser.buildIpv4TcpPacket(
                                srcIpBytes = session.serverIpBytes,
                                dstIpBytes = session.clientIpBytes,
                                srcPort = session.key.dstPort,
                                dstPort = session.key.srcPort,
                                seqNum = session.mySeq,
                                ackNum = session.clientNextSeq,
                                flags = IpPacketParser.TCP_FIN or IpPacketParser.TCP_ACK
                            )
                            session.mySeq = (session.mySeq + 1L) and 0xFFFFFFFFL
                            closeNow = session.clientFinReceived
                        }
                        enqueueTunWrite(finPkt, forcePriority = true)
                        if (closeNow) {
                            closeSession(session.key)
                        }
                        break
                    }
                    if (n > 0) {
                        statisticsManager.recordBytesReceived(n.toLong())
                        var dataPkt: ByteArray
                        synchronized(session.stateLock) {
                            dataPkt = IpPacketParser.buildIpv4TcpPacket(
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
                        }
                        enqueueTunWrite(dataPkt, forcePriority = n <= PRIORITY_PACKET_THRESHOLD_BYTES)
                    }
                }
            } catch (_: Throwable) {
                if (!session.closed) sendRstAndClose(session)
            }
        }
    }

    private fun sendRstAndClose(session: TcpSession) {
        val rst = synchronized(session.stateLock) {
            IpPacketParser.buildIpv4TcpPacket(
                srcIpBytes = session.serverIpBytes,
                dstIpBytes = session.clientIpBytes,
                srcPort = session.key.dstPort,
                dstPort = session.key.srcPort,
                seqNum = session.mySeq,
                ackNum = session.clientNextSeq,
                flags = IpPacketParser.TCP_RST or IpPacketParser.TCP_ACK
            )
        }
        enqueueTunWrite(rst, forcePriority = true)
        closeSession(session.key)
    }

    private fun closeSession(key: TcpFlowKey) {
        tcpSessions.remove(key)?.close()
        statisticsManager.setActiveTcpConnections(tcpSessions.size)
    }

    private fun calculateTunMss(): Int = (profile.mtu - 60).coerceIn(512, 1400)

    private fun seqDiff(a: Long, b: Long): Int = ((a - b) and 0xFFFFFFFFL).toInt()

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        readLoopJob?.cancel()
        tunWriterJob?.cancel()
        workerJobs.forEach { it.cancel() }
        workerJobs.clear()
        priorityOutboundChannel.close()
        standardOutboundChannel.close()
        priorityTunWriteChannel.close()
        standardTunWriteChannel.close()
        udpReceiverJob?.cancel()
        tcpSessions.values.forEach { it.close() }
        tcpSessions.clear()
        udpFlowClients.clear()
        statisticsManager.setActiveTcpConnections(0)
        runCatching { udpRelaySession?.close() }
        udpRelaySession = null
        runCatching { dnsManager.close() }
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        scope.cancel()
        AppLogger.info(TAG, "Router TUN detenido y recursos liberados.")
    }

    companion object {
        private const val TAG = "TunPacketRouter"
        const val PRIORITY_PACKET_THRESHOLD_BYTES = 160
        private const val OUTBOUND_WORKER_COUNT = 16
    }
}
