package com.example.engines.http

import android.util.Base64
import com.example.core.logger.AppLogger
import com.example.core.stats.StatisticsManager
import com.example.domain.models.EngineFamily
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import com.example.domain.models.TunnelStatistics
import com.example.engines.adapter.EngineContext
import com.example.engines.adapter.EngineStatus
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class HttpConnectProxyEngine : TunnelEngine {
    override val engineFamily: EngineFamily = EngineFamily.HTTP_CONNECT
    override val engineName: String = "HTTP/HTTPS CONNECT Engine"

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
            return Result.failure(IllegalArgumentException("El host del proxy no puede estar vacío."))
        }
        if (profile.port !in 1..65535) {
            return Result.failure(IllegalArgumentException("El puerto del proxy debe estar entre 1 y 65535."))
        }
        for ((k, v) in profile.customConnectHeaders) {
            if (k.contains('\r') || k.contains('\n') || v.contains('\r') || v.contains('\n')) {
                return Result.failure(IllegalArgumentException("Encabezado HTTP inválido (CRLF detectado)."))
            }
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

            // Verify real TCP/TLS reachability to the proxy server on startup
            return@withContext runCatching {
                val testSocket = createProtectedSocket(profile)
                testSocket.close()
                _status.value = EngineStatus.RUNNING
                AppLogger.info(TAG, "Motor ${profile.protocol.badgeCode} listo hacia ${profile.host}:${profile.port}")
            }.onFailure { err ->
                _status.value = EngineStatus.ERROR
                AppLogger.error(TAG, "Error conectando al proxy ${profile.host}:${profile.port}", err)
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
            val profile = activeProfile ?: throw IOException("Motor HTTP/HTTPS no inicializado.")
            val socket = createProtectedSocket(profile)
            try {
                val request = buildConnectRequest(profile, activeSecrets, targetHost, targetPort)
                val out = socket.getOutputStream()
                out.write(request.toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()

                val bufferedIn = BufferedInputStream(socket.getInputStream())
                val statusLine = readHeaderBlock(bufferedIn)
                val code = parseStatusCode(statusLine)
                if (code !in 200..299) {
                    throw IOException("El proxy rechazó CONNECT a $targetHost:$targetPort -> $statusLine")
                }

                object : TunnelStream {
                    override val inputStream: InputStream = bufferedIn
                    override val outputStream: OutputStream = out
                    override fun close() {
                        runCatching { socket.close() }
                    }
                }
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }

    private fun createProtectedSocket(profile: TunnelProfile): Socket {
        val ctx = context ?: throw IOException("EngineContext ausente.")
        val rawSocket = Socket()
        rawSocket.tcpNoDelay = true
        if (!ctx.protectSocket(rawSocket)) {
            rawSocket.close()
            throw IOException("VpnService.protect() rechazó el socket (prevención de bucle recursivo).")
        }
        rawSocket.connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS)
        rawSocket.soTimeout = READ_TIMEOUT_MS

        val useTls = profile.protocol == TunnelProtocol.HTTPS_PROXY || profile.tlsEnabled
        if (!useTls) return rawSocket

        val sniHost = profile.tlsServerName.ifBlank { profile.host }
        val sslFactory = createSslSocketFactory(profile.verifyTlsCertificate)
        val sslSocket = sslFactory.createSocket(rawSocket, sniHost, profile.port, true) as SSLSocket
        sslSocket.soTimeout = READ_TIMEOUT_MS
        sslSocket.startHandshake()

        if (profile.verifyTlsCertificate) {
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            if (!verifier.verify(sniHost, sslSocket.session)) {
                sslSocket.close()
                throw SSLPeerUnverifiedException("El certificado TLS no coincide con el dominio '$sniHost'.")
            }
            // Also ensure leaf certificate validity dates are checked explicitly
            val peerCerts = sslSocket.session.peerCertificates
            val leaf = peerCerts.firstOrNull() as? X509Certificate
            leaf?.checkValidity()
        }
        return sslSocket
    }

    private fun createSslSocketFactory(verifyCertificate: Boolean): SSLSocketFactory {
        if (verifyCertificate) {
            return SSLSocketFactory.getDefault() as SSLSocketFactory
        }
        // Only used when user explicitly disables certificate validation in profile advanced editor
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())
        return sslContext.socketFactory
    }

    private fun buildConnectRequest(
        profile: TunnelProfile,
        secrets: ProfileSecrets,
        targetHost: String,
        targetPort: Int
    ): String {
        val sb = StringBuilder()
        val authority = "$targetHost:$targetPort"
        sb.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(authority).append("\r\n")
        sb.append("Proxy-Connection: keep-alive\r\n")

        if (profile.username.isNotBlank() || secrets.password.isNotEmpty()) {
            val creds = "${profile.username}:${secrets.password}".toByteArray(StandardCharsets.UTF_8)
            val encoded = Base64.encodeToString(creds, Base64.NO_WRAP)
            sb.append("Proxy-Authorization: Basic ").append(encoded).append("\r\n")
        }

        val allowedHeaders = setOf("user-agent", "x-proxy-session", "accept", "proxy-connection")
        for ((key, value) in profile.customConnectHeaders) {
            val cleanKey = key.trim()
            val cleanVal = value.trim()
            if (cleanKey.isNotEmpty() && !cleanKey.contains('\r') && !cleanKey.contains('\n') &&
                !cleanVal.contains('\r') && !cleanVal.contains('\n') &&
                (cleanKey.lowercase() in allowedHeaders || cleanKey.lowercase().startsWith("x-"))
            ) {
                sb.append(cleanKey).append(": ").append(cleanVal).append("\r\n")
            }
        }
        sb.append("\r\n")
        return sb.toString()
    }

    private fun readHeaderBlock(input: BufferedInputStream): String {
        val lineBuf = ByteArray(4096)
        var statusLine = ""
        var headerCount = 0
        while (headerCount < 64) {
            val line = readAsciiLine(input, lineBuf) ?: throw IOException("EOF leyendo respuesta HTTP CONNECT.")
            if (headerCount == 0) statusLine = line
            if (line.isEmpty()) break
            headerCount++
        }
        return statusLine
    }

    private fun readAsciiLine(input: BufferedInputStream, buffer: ByteArray): String? {
        var pos = 0
        while (pos < buffer.size) {
            val b = input.read()
            if (b == -1) return if (pos == 0) null else String(buffer, 0, pos, StandardCharsets.ISO_8859_1)
            if (b == '\n'.code) {
                val len = if (pos > 0 && buffer[pos - 1] == '\r'.code.toByte()) pos - 1 else pos
                return String(buffer, 0, len, StandardCharsets.ISO_8859_1)
            }
            buffer[pos++] = b.toByte()
        }
        throw IOException("Línea de encabezado HTTP demasiado larga.")
    }

    private fun parseStatusCode(statusLine: String): Int {
        val parts = statusLine.split(' ')
        return if (parts.size >= 2) parts[1].toIntOrNull() ?: -1 else -1
    }

    override fun getStatistics(): TunnelStatistics =
        context?.statisticsManager?.stats?.value ?: TunnelStatistics()

    override fun release() {
        activeProfile = null
        activeSecrets = ProfileSecrets()
        _status.value = EngineStatus.IDLE
    }

    companion object {
        private const val TAG = "HttpConnectEngine"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
