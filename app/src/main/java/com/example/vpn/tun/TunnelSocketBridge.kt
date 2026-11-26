package com.example.vpn.tun

import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/**
 * Thread-safe loopback TCP bridge between standard Java/OkHttp/BoringSSL Sockets
 * and a [TunnelEngine]'s virtual [TunnelStream].
 *
 * Enforces strict single-reader / single-writer sequential pumping per stream with
 * TCP_NODELAY = true and immediate flush(), preventing TLS record corruption
 * (BAD_RECORD_MAC / BAD_DECRYPT) caused by concurrent or unbuffered stream access.
 */
class TunnelSocketBridge(
    val engine: TunnelEngine
) : Closeable {

    private data class PendingBridge(
        val stream: TunnelStream,
        val readyLatch: CountDownLatch
    )

    private val loopbackAddr: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val pendingConnections = ConcurrentHashMap<Int, PendingBridge>()
    private val activeClosables = Collections.newSetFromMap(ConcurrentHashMap<Closeable, Boolean>())
    private val closed = AtomicBoolean(false)
    private val startLock = Any()
    private val ioPool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "TunnelBridgeIO").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var bridgePort: Int = -1

    private fun ensureStarted() {
        if (closed.get()) throw IOException("TunnelSocketBridge cerrado.")
        if (bridgePort > 0) return
        synchronized(startLock) {
            if (closed.get()) throw IOException("TunnelSocketBridge cerrado.")
            if (bridgePort > 0) return
            val ss = ServerSocket(0, 64, loopbackAddr)
            serverSocket = ss
            bridgePort = ss.localPort
            ioPool.execute { acceptLoop(ss) }
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!closed.get() && !ss.isClosed) {
            val peer = try {
                ss.accept()
            } catch (_: IOException) {
                break
            }
            val pending = pendingConnections.remove(peer.port)
            if (pending == null) {
                runCatching { peer.close() }
                continue
            }
            ioPool.execute {
                pumpSession(peer, pending)
            }
        }
    }

    private fun pumpSession(peerSocket: Socket, pending: PendingBridge) {
        val stream = pending.stream
        val readLock = Any()
        val writeLock = Any()
        val peerCloseable = Closeable { runCatching { peerSocket.close() } }
        val streamCloseable = Closeable { runCatching { stream.close() } }
        activeClosables.add(peerCloseable)
        activeClosables.add(streamCloseable)

        try {
            peerSocket.tcpNoDelay = true
            peerSocket.keepAlive = true
            runCatching { peerSocket.receiveBufferSize = SOCKET_BUFFER_BYTES }
            runCatching { peerSocket.sendBufferSize = SOCKET_BUFFER_BYTES }

            val peerIn = peerSocket.getInputStream()
            val peerOut = peerSocket.getOutputStream()

            // Signal caller only after socket options & streams are ready
            pending.readyLatch.countDown()

            val done = CountDownLatch(2)

            // Upstream: local Socket (TLS client) -> TunnelStream (strictly serialized writes)
            ioPool.execute {
                val buf = ByteArray(COPY_BUFFER_BYTES)
                try {
                    while (!closed.get() && !peerSocket.isClosed) {
                        val n = peerIn.read(buf)
                        if (n <= 0) break
                        synchronized(writeLock) {
                            stream.outputStream.write(buf, 0, n)
                            if (peerIn.available() == 0) {
                                stream.outputStream.flush()
                            }
                        }
                    }
                    synchronized(writeLock) {
                        runCatching { stream.outputStream.flush() }
                        stream.halfCloseOutput()
                    }
                } catch (_: Throwable) {
                } finally {
                    done.countDown()
                }
            }

            // Downstream: TunnelStream -> local Socket (strictly serialized single sequential reader)
            ioPool.execute {
                val buf = ByteArray(COPY_BUFFER_BYTES)
                try {
                    while (!closed.get() && !peerSocket.isClosed) {
                        val n = synchronized(readLock) {
                            stream.inputStream.read(buf)
                        }
                        if (n <= 0) break
                        peerOut.write(buf, 0, n)
                        if (stream.inputStream.available() == 0) {
                            peerOut.flush()
                        }
                    }
                    runCatching { peerOut.flush() }
                    runCatching { peerSocket.shutdownOutput() }
                } catch (_: Throwable) {
                } finally {
                    done.countDown()
                }
            }

            done.await()
        } catch (_: Throwable) {
            pending.readyLatch.countDown()
        } finally {
            activeClosables.remove(peerCloseable)
            activeClosables.remove(streamCloseable)
            runCatching { stream.close() }
            runCatching { peerSocket.close() }
        }
    }

    fun createSocketFactory(): SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = BridgedTunnelSocket()

        override fun createSocket(host: String?, port: Int): Socket =
            BridgedTunnelSocket().apply {
                connect(InetSocketAddress(host ?: "127.0.0.1", port), DEFAULT_CONNECT_TIMEOUT_MS)
            }

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            createSocket(host, port)

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            BridgedTunnelSocket().apply {
                connect(InetSocketAddress(host ?: loopbackAddr, port), DEFAULT_CONNECT_TIMEOUT_MS)
            }

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            createSocket(address, port)
    }

    private inner class BridgedTunnelSocket : Socket() {
        override fun connect(endpoint: SocketAddress) {
            connect(endpoint, DEFAULT_CONNECT_TIMEOUT_MS)
        }

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val inet = endpoint as? InetSocketAddress
                ?: throw IOException("SocketAddress no soportado: $endpoint")
            val targetHost = inet.address?.hostAddress?.takeIf { it.isNotBlank() }
                ?: inet.hostString
            val targetPort = inet.port
            if (targetPort !in 1..65535) throw IOException("Puerto destino inválido: $targetPort")

            val stream = try {
                runBlocking { engine.openTcpTunnel(targetHost, targetPort) }
            } catch (t: Throwable) {
                throw IOException("Error abriendo canal en túnel hacia $targetHost:$targetPort: ${t.message}", t)
            }

            try {
                ensureStarted()
                super.bind(InetSocketAddress(loopbackAddr, 0))
                val myLocalPort = super.getLocalPort()
                val ready = CountDownLatch(1)
                pendingConnections[myLocalPort] = PendingBridge(stream, ready)
                try {
                    super.connect(
                        InetSocketAddress(loopbackAddr, bridgePort),
                        timeout.coerceAtLeast(1_000)
                    )
                    super.setTcpNoDelay(true)
                    super.setKeepAlive(true)
                    runCatching { super.setReceiveBufferSize(SOCKET_BUFFER_BYTES) }
                    runCatching { super.setSendBufferSize(SOCKET_BUFFER_BYTES) }
                } catch (t: Throwable) {
                    pendingConnections.remove(myLocalPort)
                    throw t
                }
                if (!ready.await(2, TimeUnit.SECONDS)) {
                    throw IOException("Timeout inicializando puente TCP local hacia $targetHost:$targetPort")
                }
            } catch (t: Throwable) {
                runCatching { stream.close() }
                throw if (t is IOException) t else IOException("Fallo en puente TCP: ${t.message}", t)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingConnections.values.forEach { pending ->
            pending.readyLatch.countDown()
            runCatching { pending.stream.close() }
        }
        pendingConnections.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        bridgePort = -1
        activeClosables.toList().forEach { c -> runCatching { c.close() } }
        activeClosables.clear()
        ioPool.shutdownNow()
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val SOCKET_BUFFER_BYTES = 256 * 1024
    }
}
