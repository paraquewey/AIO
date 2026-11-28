package com.example.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.AioTunnelApplication
import com.example.MainActivity
import com.example.R
import com.example.core.logger.AppLogger
import com.example.core.network.VpnRouteHelper
import com.example.domain.models.ConnectionState
import com.example.domain.models.ServerMessageMode
import com.example.domain.models.SplitTunnelMode
import com.example.domain.models.TunnelProfile
import com.example.engines.adapter.EngineContext
import com.example.engines.adapter.EngineStatus
import com.example.engines.adapter.TunnelEngine
import com.example.engines.proxy.WifiShareProxyServer
import com.example.vpn.monitor.NetworkMonitor
import com.example.vpn.tun.DnsManager
import com.example.vpn.tun.TunPacketRouter
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
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
    private var wifiShareProxy: WifiShareProxyServer? = null
    private var wifiShareObserverJob: Job? = null
    private var engineStatusWatcherJob: Job? = null
    private var notificationUpdaterJob: Job? = null
    private var activeProfileId: String? = null
    private var reconnectAttempt = 0
    private var disconnectingFlag = false
    private val wakeLockMutex = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                serviceScope.launch {
                    (application as AioTunnelApplication).connectionManager.markUserStopped()
                    disconnectInternal(userRequested = true)
                    stopForegroundAndSelf()
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
        if (connMgr.userRequestedStop) {
            stopForegroundAndSelf()
            return
        }

        val profile = app.profileRepository.getProfile(profileId)
        if (profile == null) {
            connMgr.reportError("El perfil seleccionado ya no existe.")
            stopForegroundAndSelf()
            return
        }
        val secrets = app.profileRepository.getSecrets(profile.id)
        val appSettings = app.settingsRepository.settings.first()
        val timeoutMs = appSettings.connectionTimeoutSeconds.coerceIn(1, 999) * 1_000

        disconnectInternal(userRequested = false)
        activeProfileId = profile.id
        disconnectingFlag = false

        connMgr.setState(ConnectionState.Preparing(profile.name))
        updateNotification("Preparando ${profile.name} (${profile.protocol.badgeCode})...")

        val engine = app.engineFactory.createForProtocol(profile.protocol)
        val dnsManager = DnsManager(
            protectSocket = { sock -> protect(sock) },
            protectDatagramSocket = { ds -> protect(ds) }
        )
        val dnsConfig = DnsManager.resolveActiveDnsConfig(profile, appSettings)

        val engineContext = EngineContext(
            protectSocket = { sock -> protect(sock) },
            protectDatagramSocket = { ds -> protect(ds) },
            statisticsManager = statsMgr,
            onSshHostKeyChallenge = { challenge ->
                connMgr.requestSshHostKeyConfirmation(challenge)
            },
            connectionTimeoutMs = timeoutMs
        )
        engine.initialize(engineContext)

        connMgr.setState(ConnectionState.Connecting(profile.name, "Autenticando ${profile.host}:${profile.port}"))
        updateNotification("Conectando a ${profile.host}:${profile.port}...")

        val startResult = engine.start(profile, secrets)
        if (startResult.isFailure) {
            val errMsg = startResult.exceptionOrNull()?.message ?: "No se pudo iniciar el motor de túnel."
            AppLogger.error(TAG, "Conexión fallida para ${profile.name}: $errMsg")
            engine.release()
            connMgr.clearActiveTunnel()
            connMgr.handleUnexpectedDisconnectOrFailure(profile.id, profile.name, errMsg)
            stopForegroundAndSelf()
            return
        }

        // Establish Android TUN interface AFTER engine authentication succeeds
        val effectiveBypassLan = appSettings.bypassLan && profile.bypassLan
        val tunFd = establishTunInterface(profile, dnsConfig, effectiveBypassLan)
        if (tunFd == null) {
            engine.stop()
            engine.release()
            connMgr.clearActiveTunnel()
            val errMsg = "Android rechazó la creación de la interfaz TUN (verifica permisos VPN)."
            connMgr.handleUnexpectedDisconnectOrFailure(profile.id, profile.name, errMsg)
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
            dnsConfig = dnsConfig,
            engine = engine,
            dnsManager = dnsManager,
            statisticsManager = statsMgr
        )
        packetRouter = router
        router.start()
        connMgr.setActiveTunnel(engine, dnsConfig)
        connMgr.resetRetryCounter()

        startNetworkMonitor(profile.id)
        startWifiProxyObserver(engine, statsMgr, connMgr, profile.bypassLan)
        startEngineDropWatcher(engine, profile.id, profile.name, connMgr)

        val originalBanner = if (profile.serverMessageMode == ServerMessageMode.ORIGINAL) {
            engine.getServerBanner()
        } else {
            null
        }
        connMgr.setState(
            ConnectionState.Connected(
                profileId = profile.id,
                profileName = profile.name,
                protocol = profile.protocol,
                serverEndpoint = "${profile.host}:${profile.port}",
                exitIp = null,
                latencyMs = profile.lastLatencyMs,
                serverMessageMode = profile.serverMessageMode,
                customServerMessageHtml = profile.customServerMessageHtml,
                originalServerBanner = originalBanner
            )
        )
        AppLogger.info(
            TAG,
            "Túnel activo (DNS1=${dnsConfig.primaryDns}, DNS2=${dnsConfig.secondaryDns}, modo=${dnsConfig.dnsMode.name}, timeout=${appSettings.connectionTimeoutSeconds}s)"
        )
        if (appSettings.wakeLockEnabled) {
            acquireVpnWakeLock()
        }
        updateNotification("Conectado · ${profile.name} (${profile.protocol.badgeCode})")
        startNotificationTicker(profile.name)

        // Probe real exit IP + geolocation + latency asynchronously without blocking tunnel
        serviceScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val exitInfo = dnsManager.fetchExitIpInfoThroughTunnel(engine)
            val measuredLatency = if (exitInfo != null) System.currentTimeMillis() - t0 else profile.lastLatencyMs
            val current = connMgr.connectionState.value
            if (current is ConnectionState.Connected && current.profileId == profile.id) {
                connMgr.setState(
                    current.copy(
                        exitIp = exitInfo?.ip,
                        exitCountry = exitInfo?.country,
                        exitCountryCode = exitInfo?.countryCode,
                        exitCity = exitInfo?.city,
                        latencyMs = measuredLatency
                    )
                )
                if (measuredLatency != null) {
                    app.profileRepository.updateLatency(profile.id, measuredLatency)
                }
            }
        }
    }

    private fun startWifiProxyObserver(
        engine: TunnelEngine,
        statsMgr: com.example.core.stats.StatisticsManager,
        connMgr: com.example.core.connection.ConnectionManager,
        profileBypassLan: Boolean
    ) {
        wifiShareObserverJob?.cancel()
        val app = application as AioTunnelApplication
        val bypassFlag = AtomicBoolean(profileBypassLan)
        wifiShareObserverJob = serviceScope.launch(Dispatchers.IO) {
            var currentPort: Int? = null
            app.settingsRepository.settings.collect { settings ->
                if (!disconnectingFlag && activeEngine === engine) {
                    if (settings.wakeLockEnabled) {
                        acquireVpnWakeLock()
                    } else {
                        releaseVpnWakeLock()
                    }
                }
                bypassFlag.set(settings.bypassLan && profileBypassLan)
                val desiredPort = if (settings.wifiProxyShareEnabled) {
                    settings.wifiProxySharePort.coerceIn(1024, 65535)
                } else null

                if (desiredPort == null) {
                    runCatching { wifiShareProxy?.close() }
                    wifiShareProxy = null
                    currentPort = null
                    connMgr.setWifiProxyActivePort(null)
                } else if (desiredPort != currentPort || wifiShareProxy == null) {
                    runCatching { wifiShareProxy?.close() }
                    wifiShareProxy = null
                    val timeoutMs = settings.connectionTimeoutSeconds.coerceIn(1, 999) * 1_000
                    val server = WifiShareProxyServer(
                        listenPort = desiredPort,
                        openTunnel = { host, port -> engine.openTcpTunnel(host, port) },
                        statisticsManager = statsMgr,
                        context = applicationContext,
                        protectSocket = { sock -> protect(sock) },
                        isBypassLanEnabled = { bypassFlag.get() },
                        connectTimeoutMs = timeoutMs
                    )
                    runCatching {
                        val bound = server.start()
                        wifiShareProxy = server
                        currentPort = bound
                        connMgr.setWifiProxyActivePort(bound)
                    }.onFailure { err ->
                        runCatching { server.close() }
                        currentPort = null
                        connMgr.setWifiProxyActivePort(null)
                        AppLogger.error(TAG, "No se pudo iniciar Proxy Wi-Fi en 0.0.0.0:$desiredPort", err)
                    }
                }
            }
        }
    }

    private fun startEngineDropWatcher(
        engine: TunnelEngine,
        profileId: String,
        profileName: String,
        connMgr: com.example.core.connection.ConnectionManager
    ) {
        engineStatusWatcherJob?.cancel()
        engineStatusWatcherJob = serviceScope.launch {
            engine.status.collect { st ->
                if (!disconnectingFlag &&
                    !connMgr.userRequestedStop &&
                    activeEngine === engine &&
                    (st == EngineStatus.ERROR || st == EngineStatus.IDLE)
                ) {
                    AppLogger.warn(TAG, "El motor del túnel se detuvo inesperadamente ($st).")
                    disconnectInternal(userRequested = false)
                    connMgr.handleUnexpectedDisconnectOrFailure(
                        profileId = profileId,
                        profileName = profileName,
                        reason = "Conexión interrumpida"
                    )
                    stopForegroundAndSelf()
                }
            }
        }
    }

    private fun establishTunInterface(
        profile: TunnelProfile,
        dnsConfig: DnsManager.ResolvedDnsConfig,
        bypassLan: Boolean
    ): ParcelFileDescriptor? {
        return runCatching {
            val tunLocalIp = VpnRouteHelper.sanitizeIpv4Address(DEFAULT_TUN_IPV4) ?: DEFAULT_TUN_IPV4
            val primaryDnsIp = VpnRouteHelper.sanitizeIpv4Address(dnsConfig.primaryDns)
                ?: DnsManager.DEFAULT_PRIMARY_DNS
            val secondaryDnsIp = VpnRouteHelper.sanitizeIpv4Address(dnsConfig.secondaryDns)
                ?.takeIf { it != primaryDnsIp }

            if (primaryDnsIp != dnsConfig.primaryDns.trim()) {
                AppLogger.warn(
                    TAG,
                    "DNS primario '${dnsConfig.primaryDns}' sanitizado a '$primaryDnsIp' antes de Builder.addDnsServer()."
                )
            }

            val builder = Builder()
                .setSession("AIO Tunnel - ${profile.name}")
                .setMtu(profile.mtu.coerceIn(1280, 9000))
                .addAddress(tunLocalIp, DEFAULT_TUN_PREFIX)
                .addDnsServer(primaryDnsIp)
                .apply {
                    if (secondaryDnsIp != null) {
                        addDnsServer(secondaryDnsIp)
                    }
                }

            if (bypassLan) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    builder.addRoute("0.0.0.0", 0)
                    var excludedCount = 0
                    VpnRouteHelper.EXCLUDED_LAN_CGNAT_CIDRS.forEach { rawCidr ->
                        val cidr = rawCidr.normalized()
                        if (!VpnRouteHelper.canPassToVpnBuilder(cidr, isExcludeRoute = true)) {
                            AppLogger.info(
                                TAG,
                                "Omitiendo $cidr en excludeRoute() (manejado nativamente por el sistema)."
                            )
                            return@forEach
                        }
                        val added = runCatching {
                            val mask = VpnRouteHelper.maskForPrefix(cidr.prefixLength)
                            val maskedIp = VpnRouteHelper.longToIpv4(cidr.addressLong and mask)
                            builder.excludeRoute(IpPrefix(InetAddress.getByName(maskedIp), cidr.prefixLength))
                        }.onFailure { err ->
                            AppLogger.warn(
                                TAG,
                                "excludeRoute($cidr) rechazado por el sistema: ${err.message}"
                            )
                        }.isSuccess
                        if (added) excludedCount++
                    }
                    AppLogger.info(
                        TAG,
                        "Bypass LAN/CGNAT activo (Android 13+, $excludedCount prefijos excluidos)"
                    )
                } else {
                    val allowedPublicRoutes = VpnRouteHelper.computeAllowedPublicIpv4Routes()
                    var addedCount = 0
                    allowedPublicRoutes.forEach { rawCidr ->
                        val cidr = rawCidr.normalized()
                        if (!VpnRouteHelper.canPassToVpnBuilder(cidr, isExcludeRoute = false)) {
                            AppLogger.warn(TAG, "Omitiendo ruta CIDR inválida en addRoute(): $cidr")
                            return@forEach
                        }
                        val added = runCatching {
                            val mask = VpnRouteHelper.maskForPrefix(cidr.prefixLength)
                            val maskedIp = VpnRouteHelper.longToIpv4(cidr.addressLong and mask)
                            builder.addRoute(maskedIp, cidr.prefixLength)
                        }.onFailure { err ->
                            AppLogger.warn(
                                TAG,
                                "addRoute($cidr) rechazado por el sistema: ${err.message}"
                            )
                        }.isSuccess
                        if (added) addedCount++
                    }
                    if (addedCount == 0) {
                        AppLogger.warn(TAG, "Ninguna ruta CIDR parcial aceptada; usando fallback 0.0.0.0/0")
                        builder.addRoute("0.0.0.0", 0)
                    } else {
                        AppLogger.info(
                            TAG,
                            "Bypass LAN/CGNAT activo ($addedCount rutas públicas CIDR sin LAN/CGNAT)"
                        )
                    }
                }
            } else {
                builder.addRoute("0.0.0.0", 0)
            }

            if (profile.enableIpv6) {
                runCatching {
                    builder.addAddress("fd89:1122:3344::2", 126)
                    builder.addRoute("::", 0)
                }.onFailure { err ->
                    AppLogger.warn(TAG, "No se pudo añadir ruta IPv6 al TUN: ${err.message}")
                }
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
            AppLogger.error(TAG, "Error en VpnService.Builder.establish() (${err.message})", err)
        }.getOrNull()
    }

    private fun startNetworkMonitor(profileId: String) {
        networkMonitor?.stop()
        val monitor = NetworkMonitor(applicationContext) {
            serviceScope.launch {
                val app = application as AioTunnelApplication
                val settings = app.settingsRepository.settings.first()
                if (!settings.autoReconnectOnNetworkChange && !settings.keepTryingToConnect) return@launch
                if (app.connectionManager.userRequestedStop) return@launch
                if (reconnectAttempt >= 5) {
                    AppLogger.warn(TAG, "Límite de reconexión por cambio de red alcanzado.")
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
        disconnectingFlag = true
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
        engineStatusWatcherJob?.cancel()
        engineStatusWatcherJob = null
        wifiShareObserverJob?.cancel()
        wifiShareObserverJob = null
        runCatching { wifiShareProxy?.close() }
        wifiShareProxy = null
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
        releaseVpnWakeLock()
        app.connectionManager.clearActiveTunnel()
        app.statisticsManager.stopSession()
        if (userRequested) {
            app.connectionManager.setState(ConnectionState.Disconnected)
        }
    }

    override fun onRevoke() {
        AppLogger.warn(TAG, "Permiso VPN revocado por el sistema operativo.")
        serviceScope.launch {
            (application as AioTunnelApplication).connectionManager.markUserStopped()
            disconnectInternal(userRequested = true)
            stopForegroundAndSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        disconnectingFlag = true
        engineStatusWatcherJob?.cancel()
        wifiShareObserverJob?.cancel()
        runCatching { wifiShareProxy?.close() }
        notificationUpdaterJob?.cancel()
        networkMonitor?.stop()
        packetRouter?.stop()
        runCatching { tunInterface?.close() }
        runCatching { activeEngine?.release() }
        releaseVpnWakeLock()
        (application as? AioTunnelApplication)?.connectionManager?.clearActiveTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun acquireVpnWakeLock() {
        synchronized(wakeLockMutex) {
            val existing = wakeLock
            if (existing != null && existing.isHeld) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val wl = existing ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
            }.also { wakeLock = it }
            if (!wl.isHeld) {
                @Suppress("WakelockTimeout")
                wl.acquire()
                AppLogger.info(TAG, "WakeLock acquire (CPU activa en segundo plano)")
            }
        }
    }

    private fun releaseVpnWakeLock() {
        synchronized(wakeLockMutex) {
            val wl = wakeLock ?: return
            if (wl.isHeld) {
                runCatching { wl.release() }
                AppLogger.info(TAG, "WakeLock release")
            }
            wakeLock = null
        }
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
        private const val WAKE_LOCK_TAG = "AioTunnelVpn::VpnWakeLock"
        private const val DEFAULT_TUN_IPV4 = "10.89.0.2"
        private const val DEFAULT_TUN_PREFIX = 30
        const val CHANNEL_ID = "aio_vpn_status_channel"
        const val NOTIFICATION_ID = 4107
        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
    }
}
