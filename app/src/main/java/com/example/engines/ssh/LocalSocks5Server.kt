package com.example.engines.ssh

import com.example.core.logger.AppLogger
import com.example.domain.models.EngineTuningConfig
import com.example.engines.adapter.TunnelStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class LocalSocks5Server(
    initialTuning: EngineTuningConfig = EngineTuningConfig(),
    private val openTunnel: suspend (targetHost: String, targetPort: Int) -> TunnelStream
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var tuningConfig: EngineTuningConfig = initialTuning

    fun updateTuning(config: EngineTuningConfig) {
        tuningConfig = config
    }

    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start(): Int {
        if (running.get() && localPort > 0) return localPort
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64)
        serverSocket = ss
        running.set(true)
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

    private suspend fun handleClient(client: Socket) {
        var tunnel: TunnelStream? = null
        try {
            runCatching { client.reuseAddress = true }
            client.tcpNoDelay = tuningConfig.tcpNoDelay
            client.keepAlive = true
            runCatching { client.receiveBufferSize = 256 * 1024 }
            runCatching { client.sendBufferSize = 256 * 1024 }
            client.soTimeout = 30_000
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

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
            // Select NO AUTHENTICATION REQUIRED
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            val reqVer = input.readUnsignedByte()
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            val atyp = input.readUnsignedByte()
            if (reqVer != 0x05 || cmd != 0x01) {
                // Command not supported
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
                openTunnel(targetHost, targetPort)
            } catch (e: Throwable) {
                output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                AppLogger.debug(TAG, "SOCKS5 local -> $targetHost:$targetPort falló: ${e.message}")
                return
            }

            // Success reply
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            client.soTimeout = 0

            val activeTunnel = tunnel
            val upstreamJob = scope.launch {
                var buf = ByteArray(tuningConfig.transferBufferBytes)
                try {
                    while (isActive && running.get()) {
                        val tuning = tuningConfig
                        if (buf.size != tuning.transferBufferBytes) {
                            buf = ByteArray(tuning.transferBufferBytes)
                        }
                        val n = input.read(buf)
                        if (n <= 0) break
                        activeTunnel.outputStream.write(buf, 0, n)
                        if (tuning.immediateFlush) {
                            activeTunnel.outputStream.flush()
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    activeTunnel.halfCloseOutput()
                }
            }

            val downstreamJob = scope.launch {
                var buf = ByteArray(tuningConfig.transferBufferBytes)
                try {
                    while (isActive && running.get()) {
                        val tuning = tuningConfig
                        if (buf.size != tuning.transferBufferBytes) {
                            buf = ByteArray(tuning.transferBufferBytes)
                        }
                        val n = activeTunnel.inputStream.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        if (tuning.immediateFlush) {
                            output.flush()
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { client.shutdownOutput() }
                }
            }

            downstreamJob.join()
            upstreamJob.join()
        } catch (_: Throwable) {
        } finally {
            runCatching { tunnel?.close() }
            runCatching { client.setSoLinger(true, 0) }
            runCatching { client.close() }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "LocalSocks5"
        private const val COPY_BUFFER_BYTES = 16_384
    }
}
