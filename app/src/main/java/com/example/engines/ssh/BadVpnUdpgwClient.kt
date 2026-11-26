package com.example.engines.ssh

import com.example.core.logger.AppLogger
import com.example.engines.adapter.TunnelStream
import com.example.engines.adapter.UdpPacketResult
import com.example.engines.adapter.UdpRelayInboundPacket
import com.example.engines.adapter.UdpRelaySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * BadVPN UDPGW client protocol implementation over an SSH `direct-tcpip` TCP stream
 * (typically targeting 127.0.0.1:7300 on the remote SSH server).
 *
 * Wire format per frame:
 *   - uint16_le length (bytes following the 2-byte length prefix)
 *   - uint8     flags (0x01=KEEPALIVE, 0x02=REBIND, 0x04=DNS, 0x08=IPV6)
 *   - uint16_le connId (when flags != KEEPALIVE)
 *   - uint8[4|16] address (remote IPv4 or IPv6 in network order)
 *   - uint16_be port (remote UDP port in network byte order)
 *   - uint8[]   UDP payload
 */
class BadVpnUdpgwClient(
    private val openChannel: suspend () -> TunnelStream
) : UdpRelaySession {

    data class ClientFlowKey(
        val clientIp: String,
        val clientPort: Int
    )

    data class UdpgwPacket(
        val flags: Int,
        val connId: Int,
        val remoteIp: String,
        val remotePort: Int,
        val payload: ByteArray
    )

    override val isDnsCapable: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val writeMutex = Mutex()
    private val reconnectMutex = Mutex()
    private val nextConnId = AtomicInteger(1)

    private val flowToConnId = ConcurrentHashMap<ClientFlowKey, Int>()
    private val connIdToFlow = ConcurrentHashMap<Int, ClientFlowKey>()
    private val pendingDnsByConnId = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
    private val inboundChannel = Channel<UdpgwPacket>(capacity = 256)

    @Volatile
    private var stream: TunnelStream? = null
    private var readerJob: Job? = null
    private var keepAliveJob: Job? = null

    suspend fun connectInitial(): Boolean {
        return ensureConnected() != null
    }

    override suspend fun sendDatagram(destIp: String, destPort: Int, payload: ByteArray) {
        sendUdpPacket(
            srcClientIp = "10.89.0.2",
            srcClientPort = 0,
            destIp = destIp,
            destPort = destPort,
            payload = payload,
            isDns = (destPort == 53)
        )
    }

    override suspend fun sendUdpPacket(
        srcClientIp: String,
        srcClientPort: Int,
        destIp: String,
        destPort: Int,
        payload: ByteArray,
        isDns: Boolean
    ) {
        if (closed.get() || payload.isEmpty()) return
        val activeStream = ensureConnected() ?: throw IOException("Canal UDPGW no conectado.")
        val connId = getOrCreateConnId(ClientFlowKey(srcClientIp, srcClientPort))
        val flags = if (isDns) FLAG_DNS else 0
        val frame = encodeDataFrame(
            flags = flags,
            connId = connId,
            destIp = destIp,
            destPort = destPort,
            payload = payload
        )
        writeFrameLocked(activeStream, frame)
    }

    override suspend fun resolveDnsQuery(
        dnsServerIp: String,
        queryBytes: ByteArray,
        timeoutMs: Long
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (closed.get() || queryBytes.size < 12) return@withContext null
        val activeStream = ensureConnected() ?: return@withContext null
        val connId = allocateEphemeralConnId()
        val deferred = CompletableDeferred<ByteArray>()
        pendingDnsByConnId[connId] = deferred

        try {
            val frame = encodeDataFrame(
                flags = FLAG_DNS,
                connId = connId,
                destIp = dnsServerIp,
                destPort = 53,
                payload = queryBytes
            )
            writeFrameLocked(activeStream, frame)
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
        } catch (err: Throwable) {
            AppLogger.warnRateLimited(
                TAG,
                "udpgw_dns_${dnsServerIp}",
                "Error UDPGW DNS ($dnsServerIp:53): ${err.message ?: err.javaClass.simpleName}"
            )
            null
        } finally {
            pendingDnsByConnId.remove(connId)
        }
    }

    override suspend fun receiveDatagram(buffer: ByteArray): UdpPacketResult? {
        val pkt = receiveUdpPacket(buffer) ?: return null
        return UdpPacketResult(pkt.remoteIp, pkt.remotePort, pkt.length)
    }

    override suspend fun receiveUdpPacket(buffer: ByteArray): UdpRelayInboundPacket? {
        if (closed.get()) return null
        val pkt = try {
            inboundChannel.receive()
        } catch (_: Throwable) {
            return null
        }
        val copyLen = minOf(pkt.payload.size, buffer.size)
        System.arraycopy(pkt.payload, 0, buffer, 0, copyLen)
        val flow = connIdToFlow[pkt.connId]
        return UdpRelayInboundPacket(
            clientIp = flow?.clientIp,
            clientPort = flow?.clientPort,
            remoteIp = pkt.remoteIp,
            remotePort = pkt.remotePort,
            length = copyLen
        )
    }

    private suspend fun ensureConnected(): TunnelStream? {
        stream?.let { return it }
        return reconnectMutex.withLock {
            stream?.let { return@withLock it }
            if (closed.get()) return@withLock null
            val newStream = runCatching { openChannel() }.getOrNull() ?: return@withLock null
            stream = newStream
            startReaderLoop(newStream)
            startKeepAliveLoop()
            newStream
        }
    }

    private fun startReaderLoop(activeStream: TunnelStream) {
        readerJob?.cancel()
        readerJob = scope.launch {
            try {
                val input = activeStream.inputStream
                while (isActive && !closed.get()) {
                    val pkt = readFrame(input) ?: break
                    if ((pkt.flags and FLAG_KEEPALIVE) != 0) continue
                    val dnsWaiter = pendingDnsByConnId.remove(pkt.connId)
                    if (dnsWaiter != null) {
                        dnsWaiter.complete(pkt.payload)
                    } else {
                        inboundChannel.trySend(pkt)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                if (stream === activeStream) {
                    stream = null
                }
                runCatching { activeStream.close() }
            }
        }
    }

    private fun startKeepAliveLoop() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && !closed.get()) {
                delay(20_000L)
                val activeStream = stream ?: continue
                runCatching {
                    writeFrameLocked(activeStream, KEEPALIVE_FRAME)
                }
            }
        }
    }

    private suspend fun writeFrameLocked(activeStream: TunnelStream, frame: ByteArray) {
        writeMutex.withLock {
            try {
                activeStream.outputStream.write(frame)
                activeStream.outputStream.flush()
            } catch (e: IOException) {
                if (stream === activeStream) {
                    stream = null
                }
                runCatching { activeStream.close() }
                throw e
            }
        }
    }

    private fun getOrCreateConnId(flow: ClientFlowKey): Int {
        flowToConnId[flow]?.let { return it }
        val id = allocateEphemeralConnId()
        val prev = flowToConnId.putIfAbsent(flow, id)
        return if (prev != null) {
            prev
        } else {
            connIdToFlow[id] = flow
            id
        }
    }

    private fun allocateEphemeralConnId(): Int {
        // Reserve high IDs or roll across 1..65535 avoiding collisions
        repeat(64) {
            val candidate = ((nextConnId.getAndIncrement() and 0x7FFFFFFF) % 65000) + 1
            if (!connIdToFlow.containsKey(candidate) && !pendingDnsByConnId.containsKey(candidate)) {
                return candidate
            }
        }
        return ((nextConnId.getAndIncrement() and 0x7FFFFFFF) % 65000) + 1
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        keepAliveJob?.cancel()
        readerJob?.cancel()
        pendingDnsByConnId.values.forEach { it.cancel() }
        pendingDnsByConnId.clear()
        runCatching { stream?.close() }
        stream = null
        inboundChannel.close()
        scope.cancel()
    }

    companion object {
        private const val TAG = "BadVpnUdpgw"
        const val FLAG_KEEPALIVE = 0x01
        const val FLAG_REBIND = 0x02
        const val FLAG_DNS = 0x04
        const val FLAG_IPV6 = 0x08

        // Length=1 (uint16_le: 0x01, 0x00), Flags=0x01 (KEEPALIVE)
        val KEEPALIVE_FRAME = byteArrayOf(0x01, 0x00, FLAG_KEEPALIVE.toByte())

        fun encodeDataFrame(
            flags: Int,
            connId: Int,
            destIp: String,
            destPort: Int,
            payload: ByteArray
        ): ByteArray {
            val ipBytes = InetAddress.getByName(destIp).address
            val effectiveFlags = if (ipBytes.size == 16) flags or FLAG_IPV6 else (flags and FLAG_IPV6.inv())
            val bodyLen = 1 + 2 + ipBytes.size + 2 + payload.size
            val out = ByteArrayOutputStream(2 + bodyLen)

            // Frame length (uint16_le)
            out.write(bodyLen and 0xFF)
            out.write((bodyLen ushr 8) and 0xFF)
            // Flags (uint8)
            out.write(effectiveFlags and 0xFF)
            // Connection ID (uint16_le)
            out.write(connId and 0xFF)
            out.write((connId ushr 8) and 0xFF)
            // Remote IP (4 or 16 bytes, network order)
            out.write(ipBytes)
            // Remote Port (uint16_be)
            out.write((destPort ushr 8) and 0xFF)
            out.write(destPort and 0xFF)
            // Payload
            out.write(payload)
            return out.toByteArray()
        }

        fun readFrame(input: InputStream): UdpgwPacket? {
            val lenBytes = ByteArray(2)
            if (!readFullyOrEof(input, lenBytes, 0, 2)) return null
            val frameLen = (lenBytes[0].toInt() and 0xFF) or ((lenBytes[1].toInt() and 0xFF) shl 8)
            if (frameLen <= 0 || frameLen > 65535) {
                throw IOException("Longitud UDPGW inválida: $frameLen")
            }
            val body = ByteArray(frameLen)
            if (!readFullyOrEof(input, body, 0, frameLen)) {
                throw EOFException("EOF leyendo frame UDPGW ($frameLen bytes).")
            }
            val flags = body[0].toInt() and 0xFF
            if ((flags and FLAG_KEEPALIVE) != 0 || frameLen < 9) {
                return UdpgwPacket(flags, 0, "0.0.0.0", 0, ByteArray(0))
            }
            val connId = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
            val ipLen = if ((flags and FLAG_IPV6) != 0) 16 else 4
            if (frameLen < 3 + ipLen + 2) {
                throw IOException("Frame UDPGW truncado ($frameLen bytes).")
            }
            val ipBytes = body.copyOfRange(3, 3 + ipLen)
            val remoteIp = InetAddress.getByAddress(ipBytes).hostAddress ?: "0.0.0.0"
            val portOffset = 3 + ipLen
            val remotePort = ((body[portOffset].toInt() and 0xFF) shl 8) or (body[portOffset + 1].toInt() and 0xFF)
            val payloadOffset = portOffset + 2
            val payload = body.copyOfRange(payloadOffset, frameLen)
            return UdpgwPacket(
                flags = flags,
                connId = connId,
                remoteIp = remoteIp,
                remotePort = remotePort,
                payload = payload
            )
        }

        private fun readFullyOrEof(input: InputStream, buf: ByteArray, off: Int, len: Int): Boolean {
            var readTotal = 0
            while (readTotal < len) {
                val n = input.read(buf, off + readTotal, len - readTotal)
                if (n == -1) return if (readTotal == 0) false else throw EOFException("EOF parcial")
                readTotal += n
            }
            return true
        }
    }
}
