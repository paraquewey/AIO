package com.example.engines.http

import com.example.core.logger.AppLogger
import com.example.domain.models.EngineFamily
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.TunnelProfile
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
import java.net.Socket

class HttpConnectProxyEngine : TunnelEngine {
    override val engineFamily: EngineFamily = EngineFamily.HTTP_CONNECT
    override val engineName: String = "HTTP / WebSocket Raw Socket Engine"

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
            return Result.failure(IllegalArgumentException("El host del servidor no puede estar vacío."))
        }
        if (profile.port !in 1..65535) {
            return Result.failure(IllegalArgumentException("El puerto del servidor debe estar entre 1 y 65535."))
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
                val socket = createProtectedSocket(profile)
                socket.use { sock ->
                    if (profile.rawHttpPayload.isNotEmpty()) {
                        val bufferedIn = BufferedInputStream(sock.getInputStream())
                        val statusLine = RawTcpPayloadExecutor.executeRawPayloadHandshake(
                            socket = sock,
                            bufferedIn = bufferedIn,
                            profile = profile,
                            secrets = secrets,
                            baseReadTimeoutMs = READ_TIMEOUT_MS
                        )
                        AppLogger.info(
                            TAG,
                            "Handshake payload completado hacia ${profile.host}:${profile.port} -> $statusLine"
                        )
                    } else {
                        AppLogger.info(
                            TAG,
                            "Socket TCP${if (RawTcpPayloadExecutor.usesTls(profile)) "/TLS" else ""} directo listo hacia ${profile.host}:${profile.port}"
                        )
                    }
                }
                _status.value = EngineStatus.RUNNING
            }.onFailure { err ->
                _status.value = EngineStatus.ERROR
                AppLogger.error(TAG, "Error conectando hacia ${profile.host}:${profile.port}", err)
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
                val bufferedIn = BufferedInputStream(socket.getInputStream())
                val out = socket.getOutputStream()
                if (profile.rawHttpPayload.isNotEmpty()) {
                    RawTcpPayloadExecutor.executeRawPayloadHandshake(
                        socket = socket,
                        bufferedIn = bufferedIn,
                        profile = profile,
                        secrets = activeSecrets,
                        targetHost = targetHost,
                        targetPort = targetPort,
                        baseReadTimeoutMs = READ_TIMEOUT_MS
                    )
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
        return RawTcpPayloadExecutor.connectRawSocket(
            host = profile.host.trim(),
            port = profile.port,
            useTls = RawTcpPayloadExecutor.usesTls(profile),
            sniHost = profile.tlsServerName.ifBlank { profile.host.trim() },
            verifyTlsCertificate = profile.verifyTlsCertificate,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            protectSocket = ctx.protectSocket
        )
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

        fun probeHttpEndpoint(
            profile: TunnelProfile,
            secrets: ProfileSecrets,
            timeoutMs: Int = 3_500
        ): Pair<Long, String> {
            val useTls = RawTcpPayloadExecutor.usesTls(profile)
            val t0 = System.currentTimeMillis()
            val socket = RawTcpPayloadExecutor.connectRawSocket(
                host = profile.host.trim(),
                port = profile.port,
                useTls = useTls,
                sniHost = profile.tlsServerName.ifBlank { profile.host.trim() },
                verifyTlsCertificate = profile.verifyTlsCertificate,
                connectTimeoutMs = timeoutMs,
                readTimeoutMs = timeoutMs,
                protectSocket = null
            )

            return socket.use { sock ->
                val statusLine = if (profile.rawHttpPayload.isNotEmpty()) {
                    val bufferedIn = BufferedInputStream(sock.getInputStream())
                    RawTcpPayloadExecutor.executeRawPayloadHandshake(
                        socket = sock,
                        bufferedIn = bufferedIn,
                        profile = profile,
                        secrets = secrets,
                        baseReadTimeoutMs = timeoutMs
                    )
                } else {
                    if (useTls) "Handshake TLS directo (sin payload)" else "Conexión TCP directa (sin payload)"
                }
                val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                elapsed to statusLine
            }
        }
    }
}
