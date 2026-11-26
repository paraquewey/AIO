package com.example.engines.http

import android.util.Base64
import com.example.core.logger.AppLogger
import com.example.core.stats.StatisticsManager
import com.example.domain.models.EngineFamily
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.TransportType
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
    override val engineName: String = "HTTP/HTTPS Proxy & WebSocket Engine"

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
        val method = profile.httpRequestMethod.trim().uppercase().ifBlank { "CONNECT" }
        if (method !in SUPPORTED_HTTP_METHODS) {
            return Result.failure(IllegalArgumentException("Método HTTP no válido: $method"))
        }
        val path = profile.httpRequestPath.trim().ifBlank { "/" }
        if (path.any { it.isWhitespace() || it == '\r' || it == '\n' }) {
            return Result.failure(IllegalArgumentException("Ruta HTTP inválida (espacios o saltos de línea)."))
        }
        for ((k, v) in profile.customConnectHeaders) {
            if (k.contains('\r') || k.contains('\n') || v.contains('\r') || v.contains('\n') || k.any { it.isWhitespace() }) {
                return Result.failure(IllegalArgumentException("Encabezado HTTP inválido (CRLF o espacio en clave detectado)."))
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
                AppLogger.info(
                    TAG,
                    "Motor ${profile.protocol.badgeCode} (${profile.httpRequestMethod}) listo hacia ${profile.host}:${profile.port}"
                )
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
                val request = buildTunnelRequest(profile, activeSecrets, targetHost, targetPort)
                val out = socket.getOutputStream()
                out.write(request.toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()

                val bufferedIn = BufferedInputStream(socket.getInputStream())
                val statusLine = readHeaderBlock(bufferedIn)
                val code = parseStatusCode(statusLine)
                if (code != 101 && code !in 200..299) {
                    throw IOException("El proxy rechazó ${profile.httpRequestMethod} hacia $targetHost:$targetPort -> $statusLine")
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

    internal fun buildTunnelRequest(
        profile: TunnelProfile,
        secrets: ProfileSecrets,
        targetHost: String,
        targetPort: Int
    ): String {
        val sb = StringBuilder()
        val method = profile.httpRequestMethod.trim().uppercase().ifBlank { "CONNECT" }
        val authority = "$targetHost:$targetPort"
        val path = profile.httpRequestPath.trim().ifBlank { "/" }
        val isConnect = method == "CONNECT"

        val normalizedCustom = linkedMapOf<String, Pair<String, String>>()
        for ((key, value) in profile.customConnectHeaders) {
            val cleanKey = key.trim()
            val cleanVal = value.trim()
            if (cleanKey.isNotEmpty() && !cleanKey.any { it.isWhitespace() } &&
                !cleanKey.contains('\r') && !cleanKey.contains('\n') &&
                !cleanVal.contains('\r') && !cleanVal.contains('\n') &&
                (cleanKey.lowercase() in ALLOWED_HEADERS || cleanKey.lowercase().startsWith("x-"))
            ) {
                normalizedCustom[cleanKey.lowercase()] = cleanKey to cleanVal
            }
        }

        if (isConnect) {
            sb.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
        } else {
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
        }

        val customHost = normalizedCustom.remove("host")?.second
        sb.append("Host: ").append(customHost ?: authority).append("\r\n")

        if (profile.upgradeWebSocket || profile.transport == TransportType.WEBSOCKET) {
            val upgradeVal = normalizedCustom.remove("upgrade")?.second ?: "websocket"
            val connVal = normalizedCustom.remove("connection")?.second ?: "Upgrade"
            sb.append("Connection: ").append(connVal).append("\r\n")
            sb.append("Upgrade: ").append(upgradeVal).append("\r\n")
        } else if (isConnect && !normalizedCustom.containsKey("proxy-connection")) {
            sb.append("Proxy-Connection: keep-alive\r\n")
        }

        if (!isConnect && !normalizedCustom.containsKey("x-target-host")) {
            sb.append("X-Target-Host: ").append(authority).append("\r\n")
        }

        if (profile.username.isNotBlank() || secrets.password.isNotEmpty()) {
            val creds = "${profile.username}:${secrets.password}".toByteArray(StandardCharsets.UTF_8)
            val encoded = Base64.encodeToString(creds, Base64.NO_WRAP)
            sb.append("Proxy-Authorization: Basic ").append(encoded).append("\r\n")
        }

        for ((_, pair) in normalizedCustom) {
            sb.append(pair.first).append(": ").append(pair.second).append("\r\n")
        }
        sb.append("\r\n")
        return sb.toString()
    }

    private fun readHeaderBlock(input: BufferedInputStream): String {
        val lineBuf = ByteArray(4096)
        var statusLine = ""
        var headerCount = 0
        while (headerCount < 64) {
            val line = readAsciiLine(input, lineBuf) ?: throw IOException("EOF leyendo respuesta HTTP del proxy.")
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

        val SUPPORTED_HTTP_METHODS = setOf("CONNECT", "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

        private val ALLOWED_HEADERS = setOf(
            "host",
            "user-agent",
            "accept",
            "connection",
            "upgrade",
            "proxy-connection",
            "origin",
            "cache-control",
            "pragma",
            "sec-websocket-key",
            "sec-websocket-version",
            "sec-websocket-protocol",
            "sec-websocket-extensions",
            "x-proxy-session"
        )

        fun probeHttpEndpoint(
            profile: TunnelProfile,
            secrets: ProfileSecrets,
            timeoutMs: Int = 3_500
        ): Pair<Long, String> {
            val useTls = profile.protocol == TunnelProtocol.HTTPS_PROXY || profile.tlsEnabled
            val t0 = System.currentTimeMillis()
            val engine = HttpConnectProxyEngine()
            val rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            rawSocket.connect(InetSocketAddress(profile.host, profile.port), timeoutMs)
            rawSocket.soTimeout = timeoutMs

            val activeSocket: Socket = if (useTls) {
                val sniHost = profile.tlsServerName.ifBlank { profile.host }
                val sslSocket = engine.createSslSocketFactory(profile.verifyTlsCertificate)
                    .createSocket(rawSocket, sniHost, profile.port, true) as SSLSocket
                sslSocket.soTimeout = timeoutMs
                sslSocket.startHandshake()
                if (profile.verifyTlsCertificate) {
                    val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
                    if (!verifier.verify(sniHost, sslSocket.session)) {
                        sslSocket.close()
                        throw SSLPeerUnverifiedException("El certificado TLS no coincide con '$sniHost'.")
                    }
                    val leaf = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                    leaf?.checkValidity()
                }
                sslSocket
            } else {
                rawSocket
            }

            return activeSocket.use { sock ->
                val req = engine.buildTunnelRequest(profile, secrets, "example.com", 443)
                val out = sock.getOutputStream()
                out.write(req.toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()
                val bufferedIn = BufferedInputStream(sock.getInputStream())
                val statusLine = engine.readHeaderBlock(bufferedIn)
                val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                elapsed to statusLine
            }
        }
    }
}
