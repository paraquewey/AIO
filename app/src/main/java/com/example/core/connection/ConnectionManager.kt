package com.example.core.connection

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.example.core.logger.AppLogger
import com.example.domain.models.ConnectionState
import com.example.domain.models.SshHostKeyChallenge
import com.example.engines.adapter.TunnelEngine
import com.example.vpn.service.AioVpnService
import com.example.vpn.tun.DnsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnectionManager(private val appContext: Context) {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pendingHostKeyChallenge = MutableStateFlow<SshHostKeyChallenge?>(null)
    val pendingHostKeyChallenge: StateFlow<SshHostKeyChallenge?> = _pendingHostKeyChallenge.asStateFlow()

    @Volatile
    var activeEngine: TunnelEngine? = null
        private set

    @Volatile
    var activeDnsConfig: DnsManager.ResolvedDnsConfig? = null
        private set

    private var hostKeyDecisionDeferred: CompletableDeferred<Boolean>? = null

    fun setActiveTunnel(engine: TunnelEngine?, dnsConfig: DnsManager.ResolvedDnsConfig?) {
        activeEngine = engine
        activeDnsConfig = dnsConfig
    }

    fun clearActiveTunnel() {
        activeEngine = null
        activeDnsConfig = null
    }

    fun setState(state: ConnectionState) {
        _connectionState.value = state
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
        AppLogger.info(TAG, "Solicitando inicio de AioVpnService para perfil $profileId")
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
        AppLogger.info(TAG, "Solicitando desconexión de AioVpnService")
        val intent = Intent(appContext, AioVpnService::class.java).apply {
            action = AioVpnService.ACTION_DISCONNECT
        }
        appContext.startService(intent)
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
    }
}
