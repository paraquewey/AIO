package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.models.AppLanguage
import com.example.domain.models.AppSettings
import com.example.domain.models.DnsMode
import com.example.domain.models.FontColorMode
import com.example.domain.models.NetworkPreset
import com.example.domain.models.TextureScaleMode
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

        // UI Personalization - Phase 1
        val FONT_COLOR_MODE = stringPreferencesKey("font_color_mode")
        val CUSTOM_BG_PATH = stringPreferencesKey("custom_bg_path")
        val CUSTOM_BG_LUMINANCE = floatPreferencesKey("custom_bg_luminance")
        val CUSTOM_BG_VERSION = longPreferencesKey("custom_bg_version")
        val BG_DIMMER_ALPHA = floatPreferencesKey("bg_dimmer_alpha")
        val PRIMARY_ACCENT_ARGB = longPreferencesKey("primary_accent_argb")

        // UI Personalization - Phase 2 (Textures)
        val CARD_TEXTURE_PATH = stringPreferencesKey("card_texture_path")
        val CARD_TEXTURE_MODE = stringPreferencesKey("card_texture_mode")
        val CARD_TEXTURE_ALPHA = floatPreferencesKey("card_texture_alpha")
        val CARD_TEXTURE_VERSION = longPreferencesKey("card_texture_version")
        val HERO_BUTTON_TEXTURE_PATH = stringPreferencesKey("hero_button_texture_path")
        val HERO_BUTTON_TEXTURE_ALPHA = floatPreferencesKey("hero_button_texture_alpha")
        val HERO_BUTTON_TEXTURE_VERSION = longPreferencesKey("hero_button_texture_version")
        val ACTION_BUTTON_TEXTURE_PATH = stringPreferencesKey("action_button_texture_path")
        val ACTION_BUTTON_TEXTURE_ALPHA = floatPreferencesKey("action_button_texture_alpha")
        val ACTION_BUTTON_TEXTURE_VERSION = longPreferencesKey("action_button_texture_version")
        val NAV_BAR_TEXTURE_PATH = stringPreferencesKey("nav_bar_texture_path")
        val NAV_BAR_TEXTURE_ALPHA = floatPreferencesKey("nav_bar_texture_alpha")
        val NAV_BAR_TEXTURE_VERSION = longPreferencesKey("nav_bar_texture_version")

        // UI Personalization - Phase 2 (Fonts)
        val TITLE_FONT_PATH = stringPreferencesKey("title_font_path")
        val TITLE_FONT_SCALE = floatPreferencesKey("title_font_scale")
        val TITLE_FONT_VERSION = longPreferencesKey("title_font_version")
        val BODY_FONT_PATH = stringPreferencesKey("body_font_path")
        val BODY_FONT_SCALE = floatPreferencesKey("body_font_scale")
        val BODY_FONT_VERSION = longPreferencesKey("body_font_version")
        val TERMINAL_FONT_PATH = stringPreferencesKey("terminal_font_path")
        val TERMINAL_FONT_SCALE = floatPreferencesKey("terminal_font_scale")
        val TERMINAL_FONT_VERSION = longPreferencesKey("terminal_font_version")

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
        val TUN_QUEUE_CAPACITY = intPreferencesKey("tun_queue_capacity")
        val SMART_DNS_CACHE_ENABLED = booleanPreferencesKey("smart_dns_cache_enabled")
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
        fontColorMode = prefs[Keys.FONT_COLOR_MODE]?.let {
            runCatching { FontColorMode.valueOf(it) }.getOrNull()
        } ?: FontColorMode.ADAPTIVE,
        customBackgroundPath = prefs[Keys.CUSTOM_BG_PATH]?.takeIf { it.isNotBlank() },
        customBackgroundLuminance = (prefs[Keys.CUSTOM_BG_LUMINANCE] ?: 0.04f).coerceIn(0f, 1f),
        customBackgroundVersion = prefs[Keys.CUSTOM_BG_VERSION] ?: 0L,
        backgroundDimmerAlpha = (prefs[Keys.BG_DIMMER_ALPHA] ?: 0.35f).coerceIn(0f, 0.85f),
        primaryAccentArgb = prefs[Keys.PRIMARY_ACCENT_ARGB] ?: 0xFF7B61FFL,
        cardTexturePath = prefs[Keys.CARD_TEXTURE_PATH]?.takeIf { it.isNotBlank() },
        cardTextureMode = prefs[Keys.CARD_TEXTURE_MODE]?.let {
            runCatching { TextureScaleMode.valueOf(it) }.getOrNull()
        } ?: TextureScaleMode.CROP,
        cardTextureAlpha = (prefs[Keys.CARD_TEXTURE_ALPHA] ?: 0.45f).coerceIn(0f, 1f),
        cardTextureVersion = prefs[Keys.CARD_TEXTURE_VERSION] ?: 0L,
        heroButtonTexturePath = prefs[Keys.HERO_BUTTON_TEXTURE_PATH]?.takeIf { it.isNotBlank() },
        heroButtonTextureAlpha = (prefs[Keys.HERO_BUTTON_TEXTURE_ALPHA] ?: 0.65f).coerceIn(0f, 1f),
        heroButtonTextureVersion = prefs[Keys.HERO_BUTTON_TEXTURE_VERSION] ?: 0L,
        actionButtonTexturePath = prefs[Keys.ACTION_BUTTON_TEXTURE_PATH]?.takeIf { it.isNotBlank() },
        actionButtonTextureAlpha = (prefs[Keys.ACTION_BUTTON_TEXTURE_ALPHA] ?: 0.55f).coerceIn(0f, 1f),
        actionButtonTextureVersion = prefs[Keys.ACTION_BUTTON_TEXTURE_VERSION] ?: 0L,
        navBarTexturePath = prefs[Keys.NAV_BAR_TEXTURE_PATH]?.takeIf { it.isNotBlank() },
        navBarTextureAlpha = (prefs[Keys.NAV_BAR_TEXTURE_ALPHA] ?: 0.60f).coerceIn(0f, 1f),
        navBarTextureVersion = prefs[Keys.NAV_BAR_TEXTURE_VERSION] ?: 0L,
        titleFontPath = prefs[Keys.TITLE_FONT_PATH]?.takeIf { it.isNotBlank() },
        titleFontScale = (prefs[Keys.TITLE_FONT_SCALE] ?: 1.0f).coerceIn(0.80f, 1.35f),
        titleFontVersion = prefs[Keys.TITLE_FONT_VERSION] ?: 0L,
        bodyFontPath = prefs[Keys.BODY_FONT_PATH]?.takeIf { it.isNotBlank() },
        bodyFontScale = (prefs[Keys.BODY_FONT_SCALE] ?: 1.0f).coerceIn(0.80f, 1.35f),
        bodyFontVersion = prefs[Keys.BODY_FONT_VERSION] ?: 0L,
        terminalFontPath = prefs[Keys.TERMINAL_FONT_PATH]?.takeIf { it.isNotBlank() },
        terminalFontScale = (prefs[Keys.TERMINAL_FONT_SCALE] ?: 1.0f).coerceIn(0.80f, 1.35f),
        terminalFontVersion = prefs[Keys.TERMINAL_FONT_VERSION] ?: 0L,
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
        strictKillSwitch = prefs[Keys.STRICT_KILL_SWITCH] ?: false,
        tunQueueCapacity = (prefs[Keys.TUN_QUEUE_CAPACITY] ?: 64).coerceIn(32, 256),
        smartDnsCacheEnabled = prefs[Keys.SMART_DNS_CACHE_ENABLED] ?: true
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
                customBackgroundLuminance = rawUpdated.customBackgroundLuminance.coerceIn(0f, 1f),
                backgroundDimmerAlpha = rawUpdated.backgroundDimmerAlpha.coerceIn(0f, 0.85f),
                cardTextureAlpha = rawUpdated.cardTextureAlpha.coerceIn(0f, 1f),
                heroButtonTextureAlpha = rawUpdated.heroButtonTextureAlpha.coerceIn(0f, 1f),
                actionButtonTextureAlpha = rawUpdated.actionButtonTextureAlpha.coerceIn(0f, 1f),
                navBarTextureAlpha = rawUpdated.navBarTextureAlpha.coerceIn(0f, 1f),
                titleFontScale = rawUpdated.titleFontScale.coerceIn(0.80f, 1.35f),
                bodyFontScale = rawUpdated.bodyFontScale.coerceIn(0.80f, 1.35f),
                terminalFontScale = rawUpdated.terminalFontScale.coerceIn(0.80f, 1.35f),
                transferBufferKb = rawUpdated.transferBufferKb.takeIf { it in ALLOWED_BUFFER_KB } ?: 16,
                sshWindowKb = rawUpdated.sshWindowKb.takeIf { it in ALLOWED_SSH_WINDOW_KB } ?: 256,
                sshKeepAliveSeconds = rawUpdated.sshKeepAliveSeconds.coerceIn(5, 60),
                maxConcurrentSshChannels = rawUpdated.maxConcurrentSshChannels.coerceIn(8, 64),
                tunQueueCapacity = rawUpdated.tunQueueCapacity.coerceIn(32, 256)
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
            prefs[Keys.FONT_COLOR_MODE] = updated.fontColorMode.name
            setOptionalPath(prefs, Keys.CUSTOM_BG_PATH, updated.customBackgroundPath)
            prefs[Keys.CUSTOM_BG_LUMINANCE] = updated.customBackgroundLuminance
            prefs[Keys.CUSTOM_BG_VERSION] = updated.customBackgroundVersion
            prefs[Keys.BG_DIMMER_ALPHA] = updated.backgroundDimmerAlpha
            prefs[Keys.PRIMARY_ACCENT_ARGB] = updated.primaryAccentArgb

            setOptionalPath(prefs, Keys.CARD_TEXTURE_PATH, updated.cardTexturePath)
            prefs[Keys.CARD_TEXTURE_MODE] = updated.cardTextureMode.name
            prefs[Keys.CARD_TEXTURE_ALPHA] = updated.cardTextureAlpha
            prefs[Keys.CARD_TEXTURE_VERSION] = updated.cardTextureVersion

            setOptionalPath(prefs, Keys.HERO_BUTTON_TEXTURE_PATH, updated.heroButtonTexturePath)
            prefs[Keys.HERO_BUTTON_TEXTURE_ALPHA] = updated.heroButtonTextureAlpha
            prefs[Keys.HERO_BUTTON_TEXTURE_VERSION] = updated.heroButtonTextureVersion

            setOptionalPath(prefs, Keys.ACTION_BUTTON_TEXTURE_PATH, updated.actionButtonTexturePath)
            prefs[Keys.ACTION_BUTTON_TEXTURE_ALPHA] = updated.actionButtonTextureAlpha
            prefs[Keys.ACTION_BUTTON_TEXTURE_VERSION] = updated.actionButtonTextureVersion

            setOptionalPath(prefs, Keys.NAV_BAR_TEXTURE_PATH, updated.navBarTexturePath)
            prefs[Keys.NAV_BAR_TEXTURE_ALPHA] = updated.navBarTextureAlpha
            prefs[Keys.NAV_BAR_TEXTURE_VERSION] = updated.navBarTextureVersion

            setOptionalPath(prefs, Keys.TITLE_FONT_PATH, updated.titleFontPath)
            prefs[Keys.TITLE_FONT_SCALE] = updated.titleFontScale
            prefs[Keys.TITLE_FONT_VERSION] = updated.titleFontVersion

            setOptionalPath(prefs, Keys.BODY_FONT_PATH, updated.bodyFontPath)
            prefs[Keys.BODY_FONT_SCALE] = updated.bodyFontScale
            prefs[Keys.BODY_FONT_VERSION] = updated.bodyFontVersion

            setOptionalPath(prefs, Keys.TERMINAL_FONT_PATH, updated.terminalFontPath)
            prefs[Keys.TERMINAL_FONT_SCALE] = updated.terminalFontScale
            prefs[Keys.TERMINAL_FONT_VERSION] = updated.terminalFontVersion

            prefs[Keys.NETWORK_PRESET] = updated.networkPreset.name
            prefs[Keys.TRANSFER_BUFFER_KB] = updated.transferBufferKb
            prefs[Keys.IMMEDIATE_FLUSH] = updated.immediateFlush
            prefs[Keys.SSH_WINDOW_KB] = updated.sshWindowKb
            prefs[Keys.SSH_KEEP_ALIVE_SEC] = updated.sshKeepAliveSeconds
            prefs[Keys.MAX_CONCURRENT_SSH_CHANNELS] = updated.maxConcurrentSshChannels
            prefs[Keys.TCP_NO_DELAY] = updated.tcpNoDelay
            prefs[Keys.SSH_COMPRESSION] = updated.sshCompression
            prefs[Keys.STRICT_KILL_SWITCH] = updated.strictKillSwitch
            prefs[Keys.TUN_QUEUE_CAPACITY] = updated.tunQueueCapacity
            prefs[Keys.SMART_DNS_CACHE_ENABLED] = updated.smartDnsCacheEnabled
        }
    }

    private fun setOptionalPath(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        path: String?
    ) {
        if (path.isNullOrBlank()) prefs.remove(key) else prefs[key] = path
    }

    companion object {
        val ALLOWED_BUFFER_KB = listOf(4, 8, 16, 32, 64)
        val ALLOWED_SSH_WINDOW_KB = listOf(64, 128, 256, 512, 1024)
    }
}
