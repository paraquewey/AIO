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
            val runningChecks = defaultDiagnosticChecks().map {
                it.copy(status = DiagnosticStatus.RUNNING, summary = "Ejecutando prueba real...")
            }
            _diagnosticResults.value = runningChecks

            val dnsMgr = DnsManager(
                protectSocket = { true },
                protectDatagramSocket = { true }
            )
            val currentSettings = settings.value
            val dnsIp = activeProfile?.dnsServer?.ifBlank { currentSettings.defaultDnsServer }
                ?: currentSettings.defaultDnsServer
            val dohUrl = activeProfile?.dohUrl?.ifBlank { currentSettings.defaultDohUrl }
                ?: currentSettings.defaultDohUrl

            // 1. Plain DNS UDP check
            val dnsUdpResult = withContext(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val query = dnsMgr.buildSampleDnsQuery("example.com")
                val resp = dnsMgr.resolveViaProtectedUdp(query, dnsIp)
                val dt = System.currentTimeMillis() - t0
                if (resp != null && resp.size > 12) {
                    DiagnosticCheckResult(
                        id = "dns_udp",
                        title = "Resolución DNS UDP ($dnsIp:53)",
                        status = DiagnosticStatus.PASSED,
                        summary = "Respuesta válida (${resp.size} bytes)",
                        latencyMs = dt,
                        timestampMillis = System.currentTimeMillis()
                    )
                } else {
                    DiagnosticCheckResult(
                        id = "dns_udp",
                        title = "Resolución DNS UDP ($dnsIp:53)",
                        status = DiagnosticStatus.FAILED,
                        summary = "Sin respuesta UDP desde $dnsIp:53",
                        timestampMillis = System.currentTimeMillis()
                    )
                }
            }
            updateDiagnosticItem(dnsUdpResult)

            // 2. DoH RFC 8484 check
            val dohResult = withContext(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val query = dnsMgr.buildSampleDnsQuery("cloudflare.com")
                val resp = dnsMgr.resolveViaDoh(query, dohUrl)
                val dt = System.currentTimeMillis() - t0
                if (resp != null && resp.size > 12) {
                    DiagnosticCheckResult(
                        id = "doh",
                        title = "DNS-over-HTTPS (RFC 8484)",
                        status = DiagnosticStatus.PASSED,
                        summary = "DoH operativo (${resp.size} bytes)",
                        latencyMs = dt,
                        timestampMillis = System.currentTimeMillis()
                    )
                } else {
                    DiagnosticCheckResult(
                        id = "doh",
                        title = "DNS-over-HTTPS (RFC 8484)",
                        status = DiagnosticStatus.FAILED,
                        summary = "No se pudo consultar $dohUrl",
                        timestampMillis = System.currentTimeMillis()
                    )
                }
            }
            updateDiagnosticItem(dohResult)

            // 3. Server TCP/TLS probe
            val serverResult = withContext(Dispatchers.IO) {
                if (activeProfile == null) {
                    DiagnosticCheckResult(
                        id = "server_probe",
                        title = "Conectividad del servidor seleccionado",
                        status = DiagnosticStatus.FAILED,
                        summary = "Selecciona o crea un perfil para probar el endpoint.",
                        timestampMillis = System.currentTimeMillis()
                    )
                } else {
                    val t0 = System.currentTimeMillis()
                    val latency = probeTcpHandshakeMs(activeProfile)
                    val dt = System.currentTimeMillis() - t0
                    if (latency != null) {
                        profileRepo.updateLatency(activeProfile.id, latency)
                        DiagnosticCheckResult(
                            id = "server_probe",
                            title = "Endpoint ${activeProfile.host}:${activeProfile.port}",
                            status = DiagnosticStatus.PASSED,
                            summary = "Handshake TCP/TLS exitoso (${activeProfile.protocol.badgeCode})",
                            latencyMs = latency,
                            timestampMillis = System.currentTimeMillis()
                        )
                    } else {
                        DiagnosticCheckResult(
                            id = "server_probe",
                            title = "Endpoint ${activeProfile.host}:${activeProfile.port}",
                            status = DiagnosticStatus.FAILED,
                            summary = "Timeout o certificado TLS rechazado (${dt} ms)",
                            timestampMillis = System.currentTimeMillis()
                        )
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

    private fun defaultDiagnosticChecks(): List<DiagnosticCheckResult> = listOf(
        DiagnosticCheckResult("dns_udp", "Resolución DNS UDP"),
        DiagnosticCheckResult("doh", "DNS-over-HTTPS (RFC 8484)"),
        DiagnosticCheckResult("server_probe", "Conectividad del servidor"),
        DiagnosticCheckResult("keystore", "Android Keystore AES-256-GCM")
    )
}
