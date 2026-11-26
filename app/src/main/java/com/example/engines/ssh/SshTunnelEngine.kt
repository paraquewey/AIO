package com.example.engines.ssh

import com.example.core.logger.AppLogger
import com.example.data.security.HostKeyVerificationResult
import com.example.data.security.SshHostKeyStore
import com.example.domain.models.EngineFamily
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.SshAuthMethod
import com.example.domain.models.SshHostKeyChallenge
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import com.example.domain.models.TunnelStatistics
import com.example.engines.adapter.EngineContext
import com.example.engines.adapter.EngineStatus
import com.example.engines.adapter.TunnelEngine
import com.example.engines.adapter.TunnelStream
import com.example.engines.adapter.UdpRelaySession
import com.example.engines.http.RawTcpPayloadExecutor
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.IdentityHashMap
import java.util.Properties

class SshTunnelEngine(
    private val hostKeyStore: SshHostKeyStore
) : TunnelEngine {
    override val engineFamily: EngineFamily = EngineFamily.SSH
    override val engineName: String = "JSch SSH2 Engine (Direct & Jump Host)"

    private val _status = MutableStateFlow(EngineStatus.IDLE)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private var context: EngineContext? = null
    private var activeProfile: TunnelProfile? = null
    private var jumpSession: Session? = null
    private var primarySession: Session? = null
    private var localSocksServer: LocalSocks5Server? = null
    private var forwardedLocalPort: Int = -1

    override suspend fun validateConfiguration(
        profile: TunnelProfile,
        secrets: ProfileSecrets
    ): Result<Unit> {
        if (profile.host.isBlank()) {
            return Result.failure(IllegalArgumentException("El servidor SSH no puede estar vacío."))
        }
        if (profile.port !in 1..65535) {
            return Result.failure(IllegalArgumentException("Puerto SSH inválido."))
        }
        if (profile.username.isBlank()) {
            return Result.failure(IllegalArgumentException("El usuario SSH es obligatorio."))
        }
        if (profile.authMethod == SshAuthMethod.PRIVATE_KEY && secrets.privateKeyPem.isBlank()) {
            return Result.failure(IllegalArgumentException("Se seleccionó clave privada, pero no hay clave configurada."))
        }
        if (profile.protocol == TunnelProtocol.SSH_JUMP || profile.jumpHostEnabled) {
            if (profile.jumpHost.isBlank() || profile.jumpUsername.isBlank()) {
                return Result.failure(IllegalArgumentException("Completa host y usuario del Jump Host SSH."))
            }
            if (profile.jumpPort !in 1..65535) {
                return Result.failure(IllegalArgumentException("Puerto del Jump Host inválido."))
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

            val ctx = context ?: return@withContext Result.failure(IllegalStateException("EngineContext ausente."))
            val timeoutMs = ctx.connectionTimeoutMs.coerceIn(1_000, 999_000)
            _status.value = EngineStatus.STARTING

            return@withContext runCatching {
                stopInternal()
                val jsch = JSch()
                jsch.hostKeyRepository = createVerifiedHostKeyRepository(ctx)

                val useJump = profile.jumpHostEnabled || profile.protocol == TunnelProtocol.SSH_JUMP
                if (useJump) {
                    val jumpJsch = JSch()
                    jumpJsch.hostKeyRepository = createVerifiedHostKeyRepository(ctx)
                    if (profile.jumpAuthMethod == SshAuthMethod.PRIVATE_KEY && secrets.jumpPrivateKeyPem.isNotBlank()) {
                        jumpJsch.addIdentity(
                            "jump-key",
                            secrets.jumpPrivateKeyPem.toByteArray(StandardCharsets.UTF_8),
                            null,
                            secrets.keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8)
                        )
                    }
                    val jSession = jumpJsch.getSession(profile.jumpUsername, profile.jumpHost, profile.jumpPort)
                    jSession.setSocketFactory(ProtectedSocketFactory(ctx.protectSocket, profile, secrets, applyPayload = false, timeoutMs = timeoutMs))
                    if (profile.jumpAuthMethod == SshAuthMethod.PASSWORD) {
                        jSession.setPassword(secrets.jumpPassword.toByteArray(StandardCharsets.UTF_8))
                    }
                    configureSession(jSession, ctx, profile.jumpHost, profile.jumpPort)
                    AppLogger.info(TAG, "Conectando Jump Host SSH ${profile.jumpHost}:${profile.jumpPort} (timeout=${timeoutMs} ms)...")
                    jSession.connect(timeoutMs)
                    jumpSession = jSession
                    forwardedLocalPort = jSession.setPortForwardingL("127.0.0.1", 0, profile.host, profile.port)
                    AppLogger.info(TAG, "Túnel Jump Host activo en loopback:$forwardedLocalPort")
                }

                if (profile.authMethod == SshAuthMethod.PRIVATE_KEY && secrets.privateKeyPem.isNotBlank()) {
                    jsch.addIdentity(
                        "primary-key",
                        secrets.privateKeyPem.toByteArray(StandardCharsets.UTF_8),
                        null,
                        secrets.keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8)
                    )
                }

                val targetHost = if (useJump) "127.0.0.1" else profile.host
                val targetPort = if (useJump) forwardedLocalPort else profile.port
                val session = jsch.getSession(profile.username, targetHost, targetPort)
                if (!useJump) {
                    session.setSocketFactory(
                        ProtectedSocketFactory(
                            protectSocket = ctx.protectSocket,
                            profile = profile,
                            secrets = secrets,
                            applyPayload = profile.rawHttpPayload.isNotEmpty() || profile.tlsEnabled,
                            timeoutMs = timeoutMs
                        )
                    )
                }
                if (profile.authMethod == SshAuthMethod.PASSWORD) {
                    session.setPassword(secrets.password.toByteArray(StandardCharsets.UTF_8))
                }
                session.hostKeyAlias = "${profile.host}:${profile.port}"
                configureSession(session, ctx, profile.host, profile.port)

                AppLogger.info(TAG, "Autenticando sesión SSH contra ${profile.host}:${profile.port} (timeout=${timeoutMs} ms)...")
                session.connect(timeoutMs)
                session.serverAliveInterval = 25_000
                session.serverAliveCountMax = 3
                primarySession = session
                activeProfile = profile

                val socksServer = LocalSocks5Server { dstHost, dstPort ->
                    openTcpTunnel(dstHost, dstPort)
                }
                val socksPort = socksServer.start()
                localSocksServer = socksServer

                _status.value = EngineStatus.RUNNING
                AppLogger.info(TAG, "Sesión SSH establecida correctamente.")
                AppLogger.info(TAG, "SSH Port Forwarding (direct-tcpip / SOCKS5 127.0.0.1:$socksPort) listo")
            }.onFailure { err ->
                stopInternal()
                _status.value = EngineStatus.ERROR
                AppLogger.error(TAG, "Error estableciendo túnel SSH", err)
            }
        }

    override suspend fun stop() {
        _status.value = EngineStatus.STOPPING
        withContext(Dispatchers.IO) {
            stopInternal()
        }
        _status.value = EngineStatus.IDLE
    }

    private fun stopInternal() {
        runCatching { localSocksServer?.close() }
        localSocksServer = null
        activeProfile = null
        runCatching { primarySession?.disconnect() }
        primarySession = null
        runCatching {
            if (forwardedLocalPort > 0) {
                jumpSession?.delPortForwardingL("127.0.0.1", forwardedLocalPort)
            }
        }
        forwardedLocalPort = -1
        runCatching { jumpSession?.disconnect() }
        jumpSession = null
    }

    override suspend fun openTcpTunnel(targetHost: String, targetPort: Int): TunnelStream =
        withContext(Dispatchers.IO) {
            val session = primarySession ?: throw IOException("Sesión SSH no conectada.")
            if (!session.isConnected) throw IOException("Sesión SSH desconectada.")

            val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(targetHost)
            channel.setPort(targetPort)
            channel.setOrgIPAddress("127.0.0.1")
            channel.setOrgPort(0)
            val input = channel.inputStream
            val output = channel.outputStream
            val timeoutMs = (context?.connectionTimeoutMs ?: CONNECT_TIMEOUT_MS).coerceIn(1_000, 999_000)
            channel.connect(timeoutMs)

            object : TunnelStream {
                override val inputStream: InputStream = input
                override val outputStream: OutputStream = output
                override fun halfCloseOutput() {
                    runCatching { output.close() }
                }
                override fun close() {
                    runCatching { channel.disconnect() }
                }
            }
        }

    override suspend fun openUdpRelay(): UdpRelaySession? = withContext(Dispatchers.IO) {
        val session = primarySession ?: return@withContext null
        if (!session.isConnected) return@withContext null
        val udpgwPort = activeProfile?.udpgwPort ?: 7300
        if (udpgwPort !in 1..65535) return@withContext null

        val client = BadVpnUdpgwClient {
            openTcpTunnel("127.0.0.1", udpgwPort)
        }
        if (client.connectInitial()) {
            AppLogger.info(TAG, "UDPGW conectado al puerto remoto $udpgwPort")
            client
        } else {
            client.close()
            AppLogger.warn(
                TAG,
                "UDPGW no disponible en 127.0.0.1:$udpgwPort (DNS-over-TCP/DoH seguirá activo por direct-tcpip)"
            )
            null
        }
    }

    override fun getLocalSocksPort(): Int? = localSocksServer?.localPort?.takeIf { it > 0 }

    private fun configureSession(session: Session, ctx: EngineContext, realHost: String, realPort: Int) {
        val config = Properties()
        config["StrictHostKeyChecking"] = "yes"
        config["PreferredAuthentications"] = "publickey,password,keyboard-interactive"
        session.setConfig(config)
        session.userInfo = object : UserInfo {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = null
            override fun promptPassword(message: String?): Boolean = false
            override fun promptPassphrase(message: String?): Boolean = false
            override fun promptYesNo(message: String?): Boolean = false
            override fun showMessage(message: String?) {
                if (!message.isNullOrBlank()) {
                    AppLogger.info(TAG, "SSH Banner ($realHost:$realPort): $message")
                }
            }
        }
    }

    private fun createVerifiedHostKeyRepository(ctx: EngineContext): HostKeyRepository {
        return object : HostKeyRepository {
            override fun check(host: String?, key: ByteArray?): Int {
                if (host == null || key == null) return HostKeyRepository.NOT_INCLUDED
                val (parsedHost, parsedPort) = parseHostPortAlias(host)
                val hk = HostKey(host, key)
                return when (val result = hostKeyStore.verify(parsedHost, parsedPort, hk.type, key)) {
                    is HostKeyVerificationResult.Trusted -> HostKeyRepository.OK
                    is HostKeyVerificationResult.UnknownHost -> {
                        val approved = runBlocking {
                            ctx.onSshHostKeyChallenge(
                                SshHostKeyChallenge(
                                    host = parsedHost,
                                    port = parsedPort,
                                    keyType = hk.type,
                                    sha256Fingerprint = result.sha256Fingerprint,
                                    isMismatchWarning = false
                                )
                            )
                        }
                        if (approved) {
                            hostKeyStore.trustHostKey(parsedHost, parsedPort, hk.type, result.sha256Fingerprint)
                            HostKeyRepository.OK
                        } else {
                            HostKeyRepository.NOT_INCLUDED
                        }
                    }
                    is HostKeyVerificationResult.HostKeyMismatch -> {
                        val approved = runBlocking {
                            ctx.onSshHostKeyChallenge(
                                SshHostKeyChallenge(
                                    host = parsedHost,
                                    port = parsedPort,
                                    keyType = hk.type,
                                    sha256Fingerprint = result.actualFingerprint,
                                    isMismatchWarning = true,
                                    previousFingerprint = result.expectedFingerprint
                                )
                            )
                        }
                        if (approved) {
                            hostKeyStore.trustHostKey(parsedHost, parsedPort, hk.type, result.actualFingerprint)
                            HostKeyRepository.OK
                        } else {
                            HostKeyRepository.CHANGED
                        }
                    }
                }
            }

            override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID(): String = "AioSshHostKeyStore"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
    }

    private fun parseHostPortAlias(alias: String): Pair<String, Int> {
        val clean = alias.removePrefix("[").substringBefore("]")
        val idx = clean.lastIndexOf(':')
        return if (idx > 0) {
            val h = clean.substring(0, idx)
            val p = clean.substring(idx + 1).toIntOrNull() ?: 22
            h to p
        } else clean to 22
    }

    private class ProtectedSocketFactory(
        private val protectSocket: (Socket) -> Boolean,
        private val profile: TunnelProfile,
        private val secrets: ProfileSecrets,
        private val applyPayload: Boolean,
        private val timeoutMs: Int
    ) : SocketFactory {
        private val bufferedInputs = IdentityHashMap<Socket, InputStream>()

        override fun createSocket(host: String?, port: Int): Socket {
            val targetHost = host ?: profile.host
            if (!applyPayload) {
                val socket = Socket()
                socket.tcpNoDelay = true
                if (!protectSocket(socket)) {
                    socket.close()
                    throw IOException("VpnService.protect() rechazó socket SSH.")
                }
                socket.connect(InetSocketAddress(targetHost, port), timeoutMs)
                return socket
            }

            val socket = RawTcpPayloadExecutor.connectRawSocket(
                host = targetHost,
                port = port,
                useTls = profile.tlsEnabled,
                sniHost = profile.tlsServerName.ifBlank { profile.host.trim() },
                verifyTlsCertificate = profile.verifyTlsCertificate,
                connectTimeoutMs = timeoutMs,
                readTimeoutMs = timeoutMs,
                protectSocket = protectSocket
            )
            try {
                if (profile.rawHttpPayload.isNotEmpty()) {
                    val bufferedIn = BufferedInputStream(socket.getInputStream())
                    val statusLine = RawTcpPayloadExecutor.executeRawPayloadHandshake(
                        socket = socket,
                        bufferedIn = bufferedIn,
                        profile = profile,
                        secrets = secrets,
                        targetHost = targetHost,
                        targetPort = port,
                        baseReadTimeoutMs = timeoutMs
                    )
                    AppLogger.info(TAG, "Payload SSH/WS aceptado: $statusLine")
                    synchronized(bufferedInputs) {
                        bufferedInputs[socket] = bufferedIn
                    }
                }
                socket.soTimeout = 0
                return socket
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }

        override fun getInputStream(socket: Socket): InputStream =
            synchronized(bufferedInputs) { bufferedInputs[socket] } ?: socket.getInputStream()

        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }

    override fun getStatistics(): TunnelStatistics =
        context?.statisticsManager?.stats?.value ?: TunnelStatistics()

    override fun release() {
        stopInternal()
        _status.value = EngineStatus.IDLE
    }

    companion object {
        private const val TAG = "SshTunnelEngine"
        private const val CONNECT_TIMEOUT_MS = 12_000
    }
}
