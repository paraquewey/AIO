package com.example.core.connection

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.example.core.logger.AppLogger
import com.example.domain.models.ConnectionState
import com.example.domain.models.SshHostKeyChallenge
import com.example.domain.repositories.SettingsRepository
import com.example.engines.adapter.TunnelEngine
import com.example.vpn.service.AioVpnService
import com.example.vpn.tun.DnsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ConnectionManager(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pendingHostKeyChallenge = MutableStateFlow<SshHostKeyChallenge?>(null)
    val pendingHostKeyChallenge: StateFlow<SshHostKeyChallenge?> = _pendingHostKeyChallenge.asStateFlow()

    private val _wifiProxyActivePort = MutableStateFlow<Int?>(null)
    val wifiProxyActivePort: StateFlow<Int?> = _wifiProxyActivePort.asStateFlow()

    @Volatile
    var activeEngine: TunnelEngine? = null
        private set

    @Volatile
    var activeDnsConfig: DnsManager.ResolvedDnsConfig? = null
        private set

    @Volatile
    var userRequestedStop: Boolean = false
        private set

    private var retryAttempt: Int = 0
    private var reconnectJob: Job? = null
    private var hostKeyDecisionDeferred: CompletableDeferred<Boolean>? = null

    fun setActiveTunnel(engine: TunnelEngine?, dnsConfig: DnsManager.ResolvedDnsConfig?) {
        activeEngine = engine
        activeDnsConfig = dnsConfig
    }

    fun clearActiveTunnel() {
        activeEngine = null
        activeDnsConfig = null
        _wifiProxyActivePort.value = null
    }

    fun setWifiProxyActivePort(port: Int?) {
        _wifiProxyActivePort.value = port
    }

    fun setState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun resetRetryCounter() {
        retryAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
    }

    fun reportError(message: String) {
        _connectionState.value = ConnectionState.Error(message, recoverable = true)
    }

    fun clearError() {
        if (_connectionState.value is ConnectionState.Error) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun prepareVpnIntent(): Intent? = VpnService.prepare(appContext)

    fun startVpn(profileId: String) {
        userRequestedStop = false
        retryAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        AppLogger.info(TAG, "Solicitando inicio de AioVpnService para perfil $profileId")
        launchVpnServiceConnect(profileId)
    }

    private fun launchVpnServiceConnect(profileId: String) {
        val intent = Intent(appContext, AioVpnService::class.java).apply {
            action = AioVpnService.ACTION_CONNECT
            putExtra(AioVpnService.EXTRA_PROFILE_ID, profileId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    fun stopVpn() {
        userRequestedStop = true
        retryAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        AppLogger.info(TAG, "Solicitando desconexión de AioVpnService")
        val intent = Intent(appContext, AioVpnService::class.java).apply {
            action = AioVpnService.ACTION_DISCONNECT
        }
        appContext.startService(intent)
    }

    fun markUserStopped() {
        userRequestedStop = true
        retryAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
    }

    suspend fun handleUnexpectedDisconnectOrFailure(
        profileId: String?,
        profileName: String,
        reason: String
    ) {
        if (userRequestedStop) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }
        val keepTrying = runCatching {
            settingsRepository?.settings?.first()?.keepTryingToConnect
        }.getOrNull() ?: true

        if (!keepTrying || profileId == null) {
            retryAttempt = 0
            _connectionState.value = ConnectionState.Error(reason, recoverable = true)
            return
        }

        retryAttempt++
        val attemptNow = retryAttempt
        _connectionState.value = ConnectionState.Reconnecting(
            profileName = profileName,
            attempt = attemptNow,
            reason = reason
        )
        AppLogger.warn(TAG, "Reintento automático #$attemptNow en 2.5 s ($reason)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(AUTO_RETRY_DELAY_MS)
            if (!userRequestedStop) {
                launchVpnServiceConnect(profileId)
            }
        }
    }

    suspend fun requestSshHostKeyConfirmation(challenge: SshHostKeyChallenge): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        hostKeyDecisionDeferred = deferred
        _pendingHostKeyChallenge.value = challenge
        return try {
            deferred.await()
        } finally {
            _pendingHostKeyChallenge.value = null
            hostKeyDecisionDeferred = null
        }
    }

    fun resolveHostKeyChallenge(trust: Boolean) {
        hostKeyDecisionDeferred?.complete(trust)
    }

    companion object {
        private const val TAG = "ConnectionManager"
        private const val AUTO_RETRY_DELAY_MS = 2_500L
    }
}
