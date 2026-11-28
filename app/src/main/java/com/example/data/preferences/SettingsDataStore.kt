package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.models.AppLanguage
import com.example.domain.models.AppSettings
import com.example.domain.models.DnsMode
import com.example.domain.models.NetworkPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aioSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aio_tunnel_settings"
)

class SettingsDataStore(private val context: Context) {
    private object Keys {
        val SELECTED_PROFILE_ID = stringPreferencesKey("selected_profile_id")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val KEEP_TRYING_TO_CONNECT = booleanPreferencesKey("keep_trying_to_connect")
        val CONNECTION_TIMEOUT_SEC = intPreferencesKey("connection_timeout_sec")
        val WIFI_PROXY_SHARE_ENABLED = booleanPreferencesKey("wifi_proxy_share_enabled")
        val WIFI_PROXY_SHARE_PORT = intPreferencesKey("wifi_proxy_share_port")
        val FAILOVER = booleanPreferencesKey("failover_next_server")
        val DNS_MODE = stringPreferencesKey("dns_mode")
        val DNS_SERVER = stringPreferencesKey("dns_server")
        val DNS_SERVER_SECONDARY = stringPreferencesKey("dns_server_secondary")
        val DOH_URL = stringPreferencesKey("doh_url")
        val IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val SHOW_SPEED_NOTIFICATION = booleanPreferencesKey("show_speed_notification")
        val USE_LIGHT_THEME = booleanPreferencesKey("use_light_theme")
        val LANGUAGE = stringPreferencesKey("language")
        val STRICT_TLS = booleanPreferencesKey("strict_tls")
        val VERBOSE_LOGS = booleanPreferencesKey("verbose_logs")
        val WAKE_LOCK_ENABLED = booleanPreferencesKey("wake_lock_enabled")

        // Advanced Engine & Performance Tuning
        val NETWORK_PRESET = stringPreferencesKey("network_preset")
        val TRANSFER_BUFFER_KB = intPreferencesKey("transfer_buffer_kb")
        val IMMEDIATE_FLUSH = booleanPreferencesKey("immediate_flush")
        val SSH_WINDOW_KB = intPreferencesKey("ssh_window_kb")
        val SSH_KEEP_ALIVE_SEC = intPreferencesKey("ssh_keep_alive_sec")
        val MAX_CONCURRENT_SSH_CHANNELS = intPreferencesKey("max_concurrent_ssh_channels")
        val TCP_NO_DELAY = booleanPreferencesKey("tcp_no_delay")
        val SSH_COMPRESSION = booleanPreferencesKey("ssh_compression")
        val STRICT_KILL_SWITCH = booleanPreferencesKey("strict_kill_switch")
    }

    private fun decodeSettings(prefs: Preferences): AppSettings = AppSettings(
        selectedProfileId = prefs[Keys.SELECTED_PROFILE_ID],
        autoReconnectOnNetworkChange = prefs[Keys.AUTO_RECONNECT] ?: true,
        keepTryingToConnect = prefs[Keys.KEEP_TRYING_TO_CONNECT] ?: true,
        connectionTimeoutSeconds = (prefs[Keys.CONNECTION_TIMEOUT_SEC] ?: 10).coerceIn(1, 999),
        wifiProxyShareEnabled = prefs[Keys.WIFI_PROXY_SHARE_ENABLED] ?: false,
        wifiProxySharePort = (prefs[Keys.WIFI_PROXY_SHARE_PORT] ?: 8080).coerceIn(1024, 65535),
        failoverToNextServer = prefs[Keys.FAILOVER] ?: false,
        defaultDnsMode = prefs[Keys.DNS_MODE]?.let {
            runCatching { DnsMode.valueOf(it) }.getOrNull()
        } ?: DnsMode.DNS_OVER_HTTPS,
        defaultDnsServer = prefs[Keys.DNS_SERVER] ?: "1.1.1.1",
        defaultDnsServerSecondary = prefs[Keys.DNS_SERVER_SECONDARY] ?: "1.0.0.1",
        defaultDohUrl = prefs[Keys.DOH_URL] ?: "https://cloudflare-dns.com/dns-query",
        ipv6Enabled = prefs[Keys.IPV6_ENABLED] ?: false,
        bypassLan = prefs[Keys.BYPASS_LAN] ?: true,
        showSpeedInNotification = prefs[Keys.SHOW_SPEED_NOTIFICATION] ?: true,
        useLightTheme = prefs[Keys.USE_LIGHT_THEME] ?: false,
        language = prefs[Keys.LANGUAGE]?.let {
            runCatching { AppLanguage.valueOf(it) }.getOrNull()
        } ?: AppLanguage.SPANISH,
        strictTlsVerification = prefs[Keys.STRICT_TLS] ?: true,
        verboseDebugLogs = prefs[Keys.VERBOSE_LOGS] ?: false,
        wakeLockEnabled = prefs[Keys.WAKE_LOCK_ENABLED] ?: true,
        networkPreset = prefs[Keys.NETWORK_PRESET]?.let {
            runCatching { NetworkPreset.valueOf(it) }.getOrNull()
        } ?: NetworkPreset.BALANCED,
        transferBufferKb = (prefs[Keys.TRANSFER_BUFFER_KB] ?: 16).takeIf { it in ALLOWED_BUFFER_KB } ?: 16,
        immediateFlush = prefs[Keys.IMMEDIATE_FLUSH] ?: true,
        sshWindowKb = (prefs[Keys.SSH_WINDOW_KB] ?: 256).takeIf { it in ALLOWED_SSH_WINDOW_KB } ?: 256,
        sshKeepAliveSeconds = (prefs[Keys.SSH_KEEP_ALIVE_SEC] ?: 15).coerceIn(5, 60),
        maxConcurrentSshChannels = (prefs[Keys.MAX_CONCURRENT_SSH_CHANNELS] ?: 24).coerceIn(8, 64),
        tcpNoDelay = prefs[Keys.TCP_NO_DELAY] ?: true,
        sshCompression = prefs[Keys.SSH_COMPRESSION] ?: false,
        strictKillSwitch = prefs[Keys.STRICT_KILL_SWITCH] ?: false
    )

