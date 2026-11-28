package com.example.domain.models

enum class AppLanguage(val code: String, val label: String) {
    SPANISH("es", "Español"),
    ENGLISH("en", "English (preparado i18n)")
}

enum class FontColorMode(val label: String) {
    ADAPTIVE("Adaptable"),
    FORCED_WHITE("Blanco"),
    FORCED_BLACK("Negro")
}

enum class TextureScaleMode(val label: String) {
    CROP("Ajustar"),
    FILL("Rellenar"),
    TILE("Mosaico")
}

enum class NetworkPreset(val label: String, val subtitle: String) {
    BALANCED("Equilibrado (Recomendado)", "16 KB buf · 256 KB SSH · 24 canales · Cola 64 · SWR"),
    MAX_STABILITY("Máxima Estabilidad", "8 KB buf · 128 KB SSH · 16 canales · Cola 48 · Keep-Alive 10s"),
    MAX_SPEED("Máxima Velocidad", "32 KB buf · 512 KB SSH · 32 canales · Cola 192 · Keep-Alive 20s"),
    MAX_SECURITY_PRIVACY("Máxima Seguridad y Privacidad", "DoH + TLS estricto + Kill-Switch (sin Bypass LAN ni Wi-Fi Proxy)"),
    CUSTOM("Personalizado", "Ajuste manual de parámetros del motor")
}

data class EngineTuningConfig(
    val transferBufferKb: Int = 16,
    val immediateFlush: Boolean = true,
    val sshWindowKb: Int = 256,
    val sshKeepAliveSeconds: Int = 15,
    val maxConcurrentSshChannels: Int = 24,
    val tcpNoDelay: Boolean = true,
    val sshCompression: Boolean = false,
    val tunQueueCapacity: Int = 64,
    val smartDnsCacheEnabled: Boolean = true
) {
    val transferBufferBytes: Int
        get() = (transferBufferKb.coerceIn(4, 64)) * 1024

    val sshWindowBytes: Int
        get() = (sshWindowKb.coerceIn(64, 1024)) * 1024

    val sshKeepAliveMs: Int
        get() = sshKeepAliveSeconds.coerceIn(5, 60) * 1000

    val clampedConcurrency: Int
        get() = maxConcurrentSshChannels.coerceIn(8, 64)

    val clampedTunQueueCapacity: Int
        get() = tunQueueCapacity.coerceIn(32, 256)
}

