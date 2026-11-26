package com.example.vpn.tun

import com.example.core.logger.AppLogger
import com.example.domain.models.DnsMode
import com.example.domain.models.TunnelProfile
import com.example.engines.adapter.TunnelEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class DnsManager(
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagramSocket: (DatagramSocket) -> Boolean
) {
    private val protectedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .socketFactory(object : SocketFactory() {
                override fun createSocket(): Socket = Socket().also { protectSocket(it) }
                override fun createSocket(host: String?, port: Int): Socket =
                    Socket(host, port).also { protectSocket(it) }
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                    Socket(host, port, localHost, localPort).also { protectSocket(it) }
                override fun createSocket(host: InetAddress?, port: Int): Socket =
                    Socket(host, port).also { protectSocket(it) }
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                    Socket(address, port, localAddress, localPort).also { protectSocket(it) }
            })
            .build()
    }

    suspend fun resolveWireQuery(queryBytes: ByteArray, profile: TunnelProfile): ByteArray? =
        withContext(Dispatchers.IO) {
            when (profile.dnsMode) {
                DnsMode.DNS_OVER_HTTPS -> resolveViaDoh(queryBytes, profile.dohUrl)
                    ?: resolveViaProtectedUdp(queryBytes, profile.dnsServer.ifBlank { "1.1.1.1" })
                DnsMode.PLAIN_DNS, DnsMode.SYSTEM_DEFAULT ->
                    resolveViaProtectedUdp(queryBytes, profile.dnsServer.ifBlank { "1.1.1.1" })
            }
        }

    fun resolveViaDoh(queryBytes: ByteArray, dohUrl: String): ByteArray? {
        val url = dohUrl.ifBlank { "https://cloudflare-dns.com/dns-query" }
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-message")
                .post(queryBytes.toRequestBody("application/dns-message".toMediaType()))
                .build()
            protectedHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.warn(TAG, "DoH HTTP ${response.code} desde $url")
                    return null
                }
                response.body?.bytes()
            }
        }.onFailure { err ->
            AppLogger.warn(TAG, "Error DoH ($url): ${err.message}")
        }.getOrNull()
    }

    fun resolveViaProtectedUdp(queryBytes: ByteArray, dnsIp: String): ByteArray? {
        return runCatching {
            val socket = DatagramSocket()
            if (!protectDatagramSocket(socket)) {
                socket.close()
                return null
            }
            socket.use { sock ->
                sock.soTimeout = 4000
                val addr = InetAddress.getByName(dnsIp)
                sock.send(DatagramPacket(queryBytes, queryBytes.size, addr, 53))
                val respBuf = ByteArray(4096)
                val respPacket = DatagramPacket(respBuf, respBuf.size)
                sock.receive(respPacket)
                respBuf.copyOf(respPacket.length)
            }
        }.onFailure { err ->
            AppLogger.warn(TAG, "Error DNS UDP ($dnsIp): ${err.message}")
        }.getOrNull()
    }

    /**
     * Queries public exit IP through the active TunnelEngine over HTTP/1.1 using api.ipify.org.
     * Returns null if unreachable—never invents or guesses an IP address.
     */
    suspend fun fetchExitIpThroughTunnel(engine: TunnelEngine): String? = withContext(Dispatchers.IO) {
        runCatching {
            engine.openTcpTunnel("api.ipify.org", 80).use { stream ->
                val req = "GET / HTTP/1.1\r\nHost: api.ipify.org\r\nConnection: close\r\nUser-Agent: AIOTunnelVPN/1.0\r\n\r\n"
                stream.outputStream.write(req.toByteArray(StandardCharsets.US_ASCII))
                stream.outputStream.flush()
                val raw = stream.inputStream.readBytes()
                val text = String(raw, StandardCharsets.UTF_8)
                val body = text.substringAfter("\r\n\r\n", "").trim()
                if (body.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$|^[0-9a-fA-F:]+$"""))) {
                    body
                } else null
            }
        }.getOrNull()
    }

    fun buildSampleDnsQuery(domain: String = "example.com"): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x13)
        out.write(0x37) // Transaction ID
        out.write(0x01)
        out.write(0x00) // Standard recursive query
        out.write(0x00)
        out.write(0x01) // QDCOUNT = 1
        out.write(0x00)
        out.write(0x00) // ANCOUNT = 0
        out.write(0x00)
        out.write(0x00) // NSCOUNT = 0
        out.write(0x00)
        out.write(0x00) // ARCOUNT = 0
        for (label in domain.split('.')) {
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00) // Root label
        out.write(0x00)
        out.write(0x01) // QTYPE = A
        out.write(0x00)
        out.write(0x01) // QCLASS = IN
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "DnsManager"
    }
}
