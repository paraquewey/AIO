package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.models.AppLanguage
import com.example.domain.models.AppSettings
import com.example.domain.models.DnsMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aioSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aio_tunnel_settings"
)

class SettingsDataStore(private val context: Context) {
    private object Keys {
        val SELECTED_PROFILE_ID = stringPreferencesKey("selected_profile_id")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val FAILOVER = booleanPreferencesKey("failover_next_server")
        val DNS_MODE = stringPreferencesKey("dns_mode")
        val DNS_SERVER = stringPreferencesKey("dns_server")
        val DOH_URL = stringPreferencesKey("doh_url")
        val IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val SHOW_SPEED_NOTIFICATION = booleanPreferencesKey("show_speed_notification")
        val USE_LIGHT_THEME = booleanPreferencesKey("use_light_theme")
        val LANGUAGE = stringPreferencesKey("language")
        val STRICT_TLS = booleanPreferencesKey("strict_tls")
        val VERBOSE_LOGS = booleanPreferencesKey("verbose_logs")
    }

    val settingsFlow: Flow<AppSettings> = context.aioSettingsDataStore.data.map { prefs ->
        AppSettings(
            selectedProfileId = prefs[Keys.SELECTED_PROFILE_ID],
            autoReconnectOnNetworkChange = prefs[Keys.AUTO_RECONNECT] ?: true,
            failoverToNextServer = prefs[Keys.FAILOVER] ?: false,
            defaultDnsMode = prefs[Keys.DNS_MODE]?.let {
                runCatching { DnsMode.valueOf(it) }.getOrNull()
            } ?: DnsMode.DNS_OVER_HTTPS,
            defaultDnsServer = prefs[Keys.DNS_SERVER] ?: "1.1.1.1",
            defaultDohUrl = prefs[Keys.DOH_URL] ?: "https://cloudflare-dns.com/dns-query",
            ipv6Enabled = prefs[Keys.IPV6_ENABLED] ?: false,
            bypassLan = prefs[Keys.BYPASS_LAN] ?: true,
            showSpeedInNotification = prefs[Keys.SHOW_SPEED_NOTIFICATION] ?: true,
            useLightTheme = prefs[Keys.USE_LIGHT_THEME] ?: false,
            language = prefs[Keys.LANGUAGE]?.let {
                runCatching { AppLanguage.valueOf(it) }.getOrNull()
            } ?: AppLanguage.SPANISH,
            strictTlsVerification = prefs[Keys.STRICT_TLS] ?: true,
            verboseDebugLogs = prefs[Keys.VERBOSE_LOGS] ?: false
        )
    }

    suspend fun updateSelectedProfileId(id: String?) {
        context.aioSettingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.SELECTED_PROFILE_ID)
            else prefs[Keys.SELECTED_PROFILE_ID] = id
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.aioSettingsDataStore.edit { prefs ->
            val current = AppSettings(
                selectedProfileId = prefs[Keys.SELECTED_PROFILE_ID],
                autoReconnectOnNetworkChange = prefs[Keys.AUTO_RECONNECT] ?: true,
                failoverToNextServer = prefs[Keys.FAILOVER] ?: false,
                defaultDnsMode = prefs[Keys.DNS_MODE]?.let {
                    runCatching { DnsMode.valueOf(it) }.getOrNull()
                } ?: DnsMode.DNS_OVER_HTTPS,
                defaultDnsServer = prefs[Keys.DNS_SERVER] ?: "1.1.1.1",
                defaultDohUrl = prefs[Keys.DOH_URL] ?: "https://cloudflare-dns.com/dns-query",
                ipv6Enabled = prefs[Keys.IPV6_ENABLED] ?: false,
                bypassLan = prefs[Keys.BYPASS_LAN] ?: true,
                showSpeedInNotification = prefs[Keys.SHOW_SPEED_NOTIFICATION] ?: true,
                useLightTheme = prefs[Keys.USE_LIGHT_THEME] ?: false,
                language = prefs[Keys.LANGUAGE]?.let {
                    runCatching { AppLanguage.valueOf(it) }.getOrNull()
                } ?: AppLanguage.SPANISH,
                strictTlsVerification = prefs[Keys.STRICT_TLS] ?: true,
                verboseDebugLogs = prefs[Keys.VERBOSE_LOGS] ?: false
            )
            val updated = transform(current)
            prefs[Keys.AUTO_RECONNECT] = updated.autoReconnectOnNetworkChange
            prefs[Keys.FAILOVER] = updated.failoverToNextServer
            prefs[Keys.DNS_MODE] = updated.defaultDnsMode.name
            prefs[Keys.DNS_SERVER] = updated.defaultDnsServer
            prefs[Keys.DOH_URL] = updated.defaultDohUrl
            prefs[Keys.IPV6_ENABLED] = updated.ipv6Enabled
            prefs[Keys.BYPASS_LAN] = updated.bypassLan
            prefs[Keys.SHOW_SPEED_NOTIFICATION] = updated.showSpeedInNotification
            prefs[Keys.USE_LIGHT_THEME] = updated.useLightTheme
            prefs[Keys.LANGUAGE] = updated.language.name
            prefs[Keys.STRICT_TLS] = updated.strictTlsVerification
            prefs[Keys.VERBOSE_LOGS] = updated.verboseDebugLogs
        }
    }
}
