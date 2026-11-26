package com.example.engines.http

import com.example.core.logger.AppLogger
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
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

data class HttpResponseHead(
    val statusLine: String,
    val statusCode: Int,
    val contentLength: Long
)

object RawTcpPayloadExecutor {
    private const val TAG = "RawTcpPayloadExecutor"

    fun resolveIpBestEffort(host: String): String {
        val clean = host.trim()
        if (clean.isEmpty()) return ""
        return runCatching { InetAddress.getByName(clean).hostAddress ?: clean }.getOrDefault(clean)
    }

    fun connectRawSocket(
        host: String,
        port: Int,
        useTls: Boolean,
        sniHost: String = host,
        verifyTlsCertificate: Boolean = true,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
        protectSocket: ((Socket) -> Boolean)? = null
    ): Socket {
        val rawSocket = Socket()
        rawSocket.tcpNoDelay = true
        rawSocket.keepAlive = true
        runCatching { rawSocket.receiveBufferSize = SOCKET_BUFFER_BYTES }
        runCatching { rawSocket.sendBufferSize = SOCKET_BUFFER_BYTES }
        if (protectSocket != null && !protectSocket(rawSocket)) {
            rawSocket.close()
            throw IOException("VpnService.protect() rechazó el socket (prevención de bucle recursivo).")
        }
        rawSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        rawSocket.soTimeout = readTimeoutMs

        if (!useTls) return rawSocket

        val effectiveSni = sniHost.ifBlank { host }
        val sslFactory = createSslSocketFactory(verifyTlsCertificate)
        val sslSocket = sslFactory.createSocket(rawSocket, effectiveSni, port, true) as SSLSocket
        runCatching { sslSocket.tcpNoDelay = true }
        sslSocket.soTimeout = readTimeoutMs
        sslSocket.startHandshake()

        if (verifyTlsCertificate) {
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            if (!verifier.verify(effectiveSni, sslSocket.session)) {
                sslSocket.close()
                throw SSLPeerUnverifiedException("El certificado TLS no coincide con el dominio '$effectiveSni'.")
            }
            val leaf = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
            leaf?.checkValidity()
        }
        return sslSocket
    }

