package com.example.domain.models

import kotlinx.serialization.Serializable

@Serializable
enum class TunnelProtocol(
    val displayName: String,
    val badgeCode: String,
    val defaultPort: Int,
    val isBuiltInEngine: Boolean,
    val engineFamily: EngineFamily,
    val requirementNote: String? = null
) {
    HTTP_PROXY(
        displayName = "HTTP Proxy (CONNECT)",
        badgeCode = "HTTP",
        defaultPort = 8080,
        isBuiltInEngine = true,
        engineFamily = EngineFamily.HTTP_CONNECT
    ),
    HTTPS_PROXY(
        displayName = "HTTPS Proxy (TLS CONNECT)",
        badgeCode = "HTTPS",
        defaultPort = 443,
        isBuiltInEngine = true,
        engineFamily = EngineFamily.HTTP_CONNECT
    ),
    SOCKS5(
        displayName = "SOCKS5",
        badgeCode = "SOCKS5",
        defaultPort = 1080,
        isBuiltInEngine = true,
        engineFamily = EngineFamily.SOCKS5
    ),
    SSH(
        displayName = "SSH Tunnel",
        badgeCode = "SSH",
        defaultPort = 22,
        isBuiltInEngine = true,
        engineFamily = EngineFamily.SSH
    ),
    SSH_JUMP(
        displayName = "SSH + Jump Host",
        badgeCode = "SSH-JUMP",
        defaultPort = 22,
        isBuiltInEngine = true,
        engineFamily = EngineFamily.SSH
    ),
    VLESS(
        displayName = "VLESS (Xray/sing-box)",
        badgeCode = "VLESS",
        defaultPort = 443,
        isBuiltInEngine = false,
        engineFamily = EngineFamily.NATIVE_CORE,
        requirementNote = "Requiere librería nativa libXray.aar / libbox.aar compilada con Go mobile."
    ),
    VMESS(
        displayName = "VMess (Xray/sing-box)",
        badgeCode = "VMESS",
        defaultPort = 443,
        isBuiltInEngine = false,
        engineFamily = EngineFamily.NATIVE_CORE,
        requirementNote = "Requiere librería nativa libXray.aar / libbox.aar compilada con Go mobile."
    ),
    TROJAN(
        displayName = "Trojan (TLS)",
        badgeCode = "TROJAN",
        defaultPort = 443,
        isBuiltInEngine = false,
        engineFamily = EngineFamily.NATIVE_CORE,
        requirementNote = "Requiere núcleo nativo sing-box / Xray-core."
    ),
    SHADOWSOCKS(
        displayName = "Shadowsocks",
        badgeCode = "SS",
        defaultPort = 8388,
        isBuiltInEngine = false,
        engineFamily = EngineFamily.NATIVE_CORE,
        requirementNote = "Requiere núcleo nativo sing-box / Xray-core."
    ),
    WIREGUARD(
        displayName = "WireGuard",
        badgeCode = "WG",
        defaultPort = 51820,
        isBuiltInEngine = false,
        engineFamily = EngineFamily.NATIVE_CORE,
        requirementNote = "Requiere módulo Go WireGuard o núcleo libbox.aar."
    ),
    HYSTERIA2(
        displayName = "Hysteria2 (QUIC)",
        badgeCode = "HY2",
        defaultPort = 443,
        isBuiltInEngine = false,
        engineFamily = EngineFamily.NATIVE_CORE,
        requirementNote = "Requiere núcleo nativo sing-box con soporte QUIC."
    ),
    TUIC(
        displayName = "TUIC v5",
        badgeCode = "TUIC",
        defaultPort = 443,
        isBuiltInEngine = false,
        engineFamily = EngineFamily.NATIVE_CORE,
        requirementNote = "Requiere núcleo nativo sing-box."
    ),
    NAIVE_PROXY(
        displayName = "NaiveProxy",
        badgeCode = "NAIVE",
        defaultPort = 443,
        isBuiltInEngine = false,
        engineFamily = EngineFamily.NATIVE_CORE,
        requirementNote = "Requiere binario nativo Chromium net stack."
    ),
    ANYTLS(
        displayName = "AnyTLS",
        badgeCode = "ANYTLS",
        defaultPort = 443,
        isBuiltInEngine = false,
        engineFamily = EngineFamily.NATIVE_CORE,
        requirementNote = "Requiere núcleo nativo sing-box 1.12+."
    ),
    REALITY(
        displayName = "VLESS + REALITY",
        badgeCode = "REALITY",
        defaultPort = 443,
        isBuiltInEngine = false,
        engineFamily = EngineFamily.NATIVE_CORE,
        requirementNote = "Requiere núcleo nativo Xray-core / sing-box."
    ),
    DNSTT(
        displayName = "DNSTT (DNS Tunnel)",
        badgeCode = "DNSTT",
        defaultPort = 53,
        isBuiltInEngine = false,
        engineFamily = EngineFamily.NATIVE_CORE,
        requirementNote = "Requiere binario nativo dnstt-client y servidor autoritativo remoto."
    ),
    SLIPSTREAM(
        displayName = "Slipstream",
        badgeCode = "SLIP",
        defaultPort = 53,
        isBuiltInEngine = false,
        engineFamily = EngineFamily.NATIVE_CORE,
        requirementNote = "Requiere motor nativo Slipstream (QUIC sobre DNS) y servidor dedicado."
    )
}

@Serializable
enum class EngineFamily {
    HTTP_CONNECT,
    SOCKS5,
    SSH,
    NATIVE_CORE
}

@Serializable
enum class SshAuthMethod {
    PASSWORD,
    PRIVATE_KEY
}

@Serializable
enum class DnsMode {
    SYSTEM_DEFAULT,
    PLAIN_DNS,
    DNS_OVER_HTTPS
}

@Serializable
enum class TransportType {
    TCP,
    TLS,
    WEBSOCKET,
    GRPC,
    QUIC
}

@Serializable
enum class SplitTunnelMode {
    ALL_APPS,
    INCLUDE_SELECTED,
    EXCLUDE_SELECTED
}

@Serializable
enum class ServerMessageMode(val label: String) {
    ORIGINAL("Original del servidor"),
    HIDDEN("Ocultar mensaje (Remove Server Message)"),
    CUSTOM_HTML("Personalizado (Custom HTML/CSS)")
}
