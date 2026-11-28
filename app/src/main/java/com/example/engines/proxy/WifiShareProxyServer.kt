package com.example.engines.proxy

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.example.core.logger.AppLogger
import com.example.core.network.VpnRouteHelper
import com.example.core.stats.StatisticsManager
import com.example.engines.adapter.TunnelStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local Wi-Fi / Hotspot / USB-CGNAT Proxy sharing server bound to 0.0.0.0:<port> (default 8080).
 * Supports SOCKS5 (0x05), HTTPS CONNECT, and plain HTTP forwarding on the same port.
 * Uses 16 KB stream buffers, immediate flush() per read cycle, 256 KB TCP socket buffers, and
 * protected local sockets for LAN/CGNAT bypass destinations (e.g. 192.168.x.x, 100.64.0.0/10).
 */
class WifiShareProxyServer(
    private val listenPort: Int = 8080,
    private val openTunnel: suspend (targetHost: String, targetPort: Int) -> TunnelStream,
    private val statisticsManager: StatisticsManager? = null,
    private val context: Context? = null,
    private val protectSocket: (Socket) -> Boolean = { true },
    private val isBypassLanEnabled: () -> Boolean = { true },
    private val connectTimeoutMs: Int = 10_000
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val boundPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start(): Int {
        if (running.get() && boundPort > 0) return boundPort
        val ss = ServerSocket()
        ss.reuseAddress = true
        runCatching { ss.receiveBufferSize = SOCKET_BUFFER_BYTES }
        ss.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), listenPort), 128)
        serverSocket = ss
        running.set(true)
        acquirePerformanceLocks()
        AppLogger.info(
            TAG,
            "Proxy Wi-Fi/Hotspot compartido escuchando en 0.0.0.0:${ss.localPort} (HTTP plano + CONNECT + SOCKS5)"
        )

        acceptJob = scope.launch {
            while (isActive && running.get()) {
                val client = try {
                    ss.accept()
                } catch (_: IOException) {
                    break
                }
                launch {
                    handleClient(client)
                }
            }
        }
        return ss.localPort
    }

    private fun acquirePerformanceLocks() {
        val ctx = context ?: return
        runCatching {
            val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
        runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wifiLock == null) {
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releasePerformanceLocks() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private suspend fun handleClient(client: Socket) {
        try {
            tuneSocket(client)
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val bufferedIn = BufferedInputStream(client.getInputStream(), COPY_BUFFER_BYTES)
            val output = client.getOutputStream()

            bufferedIn.mark(4)
            val firstByte = bufferedIn.read()
            if (firstByte == -1) return
            bufferedIn.reset()

            if (firstByte == 0x05) {
                handleSocks5Client(client, bufferedIn, output)
            } else {
                handleHttpProxyClient(client, bufferedIn, output)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { client.close() }
        }
    }

    private fun tuneSocket(socket: Socket) {
        runCatching { socket.tcpNoDelay = true }
        runCatching { socket.keepAlive = true }
        runCatching { socket.receiveBufferSize = SOCKET_BUFFER_BYTES }
        runCatching { socket.sendBufferSize = SOCKET_BUFFER_BYTES }
    }

    private suspend fun openDestinationStream(host: String, port: Int): TunnelStream {
        if (isBypassLanEnabled() && VpnRouteHelper.isLocalOrCgnatHost(host)) {
            val localSocket = Socket()
            tuneSocket(localSocket)
            if (!protectSocket(localSocket)) {
                runCatching { localSocket.close() }
                throw IOException("VpnService.protect() rechazó socket local para $host:$port")
            }
            localSocket.connect(InetSocketAddress(host, port), connectTimeoutMs.coerceIn(1_000, 120_000))
            localSocket.soTimeout = 0
            val localIn = BufferedInputStream(localSocket.getInputStream(), COPY_BUFFER_BYTES)
            val localOut = localSocket.getOutputStream()
            return object : TunnelStream {
                override val inputStream: InputStream = localIn
                override val outputStream: OutputStream = localOut
                override fun halfCloseOutput() {
                    runCatching { localSocket.shutdownOutput() }
                }
                override fun close() {
                    runCatching { localSocket.close() }
                }
            }
        }
        return openTunnel(host, port)
    }

    private suspend fun handleSocks5Client(
        client: Socket,
        bufferedIn: BufferedInputStream,
        output: OutputStream
    ) {
        var tunnel: TunnelStream? = null
        try {
            val input = DataInputStream(bufferedIn)
            val ver = input.readUnsignedByte()
            if (ver != 0x05) return
            val nMethods = input.readUnsignedByte()
            val methods = ByteArray(nMethods)
            input.readFully(methods)
            if (!methods.contains(0x00.toByte())) {
                output.write(byteArrayOf(0x05, 0xFF.toByte()))
                output.flush()
                return
            }
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            val reqVer = input.readUnsignedByte()
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            val atyp = input.readUnsignedByte()
            if (reqVer != 0x05 || cmd != 0x01) {
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                return
            }

            val targetHost = when (atyp) {
                0x01 -> {
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: return
                }
                0x03 -> {
                    val len = input.readUnsignedByte()
                    val domain = ByteArray(len)
                    input.readFully(domain)
                    String(domain, StandardCharsets.UTF_8)
                }
                0x04 -> {
                    val addr = ByteArray(16)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: return
                }
                else -> {
                    output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    return
                }
            }
            val targetPort = (input.readUnsignedByte() shl 8) or input.readUnsignedByte()

            tunnel = try {
                openDestinationStream(targetHost, targetPort)
            } catch (e: Throwable) {
                output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                AppLogger.debug(TAG, "Proxy Wi-Fi SOCKS5 -> $targetHost:$targetPort falló: ${e.message}")
                return
            }

            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            client.soTimeout = 0
            bridgeStreams(client, bufferedIn, output, tunnel)
        } finally {
            runCatching { tunnel?.close() }
        }
    }

    private suspend fun handleHttpProxyClient(
        client: Socket,
        bufferedIn: BufferedInputStream,
        output: OutputStream
    ) {
        val headerLines = readHttpHeaderLines(bufferedIn)
        if (headerLines.isEmpty()) return

        val requestLine = headerLines.first()
        val parts = requestLine.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 3) {
            output.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
            return
        }

        val method = parts[0].uppercase()
        val rawTarget = parts[1]
        val httpVersion = parts[2]

        var tunnel: TunnelStream? = null
        try {
            if (method == "CONNECT") {
                val (host, port) = parseHostAndPort(rawTarget, defaultPort = 443)
                if (host.isBlank() || port !in 1..65535) {
                    output.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                    output.flush()
                    return
                }
                tunnel = try {
                    openDestinationStream(host, port)
                } catch (e: Throwable) {
                    output.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                    output.flush()
                    AppLogger.debug(TAG, "Proxy Wi-Fi CONNECT $host:$port falló: ${e.message}")
                    return
                }
                output.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: AIOTunnelVPN\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                output.flush()
                client.soTimeout = 0
                bridgeStreams(client, bufferedIn, output, tunnel)
            } else {
                val targetInfo = resolveHttpForwardTarget(rawTarget, headerLines)
                if (targetInfo == null) {
                    output.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                    output.flush()
                    return
                }
                val (host, port, relativePath) = targetInfo
                tunnel = try {
                    openDestinationStream(host, port)
                } catch (e: Throwable) {
                    output.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                    output.flush()
                    AppLogger.debug(TAG, "Proxy Wi-Fi HTTP $method $host:$port falló: ${e.message}")
                    return
                }

                val rebuiltHead = buildForwardedHttpRequest(method, relativePath, httpVersion, host, port, headerLines)
                tunnel.outputStream.write(rebuiltHead)
                tunnel.outputStream.flush()
                statisticsManager?.recordBytesSent(rebuiltHead.size.toLong())
                client.soTimeout = 0
                bridgeStreams(client, bufferedIn, output, tunnel)
            }
        } finally {
            runCatching { tunnel?.close() }
        }
    }

    private suspend fun bridgeStreams(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        tunnel: TunnelStream
    ) {
        val upstreamJob = scope.launch {
            val buf = ByteArray(COPY_BUFFER_BYTES)
            try {
                while (isActive && running.get()) {
                    val n = clientIn.read(buf)
                    if (n <= 0) break
                    tunnel.outputStream.write(buf, 0, n)
                    tunnel.outputStream.flush()
                    statisticsManager?.recordBytesSent(n.toLong())
                    statisticsManager?.recordPacketForwarded()
                }
            } catch (_: Throwable) {
            } finally {
                tunnel.halfCloseOutput()
            }
        }

        val downstreamJob = scope.launch {
            val buf = ByteArray(COPY_BUFFER_BYTES)
            try {
                while (isActive && running.get()) {
                    val n = tunnel.inputStream.read(buf)
                    if (n <= 0) break
                    clientOut.write(buf, 0, n)
                    clientOut.flush()
                    statisticsManager?.recordBytesReceived(n.toLong())
                    statisticsManager?.recordPacketForwarded()
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { client.shutdownOutput() }
            }
        }

        downstreamJob.join()
        withTimeoutOrNull(1_500L) { upstreamJob.join() }
        upstreamJob.cancel()
    }

    private fun readHttpHeaderLines(input: BufferedInputStream): List<String> {
        val lines = mutableListOf<String>()
        val lineOut = ByteArrayOutputStream(256)
        while (lines.size < 96) {
            lineOut.reset()
            while (true) {
                val b = input.read()
                if (b == -1) {
                    if (lineOut.size() > 0) {
                        lines.add(String(lineOut.toByteArray(), StandardCharsets.ISO_8859_1))
                    }
                    return lines
                }
                if (b == '\n'.code) {
                    val bytes = lineOut.toByteArray()
                    val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                    val line = String(bytes, 0, len, StandardCharsets.ISO_8859_1)
                    if (line.isEmpty()) {
                        return lines
                    }
                    lines.add(line)
                    break
                }
                if (lineOut.size() > 8192) throw IOException("Cabecera HTTP demasiado larga")
                lineOut.write(b)
            }
        }
        return lines
    }

    private fun buildForwardedHttpRequest(
        method: String,
        relativePath: String,
        httpVersion: String,
        targetHost: String,
        targetPort: Int,
        originalLines: List<String>
    ): ByteArray {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(relativePath).append(' ').append(httpVersion).append("\r\n")
        val hasExplicitConnection = originalLines.drop(1).any { it.lowercase().startsWith("connection:") }
        val hasExplicitHost = originalLines.drop(1).any { it.lowercase().startsWith("host:") }
        if (!hasExplicitHost) {
            val hostLine = if (targetPort == 80) targetHost else "$targetHost:$targetPort"
            sb.append("Host: ").append(hostLine).append("\r\n")
        }
        for (i in 1 until originalLines.size) {
            val line = originalLines[i]
            val lower = line.lowercase()
            when {
                lower.startsWith("proxy-authorization:") -> continue
                lower.startsWith("proxy-connection:") -> {
                    if (!hasExplicitConnection) {
                        val value = line.substringAfter(':').trim().ifEmpty { "close" }
                        sb.append("Connection: ").append(value).append("\r\n")
                    }
                }
                else -> sb.append(line).append("\r\n")
            }
        }
        sb.append("\r\n")
        return sb.toString().toByteArray(StandardCharsets.ISO_8859_1)
    }

    private fun resolveHttpForwardTarget(
        rawTarget: String,
        headerLines: List<String>
    ): Triple<String, Int, String>? {
        if (rawTarget.startsWith("http://", ignoreCase = true) || rawTarget.startsWith("https://", ignoreCase = true)) {
            val uri = runCatching { URI(rawTarget) }.getOrNull()
            if (uri != null && !uri.host.isNullOrBlank()) {
                val host = uri.host!!
                val defaultPort = if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
                val port = if (uri.port in 1..65535) uri.port else defaultPort
                val path = buildString {
                    append(uri.rawPath?.takeIf { it.isNotBlank() } ?: "/")
                    if (!uri.rawQuery.isNullOrBlank()) {
                        append('?').append(uri.rawQuery)
                    }
                }
                return Triple(host, port, path)
            }
            // Fallback parser for URLs containing non-RFC characters
            val isHttps = rawTarget.startsWith("https://", ignoreCase = true)
            val withoutScheme = rawTarget.substringAfter("://")
            val slashIdx = withoutScheme.indexOf('/')
            val authority = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
            val path = if (slashIdx >= 0) withoutScheme.substring(slashIdx) else "/"
            val (host, port) = parseHostAndPort(authority, defaultPort = if (isHttps) 443 else 80)
            if (host.isNotBlank() && port in 1..65535) {
                return Triple(host, port, path)
            }
            return null
        }

        val hostHeader = headerLines
            .drop(1)
            .firstOrNull { it.lowercase().startsWith("host:") }
            ?.substringAfter(':')
            ?.trim()
            ?: return null
        val (host, port) = parseHostAndPort(hostHeader, defaultPort = 80)
        if (host.isBlank() || port !in 1..65535) return null
        val path = rawTarget.ifBlank { "/" }
        return Triple(host, port, path)
    }

    private fun parseHostAndPort(authority: String, defaultPort: Int): Pair<String, Int> {
        val trimmed = authority.trim()
        if (trimmed.startsWith("[")) {
            val endBracket = trimmed.indexOf(']')
            if (endBracket > 0) {
                val host = trimmed.substring(1, endBracket)
                val portPart = trimmed.substring(endBracket + 1).removePrefix(":")
                return host to (portPart.toIntOrNull() ?: defaultPort)
            }
        }
        val colonIdx = trimmed.lastIndexOf(':')
        return if (colonIdx > 0) {
            val host = trimmed.substring(0, colonIdx)
            val port = trimmed.substring(colonIdx + 1).toIntOrNull() ?: defaultPort
            host to port
        } else {
            trimmed to defaultPort
        }
    }

    override fun close() {
        releasePerformanceLocks()
        if (!running.compareAndSet(true, false)) return
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
        AppLogger.info(TAG, "Proxy Wi-Fi/Hotspot compartido detenido.")
    }

    companion object {
        private const val TAG = "WifiShareProxy"
        internal const val COPY_BUFFER_BYTES = 16_384
        private const val SOCKET_BUFFER_BYTES = 262_144
        private const val HANDSHAKE_TIMEOUT_MS = 20_000
        private const val WAKE_LOCK_TAG = "AioTunnelVPN::WifiProxyWakeLock"
        private const val WIFI_LOCK_TAG = "AioTunnelVPN::WifiProxyLock"
    }
}
