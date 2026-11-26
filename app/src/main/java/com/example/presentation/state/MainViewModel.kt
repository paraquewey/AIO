package com.example.presentation.state

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AioTunnelApplication
import com.example.core.logger.AppLogger
import com.example.data.import_export.ConfigParserAndExporter
import com.example.data.security.SshHostKeyStore
import com.example.domain.models.AppSettings
import com.example.domain.models.ConnectionState
import com.example.domain.models.DiagnosticCheckResult
import com.example.domain.models.DiagnosticStatus
import com.example.domain.models.LogEntry
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.SshHostKeyChallenge
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import com.example.domain.models.TunnelStatistics
import com.example.engines.http.HttpConnectProxyEngine
import com.example.vpn.tun.DnsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class InstalledAppInfo(
    val packageName: String,
    val label: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AioTunnelApplication
    private val profileRepo = app.profileRepository
    private val settingsRepo = app.settingsRepository
    private val connectionManager = app.connectionManager
    private val statisticsManager = app.statisticsManager
    private val hostKeyStore = app.sshHostKeyStore

    val profiles: StateFlow<List<TunnelProfile>> = profileRepo.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val statistics: StateFlow<TunnelStatistics> = statisticsManager.stats
    val pendingHostKeyChallenge: StateFlow<SshHostKeyChallenge?> = connectionManager.pendingHostKeyChallenge
    val logs: StateFlow<List<LogEntry>> = AppLogger.logs

    private val _diagnosticResults = MutableStateFlow(defaultDiagnosticChecks())
    val diagnosticResults: StateFlow<List<DiagnosticCheckResult>> = _diagnosticResults.asStateFlow()

    private val _knownHostKeys = MutableStateFlow<List<SshHostKeyStore.KnownHostRecord>>(emptyList())
    val knownHostKeys: StateFlow<List<SshHostKeyStore.KnownHostRecord>> = _knownHostKeys.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _isMeasuringLatency = MutableStateFlow(false)
    val isMeasuringLatency: StateFlow<Boolean> = _isMeasuringLatency.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    init {
        refreshKnownHosts()
        viewModelScope.launch {
            settings.collect { current ->
                AppLogger.verboseEnabled = current.verboseDebugLogs
            }
        }
    }

    fun consumeMessage() {
        _userMessage.value = null
    }

    fun postMessage(msg: String) {
        _userMessage.value = msg
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            settingsRepo.selectProfile(profileId)
        }
    }

    fun toggleFavorite(profile: TunnelProfile) {
        viewModelScope.launch {
            profileRepo.setFavorite(profile.id, !profile.isFavorite)
        }
    }

    fun saveProfile(profile: TunnelProfile, secrets: ProfileSecrets?, selectAfterSave: Boolean = true) {
        viewModelScope.launch {
            profileRepo.saveProfile(profile, secrets)
            if (selectAfterSave) {
                settingsRepo.selectProfile(profile.id)
            }
            AppLogger.info("Profiles", "Perfil '${profile.name}' (${profile.protocol.badgeCode}) guardado.")
            _userMessage.value = "Perfil '${profile.name}' guardado."
        }
    }

    fun loadSecretsForEditor(profileId: String): ProfileSecrets =
        profileRepo.getSecrets(profileId)

    fun duplicateProfile(profileId: String) {
        viewModelScope.launch {
            val copy = profileRepo.duplicateProfile(profileId)
            if (copy != null) {
                _userMessage.value = "Perfil duplicado: '${copy.name}'."
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            profileRepo.deleteProfile(profileId)
            if (settings.value.selectedProfileId == profileId) {
                val remaining = profiles.value.firstOrNull { it.id != profileId }?.id
                settingsRepo.selectProfile(remaining)
            }
            _userMessage.value = "Perfil eliminado de forma segura."
        }
    }

    fun importConfigurations(rawText: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                ConfigParserAndExporter.importFromText(rawText)
            }
            result.onSuccess { entries ->
                val existingNames = profileRepo.getAllProfiles().map { it.name }.toMutableSet()
                var lastId: String? = null
                for (entry in entries) {
                    var candidateName = entry.profile.name
                    if (existingNames.contains(candidateName)) {
                        candidateName = "$candidateName (Importado)"
                    }
                    existingNames.add(candidateName)
                    val safeProfile = entry.profile.copy(name = candidateName)
                    profileRepo.saveProfile(safeProfile, entry.secrets)
                    lastId = safeProfile.id
                }
                if (settings.value.selectedProfileId == null && lastId != null) {
                    settingsRepo.selectProfile(lastId)
                }
                AppLogger.info("Import", "Importados ${entries.size} perfil(es) tras validación estricta.")
                _userMessage.value = "Se importaron ${entries.size} perfil(es) correctamente."
            }.onFailure { err ->
                AppLogger.warn("Import", "Importación rechazada: ${err.message}")
                _userMessage.value = err.message ?: "Error al importar configuración."
            }
        }
    }

    suspend fun buildExportJson(profileIds: List<String>?, includeSecrets: Boolean): String {
        return withContext(Dispatchers.IO) {
            val all = profileRepo.getAllProfiles()
            val target = if (profileIds == null) all else all.filter { it.id in profileIds }
            val pairs = target.map { p -> p to profileRepo.getSecrets(p.id) }
            ConfigParserAndExporter.exportProfilesToJson(pairs, includeSecrets = includeSecrets)
        }
    }

    fun exportProfileUri(profile: TunnelProfile): String =
        ConfigParserAndExporter.exportProfileToUri(profile)

    fun startVpnConnection(profileId: String) {
        connectionManager.startVpn(profileId)
    }

    fun stopVpnConnection() {
        connectionManager.stopVpn()
    }

    fun clearConnectionError() {
        connectionManager.clearError()
    }

    fun resolveHostKeyChallenge(trust: Boolean) {
        connectionManager.resolveHostKeyChallenge(trust)
        refreshKnownHosts()
    }

    fun refreshKnownHosts() {
        _knownHostKeys.value = hostKeyStore.getAllRecords()
    }

    fun removeKnownHost(host: String, port: Int) {
        hostKeyStore.removeHostKey(host, port)
        refreshKnownHosts()
    }

    fun clearAllKnownHosts() {
        hostKeyStore.clearAll()
        refreshKnownHosts()
        _userMessage.value = "Claves de host SSH eliminadas."
    }

    fun measureAllServersLatency(autoSelectFastest: Boolean = false) {
        if (_isMeasuringLatency.value) return
        viewModelScope.launch {
            _isMeasuringLatency.value = true
            try {
                val list = profileRepo.getAllProfiles()
                if (list.isEmpty()) {
                    _userMessage.value = "No hay servidores configurados para medir."
                    return@launch
                }
                val measured = withContext(Dispatchers.IO) {
                    list.map { profile ->
                        async {
                            val latency = probeTcpHandshakeMs(profile)
                            profileRepo.updateLatency(profile.id, latency)
                            profile to latency
                        }
                    }.awaitAll()
                }
                val reachable = measured.filter { it.second != null && it.first.protocol.isBuiltInEngine }
                if (autoSelectFastest) {
                    val fastest = reachable.minByOrNull { it.second ?: Long.MAX_VALUE }
                    if (fastest != null) {
                        settingsRepo.selectProfile(fastest.first.id)
                        _userMessage.value = "Seleccionado '${fastest.first.name}' (${fastest.second} ms)."
                    } else {
                        _userMessage.value = "Ningún servidor integrado respondió al sondeo TCP."
                    }
                } else {
                    _userMessage.value = "Latencia medida en ${reachable.size}/${list.size} servidores."
                }
            } finally {
                _isMeasuringLatency.value = false
            }
        }
    }

    private fun probeTcpHandshakeMs(profile: TunnelProfile): Long? {
        return runCatching {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(profile.host, profile.port), 4_000)
                if (profile.protocol == TunnelProtocol.HTTPS_PROXY || profile.tlsEnabled) {
                    val sni = profile.tlsServerName.ifBlank { profile.host }
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(socket, sni, profile.port, true) as SSLSocket
                    ssl.soTimeout = 4_000
                    ssl.startHandshake()
                    if (profile.verifyTlsCertificate) {
                        val ok = HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, ssl.session)
                        if (!ok) throw SecurityException("Hostname TLS inválido")
                    }
                    ssl.close()
                }
            }
            (System.currentTimeMillis() - start).coerceAtLeast(1L)
        }.getOrNull()
    }

    fun runFullDiagnostics(activeProfile: TunnelProfile?) {
        viewModelScope.launch {
            val activeEngine = connectionManager.activeEngine
            val isConnected = connectionState.value is ConnectionState.Connected && activeEngine != null
            val currentSettings = settings.value
            val dnsConfig = connectionManager.activeDnsConfig
                ?: DnsManager.resolveActiveDnsConfig(activeProfile, currentSettings)

            val runningChecks = defaultDiagnosticChecks(isConnected).map {
                it.copy(status = DiagnosticStatus.RUNNING, summary = "Ejecutando prueba real...")
            }
            _diagnosticResults.value = runningChecks

            val dnsMgr = DnsManager(
                protectSocket = { true },
                protectDatagramSocket = { true }
            )

            // 1. DNS Resolution check (In-tunnel TCP when connected vs direct UDP when disconnected)
            val dnsResult = withContext(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val query = dnsMgr.buildSampleDnsQuery("example.com")
                if (isConnected) {
                    val engine = activeEngine!!
                    val res = dnsMgr.resolveViaTunnelTcpWithFallback(
                        queryBytes = query,
                        primaryDns = dnsConfig.primaryDns,
                        secondaryDns = dnsConfig.secondaryDns,
                        engine = engine
                    )
                    val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    if (res.responseBytes != null && res.responseBytes.size >= 12) {
                        DiagnosticCheckResult(
                            id = "dns_udp",
                            title = "DNS en túnel (${res.serverLabel})",
                            status = DiagnosticStatus.PASSED,
                            summary = if (res.usedFallback) {
                                "DNS 1 no respondió; respaldo DNS 2 operativo (${res.responseBytes.size} bytes)"
                            } else {
                                "Respuesta DNS-over-TCP dentro del túnel (${res.responseBytes.size} bytes)"
                            },
                            latencyMs = dt,
                            timestampMillis = System.currentTimeMillis()
                        )
                    } else {
                        DiagnosticCheckResult(
                            id = "dns_udp",
                            title = "DNS en túnel (${dnsConfig.primaryDns} / ${dnsConfig.secondaryDns})",
                            status = DiagnosticStatus.FAILED,
                            summary = res.errorSummary ?: "Sin respuesta DNS por TCP dentro del túnel",
                            timestampMillis = System.currentTimeMillis()
                        )
                    }
                } else {
                    val res = dnsMgr.resolveViaProtectedUdpWithFallback(
                        queryBytes = query,
                        primaryDns = dnsConfig.primaryDns,
                        secondaryDns = dnsConfig.secondaryDns
                    )
                    val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    if (res.responseBytes != null && res.responseBytes.size >= 12) {
                        DiagnosticCheckResult(
                            id = "dns_udp",
                            title = "DNS directo sin túnel (${res.serverLabel})",
                            status = DiagnosticStatus.PASSED,
                            summary = "Salida UDP directa operativa (${res.responseBytes.size} bytes)",
                            latencyMs = dt,
                            timestampMillis = System.currentTimeMillis()
                        )
                    } else {
                        DiagnosticCheckResult(
                            id = "dns_udp",
                            title = "DNS directo sin túnel (${dnsConfig.primaryDns} / ${dnsConfig.secondaryDns})",
                            status = DiagnosticStatus.FAILED,
                            summary = "Sin salida UDP directa desde ${dnsConfig.primaryDns}/${dnsConfig.secondaryDns} (habitual en red móvil sin saldo; al conectar usará TCP/DoH por el túnel)",
                            timestampMillis = System.currentTimeMillis()
                        )
                    }
                }
            }
            updateDiagnosticItem(dnsResult)

            // 2. DoH RFC 8484 check (In-tunnel when connected vs direct when disconnected)
            val dohResult = withContext(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val query = dnsMgr.buildSampleDnsQuery("cloudflare.com")
                if (isConnected) {
                    val res = dnsMgr.resolveViaTunnelDohWithDetails(query, dnsConfig, activeEngine!!)
                    val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    if (res.responseBytes != null && res.responseBytes.size >= 12) {
                        DiagnosticCheckResult(
                            id = "doh",
                            title = "DoH por túnel (RFC 8484)",
                            status = DiagnosticStatus.PASSED,
                            summary = "${res.serverLabel} (${res.responseBytes.size} bytes)",
                            latencyMs = dt,
                            timestampMillis = System.currentTimeMillis()
                        )
                    } else {
                        DiagnosticCheckResult(
                            id = "doh",
                            title = "DoH por túnel (RFC 8484)",
                            status = DiagnosticStatus.FAILED,
                            summary = "${res.errorSummary ?: "DoH no disponible"} (se usa respaldo DNS-over-TCP)",
                            timestampMillis = System.currentTimeMillis()
                        )
                    }
                } else {
                    val resp = dnsMgr.resolveViaDohDirect(query, dnsConfig.dohUrl)
                    val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    if (resp != null && resp.size >= 12) {
                        DiagnosticCheckResult(
                            id = "doh",
                            title = "DoH directo sin túnel (RFC 8484)",
                            status = DiagnosticStatus.PASSED,
                            summary = "DoH directo operativo (${resp.size} bytes)",
                            latencyMs = dt,
                            timestampMillis = System.currentTimeMillis()
                        )
                    } else {
                        DiagnosticCheckResult(
                            id = "doh",
                            title = "DoH directo sin túnel (RFC 8484)",
                            status = DiagnosticStatus.FAILED,
                            summary = "Sin salida directa hacia ${dnsConfig.dohUrl} (en red sin salida externa el DoH se enrutará por el túnel al conectar)",
                            timestampMillis = System.currentTimeMillis()
                        )
                    }
                }
            }
            updateDiagnosticItem(dohResult)

            // 3. Server Endpoint / Tunnel Flow probe
            val serverResult = withContext(Dispatchers.IO) {
                if (activeProfile == null) {
                    DiagnosticCheckResult(
                        id = "server_probe",
                        title = "Conectividad del servidor seleccionado",
                        status = DiagnosticStatus.FAILED,
                        summary = "Selecciona o crea un perfil para probar el endpoint.",
                        timestampMillis = System.currentTimeMillis()
                    )
                } else if (isConnected) {
                    val engine = activeEngine!!
                    val t0 = System.currentTimeMillis()
                    val exitIp = dnsMgr.fetchExitIpThroughTunnel(engine)
                    val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    if (exitIp != null) {
                        DiagnosticCheckResult(
                            id = "server_probe",
                            title = "Salida del túnel (${activeProfile.host}:${activeProfile.port})",
                            status = DiagnosticStatus.PASSED,
                            summary = "Flujo TCP activo por túnel · IP de salida: $exitIp",
                            latencyMs = dt,
                            timestampMillis = System.currentTimeMillis()
                        )
                    } else {
                        val fallbackTcp = dnsMgr.resolveViaTunnelTcp(
                            queryBytes = dnsMgr.buildSampleDnsQuery("example.com"),
                            dnsIp = dnsConfig.primaryDns,
                            engine = engine
                        )
                        val dt2 = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        if (fallbackTcp != null) {
                            DiagnosticCheckResult(
                                id = "server_probe",
                                title = "Salida del túnel (${activeProfile.host}:${activeProfile.port})",
                                status = DiagnosticStatus.PASSED,
                                summary = "Túnel TCP operativo (api.ipify.org no respondió, DNS TCP OK)",
                                latencyMs = dt2,
                                timestampMillis = System.currentTimeMillis()
                            )
                        } else {
                            DiagnosticCheckResult(
                                id = "server_probe",
                                title = "Salida del túnel (${activeProfile.host}:${activeProfile.port})",
                                status = DiagnosticStatus.FAILED,
                                summary = "El túnel conectado no devolvió tráfico de salida (${dt2} ms)",
                                timestampMillis = System.currentTimeMillis()
                            )
                        }
                    }
                } else {
                    val t0 = System.currentTimeMillis()
                    val useTls = activeProfile.protocol == TunnelProtocol.HTTPS_PROXY || activeProfile.tlsEnabled
                    if (activeProfile.protocol == TunnelProtocol.HTTP_PROXY || activeProfile.protocol == TunnelProtocol.HTTPS_PROXY) {
                        val secrets = profileRepo.getSecrets(activeProfile.id)
                        val probe = runCatching {
                            HttpConnectProxyEngine.probeHttpEndpoint(activeProfile, secrets, timeoutMs = 3_500)
                        }
                        val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        probe.fold(
                            onSuccess = { (latency, statusLine) ->
                                profileRepo.updateLatency(activeProfile.id, latency)
                                val modeTag = if (useTls) "TLS + HTTP" else "TCP + HTTP (sin TLS)"
                                val payloadTag = if (activeProfile.rawHttpPayload.isBlank()) "directo" else "Payload crudo"
                                DiagnosticCheckResult(
                                    id = "server_probe",
                                    title = "Endpoint ${activeProfile.host}:${activeProfile.port}",
                                    status = DiagnosticStatus.PASSED,
                                    summary = "$modeTag ($payloadTag) -> $statusLine",
                                    latencyMs = latency,
                                    timestampMillis = System.currentTimeMillis()
                                )
                            },
                            onFailure = { err ->
                                DiagnosticCheckResult(
                                    id = "server_probe",
                                    title = "Endpoint ${activeProfile.host}:${activeProfile.port}",
                                    status = DiagnosticStatus.FAILED,
                                    summary = "${err.message ?: "Error de conexión"} (${dt} ms)",
                                    timestampMillis = System.currentTimeMillis()
                                )
                            }
                        )
                    } else {
                        val latency = probeTcpHandshakeMs(activeProfile)
                        val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        if (latency != null) {
                            profileRepo.updateLatency(activeProfile.id, latency)
                            val modeTag = if (useTls) "TCP + TLS" else "TCP directo"
                            DiagnosticCheckResult(
                                id = "server_probe",
                                title = "Endpoint ${activeProfile.host}:${activeProfile.port}",
                                status = DiagnosticStatus.PASSED,
                                summary = "Handshake $modeTag exitoso (${activeProfile.protocol.badgeCode})",
                                latencyMs = latency,
                                timestampMillis = System.currentTimeMillis()
                            )
                        } else {
                            DiagnosticCheckResult(
                                id = "server_probe",
                                title = "Endpoint ${activeProfile.host}:${activeProfile.port}",
                                status = DiagnosticStatus.FAILED,
                                summary = if (useTls) {
                                    "Timeout o certificado TLS rechazado (${dt} ms)"
                                } else {
                                    "Sin respuesta TCP en ${activeProfile.host}:${activeProfile.port} (${dt} ms)"
                                },
                                timestampMillis = System.currentTimeMillis()
                            )
                        }
                    }
                }
            }
            updateDiagnosticItem(serverResult)

            // 4. Keystore AES-GCM health
            val keystoreResult = withContext(Dispatchers.IO) {
                val ok = app.cryptoManager.isKeystoreHardwareAvailable()
                DiagnosticCheckResult(
                    id = "keystore",
                    title = "Almacén criptográfico Android Keystore",
                    status = if (ok) DiagnosticStatus.PASSED else DiagnosticStatus.FAILED,
                    summary = if (ok) "Clave AES-256-GCM verificada" else "Keystore no disponible en este entorno",
                    timestampMillis = System.currentTimeMillis()
                )
            }
            updateDiagnosticItem(keystoreResult)
        }
    }

    private fun updateDiagnosticItem(item: DiagnosticCheckResult) {
        _diagnosticResults.value = _diagnosticResults.value.map {
            if (it.id == item.id) item else it
        }
    }

    fun loadInstalledLaunchableApps() {
        if (_installedApps.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val pm = app.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            val apps = resolved
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == app.packageName) return@mapNotNull null
                    val label = info.loadLabel(pm).toString()
                    InstalledAppInfo(pkg, label)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            _installedApps.value = apps
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepo.update(transform)
        }
    }

    fun clearLogs() {
        AppLogger.clear()
    }

    private fun defaultDiagnosticChecks(connected: Boolean = false): List<DiagnosticCheckResult> = listOf(
        DiagnosticCheckResult(
            "dns_udp",
            if (connected) "Resolución DNS por túnel (TCP)" else "Resolución DNS directa"
        ),
        DiagnosticCheckResult(
            "doh",
            if (connected) "DNS-over-HTTPS por túnel (RFC 8484)" else "DNS-over-HTTPS directo (RFC 8484)"
        ),
        DiagnosticCheckResult(
            "server_probe",
            if (connected) "Salida del túnel activo" else "Conectividad del servidor"
        ),
        DiagnosticCheckResult("keystore", "Android Keystore AES-256-GCM")
    )
}
