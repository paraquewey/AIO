package com.example.domain.models

enum class AppLanguage(val code: String, val label: String) {
    SPANISH("es", "Español"),
    ENGLISH("en", "English (preparado i18n)")
}

enum class NetworkPreset(val label: String, val subtitle: String) {
    BALANCED("Equilibrado (Recomendado)", "16 KB buf · 256 KB SSH · 24 canales · Flush inmediato"),
    MAX_STABILITY("Máxima Estabilidad", "8 KB buf · 128 KB SSH · 16 canales · Keep-Alive 10s"),
    MAX_SPEED("Máxima Velocidad", "32 KB buf · 512 KB SSH · 32 canales · Keep-Alive 20s"),
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
    val sshCompression: Boolean = false
) {
    val transferBufferBytes: Int
        get() = (transferBufferKb.coerceIn(4, 64)) * 1024

    val sshWindowBytes: Int
        get() = (sshWindowKb.coerceIn(64, 1024)) * 1024

    val sshKeepAliveMs: Int
        get() = sshKeepAliveSeconds.coerceIn(5, 60) * 1000

    val clampedConcurrency: Int
        get() = maxConcurrentSshChannels.coerceIn(8, 64)
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
    // Advanced Engine & Performance Tuning (v2.0)
    val networkPreset: NetworkPreset = NetworkPreset.BALANCED,
    val transferBufferKb: Int = 16,
    val immediateFlush: Boolean = true,
    val sshWindowKb: Int = 256,
    val sshKeepAliveSeconds: Int = 15,
    val maxConcurrentSshChannels: Int = 24,
    val tcpNoDelay: Boolean = true,
    val sshCompression: Boolean = false,
    val strictKillSwitch: Boolean = false
) {
    fun toEngineTuningConfig(): EngineTuningConfig = EngineTuningConfig(
        transferBufferKb = transferBufferKb,
        immediateFlush = immediateFlush,
        sshWindowKb = sshWindowKb,
        sshKeepAliveSeconds = sshKeepAliveSeconds,
        maxConcurrentSshChannels = maxConcurrentSshChannels,
        tcpNoDelay = tcpNoDelay,
        sshCompression = sshCompression
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
            strictKillSwitch = false
        )
        NetworkPreset.MAX_STABILITY -> copy(
            networkPreset = NetworkPreset.MAX_STABILITY,
            transferBufferKb = 8,
            immediateFlush = true,
            sshWindowKb = 128,
            sshKeepAliveSeconds = 10,
            maxConcurrentSshChannels = 16,
            tcpNoDelay = true,
            sshCompression = false
        )
        NetworkPreset.MAX_SPEED -> copy(
            networkPreset = NetworkPreset.MAX_SPEED,
            transferBufferKb = 32,
            immediateFlush = true,
            sshWindowKb = 512,
            sshKeepAliveSeconds = 20,
            maxConcurrentSshChannels = 32,
            tcpNoDelay = true,
            sshCompression = false
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
            sshCompression = false
        )
        NetworkPreset.CUSTOM -> copy(networkPreset = NetworkPreset.CUSTOM)
    }

    fun restoreOptimalEngineDefaults(): AppSettings = applyNetworkPreset(NetworkPreset.BALANCED).copy(
        bypassLan = true,
        strictTlsVerification = true
    )
}