    val settingsFlow: Flow<AppSettings> = context.aioSettingsDataStore.data.map { prefs ->
        decodeSettings(prefs)
    }

    suspend fun updateSelectedProfileId(id: String?) {
        context.aioSettingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.SELECTED_PROFILE_ID)
            else prefs[Keys.SELECTED_PROFILE_ID] = id
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.aioSettingsDataStore.edit { prefs ->
            val current = decodeSettings(prefs)
            val rawUpdated = transform(current)
            val updated = rawUpdated.copy(
                connectionTimeoutSeconds = rawUpdated.connectionTimeoutSeconds.coerceIn(1, 999),
                wifiProxySharePort = rawUpdated.wifiProxySharePort.coerceIn(1024, 65535),
                transferBufferKb = rawUpdated.transferBufferKb.takeIf { it in ALLOWED_BUFFER_KB } ?: 16,
                sshWindowKb = rawUpdated.sshWindowKb.takeIf { it in ALLOWED_SSH_WINDOW_KB } ?: 256,
                sshKeepAliveSeconds = rawUpdated.sshKeepAliveSeconds.coerceIn(5, 60),
                maxConcurrentSshChannels = rawUpdated.maxConcurrentSshChannels.coerceIn(8, 64)
            )
            prefs[Keys.AUTO_RECONNECT] = updated.autoReconnectOnNetworkChange
            prefs[Keys.KEEP_TRYING_TO_CONNECT] = updated.keepTryingToConnect
            prefs[Keys.CONNECTION_TIMEOUT_SEC] = updated.connectionTimeoutSeconds
            prefs[Keys.WIFI_PROXY_SHARE_ENABLED] = updated.wifiProxyShareEnabled
            prefs[Keys.WIFI_PROXY_SHARE_PORT] = updated.wifiProxySharePort
            prefs[Keys.FAILOVER] = updated.failoverToNextServer
            prefs[Keys.DNS_MODE] = updated.defaultDnsMode.name
            prefs[Keys.DNS_SERVER] = updated.defaultDnsServer
            prefs[Keys.DNS_SERVER_SECONDARY] = updated.defaultDnsServerSecondary
            prefs[Keys.DOH_URL] = updated.defaultDohUrl
            prefs[Keys.IPV6_ENABLED] = updated.ipv6Enabled
            prefs[Keys.BYPASS_LAN] = updated.bypassLan
            prefs[Keys.SHOW_SPEED_NOTIFICATION] = updated.showSpeedInNotification
            prefs[Keys.USE_LIGHT_THEME] = updated.useLightTheme
            prefs[Keys.LANGUAGE] = updated.language.name
            prefs[Keys.STRICT_TLS] = updated.strictTlsVerification
            prefs[Keys.VERBOSE_LOGS] = updated.verboseDebugLogs
            prefs[Keys.WAKE_LOCK_ENABLED] = updated.wakeLockEnabled
            prefs[Keys.NETWORK_PRESET] = updated.networkPreset.name
            prefs[Keys.TRANSFER_BUFFER_KB] = updated.transferBufferKb
            prefs[Keys.IMMEDIATE_FLUSH] = updated.immediateFlush
            prefs[Keys.SSH_WINDOW_KB] = updated.sshWindowKb
            prefs[Keys.SSH_KEEP_ALIVE_SEC] = updated.sshKeepAliveSeconds
            prefs[Keys.MAX_CONCURRENT_SSH_CHANNELS] = updated.maxConcurrentSshChannels
            prefs[Keys.TCP_NO_DELAY] = updated.tcpNoDelay
            prefs[Keys.SSH_COMPRESSION] = updated.sshCompression
            prefs[Keys.STRICT_KILL_SWITCH] = updated.strictKillSwitch
        }
    }

    companion object {
        val ALLOWED_BUFFER_KB = listOf(4, 8, 16, 32, 64)
        val ALLOWED_SSH_WINDOW_KB = listOf(64, 128, 256, 512, 1024)
    }
}
