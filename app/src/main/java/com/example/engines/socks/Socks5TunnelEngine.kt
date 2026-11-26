package com.example.engines.socks

import com.example.core.logger.AppLogger
import com.example.domain.models.EngineFamily
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelStatistics
import com.example.engines.adapter.EngineContext
import com.example.engines.adapter.EngineStatus
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import com.example.engines.adapter.UdpPacketResult
import com.example.engines.adapter.UdpRelaySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class Socks5TunnelEngine : TunnelEngine {
    override val engineFamily: EngineFamily = EngineFamily.SOCKS5
    override val engineName: String = "SOCKS5 Engine (RFC 1928/1929)"

    private val _status = MutableStateFlow(EngineStatus.IDLE)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private var context: EngineContext? = null
    private var activeProfile: TunnelProfile? = null
    private var activeSecrets: ProfileSecrets = ProfileSecrets()

    override suspend fun validateConfiguration(
        profile: TunnelProfile,
        secrets: ProfileSecrets
    ): Result<Unit> {
        if (profile.host.isBlank()) {
            return Result.failure(IllegalArgumentException("El host SOCKS5 no puede estar vacío."))
        }
        if (profile.port !in 1..65535) {
            return Result.failure(IllegalArgumentException("El puerto SOCKS5 debe estar entre 1 y 65535."))
        }
        if (profile.username.toByteArray().size > 255 || secrets.password.toByteArray().size > 255) {
            return Result.failure(IllegalArgumentException("Usuario o contraseña excede los 255 bytes (RFC 1929)."))
        }
        return Result.success(Unit)
    }

    override suspend fun initialize(context: EngineContext) {
        this.context = context
        _status.value = EngineStatus.INITIALIZED
    }

    override suspend fun start(profile: TunnelProfile, secrets: ProfileSecrets): Result<Unit> =
        withContext(Dispatchers.IO) {
            val validation = validateConfiguration(profile, secrets)
            if (validation.isFailure) return@withContext validation

            _status.value = EngineStatus.STARTING
            activeProfile = profile
            activeSecrets = secrets

            return@withContext runCatching {
                val probeSocket = openAndAuthenticateSocket(profile, secrets)
                probeSocket.close()
                _status.value = EngineStatus.RUNNING
                AppLogger.info(TAG, "Motor SOCKS5 validado contra ${profile.host}:${profile.port}")
            }.onFailure { err ->
                _status.value = EngineStatus.ERROR
                AppLogger.error(TAG, "Error en handshake SOCKS5 ${profile.host}:${profile.port}", err)
            }
        }

    override suspend fun stop() {
        _status.value = EngineStatus.STOPPING
        activeProfile = null
        activeSecrets = ProfileSecrets()
        _status.value = EngineStatus.IDLE
    }

    override suspend fun openTcpTunnel(targetHost: String, targetPort: Int): TunnelStream =
        withContext(Dispatchers.IO) {
            val profile = activeProfile ?: throw IOException("Motor SOCKS5 no está activo.")
            val socket = openAndAuthenticateSocket(profile, activeSecrets)
            try {
                sendCommand(socket, CMD_CONNECT, targetHost, targetPort)
                object : TunnelStream {
                    override val inputStream: InputStream = socket.getInputStream()
                    override val outputStream: OutputStream = socket.getOutputStream()
                    override fun close() {
                        runCatching { socket.close() }
                    }
                }
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }

    override suspend fun openUdpRelay(): UdpRelaySession? = withContext(Dispatchers.IO) {
        val profile = activeProfile ?: return@withContext null
        val ctx = context ?: return@withContext null
        val controlSocket = openAndAuthenticateSocket(profile, activeSecrets)
        try {
            val relayAddr = sendCommand(controlSocket, CMD_UDP_ASSOCIATE, "0.0.0.0", 0)
            val udpSocket = DatagramSocket()
            if (!ctx.protectDatagramSocket(udpSocket)) {
                udpSocket.close()
                controlSocket.close()
                return@withContext null
            }
            udpSocket.soTimeout = 10_000
            val targetHost = if (relayAddr.first == "0.0.0.0") profile.host else relayAddr.first
            val targetEndpoint = InetSocketAddress(targetHost, relayAddr.second)

            object : UdpRelaySession {
                override suspend fun sendDatagram(destIp: String, destPort: Int, payload: ByteArray) {
                    withContext(Dispatchers.IO) {
                        val ipBytes = InetAddress.getByName(destIp).address
                        val out = ByteArrayOutputStream(10 + payload.size)
                        out.write(0) // RSV
                        out.write(0) // RSV
                        out.write(0) // FRAG
                        if (ipBytes.size == 4) {
                            out.write(ATYP_IPV4)
                            out.write(ipBytes)
                        } else {
                            out.write(ATYP_IPV6)
                            out.write(ipBytes)
                        }
                        out.write((destPort ushr 8) and 0xFF)
                        out.write(destPort and 0xFF)
                        out.write(payload)
                        val bytes = out.toByteArray()
                        udpSocket.send(DatagramPacket(bytes, bytes.size, targetEndpoint))
                    }
                }

                override suspend fun receiveDatagram(buffer: ByteArray): UdpPacketResult? =
                    withContext(Dispatchers.IO) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket.receive(packet)
                        if (packet.length < 10) return@withContext null
                        val atyp = buffer[3].toInt() and 0xFF
                        if (atyp != ATYP_IPV4) return@withContext null
                        val srcIp = "${buffer[4].toInt() and 0xFF}.${buffer[5].toInt() and 0xFF}.${buffer[6].toInt() and 0xFF}.${buffer[7].toInt() and 0xFF}"
                        val srcPort = ((buffer[8].toInt() and 0xFF) shl 8) or (buffer[9].toInt() and 0xFF)
                        val payloadLen = packet.length - 10
                        System.arraycopy(buffer, 10, buffer, 0, payloadLen)
                        UdpPacketResult(srcIp, srcPort, payloadLen)
                    }

                override fun close() {
                    runCatching { udpSocket.close() }
                    runCatching { controlSocket.close() }
                }
            }
        } catch (_: Throwable) {
            runCatching { controlSocket.close() }
            null
        }
    }

    private fun openAndAuthenticateSocket(profile: TunnelProfile, secrets: ProfileSecrets): Socket {
        val ctx = context ?: throw IOException("EngineContext ausente.")
        val socket = Socket()
        socket.tcpNoDelay = true
        if (!ctx.protectSocket(socket)) {
            socket.close()
            throw IOException("VpnService.protect() rechazó el socket SOCKS5.")
        }
        socket.connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS

        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())

        val hasCreds = profile.username.isNotBlank() || secrets.password.isNotEmpty()
        if (hasCreds) {
            out.write(byteArrayOf(SOCKS_VERSION, 2, AUTH_NONE, AUTH_USER_PASS))
        } else {
            out.write(byteArrayOf(SOCKS_VERSION, 1, AUTH_NONE))
        }
        out.flush()

        val ver = input.readUnsignedByte()
        val method = input.readUnsignedByte()
        if (ver != SOCKS_VERSION.toInt()) {
            throw IOException("Versión SOCKS no soportada: $ver")
        }
        when (method) {
            AUTH_NONE.toInt() -> Unit
            AUTH_USER_PASS.toInt() -> {
                val uBytes = profile.username.toByteArray(StandardCharsets.UTF_8)
                val pBytes = secrets.password.toByteArray(StandardCharsets.UTF_8)
                val authBuf = ByteArrayOutputStream(3 + uBytes.size + pBytes.size)
                authBuf.write(0x01)
                authBuf.write(uBytes.size)
                authBuf.write(uBytes)
                authBuf.write(pBytes.size)
                authBuf.write(pBytes)
                out.write(authBuf.toByteArray())
                out.flush()
                val authVer = input.readUnsignedByte()
                val authStatus = input.readUnsignedByte()
                if (authVer != 0x01 || authStatus != 0x00) {
                    throw IOException("Autenticación SOCKS5 fallida (código $authStatus).")
                }
            }
            0xFF -> throw IOException("El servidor SOCKS5 no aceptó ningún método de autenticación.")
            else -> throw IOException("Método SOCKS5 desconocido: $method")
        }
        return socket
    }

    private fun sendCommand(
        socket: Socket,
        cmd: Int,
        targetHost: String,
        targetPort: Int
    ): Pair<String, Int> {
        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        val req = ByteArrayOutputStream()
        req.write(SOCKS_VERSION.toInt())
        req.write(cmd)
        req.write(0x00)

        val ipv4 = parseIpv4OrNull(targetHost)
        if (ipv4 != null) {
            req.write(ATYP_IPV4)
            req.write(ipv4)
        } else {
            val hostBytes = targetHost.toByteArray(StandardCharsets.UTF_8)
            req.write(ATYP_DOMAIN)
            req.write(hostBytes.size)
            req.write(hostBytes)
        }
        req.write((targetPort ushr 8) and 0xFF)
        req.write(targetPort and 0xFF)
        out.write(req.toByteArray())
        out.flush()

        val ver = input.readUnsignedByte()
        val rep = input.readUnsignedByte()
        input.readUnsignedByte() // RSV
        val atyp = input.readUnsignedByte()
        if (ver != SOCKS_VERSION.toInt() || rep != 0x00) {
            throw IOException("SOCKS5 rechazó la petición ($cmd) con código $rep.")
        }
        val bndHost = when (atyp) {
            ATYP_IPV4 -> {
                val addr = ByteArray(4)
                input.readFully(addr)
                addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            ATYP_DOMAIN -> {
                val len = input.readUnsignedByte()
                val domain = ByteArray(len)
                input.readFully(domain)
                String(domain, StandardCharsets.UTF_8)
            }
            ATYP_IPV6 -> {
                val addr = ByteArray(16)
                input.readFully(addr)
                InetAddress.getByAddress(addr).hostAddress ?: "::"
            }
            else -> throw IOException("ATYP inválido en respuesta SOCKS5: $atyp")
        }
        val bndPort = (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        return bndHost to bndPort
    }

    private fun parseIpv4OrNull(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            bytes[i] = v.toByte()
        }
        return bytes
    }

    override fun getStatistics(): TunnelStatistics =
        context?.statisticsManager?.stats?.value ?: TunnelStatistics()

    override fun release() {
        activeProfile = null
        activeSecrets = ProfileSecrets()
        _status.value = EngineStatus.IDLE
    }

    companion object {
        private const val TAG = "Socks5Engine"
        private const val SOCKS_VERSION: Byte = 0x05
        private const val AUTH_NONE: Byte = 0x00
        private const val AUTH_USER_PASS: Byte = 0x02
        private const val CMD_CONNECT = 0x01
        private const val CMD_UDP_ASSOCIATE = 0x03
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