    fun createSslSocketFactory(verifyCertificate: Boolean): SSLSocketFactory {
        if (verifyCertificate) {
            return SSLSocketFactory.getDefault() as SSLSocketFactory
        }
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())
        return sslContext.socketFactory
    }

    fun executeRawPayloadHandshake(
        socket: Socket,
        bufferedIn: BufferedInputStream,
        profile: TunnelProfile,
        secrets: ProfileSecrets,
        targetHost: String? = null,
        targetPort: Int? = null,
        baseReadTimeoutMs: Int = 10_000
    ): String {
        val resolvedIp = resolveIpBestEffort(profile.host)
        val sshTarget = if (!targetHost.isNullOrBlank() && targetPort != null && targetPort in 1..65535) {
            "$targetHost:$targetPort"
        } else {
            "$resolvedIp:${profile.port}"
        }
        val context = PayloadMacroContext(
            host = profile.host.trim(),
            port = profile.port,
            resolvedIp = resolvedIp,
            sshEndpoint = sshTarget,
            username = profile.username,
            password = secrets.password
        )
        val plan = RawPayloadParser.parse(profile.rawHttpPayload, context)
        if (plan.chunks.isEmpty()) {
            return "TCP/TLS directo (sin payload HTTP)"
        }
        sendChunks(socket.getOutputStream(), plan.chunks)
        return readTunnelHandshakeResponse(
            socket = socket,
            bufferedIn = bufferedIn,
            expectedResponses = plan.expectedHttpResponses,
            isWebSocketUpgrade = plan.isWebSocketUpgrade,
            baseReadTimeoutMs = baseReadTimeoutMs
        )
    }

    fun sendChunks(output: OutputStream, chunks: List<PayloadChunk>) {
        for (chunk in chunks) {
            if (chunk.bytes.isNotEmpty()) {
                output.write(chunk.bytes)
                output.flush()
            }
            if (chunk.delayAfterMs > 0L) {
                Thread.sleep(chunk.delayAfterMs)
            }
        }
    }

    fun readTunnelHandshakeResponse(
        socket: Socket,
        bufferedIn: BufferedInputStream,
        expectedResponses: Int,
        isWebSocketUpgrade: Boolean,
        baseReadTimeoutMs: Int
    ): String {
        val maxAttempts = expectedResponses.coerceIn(1, 8)
        var effectiveStatusLine: String? = null

        for (attempt in 1..maxAttempts) {
            // Preserve direct SSH-2.0- banner if server skipped intermediate HTTP responses
            if (peekStartsWithPrefix(socket, bufferedIn, "SSH-2.0-", baseReadTimeoutMs, peekTimeoutMs = 120)) {
                return effectiveStatusLine ?: "SSH-2.0- (banner directo)"
            }

            val head = readHttpResponseHead(bufferedIn)
            val isValidHttpVersion = head.statusLine.startsWith("HTTP/1.0", ignoreCase = true) ||
                head.statusLine.startsWith("HTTP/1.1", ignoreCase = true)
            if (!isValidHttpVersion || head.statusCode !in 100..599) {
                throw IOException("Respuesta HTTP inválida del servidor -> ${head.statusLine}")
            }

            effectiveStatusLine = if (head.statusCode == 101 || head.statusCode == 200) {
                head.statusLine
            } else {
                AppLogger.info(TAG, "HTTP/1.1 ${head.statusCode} recibido -> Auto-Replace a 200 OK")
                "HTTP/1.1 200 OK (Auto-Replace ${head.statusCode})"
            }

            // Immediately stop without draining any body if SSH-2.0- banner follows headers
            if (peekStartsWithPrefix(socket, bufferedIn, "SSH-2.0-", baseReadTimeoutMs, peekTimeoutMs = 40)) {
                return effectiveStatusLine
            }

            val hasMorePipelined = attempt < maxAttempts
            if (head.statusCode == 101 && !hasMorePipelined) {
                return effectiveStatusLine
            }

            if (hasMorePipelined || isWebSocketUpgrade) {
                if (peekStartsWithPrefix(socket, bufferedIn, "HTTP/", baseReadTimeoutMs, peekTimeoutMs = 180)) {
                    continue
                }
                if (hasMorePipelined &&
                    head.contentLength in 1..MAX_DRAIN_BODY_BYTES &&
                    !peekStartsWithPrefix(socket, bufferedIn, "SSH-2.0-", baseReadTimeoutMs, peekTimeoutMs = 40)
                ) {
                    drainExactBytes(bufferedIn, head.contentLength)
                    if (peekStartsWithPrefix(socket, bufferedIn, "SSH-2.0-", baseReadTimeoutMs, peekTimeoutMs = 40)) {
                        return effectiveStatusLine
                    }
                    if (peekStartsWithPrefix(socket, bufferedIn, "HTTP/", baseReadTimeoutMs, peekTimeoutMs = 250)) {
                        continue
                    }
                }
            }
            return effectiveStatusLine
        }

        return effectiveStatusLine ?: throw IOException("No se recibió respuesta HTTP del servidor.")
    }

    fun readHttpResponseHead(input: BufferedInputStream): HttpResponseHead {
        val lineBuf = ByteArray(8192)
        var statusLine = ""
        var contentLength = 0L
        var headerCount = 0
        while (headerCount < 96) {
            val line = readAsciiLine(input, lineBuf)
                ?: throw IOException("EOF leyendo respuesta HTTP del servidor.")
            if (headerCount == 0) {
                statusLine = line
            } else if (line.isEmpty()) {
                break
            } else {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim()
                    if (key.equals("Content-Length", ignoreCase = true)) {
                        contentLength = line.substring(colon + 1).trim().toLongOrNull() ?: 0L
                    }
                }
            }
            headerCount++
        }
        val code = parseStatusCode(statusLine)
        return HttpResponseHead(
            statusLine = statusLine,
            statusCode = code,
            contentLength = contentLength
        )
    }

    private fun peekStartsWithPrefix(
        socket: Socket,
        bufferedIn: BufferedInputStream,
        prefix: String,
        baseReadTimeoutMs: Int,
        peekTimeoutMs: Int
    ): Boolean {
        val targetBytes = prefix.toByteArray(StandardCharsets.ISO_8859_1)
        val previousTimeout = runCatching { socket.soTimeout }.getOrDefault(baseReadTimeoutMs)
        return try {
            socket.soTimeout = peekTimeoutMs
            bufferedIn.mark(targetBytes.size + 8)
            val probe = ByteArray(targetBytes.size)
            var read = 0
            while (read < probe.size) {
                val n = bufferedIn.read(probe, read, probe.size - read)
                if (n <= 0) break
                read += n
            }
            bufferedIn.reset()
            if (read < targetBytes.size) return false
            for (i in targetBytes.indices) {
                if (probe[i] != targetBytes[i]) return false
            }
            true
        } catch (_: SocketTimeoutException) {
            runCatching { bufferedIn.reset() }
            false
        } catch (_: IOException) {
            runCatching { bufferedIn.reset() }
            false
        } finally {
            runCatching { socket.soTimeout = previousTimeout }
        }
    }

    private fun drainExactBytes(input: BufferedInputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(2048)
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            remaining -= n
        }
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
        val parts = statusLine.trim().split(Regex("\\s+"))
        return if (parts.size >= 2) parts[1].toIntOrNull() ?: -1 else -1
    }

    fun usesTls(profile: TunnelProfile): Boolean =
        profile.protocol == TunnelProtocol.HTTPS_PROXY || profile.tlsEnabled

    private const val MAX_DRAIN_BODY_BYTES = 65_536L
    private const val SOCKET_BUFFER_BYTES = 256 * 1024
}
