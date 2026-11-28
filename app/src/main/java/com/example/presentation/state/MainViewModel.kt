package com.example.presentation.state

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AioTunnelApplication
import com.example.ui.theme.computeBitmapAverageLuminance
import com.example.ui.theme.decodeSampledBackgroundBitmap
import com.example.core.logger.AppLogger
import com.example.data.import_export.ConfigParserAndExporter
import com.example.data.security.SshHostKeyStore
import com.example.domain.models.AppSettings
import com.example.domain.models.ConnectionState
import com.example.domain.models.DiagnosticCheckResult
import com.example.domain.models.DiagnosticStatus
import com.example.domain.models.DnsLeakReport
import com.example.domain.models.LatencyStabilityState
import com.example.domain.models.LogEntry
import com.example.domain.models.PortProbeEntry
import com.example.domain.models.PortScannerState
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.SpeedSample
import com.example.domain.models.SpeedTestState
import com.example.domain.models.SshHostKeyChallenge
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import com.example.domain.models.TunnelStatistics
import com.example.engines.http.HttpConnectProxyEngine
import com.example.vpn.tun.DnsManager
import com.example.vpn.tun.TunnelSocketBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs

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
    val wifiProxyActivePort: StateFlow<Int?> = connectionManager.wifiProxyActivePort
    val logs: StateFlow<List<LogEntry>> = AppLogger.logs

    private val _diagnosticResults = MutableStateFlow(defaultDiagnosticChecks())
    val diagnosticResults: StateFlow<List<DiagnosticCheckResult>> = _diagnosticResults.asStateFlow()

    private val _knownHostKeys = MutableStateFlow<List<SshHostKeyStore.KnownHostRecord>>(emptyList())
    val knownHostKeys: StateFlow<List<SshHostKeyStore.KnownHostRecord>> = _knownHostKeys.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _isMeasuringLatency = MutableStateFlow(false)
    val isMeasuringLatency: StateFlow<Boolean> = _isMeasuringLatency.asStateFlow()

    private val _isRefreshingExitIp = MutableStateFlow(false)
    val isRefreshingExitIp: StateFlow<Boolean> = _isRefreshingExitIp.asStateFlow()

    private val _speedTestState = MutableStateFlow(SpeedTestState())
    val speedTestState: StateFlow<SpeedTestState> = _speedTestState.asStateFlow()

    private val _stabilityState = MutableStateFlow(LatencyStabilityState())
    val stabilityState: StateFlow<LatencyStabilityState> = _stabilityState.asStateFlow()

    private val _portScannerState = MutableStateFlow(PortScannerState())
    val portScannerState: StateFlow<PortScannerState> = _portScannerState.asStateFlow()

    private val _dnsLeakReport = MutableStateFlow(DnsLeakReport())
    val dnsLeakReport: StateFlow<DnsLeakReport> = _dnsLeakReport.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val diagnosticsDnsManager = DnsManager(
        protectSocket = { true },
        protectDatagramSocket = { true }
    )

    override fun onCleared() {
        runCatching { diagnosticsDnsManager.close() }
        super.onCleared()
    }

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

    fun refreshExitIp() {
        if (_isRefreshingExitIp.value) return
        val engine = connectionManager.activeEngine
        val currentState = connectionState.value as? ConnectionState.Connected
        if (engine == null || currentState == null) {
            _userMessage.value = "Conecta el túnel primero para consultar tu IP de salida."
            return
        }
        viewModelScope.launch {
            _isRefreshingExitIp.value = true
            try {
                val t0 = System.currentTimeMillis()
                val exitInfo = withContext(Dispatchers.IO) {
                    diagnosticsDnsManager.fetchExitIpInfoThroughTunnel(engine)
                }
                val measuredLatency = if (exitInfo != null) {
                    (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                } else {
                    currentState.latencyMs
                }
                val latest = connectionState.value
                if (latest is ConnectionState.Connected && latest.profileId == currentState.profileId && exitInfo != null) {
                    connectionManager.setState(
                        latest.copy(
                            exitIp = exitInfo.ip,
                            exitCountry = exitInfo.country,
                            exitCountryCode = exitInfo.countryCode,
                            exitCity = exitInfo.city,
                            latencyMs = measuredLatency
                        )
                    )
                    _userMessage.value = "IP de salida actualizada: ${exitInfo.ip}"
                } else if (exitInfo == null) {
                    _userMessage.value = "No se pudo consultar la IP de salida en este momento."
                }
            } finally {
                _isRefreshingExitIp.value = false
            }
        }
    }

    fun runSpeedAndBurstTest() {
        if (_speedTestState.value.isRunning) return
        viewModelScope.launch {
            val activeEngine = connectionManager.activeEngine
            val isConnected = connectionState.value is ConnectionState.Connected && activeEngine != null
            _speedTestState.value = SpeedTestState(
                isRunning = true,
                phaseLabel = if (isConnected) "Midiendo descarga vía túnel..." else "Midiendo descarga directa...",
                usedTunnel = isConnected
            )

            withContext(Dispatchers.IO) {
                var bridge: TunnelSocketBridge? = null
                try {
                    val client = if (isConnected && activeEngine != null) {
                        val b = TunnelSocketBridge(activeEngine)
                        bridge = b
                        OkHttpClient.Builder()
                            .socketFactory(b.createSocketFactory())
                            .protocols(listOf(Protocol.HTTP_1_1))
                            .connectTimeout(6_000L, TimeUnit.MILLISECONDS)
                            .readTimeout(9_000L, TimeUnit.MILLISECONDS)
                            .writeTimeout(9_000L, TimeUnit.MILLISECONDS)
                            .build()
                    } else {
                        OkHttpClient.Builder()
                            .protocols(listOf(Protocol.HTTP_1_1))
                            .connectTimeout(6_000L, TimeUnit.MILLISECONDS)
                            .readTimeout(9_000L, TimeUnit.MILLISECONDS)
                            .writeTimeout(9_000L, TimeUnit.MILLISECONDS)
                            .build()
                    }

                    val samples = mutableListOf<SpeedSample>()
                    val startNs = System.nanoTime()
                    var peakMbps = 0f
                    var timeToPeakSec: Float? = null
                    var totalDownloaded = 0L

                    val dlUrls = listOf(
                        "https://speed.cloudflare.com/__down?bytes=5000000",
                        "http://speed.cloudflare.com/__down?bytes=5000000"
                    )
                    var downloadSucceeded = false
                    for (url in dlUrls) {
                        val ok = runCatching {
                            val req = Request.Builder()
                                .url(url)
                                .header("User-Agent", "AIOTunnelVPN/2.0")
                                .build()
                            client.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) return@use false
                                val stream = resp.body?.byteStream() ?: return@use false
                                val buf = ByteArray(16_384)
                                var windowBytes = 0L
                                var windowStartNs = System.nanoTime()
                                while (true) {
                                    val nowNs = System.nanoTime()
                                    val totalElapsedSec = (nowNs - startNs) / 1_000_000_000f
                                    if (totalElapsedSec >= 7.0f) break
                                    val n = stream.read(buf)
                                    if (n == -1) break
                                    if (n > 0) {
                                        totalDownloaded += n
                                        windowBytes += n
                                        val dtWindow = (nowNs - windowStartNs) / 1_000_000_000f
                                        if (dtWindow >= 0.40f) {
                                            val instMbps = (windowBytes * 8f) / (dtWindow * 1_000_000f)
                                            if (instMbps > peakMbps) {
                                                peakMbps = instMbps
                                                timeToPeakSec = totalElapsedSec
                                            }
                                            samples.add(SpeedSample(elapsedSec = totalElapsedSec, downloadMbps = instMbps))
                                            _speedTestState.value = _speedTestState.value.copy(
                                                downloadMbps = instMbps,
                                                peakDownloadMbps = peakMbps,
                                                timeToPeakSec = timeToPeakSec,
                                                samples = samples.toList()
                                            )
                                            windowBytes = 0L
                                            windowStartNs = nowNs
                                        }
                                    }
                                }
                                true
                            }
                        }.getOrDefault(false)
                        if (ok && totalDownloaded > 0L) {
                            downloadSucceeded = true
                            break
                        }
                    }

                    val totalDlSec = ((System.nanoTime() - startNs) / 1_000_000_000f).coerceAtLeast(0.1f)
                    val avgDlMbps = if (downloadSucceeded) (totalDownloaded * 8f) / (totalDlSec * 1_000_000f) else null
                    if (avgDlMbps != null && samples.isEmpty()) {
                        samples.add(SpeedSample(totalDlSec, avgDlMbps))
                        peakMbps = maxOf(peakMbps, avgDlMbps)
                        timeToPeakSec = timeToPeakSec ?: totalDlSec
                    }

                    _speedTestState.value = _speedTestState.value.copy(
                        phaseLabel = "Midiendo velocidad de subida...",
                        downloadMbps = avgDlMbps,
                        peakDownloadMbps = peakMbps.takeIf { it > 0f },
                        timeToPeakSec = timeToPeakSec,
                        samples = samples.toList()
                    )

                    // Upload phase (512 KB HTTP POST burst)
                    val uploadPayload = ByteArray(512 * 1024)
                    val ulStartNs = System.nanoTime()
                    val uploadSucceeded = runCatching {
                        val req = Request.Builder()
                            .url("https://speed.cloudflare.com/__up")
                            .post(uploadPayload.toRequestBody("application/octet-stream".toMediaType()))
                            .build()
                        client.newCall(req).execute().use { it.isSuccessful }
                    }.getOrDefault(false)
                    val ulElapsedSec = ((System.nanoTime() - ulStartNs) / 1_000_000_000f).coerceAtLeast(0.05f)
                    val ulMbps = if (uploadSucceeded) (uploadPayload.size * 8f) / (ulElapsedSec * 1_000_000f) else null

                    if (samples.isNotEmpty() && ulMbps != null) {
                        val last = samples.last()
                        samples[samples.lastIndex] = last.copy(uploadMbps = ulMbps)
                    }

                    _speedTestState.value = _speedTestState.value.copy(
                        isRunning = false,
                        phaseLabel = if (downloadSucceeded) "Test completado" else "Sin respuesta del servidor de prueba",
                        downloadMbps = avgDlMbps,
                        uploadMbps = ulMbps,
                        peakDownloadMbps = peakMbps.takeIf { it > 0f },
                        timeToPeakSec = timeToPeakSec,
                        samples = samples.toList(),
                        errorMessage = if (!downloadSucceeded) "No se pudo completar la transferencia de prueba." else null
                    )
                } catch (err: Throwable) {
                    _speedTestState.value = _speedTestState.value.copy(
                        isRunning = false,
                        phaseLabel = "Error en test de velocidad",
                        errorMessage = err.message ?: "Error de red"
                    )
                } finally {
                    runCatching { bridge?.close() }
                }
            }
        }
    }

    fun runStabilityAndJitterTest(activeProfile: TunnelProfile?) {
        if (_stabilityState.value.isRunning) return
        viewModelScope.launch {
            val activeEngine = connectionManager.activeEngine
            val isConnected = connectionState.value is ConnectionState.Connected && activeEngine != null
            _stabilityState.value = LatencyStabilityState(
                isRunning = true,
                usedTunnel = isConnected
            )

            withContext(Dispatchers.IO) {
                val results = mutableListOf<Long?>()
                val totalPings = 20
                for (i in 0 until totalPings) {
                    val sampleMs: Long? = if (isConnected && activeEngine != null) {
                        diagnosticsDnsManager.probeTunnelExitTtfb(activeEngine)?.ttfbMs
                            ?: runCatching {
                                val t0 = System.nanoTime()
                                activeEngine.openTcpTunnel("1.1.1.1", 443).use { }
                                ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                            }.getOrNull()
                    } else {
                        val host = activeProfile?.host?.takeIf { it.isNotBlank() } ?: "1.1.1.1"
                        val port = activeProfile?.port?.takeIf { it in 1..65535 } ?: 443
                        runCatching {
                            val t0 = System.nanoTime()
                            Socket().use { sock ->
                                sock.tcpNoDelay = true
                                sock.connect(InetSocketAddress(host, port), 2_500)
                            }
                            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                        }.getOrNull()
                    }
                    results.add(sampleMs)

                    val valid = results.filterNotNull()
                    val minMs = valid.minOrNull()
                    val maxMs = valid.maxOrNull()
                    val avgMs = if (valid.isNotEmpty()) valid.sum() / valid.size else null
                    val jitterMs = computeJitterMs(valid)
                    val lossPct = ((results.count { it == null }.toFloat() / results.size.toFloat()) * 100f)

                    _stabilityState.value = LatencyStabilityState(
                        isRunning = i < totalPings - 1,
                        pingSamplesMs = results.toList(),
                        minMs = minMs,
                        avgMs = avgMs,
                        maxMs = maxMs,
                        jitterMs = jitterMs,
                        packetLossPercent = lossPct,
                        qualityGrade = if (i == totalPings - 1) classifyQuality(avgMs, jitterMs, lossPct) else null,
                        usedTunnel = isConnected
                    )
                }
            }
        }
    }

    fun runBugHostPortScan(rawHost: String) {
        if (_portScannerState.value.isRunning) return
        val targetHost = rawHost.trim().removePrefix("https://").removePrefix("http://").substringBefore("/").substringBefore(":")
        if (targetHost.isBlank() || targetHost.any { it.isWhitespace() }) {
            _portScannerState.value = PortScannerState(
                isRunning = false,
                targetHost = rawHost,
                errorMessage = "Ingresa un dominio o IP válido para escanear."
            )
            return
        }

        viewModelScope.launch {
            _portScannerState.value = PortScannerState(
                isRunning = true,
                targetHost = targetHost
            )
            val ports = listOf(80, 443, 8080, 8443, 2052, 2082)
            val results = withContext(Dispatchers.IO) {
                ports.map { port ->
                    async { probeSingleBugHostPort(targetHost, port) }
                }.awaitAll().sortedBy { ports.indexOf(it.port) }
            }
            _portScannerState.value = PortScannerState(
                isRunning = false,
                targetHost = targetHost,
                results = results
            )
        }
    }

    private fun probeSingleBugHostPort(host: String, port: Int): PortProbeEntry {
        val isTlsPort = port == 443 || port == 8443
        val t0 = System.nanoTime()
        return runCatching {
            Socket().use { rawSocket ->
                rawSocket.tcpNoDelay = true
                rawSocket.soTimeout = 2_500
                rawSocket.connect(InetSocketAddress(host, port), 2_500)

                val ioSocket: Socket = if (isTlsPort) {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(rawSocket, host, port, true) as SSLSocket
                    runCatching {
                        val params = ssl.sslParameters
                        params.serverNames = listOf(SNIHostName(host))
                        ssl.sslParameters = params
                    }
                    ssl.soTimeout = 2_500
                    ssl.startHandshake()
                    ssl
                } else {
                    rawSocket
                }

                val out = ioSocket.getOutputStream()
                val req = "HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: AIOTunnelVPN/2.0\r\nConnection: close\r\n\r\n"
                out.write(req.toByteArray(StandardCharsets.US_ASCII))
                out.flush()

                val reader = BufferedReader(InputStreamReader(ioSocket.getInputStream(), StandardCharsets.ISO_8859_1))
                val statusLine = reader.readLine().orEmpty()
                val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                val dt = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                if (isTlsPort) runCatching { ioSocket.close() }

                PortProbeEntry(
                    port = port,
                    isOpen = true,
                    httpCode = code,
                    tlsOk = if (isTlsPort) true else null,
                    latencyMs = dt,
                    note = statusLine.ifBlank { "Puerto TCP abierto" }
                )
            }
        }.getOrElse { err ->
            PortProbeEntry(
                port = port,
                isOpen = false,
                tlsOk = if (isTlsPort) false else null,
                note = err.message?.take(48) ?: "Cerrado / Timeout"
            )
        }
    }

    fun runDnsLeakAndIpAudit(activeProfile: TunnelProfile?) {
        if (_dnsLeakReport.value.isRunning) return
        viewModelScope.launch {
            val activeEngine = connectionManager.activeEngine
            val isConnected = connectionState.value is ConnectionState.Connected && activeEngine != null
            val dnsConfig = connectionManager.activeDnsConfig
                ?: DnsManager.resolveActiveDnsConfig(activeProfile, settings.value)

            _dnsLeakReport.value = DnsLeakReport(
                isRunning = true,
                activeModeLabel = dnsConfig.dnsMode.name,
                primaryResolver = dnsConfig.primaryDns,
                secondaryResolver = dnsConfig.secondaryDns,
                usedTunnel = isConnected
            )

            withContext(Dispatchers.IO) {
                val query = diagnosticsDnsManager.buildSampleDnsQuery("cloudflare.com")
                val (dohStatus, leakStatus, exitIpInfo) = if (isConnected && activeEngine != null) {
                    val dohRes = diagnosticsDnsManager.resolveViaTunnelDohWithDetails(
                        queryBytes = query,
                        dnsConfig = dnsConfig,
                        engine = activeEngine,
                        bypassCache = true
                    )
                    val tcpRes = diagnosticsDnsManager.resolveViaTunnelTcpWithFallback(
                        queryBytes = query,
                        primaryDns = dnsConfig.primaryDns,
                        secondaryDns = dnsConfig.secondaryDns,
                        engine = activeEngine,
                        bypassCache = true
                    )
                    val exitInfo = diagnosticsDnsManager.fetchExitIpInfoThroughTunnel(activeEngine)
                    val dohText = if (dohRes.responseBytes != null) {
                        "DoH activo (${dohRes.serverLabel})"
                    } else {
                        "DoH fallback -> ${tcpRes.serverLabel}"
                    }
                    val leakText = if (dohRes.responseBytes != null || tcpRes.responseBytes != null) {
                        "Sin fugas: DNS resuelto dentro del túnel VPN"
                    } else {
                        "Advertencia: no hubo respuesta DNS dentro del túnel"
                    }
                    Triple(dohText, leakText, exitInfo)
                } else {
                    val directDoh = diagnosticsDnsManager.resolveViaDohDirect(query, dnsConfig.dohUrl)
                    val directExit = fetchDirectExitIpInfo()
                    val dohText = if (directDoh != null) {
                        "DoH directo disponible (${dnsConfig.dohUrl})"
                    } else {
                        "DoH directo inaccesible sin túnel"
                    }
                    Triple(dohText, "Túnel desconectado (auditoría de red directa)", directExit)
                }

                _dnsLeakReport.value = DnsLeakReport(
                    isRunning = false,
                    activeModeLabel = when (dnsConfig.dnsMode) {
                        com.example.domain.models.DnsMode.DNS_OVER_HTTPS -> "DNS-over-HTTPS (RFC 8484)"
                        com.example.domain.models.DnsMode.PLAIN_DNS -> "DNS-over-TCP en túnel"
                        com.example.domain.models.DnsMode.SYSTEM_DEFAULT -> "Interceptación TUN activa"
                    },
                    primaryResolver = dnsConfig.primaryDns,
                    secondaryResolver = dnsConfig.secondaryDns,
                    dohStatus = dohStatus,
                    leakStatus = leakStatus,
                    exitIpInfo = exitIpInfo,
                    usedTunnel = isConnected
                )
            }
        }
    }

    private fun fetchDirectExitIpInfo(): DnsManager.ExitIpInfo? = runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(4_000L, TimeUnit.MILLISECONDS)
            .readTimeout(4_000L, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder()
            .url("http://ip-api.com/json/?fields=status,country,countryCode,city,isp,as,query")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            DnsManager.parseIpApiJson(body)
        }
    }.getOrNull()

    private fun computeJitterMs(samples: List<Long>): Long? {
        if (samples.size < 2) return if (samples.size == 1) 0L else null
        var sumDiff = 0L
        for (i in 1 until samples.size) {
            sumDiff += abs(samples[i] - samples[i - 1])
        }
        return sumDiff / (samples.size - 1)
    }

    private fun classifyQuality(avgMs: Long?, jitterMs: Long?, lossPct: Float): String = when {
        avgMs == null || lossPct > 15f -> "Inestable"
        lossPct == 0f && avgMs < 130L && (jitterMs ?: 0L) < 35L -> "Excelente"
        lossPct <= 10f && avgMs < 260L -> "Estable"
        else -> "Inestable"
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

            val dnsMgr = diagnosticsDnsManager

            // 1. DNS Resolution check (In-tunnel TCP when connected vs direct UDP when disconnected)
            val dnsResult = withContext(Dispatchers.IO) {
                val t0 = System.nanoTime()
                val query = dnsMgr.buildSampleDnsQuery("example.com")
                if (isConnected) {
                    val res = dnsMgr.resolveViaTunnelTcpWithFallback(
                        queryBytes = query,
                        primaryDns = dnsConfig.primaryDns,
                        secondaryDns = dnsConfig.secondaryDns,
                        engine = activeEngine,
                        bypassCache = true
                    )
                    val dt = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
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
                    val dt = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
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
                val t0 = System.nanoTime()
                val query = dnsMgr.buildSampleDnsQuery("cloudflare.com")
                if (isConnected) {
                    val res = dnsMgr.resolveViaTunnelDohWithDetails(
                        queryBytes = query,
                        dnsConfig = dnsConfig,
                        engine = activeEngine,
                        bypassCache = true
                    )
                    val dt = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
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
                    val dt = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
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
                    val knownExitIp = (connectionState.value as? ConnectionState.Connected)?.exitIp
                    val ttfbProbe = dnsMgr.probeTunnelExitTtfb(activeEngine, cachedExitIp = knownExitIp)
                    if (ttfbProbe != null) {
                        val resolvedExitIp = knownExitIp ?: dnsMgr.fetchExitIpThroughTunnel(activeEngine)
                        val ipText = resolvedExitIp?.let { " · IP de salida: $it" }.orEmpty()
                        DiagnosticCheckResult(
                            id = "server_probe",
                            title = "Salida del túnel (${activeProfile.host}:${activeProfile.port})",
                            status = DiagnosticStatus.PASSED,
                            summary = "TTFB HTTP ${ttfbProbe.statusCode} sin descarga de cuerpo$ipText",
                            latencyMs = ttfbProbe.ttfbMs,
                            timestampMillis = System.currentTimeMillis()
                        )
                    } else {
                        val t0 = System.nanoTime()
                        val fallbackTcp = dnsMgr.resolveViaTunnelTcp(
                            queryBytes = dnsMgr.buildSampleDnsQuery("example.com"),
                            dnsIp = dnsConfig.primaryDns,
                            engine = activeEngine
                        )
                        val dt2 = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                        if (fallbackTcp != null) {
                            DiagnosticCheckResult(
                                id = "server_probe",
                                title = "Salida del túnel (${activeProfile.host}:${activeProfile.port})",
                                status = DiagnosticStatus.PASSED,
                                summary = "Túnel TCP operativo (endpoint 204 no respondió, DNS TCP OK)",
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

    fun importCustomBackground(uri: Uri) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val destFile = File(app.filesDir, "custom_bg.jpg")
                    val tempFile = File(app.filesDir, "custom_bg.tmp")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: error("No se pudo abrir la imagen seleccionada.")

                    if (!tempFile.exists() || tempFile.length() <= 0L) {
                        tempFile.delete()
                        error("La imagen seleccionada está vacía.")
                    }

                    val bitmap = decodeSampledBackgroundBitmap(tempFile.absolutePath, maxDimensionPx = 1080)
                    if (bitmap == null) {
                        tempFile.delete()
                        error("Formato de imagen no compatible.")
                    }
                    val avgLum = try {
                        computeBitmapAverageLuminance(bitmap)
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                    if (destFile.exists()) destFile.delete()
                    if (!tempFile.renameTo(destFile)) {
                        tempFile.copyTo(destFile, overwrite = true)
                        tempFile.delete()
                    }
                    destFile.absolutePath to avgLum
                }
            }

            imported.onSuccess { (path, luminance) ->
                val version = System.currentTimeMillis()
                settingsRepo.update {
                    it.copy(
                        customBackgroundPath = path,
                        customBackgroundLuminance = luminance,
                        customBackgroundVersion = version
                    )
                }
                _userMessage.value = "Fondo personalizado aplicado correctamente."
            }.onFailure { err ->
                _userMessage.value = err.message ?: "No se pudo cargar la imagen de galería."
            }
        }
    }

    fun clearCustomBackground() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { File(app.filesDir, "custom_bg.jpg").delete() }
            }
            settingsRepo.update {
                it.copy(
                    customBackgroundPath = null,
                    customBackgroundLuminance = 0.04f,
                    customBackgroundVersion = 0L
                )
            }
            _userMessage.value = "Fondo espacial restaurado."
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
