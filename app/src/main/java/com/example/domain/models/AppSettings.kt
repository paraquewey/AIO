package com.example.domain.models

enum class AppLanguage(val code: String, val label: String) {
    SPANISH("es", "Español"),
    ENGLISH("en", "English (preparado i18n)")
}

data class AppSettings(
    val selectedProfileId: String? = null,
    val autoReconnectOnNetworkChange: Boolean = true,
    val failoverToNextServer: Boolean = false,
    val defaultDnsMode: DnsMode = DnsMode.DNS_OVER_HTTPS,
    val defaultDnsServer: String = "1.1.1.1",
    val defaultDohUrl: String = "https://cloudflare-dns.com/dns-query",
    val ipv6Enabled: Boolean = false,
    val bypassLan: Boolean = true,
    val showSpeedInNotification: Boolean = true,
    val useLightTheme: Boolean = false,
    val language: AppLanguage = AppLanguage.SPANISH,
    val strictTlsVerification: Boolean = true,
    val verboseDebugLogs: Boolean = false
)
