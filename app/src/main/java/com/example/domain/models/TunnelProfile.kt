package com.example.domain.models

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class TunnelProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: TunnelProtocol,
    val host: String,
    val port: Int,
    val username: String = "",
    // Secret fields are stored encrypted at rest via KeystoreCryptoManager; never exported by default
    val passwordSecretId: String? = null,
    val privateKeySecretId: String? = null,
    val passphraseSecretId: String? = null,
    val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    // Jump Host (SSH Multi-Hop or Intermediate HTTPS Proxy)
    val jumpHostEnabled: Boolean = false,
    val jumpHost: String = "",
    val jumpPort: Int = 22,
    val jumpUsername: String = "",
    val jumpAuthMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    val jumpPasswordSecretId: String? = null,
    val jumpPrivateKeySecretId: String? = null,
    // TLS & Transport
    val transport: TransportType = TransportType.TCP,
    val tlsEnabled: Boolean = false,
    val tlsServerName: String = "",
    val tlsAlpn: String = "http/1.1",
    val verifyTlsCertificate: Boolean = true,
    val rawHttpPayload: String = "",
    // DNS & Routing
    val dnsMode: DnsMode = DnsMode.DNS_OVER_HTTPS,
    val useCustomDns: Boolean = false,
    val dnsServer: String = "1.1.1.1",
    val dnsServerSecondary: String = "1.0.0.1",
    val dohUrl: String = "https://cloudflare-dns.com/dns-query",
    val enableIpv6: Boolean = false,
    val bypassLan: Boolean = true,
    val mtu: Int = 1500,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val splitTunnelPackages: List<String> = emptyList(),
    // Organization & Metadata
    val category: String = "General",
    val colorTagHex: Long = 0xFFFE8019,
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val lastLatencyMs: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    // Optional raw config for imported Phase C native-core profiles
    val rawNativeConfigJson: String? = null
)

data class ProfileSecrets(
    val password: String = "",
    val privateKeyPem: String = "",
    val keyPassphrase: String = "",
    val jumpPassword: String = "",
    val jumpPrivateKeyPem: String = ""
) {
    fun hasAnySecret(): Boolean =
        password.isNotEmpty() || privateKeyPem.isNotEmpty() || keyPassphrase.isNotEmpty() ||
            jumpPassword.isNotEmpty() || jumpPrivateKeyPem.isNotEmpty()
}
