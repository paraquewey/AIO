package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.models.DnsMode
import com.example.domain.models.ServerMessageMode
import com.example.domain.models.SplitTunnelMode
import com.example.domain.models.SshAuthMethod
import com.example.domain.models.TransportType
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "tunnel_profiles")
data class TunnelProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocolName: String,
    val host: String,
    val port: Int,
    val username: String,
    val authMethodName: String,
    val jumpHostEnabled: Boolean,
    val jumpHost: String,
    val jumpPort: Int,
    val jumpUsername: String,
    val jumpAuthMethodName: String,
    val udpgwPort: Int = 7300,
    val transportName: String,
    val tlsEnabled: Boolean,
    val tlsServerName: String,
    val tlsAlpn: String,
    val verifyTlsCertificate: Boolean,
    val rawHttpPayload: String = "",
    val dnsModeName: String,
    val useCustomDns: Boolean = false,
    val dnsServer: String,
    val dnsServerSecondary: String = "1.0.0.1",
    val dohUrl: String,
    val enableIpv6: Boolean,
    val bypassLan: Boolean,
    val mtu: Int,
    val splitTunnelModeName: String,
    val splitTunnelPackagesJson: String,
    val serverMessageModeName: String = ServerMessageMode.ORIGINAL.name,
    val customServerMessageHtml: String = "",
    val category: String,
    val colorTagHex: Long,
    val tagsJson: String,
    val isFavorite: Boolean,
    val lastLatencyMs: Long?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val rawNativeConfigJson: String?
)

private val dbJson = Json { ignoreUnknownKeys = true }

fun TunnelProfileEntity.toDomain(): TunnelProfile = TunnelProfile(
    id = id,
    name = name,
    protocol = runCatching { TunnelProtocol.valueOf(protocolName) }.getOrDefault(TunnelProtocol.HTTPS_PROXY),
    host = host,
    port = port,
    username = username,
    passwordSecretId = id,
    privateKeySecretId = id,
    passphraseSecretId = id,
    authMethod = runCatching { SshAuthMethod.valueOf(authMethodName) }.getOrDefault(SshAuthMethod.PASSWORD),
    jumpHostEnabled = jumpHostEnabled,
    jumpHost = jumpHost,
    jumpPort = jumpPort,
    jumpUsername = jumpUsername,
    jumpAuthMethod = runCatching { SshAuthMethod.valueOf(jumpAuthMethodName) }.getOrDefault(SshAuthMethod.PASSWORD),
    jumpPasswordSecretId = id,
    jumpPrivateKeySecretId = id,
    udpgwPort = udpgwPort,
    transport = runCatching { TransportType.valueOf(transportName) }.getOrDefault(TransportType.TCP),
    tlsEnabled = tlsEnabled,
    tlsServerName = tlsServerName,
    tlsAlpn = tlsAlpn,
    verifyTlsCertificate = verifyTlsCertificate,
    rawHttpPayload = rawHttpPayload,
    dnsMode = runCatching { DnsMode.valueOf(dnsModeName) }.getOrDefault(DnsMode.DNS_OVER_HTTPS),
    useCustomDns = useCustomDns,
    dnsServer = dnsServer,
    dnsServerSecondary = dnsServerSecondary,
    dohUrl = dohUrl,
    enableIpv6 = enableIpv6,
    bypassLan = bypassLan,
    mtu = mtu,
    splitTunnelMode = runCatching { SplitTunnelMode.valueOf(splitTunnelModeName) }.getOrDefault(SplitTunnelMode.ALL_APPS),
    splitTunnelPackages = runCatching {
        dbJson.decodeFromString<List<String>>(splitTunnelPackagesJson)
    }.getOrDefault(emptyList()),
    serverMessageMode = runCatching { ServerMessageMode.valueOf(serverMessageModeName) }.getOrDefault(ServerMessageMode.ORIGINAL),
    customServerMessageHtml = customServerMessageHtml,
    category = category,
    colorTagHex = colorTagHex,
    tags = runCatching { dbJson.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList()),
    isFavorite = isFavorite,
    lastLatencyMs = lastLatencyMs,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    rawNativeConfigJson = rawNativeConfigJson
)

fun TunnelProfile.toEntity(): TunnelProfileEntity = TunnelProfileEntity(
    id = id,
    name = name,
    protocolName = protocol.name,
    host = host,
    port = port,
    username = username,
    authMethodName = authMethod.name,
    jumpHostEnabled = jumpHostEnabled,
    jumpHost = jumpHost,
    jumpPort = jumpPort,
    jumpUsername = jumpUsername,
    jumpAuthMethodName = jumpAuthMethod.name,
    udpgwPort = udpgwPort,
    transportName = transport.name,
    tlsEnabled = tlsEnabled,
    tlsServerName = tlsServerName,
    tlsAlpn = tlsAlpn,
    verifyTlsCertificate = verifyTlsCertificate,
    rawHttpPayload = rawHttpPayload,
    dnsModeName = dnsMode.name,
    useCustomDns = useCustomDns,
    dnsServer = dnsServer,
    dnsServerSecondary = dnsServerSecondary,
    dohUrl = dohUrl,
    enableIpv6 = enableIpv6,
    bypassLan = bypassLan,
    mtu = mtu,
    splitTunnelModeName = splitTunnelMode.name,
    splitTunnelPackagesJson = dbJson.encodeToString(splitTunnelPackages),
    serverMessageModeName = serverMessageMode.name,
    customServerMessageHtml = customServerMessageHtml,
    category = category,
    colorTagHex = colorTagHex,
    tagsJson = dbJson.encodeToString(tags),
    isFavorite = isFavorite,
    lastLatencyMs = lastLatencyMs,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    rawNativeConfigJson = rawNativeConfigJson
)
