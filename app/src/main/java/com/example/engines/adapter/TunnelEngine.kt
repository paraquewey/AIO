package com.example.engines.adapter

import com.example.core.stats.StatisticsManager
import com.example.domain.models.EngineFamily
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.SshHostKeyChallenge
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelStatistics
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.Socket

enum class EngineStatus {
    IDLE,
    INITIALIZED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

interface TunnelStream : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
    fun halfCloseOutput() {
        runCatching { outputStream.close() }
    }
}

interface UdpRelaySession : Closeable {
    val isDnsCapable: Boolean get() = false

    suspend fun sendDatagram(destIp: String, destPort: Int, payload: ByteArray)

    suspend fun sendUdpPacket(
        srcClientIp: String,
        srcClientPort: Int,
        destIp: String,
        destPort: Int,
        payload: ByteArray,
        isDns: Boolean = false
    ) {
        sendDatagram(destIp, destPort, payload)
    }

    suspend fun receiveDatagram(buffer: ByteArray): UdpPacketResult?

    suspend fun receiveUdpPacket(buffer: ByteArray): UdpRelayInboundPacket? {
        val res = receiveDatagram(buffer) ?: return null
        return UdpRelayInboundPacket(
            clientIp = null,
            clientPort = null,
            remoteIp = res.sourceIp,
            remotePort = res.sourcePort,
            length = res.length
        )
    }

    suspend fun resolveDnsQuery(
        dnsServerIp: String,
        queryBytes: ByteArray,
        timeoutMs: Long = 2_000L
    ): ByteArray? = null
}

data class UdpPacketResult(
    val sourceIp: String,
    val sourcePort: Int,
    val length: Int
)

data class UdpRelayInboundPacket(
    val clientIp: String?,
    val clientPort: Int?,
    val remoteIp: String,
    val remotePort: Int,
    val length: Int
)

data class EngineContext(
    val protectSocket: (Socket) -> Boolean,
    val protectDatagramSocket: (DatagramSocket) -> Boolean,
    val statisticsManager: StatisticsManager,
    val onSshHostKeyChallenge: suspend (SshHostKeyChallenge) -> Boolean,
    val connectionTimeoutMs: Int = 10_000
)

interface TunnelEngine {
    val engineFamily: EngineFamily
    val engineName: String
    val status: StateFlow<EngineStatus>

    suspend fun validateConfiguration(profile: TunnelProfile, secrets: ProfileSecrets): Result<Unit>
    suspend fun initialize(context: EngineContext)
    suspend fun start(profile: TunnelProfile, secrets: ProfileSecrets): Result<Unit>
    suspend fun stop()
    suspend fun openTcpTunnel(targetHost: String, targetPort: Int): TunnelStream
    suspend fun openUdpRelay(): UdpRelaySession? = null
    fun getLocalSocksPort(): Int? = null
    fun getServerBanner(): String? = null
    fun getStatistics(): TunnelStatistics
    fun release()
}
