package com.example.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.AioTunnelApplication
import com.example.MainActivity
import com.example.R
import com.example.core.logger.AppLogger
import com.example.domain.models.ConnectionState
import com.example.domain.models.SplitTunnelMode
import com.example.domain.models.TunnelProfile
import com.example.engines.adapter.EngineContext
import com.example.engines.adapter.TunnelEngine
import com.example.vpn.monitor.NetworkMonitor
import com.example.vpn.tun.DnsManager
import com.example.vpn.tun.TunPacketRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AioVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tunInterface: ParcelFileDescriptor? = null
    private var packetRouter: TunPacketRouter? = null
    private var activeEngine: TunnelEngine? = null
    private var networkMonitor: NetworkMonitor? = null
    private var notificationUpdaterJob: Job? = null
    private var activeProfileId: String? = null
    private var reconnectAttempt = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                serviceScope.launch {
                    disconnectInternal(userRequested = true)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_CONNECT, null -> {
                val requestedProfileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
                promoteToForeground("Preparando túnel VPN...")
                serviceScope.launch {
                    val app = application as AioTunnelApplication
                    val profileId = requestedProfileId
                        ?: app.settingsRepository.settings.first().selectedProfileId
                    if (profileId == null) {
                        app.connectionManager.reportError("No hay ningún perfil seleccionado para Always-on o conexión rápida.")
                        stopForegroundAndSelf()
                        return@launch
                    }
                    connectProfile(profileId)
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun connectProfile(profileId: String) {
        val app = application as AioTunnelApplication
        val connMgr = app.connectionManager
        val statsMgr = app.statisticsManager

        val profile = app.profileRepository.getProfile(profileId)
        if (profile == null) {
            connMgr.reportError("El perfil seleccionado ya no existe.")
            stopForegroundAndSelf()
            return
        }
        val secrets = app.profileRepository.getSecrets(profile.id)

        activeProfileId = profile.id
        connMgr.setState(ConnectionState.Preparing(profile.name))
        updateNotification("Preparando ${profile.name} (${profile.protocol.badgeCode})...")

        val engine = app.engineFactory.createForProtocol(profile.protocol)
        val dnsManager = DnsManager(
            protectSocket = { sock -> protect(sock) },
            protectDatagramSocket = { ds -> protect(ds) }
        )

        val engineContext = EngineContext(
            protectSocket = { sock -> protect(sock) },
            protectDatagramSocket = { ds -> protect(ds) },
            statisticsManager = statsMgr,
            onSshHostKeyChallenge = { challenge ->
                connMgr.requestSshHostKeyConfirmation(challenge)
            }
        )
        engine.initialize(engineContext)

        connMgr.setState(ConnectionState.Connecting(profile.name, "Autenticando ${profile.host}:${profile.port}"))
        updateNotification("Conectando a ${profile.host}:${profile.port}...")

        val startResult = engine.start(profile, secrets)
        if (startResult.isFailure) {
            val errMsg = startResult.exceptionOrNull()?.message ?: "No se pudo iniciar el motor de túnel."
            AppLogger.error(TAG, "Conexión fallida para ${profile.name}: $errMsg")
            engine.release()
            connMgr.reportError(errMsg)
            stopForegroundAndSelf()
            return
        }

        // Establish Android TUN interface AFTER engine authentication succeeds
        val tunFd = establishTunInterface(profile)
        if (tunFd == null) {
            engine.stop()
            engine.release()
            connMgr.reportError("Android rechazó la creación de la interfaz TUN (verifica permisos VPN).")
            stopForegroundAndSelf()
            return
        }

        tunInterface = tunFd
        activeEngine = engine
        reconnectAttempt = 0
        statsMgr.startSession()

        val router = TunPacketRouter(
            tunFd = tunFd,
            profile = profile,
            engine = engine,
            dnsManager = dnsManager,
            statisticsManager = statsMgr
        )
        packetRouter = router
        router.start()

        startNetworkMonitor(profile.id)

        connMgr.setState(
            ConnectionState.Connected(
                profileId = profile.id,
                profileName = profile.name,
                protocol = profile.protocol,
                serverEndpoint = "${profile.host}:${profile.port}",
                exitIp = null,
                latencyMs = profile.lastLatencyMs
            )
        )
        updateNotification("Conectado · ${profile.name} (${profile.protocol.badgeCode})")
        startNotificationTicker(profile.name)

        // Probe real exit IP & latency asynchronously without blocking tunnel
        serviceScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val exitIp = dnsManager.fetchExitIpThroughTunnel(engine)
            val measuredLatency = if (exitIp != null) System.currentTimeMillis() - t0 else profile.lastLatencyMs
            val current = connMgr.connectionState.value
            if (current is ConnectionState.Connected && current.profileId == profile.id) {
                connMgr.setState(current.copy(exitIp = exitIp, latencyMs = measuredLatency))
                if (measuredLatency != null) {
                    app.profileRepository.updateLatency(profile.id, measuredLatency)
                }
            }
        }
    }

    private fun establishTunInterface(profile: TunnelProfile): ParcelFileDescriptor? {
        return runCatching {
            val builder = Builder()
                .setSession("AIO Tunnel - ${profile.name}")
                .setMtu(profile.mtu.coerceIn(1280, 9000))
                .addAddress("10.89.0.2", 30)
                .addDnsServer(profile.dnsServer.ifBlank { "1.1.1.1" })
                .addRoute("0.0.0.0", 0)

            if (profile.enableIpv6) {
                builder.addAddress("fd89:1122:3344::2", 126)
                builder.addRoute("::", 0)
            }

            // Recursive loop protection: NEVER route AIO Tunnel VPN's own sockets back into TUN!
            runCatching { builder.addDisallowedApplication(packageName) }

            // Per-app Split Tunneling
            when (profile.splitTunnelMode) {
                SplitTunnelMode.ALL_APPS -> Unit
                SplitTunnelMode.INCLUDE_SELECTED -> {
                    profile.splitTunnelPackages
                        .filter { it != packageName }
                        .forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
                }
                SplitTunnelMode.EXCLUDE_SELECTED -> {
                    profile.splitTunnelPackages
                        .filter { it != packageName }
                        .forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            builder.establish()
        }.onFailure { err ->
            AppLogger.error(TAG, "Error en VpnService.Builder.establish()", err)
        }.getOrNull()
    }

    private fun startNetworkMonitor(profileId: String) {
        networkMonitor?.stop()
        val monitor = NetworkMonitor(applicationContext) {
            serviceScope.launch {
                val app = application as AioTunnelApplication
                val autoReconnect = app.settingsRepository.settings.first().autoReconnectOnNetworkChange
                if (!autoReconnect) return@launch
                if (reconnectAttempt >= 3) {
                    AppLogger.warn(TAG, "Límite de reconexión alcanzado.")
                    return@launch
                }
                reconnectAttempt++
                val profileName = app.profileRepository.getProfile(profileId)?.name ?: "Túnel"
                app.connectionManager.setState(
                    ConnectionState.Reconnecting(profileName, reconnectAttempt, "Cambio de red")
                )
                disconnectInternal(userRequested = false)
                delay(1200L)
                connectProfile(profileId)
            }
        }
        monitor.start()
        networkMonitor = monitor
    }

    private suspend fun disconnectInternal(userRequested: Boolean) {
        val app = application as AioTunnelApplication
        val currentState = app.connectionManager.connectionState.value
        val profileLabel = when (currentState) {
            is ConnectionState.Connected -> currentState.profileName
            is ConnectionState.Connecting -> currentState.profileName
            is ConnectionState.Preparing -> currentState.profileName
            else -> "Túnel"
        }
        if (userRequested) {
            app.connectionManager.setState(ConnectionState.Disconnecting(profileLabel))
        }
        notificationUpdaterJob?.cancel()
        notificationUpdaterJob = null
        networkMonitor?.stop()
        networkMonitor = null
        packetRouter?.stop()
        packetRouter = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        runCatching {
            activeEngine?.stop()
            activeEngine?.release()
        }
        activeEngine = null
        app.statisticsManager.stopSession()
        if (userRequested) {
            app.connectionManager.setState(ConnectionState.Disconnected)
        }
    }

    override fun onRevoke() {
        AppLogger.warn(TAG, "Permiso VPN revocado por el sistema operativo.")
        serviceScope.launch {
            disconnectInternal(userRequested = true)
            stopForegroundAndSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        notificationUpdaterJob?.cancel()
        networkMonitor?.stop()
        packetRouter?.stop()
        runCatching { tunInterface?.close() }
        runCatching { activeEngine?.release() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun promoteToForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startNotificationTicker(profileName: String) {
        notificationUpdaterJob?.cancel()
        notificationUpdaterJob = serviceScope.launch {
            val app = application as AioTunnelApplication
            while (isActive) {
                delay(2000L)
                val showSpeed = app.settingsRepository.settings.first().showSpeedInNotification
                val stats = app.statisticsManager.stats.value
                val text = if (showSpeed) {
                    "$profileName · ↓ ${formatSpeed(stats.downloadBytesPerSec)} · ↑ ${formatSpeed(stats.uploadBytesPerSec)}"
                } else {
                    "$profileName · Túnel cifrado activo"
                }
                updateNotification(text)
            }
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPi = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnectIntent = Intent(this, AioVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPi = PendingIntent.getService(
            this, 1, disconnectIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchPi)
            .addAction(0, getString(R.string.vpn_action_disconnect), disconnectPi)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_notification_channel_desc)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }

    companion object {
        private const val TAG = "AioVpnService"
        const val CHANNEL_ID = "aio_vpn_status_channel"
        const val NOTIFICATION_ID = 4107
        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
    }
}