data class AppSettings(
    val selectedProfileId: String? = null,
    val autoReconnectOnNetworkChange: Boolean = true,
    val keepTryingToConnect: Boolean = true,
    val connectionTimeoutSeconds: Int = 10,
    val wifiProxyShareEnabled: Boolean = false,
    val wifiProxySharePort: Int = 8080,
    val failoverToNextServer: Boolean = false,
    val defaultDnsMode: DnsMode = DnsMode.DNS_OVER_HTTPS,
    val defaultDnsServer: String = "1.1.1.1",
    val defaultDnsServerSecondary: String = "1.0.0.1",
    val defaultDohUrl: String = "https://cloudflare-dns.com/dns-query",
    val ipv6Enabled: Boolean = false,
    val bypassLan: Boolean = true,
    val showSpeedInNotification: Boolean = true,
    val useLightTheme: Boolean = false,
    val language: AppLanguage = AppLanguage.SPANISH,
    val strictTlsVerification: Boolean = true,
    val verboseDebugLogs: Boolean = false,
    val wakeLockEnabled: Boolean = true,
    // UI Personalization - Phase 1 (Background & Colors)
    val fontColorMode: FontColorMode = FontColorMode.ADAPTIVE,
    val customBackgroundPath: String? = null,
    val customBackgroundLuminance: Float = 0.04f,
    val customBackgroundVersion: Long = 0L,
    val backgroundDimmerAlpha: Float = 0.35f,
    val primaryAccentArgb: Long = 0xFF7B61FFL,
    // UI Personalization - Phase 2 (Component Textures)
    val cardTexturePath: String? = null,
    val cardTextureMode: TextureScaleMode = TextureScaleMode.CROP,
    val cardTextureAlpha: Float = 0.45f,
    val cardTextureVersion: Long = 0L,
    val heroButtonTexturePath: String? = null,
    val heroButtonTextureAlpha: Float = 0.65f,
    val heroButtonTextureVersion: Long = 0L,
    val actionButtonTexturePath: String? = null,
    val actionButtonTextureAlpha: Float = 0.55f,
    val actionButtonTextureVersion: Long = 0L,
    val navBarTexturePath: String? = null,
    val navBarTextureAlpha: Float = 0.60f,
    val navBarTextureVersion: Long = 0L,
    // UI Personalization - Phase 2 (Granular Typography)
    val titleFontPath: String? = null,
    val titleFontScale: Float = 1.0f,
    val titleFontVersion: Long = 0L,
    val bodyFontPath: String? = null,
    val bodyFontScale: Float = 1.0f,
    val bodyFontVersion: Long = 0L,
    val terminalFontPath: String? = null,
    val terminalFontScale: Float = 1.0f,
    val terminalFontVersion: Long = 0L,
    // Advanced Engine & Performance Tuning
    val networkPreset: NetworkPreset = NetworkPreset.BALANCED,
    val transferBufferKb: Int = 16,
    val immediateFlush: Boolean = true,
    val sshWindowKb: Int = 256,
    val sshKeepAliveSeconds: Int = 15,
    val maxConcurrentSshChannels: Int = 24,
    val tcpNoDelay: Boolean = true,
    val sshCompression: Boolean = false,
    val strictKillSwitch: Boolean = false,
    val tunQueueCapacity: Int = 64,
    val smartDnsCacheEnabled: Boolean = true
) {
    fun toEngineTuningConfig(): EngineTuningConfig = EngineTuningConfig(
        transferBufferKb = transferBufferKb,
        immediateFlush = immediateFlush,
        sshWindowKb = sshWindowKb,
        sshKeepAliveSeconds = sshKeepAliveSeconds,
        maxConcurrentSshChannels = maxConcurrentSshChannels,
        tcpNoDelay = tcpNoDelay,
        sshCompression = sshCompression,
        tunQueueCapacity = tunQueueCapacity,
        smartDnsCacheEnabled = smartDnsCacheEnabled
    )

    fun applyNetworkPreset(preset: NetworkPreset): AppSettings = when (preset) {
        NetworkPreset.BALANCED -> copy(
            networkPreset = NetworkPreset.BALANCED,
            transferBufferKb = 16,
            immediateFlush = true,
            sshWindowKb = 256,
            sshKeepAliveSeconds = 15,
            maxConcurrentSshChannels = 24,
            tcpNoDelay = true,
            sshCompression = false,
            strictKillSwitch = false,
            tunQueueCapacity = 64,
            smartDnsCacheEnabled = true
        )
        NetworkPreset.MAX_STABILITY -> copy(
            networkPreset = NetworkPreset.MAX_STABILITY,
            transferBufferKb = 8,
            immediateFlush = true,
            sshWindowKb = 128,
            sshKeepAliveSeconds = 10,
            maxConcurrentSshChannels = 16,
            tcpNoDelay = true,
            sshCompression = false,
            tunQueueCapacity = 48,
            smartDnsCacheEnabled = true
        )
        NetworkPreset.MAX_SPEED -> copy(
            networkPreset = NetworkPreset.MAX_SPEED,
            transferBufferKb = 32,
            immediateFlush = true,
            sshWindowKb = 512,
            sshKeepAliveSeconds = 20,
            maxConcurrentSshChannels = 32,
            tcpNoDelay = true,
            sshCompression = false,
            tunQueueCapacity = 192,
            smartDnsCacheEnabled = true
        )
        NetworkPreset.MAX_SECURITY_PRIVACY -> copy(
            networkPreset = NetworkPreset.MAX_SECURITY_PRIVACY,
            strictTlsVerification = true,
            defaultDnsMode = DnsMode.DNS_OVER_HTTPS,
            wifiProxyShareEnabled = false,
            bypassLan = false,
            strictKillSwitch = true,
            transferBufferKb = 16,
            immediateFlush = true,
            sshWindowKb = 256,
            sshKeepAliveSeconds = 15,
            maxConcurrentSshChannels = 24,
            tcpNoDelay = true,
            sshCompression = false,
            tunQueueCapacity = 64,
            smartDnsCacheEnabled = true
        )
        NetworkPreset.CUSTOM -> copy(networkPreset = NetworkPreset.CUSTOM)
    }

    fun restoreOptimalEngineDefaults(): AppSettings = applyNetworkPreset(NetworkPreset.BALANCED).copy(
        bypassLan = true,
        strictTlsVerification = true
    )

    fun restoreFactoryUiDefaults(): AppSettings = copy(
        useLightTheme = false,
        fontColorMode = FontColorMode.ADAPTIVE,
        customBackgroundPath = null,
        customBackgroundLuminance = 0.04f,
        customBackgroundVersion = 0L,
        backgroundDimmerAlpha = 0.35f,
        primaryAccentArgb = 0xFF7B61FFL,
        cardTexturePath = null,
        cardTextureMode = TextureScaleMode.CROP,
        cardTextureAlpha = 0.45f,
        cardTextureVersion = 0L,
        heroButtonTexturePath = null,
        heroButtonTextureAlpha = 0.65f,
        heroButtonTextureVersion = 0L,
        actionButtonTexturePath = null,
        actionButtonTextureAlpha = 0.55f,
        actionButtonTextureVersion = 0L,
        navBarTexturePath = null,
        navBarTextureAlpha = 0.60f,
        navBarTextureVersion = 0L,
        titleFontPath = null,
        titleFontScale = 1.0f,
        titleFontVersion = 0L,
        bodyFontPath = null,
        bodyFontScale = 1.0f,
        bodyFontVersion = 0L,
        terminalFontPath = null,
        terminalFontScale = 1.0f,
        terminalFontVersion = 0L
    )
}
