package com.example.data.import_export

import android.util.Base64
import com.example.domain.models.DnsMode
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.SplitTunnelMode
import com.example.domain.models.SshAuthMethod
import com.example.domain.models.TransportType
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
data class AioExportBundle(
    val schemaVersion: Int = 1,
    val app: String = "AIO Tunnel VPN",
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val secretsIncluded: Boolean = false,
    val profiles: List<AioExportedProfile>
)

@Serializable
data class AioExportedProfile(
    val profile: TunnelProfile,
    val secrets: ExportedSecrets? = null
)

@Serializable
data class ExportedSecrets(
    val password: String = "",
    val privateKeyPem: String = "",
    val keyPassphrase: String = "",
    val jumpPassword: String = "",
    val jumpPrivateKeyPem: String = ""
)

data class ImportedProfileEntry(
    val profile: TunnelProfile,
    val secrets: ProfileSecrets
)

object ConfigParserAndExporter {
    private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024 // 2 MB strict limit

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exportProfilesToJson(
        profiles: List<Pair<TunnelProfile, ProfileSecrets>>,
        includeSecrets: Boolean = false
    ): String {
        val entries = profiles.map { (profile, secrets) ->
            val cleanProfile = profile.copy(
                passwordSecretId = null,
                privateKeySecretId = null,
                passphraseSecretId = null,
                jumpPasswordSecretId = null,
                jumpPrivateKeySecretId = null
            )
            val exportedSecrets = if (includeSecrets && secrets.hasAnySecret()) {
                ExportedSecrets(
                    password = secrets.password,
                    privateKeyPem = secrets.privateKeyPem,
                    keyPassphrase = secrets.keyPassphrase,
                    jumpPassword = secrets.jumpPassword,
                    jumpPrivateKeyPem = secrets.jumpPrivateKeyPem
                )
            } else null
            AioExportedProfile(profile = cleanProfile, secrets = exportedSecrets)
        }
        val bundle = AioExportBundle(
            schemaVersion = 1,
            secretsIncluded = includeSecrets,
            profiles = entries
        )
        return json.encodeToString(bundle)
    }

    fun exportProfileToUri(profile: TunnelProfile): String {
        val userPart = if (profile.username.isNotBlank()) {
            "${urlEncode(profile.username)}@"
        } else ""
        val nameFragment = if (profile.name.isNotBlank()) "#${urlEncode(profile.name)}" else ""
        return when (profile.protocol) {
            TunnelProtocol.HTTP_PROXY -> "http://$userPart${profile.host}:${profile.port}$nameFragment"
            TunnelProtocol.HTTPS_PROXY -> "https://$userPart${profile.host}:${profile.port}$nameFragment"
            TunnelProtocol.SOCKS5 -> "socks5://$userPart${profile.host}:${profile.port}$nameFragment"
            TunnelProtocol.SSH, TunnelProtocol.SSH_JUMP -> {
                val queryParams = mutableListOf<String>()
                if (profile.jumpHostEnabled && profile.jumpHost.isNotBlank()) {
                    val jUser = if (profile.jumpUsername.isNotBlank()) "${urlEncode(profile.jumpUsername)}@" else ""
                    queryParams += "jump=$jUser${urlEncode(profile.jumpHost)}:${profile.jumpPort}"
                }
                if (profile.authMethod == SshAuthMethod.PRIVATE_KEY) {
                    queryParams += "auth=key"
                }
                val q = if (queryParams.isNotEmpty()) "?${queryParams.joinToString("&")}" else ""
                "ssh://$userPart${profile.host}:${profile.port}$q$nameFragment"
            }
            else -> exportProfilesToJson(listOf(profile to ProfileSecrets()), includeSecrets = false)
        }
    }

    fun importFromText(rawInput: String): Result<List<ImportedProfileEntry>> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("El contenido de importación está vacío."))
        }
        if (trimmed.toByteArray(StandardCharsets.UTF_8).size > MAX_IMPORT_BYTES) {
            return Result.failure(IllegalArgumentException("El archivo supera el límite seguro de 2 MB."))
        }

        // 1. Try JSON formats (AIO bundle, single profile, or sing-box config)
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseJsonImport(trimmed)
        }

        // 2. Try Clash YAML-subset if "proxies:" is present
        if (trimmed.contains("proxies:")) {
            val clashEntries = parseClashProxies(trimmed)
            if (clashEntries.isNotEmpty()) {
                return validateImportedEntries(clashEntries)
            }
        }

        // 3. Try line-by-line URIs (ssh://, socks5://, http://, https://, vless://, vmess://, trojan://, ss://)
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val entries = mutableListOf<ImportedProfileEntry>()
        for (line in lines) {
            val parsed = parseUriLine(line)
                ?: return Result.failure(
                    IllegalArgumentException("Formato no reconocido o inválido en línea: ${line.take(48)}")
                )
            entries.add(parsed)
        }

        return if (entries.isEmpty()) {
            Result.failure(IllegalArgumentException("No se encontraron configuraciones válidas para importar."))
        } else {
            validateImportedEntries(entries)
        }
    }

    private fun parseJsonImport(rawJson: String): Result<List<ImportedProfileEntry>> {
        // Check AIO Export Bundle first
        runCatching {
            val bundle = json.decodeFromString<AioExportBundle>(rawJson)
            if (bundle.schemaVersion > 1) {
                return Result.failure(
                    IllegalArgumentException("Versión de esquema JSON no soportada: v${bundle.schemaVersion}")
                )
            }
            if (bundle.profiles.isNotEmpty()) {
                val imported = bundle.profiles.map { item ->
                    val freshId = UUID.randomUUID().toString()
                    val sec = item.secrets?.let {
                        ProfileSecrets(
                            password = it.password,
                            privateKeyPem = it.privateKeyPem,
                            keyPassphrase = it.keyPassphrase,
                            jumpPassword = it.jumpPassword,
                            jumpPrivateKeyPem = it.jumpPrivateKeyPem
                        )
                    } ?: ProfileSecrets()
                    ImportedProfileEntry(
                        profile = item.profile.copy(
                            id = freshId,
                            createdAtMillis = System.currentTimeMillis(),
                            updatedAtMillis = System.currentTimeMillis()
                        ),
                        secrets = sec
                    )
                }
                return validateImportedEntries(imported)
            }
        }

        // Check sing-box JSON format (has "outbounds" array)
        runCatching {
            val obj = json.parseToJsonElement(rawJson).jsonObject
            val outbounds = obj["outbounds"] as? JsonArray
            if (outbounds != null) {
                val parsed = parseSingBoxOutbounds(outbounds, rawJson)
                if (parsed.isNotEmpty()) {
                    return validateImportedEntries(parsed)
                }
            }
        }

        // Check single TunnelProfile JSON
        runCatching {
            val single = json.decodeFromString<TunnelProfile>(rawJson)
            val entry = ImportedProfileEntry(
                profile = single.copy(
                    id = UUID.randomUUID().toString(),
                    createdAtMillis = System.currentTimeMillis(),
                    updatedAtMillis = System.currentTimeMillis()
                ),
                secrets = ProfileSecrets()
            )
            return validateImportedEntries(listOf(entry))
        }

        return Result.failure(IllegalArgumentException("JSON inválido o estructura de perfil no reconocida."))
    }

    private fun parseSingBoxOutbounds(outbounds: JsonArray, rawJson: String): List<ImportedProfileEntry> {
        val result = mutableListOf<ImportedProfileEntry>()
        for (el in outbounds) {
            val ob = el as? JsonObject ?: continue
            val type = ob["type"]?.jsonPrimitive?.content?.lowercase() ?: continue
            if (type in listOf("direct", "block", "dns", "selector", "urltest")) continue
            val server = ob["server"]?.jsonPrimitive?.content ?: continue
            val port = ob["server_port"]?.jsonPrimitive?.intOrNull ?: continue
            val tag = ob["tag"]?.jsonPrimitive?.content ?: "$type-$server"
            val protocol = when (type) {
                "socks" -> TunnelProtocol.SOCKS5
                "http" -> TunnelProtocol.HTTPS_PROXY
                "ssh" -> TunnelProtocol.SSH
                "vless" -> TunnelProtocol.VLESS
                "vmess" -> TunnelProtocol.VMESS
                "trojan" -> TunnelProtocol.TROJAN
                "shadowsocks" -> TunnelProtocol.SHADOWSOCKS
                "wireguard" -> TunnelProtocol.WIREGUARD
                "hysteria2" -> TunnelProtocol.HYSTERIA2
                "tuic" -> TunnelProtocol.TUIC
                "naive" -> TunnelProtocol.NAIVE_PROXY
                "anytls" -> TunnelProtocol.ANYTLS
                else -> continue
            }
            val username = ob["username"]?.jsonPrimitive?.content ?: ""
            val password = ob["password"]?.jsonPrimitive?.content ?: ""
            result.add(
                ImportedProfileEntry(
                    profile = TunnelProfile(
                        name = tag,
                        protocol = protocol,
                        host = server,
                        port = port,
                        username = username,
                        tlsEnabled = ob["tls"] != null || protocol == TunnelProtocol.HTTPS_PROXY,
                        category = "sing-box",
                        tags = listOf("sing-box", protocol.badgeCode),
                        rawNativeConfigJson = if (!protocol.isBuiltInEngine) rawJson else null
                    ),
                    secrets = ProfileSecrets(password = password)
                )
            )
        }
        return result
    }

    private fun parseClashProxies(yaml: String): List<ImportedProfileEntry> {
        val result = mutableListOf<ImportedProfileEntry>()
        var current = mutableMapOf<String, String>()
        var inProxies = false

        fun flushCurrent() {
            val type = current["type"]?.lowercase() ?: return
            val server = current["server"] ?: return
            val port = current["port"]?.toIntOrNull() ?: return
            val name = current["name"] ?: "$type-$server"
            val protocol = when (type) {
                "socks5" -> TunnelProtocol.SOCKS5
                "http" -> if (current["tls"] == "true") TunnelProtocol.HTTPS_PROXY else TunnelProtocol.HTTP_PROXY
                "ss" -> TunnelProtocol.SHADOWSOCKS
                "vmess" -> TunnelProtocol.VMESS
                "vless" -> TunnelProtocol.VLESS
                "trojan" -> TunnelProtocol.TROJAN
                "hysteria2" -> TunnelProtocol.HYSTERIA2
                "tuic" -> TunnelProtocol.TUIC
                "wireguard" -> TunnelProtocol.WIREGUARD
                else -> return
            }
            result.add(
                ImportedProfileEntry(
                    profile = TunnelProfile(
                        name = name,
                        protocol = protocol,
                        host = server,
                        port = port,
                        username = current["username"].orEmpty(),
                        tlsEnabled = current["tls"] == "true" || protocol == TunnelProtocol.TROJAN,
                        tlsServerName = current["sni"].orEmpty(),
                        category = "Clash",
                        tags = listOf("Clash", protocol.badgeCode)
                    ),
                    secrets = ProfileSecrets(password = current["password"].orEmpty())
                )
            )
        }

        for (rawLine in yaml.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("proxies:")) {
                inProxies = true
                continue
            }
            if (inProxies && !rawLine.startsWith(" ") && !rawLine.startsWith("-") && line.endsWith(":")) {
                break
            }
            if (!inProxies) continue

            if (line.startsWith("- ")) {
                flushCurrent()
                current = mutableMapOf()
                parseClashKeyValue(line.removePrefix("- ").trim(), current)
            } else {
                parseClashKeyValue(line, current)
            }
        }
        flushCurrent()
        return result
    }

    private fun parseClashKeyValue(segment: String, target: MutableMap<String, String>) {
        // Supports inline `{ name: x, type: ss, server: a, port: 443 }` or `key: value`
        val cleaned = segment.removePrefix("{").removeSuffix("}")
        val parts = if (cleaned.contains(",") && segment.contains("{")) cleaned.split(",") else listOf(cleaned)
        for (part in parts) {
            val idx = part.indexOf(':')
            if (idx <= 0) continue
            val k = part.substring(0, idx).trim().lowercase()
            val v = part.substring(idx + 1).trim().trim('"', '\'')
            target[k] = v
        }
    }

    private fun parseUriLine(line: String): ImportedProfileEntry? {
        val scheme = line.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) return null
        return when (scheme) {
            "http", "https" -> parseStandardProxyUri(
                line,
                if (scheme == "https") TunnelProtocol.HTTPS_PROXY else TunnelProtocol.HTTP_PROXY
            )
            "socks5", "socks" -> parseStandardProxyUri(line, TunnelProtocol.SOCKS5)
            "ssh" -> parseSshUri(line)
            "vless" -> parseNativeUri(line, TunnelProtocol.VLESS)
            "trojan" -> parseNativeUri(line, TunnelProtocol.TROJAN)
            "ss" -> parseShadowsocksUri(line)
            "vmess" -> parseVmessUri(line)
            else -> null
        }
    }

    private fun extractValidatedPort(uri: URI, defaultPort: Int): Int? {
        if (uri.port != -1) {
            return if (uri.port in 1..65535) uri.port else null
        }
        val authority = uri.rawAuthority ?: return defaultPort
        val hostPart = authority.substringAfter('@')
        // If there's an explicit colon outside IPv6 brackets, a port=-1 means invalid/overflow port
        val afterBracket = if (hostPart.startsWith("[")) hostPart.substringAfter("]", "") else hostPart
        if (afterBracket.contains(':')) {
            val portStr = afterBracket.substringAfterLast(':')
            val parsed = portStr.toIntOrNull() ?: return null
            return if (parsed in 1..65535) parsed else null
        }
        return defaultPort
    }

    private fun parseStandardProxyUri(raw: String, protocol: TunnelProtocol): ImportedProfileEntry? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = extractValidatedPort(uri, protocol.defaultPort) ?: return null
        val userInfo = uri.rawUserInfo ?: ""
        val (user, pass) = splitUserPass(userInfo)
        val label = uri.fragment?.let { urlDecode(it) }?.takeIf { it.isNotBlank() }
            ?: "${protocol.badgeCode} $host"
        return ImportedProfileEntry(
            profile = TunnelProfile(
                name = label,
                protocol = protocol,
                host = host,
                port = port,
                username = user,
                tlsEnabled = protocol == TunnelProtocol.HTTPS_PROXY,
                verifyTlsCertificate = true,
                category = "Importado",
                tags = listOf(protocol.badgeCode)
            ),
            secrets = ProfileSecrets(password = pass)
        )
    }

    private fun parseSshUri(raw: String): ImportedProfileEntry? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = extractValidatedPort(uri, 22) ?: return null
        val (user, pass) = splitUserPass(uri.rawUserInfo ?: "")
        val query = parseQueryParams(uri.rawQuery)
        val jumpSpec = query["jump"]
        var jumpEnabled = false
        var jumpHost = ""
        var jumpPort = 22
        var jumpUser = ""
        if (!jumpSpec.isNullOrBlank()) {
            val atIdx = jumpSpec.indexOf('@')
            val hostPort = if (atIdx > 0) {
                jumpUser = jumpSpec.substring(0, atIdx)
                jumpSpec.substring(atIdx + 1)
            } else jumpSpec
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx > 0) {
                jumpHost = hostPort.substring(0, colonIdx)
                jumpPort = hostPort.substring(colonIdx + 1).toIntOrNull() ?: 22
            } else {
                jumpHost = hostPort
            }
            jumpEnabled = jumpHost.isNotBlank()
        }
        val authMethod = if (query["auth"] == "key") SshAuthMethod.PRIVATE_KEY else SshAuthMethod.PASSWORD
        val protocol = if (jumpEnabled) TunnelProtocol.SSH_JUMP else TunnelProtocol.SSH
        val label = uri.fragment?.let { urlDecode(it) }?.takeIf { it.isNotBlank() } ?: "SSH $host"
        return ImportedProfileEntry(
            profile = TunnelProfile(
                name = label,
                protocol = protocol,
                host = host,
                port = port,
                username = user,
                authMethod = authMethod,
                jumpHostEnabled = jumpEnabled,
                jumpHost = jumpHost,
                jumpPort = jumpPort,
                jumpUsername = jumpUser,
                category = "SSH",
                tags = listOf("SSH")
            ),
            secrets = ProfileSecrets(password = pass)
        )
    }

    private fun parseNativeUri(raw: String, protocol: TunnelProtocol): ImportedProfileEntry? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = extractValidatedPort(uri, protocol.defaultPort) ?: return null
        val query = parseQueryParams(uri.rawQuery)
        val label = uri.fragment?.let { urlDecode(it) }?.takeIf { it.isNotBlank() }
            ?: "${protocol.badgeCode} $host"
        val finalProtocol = if (protocol == TunnelProtocol.VLESS && query["security"] == "reality") {
            TunnelProtocol.REALITY
        } else protocol
        return ImportedProfileEntry(
            profile = TunnelProfile(
                name = label,
                protocol = finalProtocol,
                host = host,
                port = port,
                username = uri.userInfo.orEmpty(),
                tlsEnabled = query["security"] in listOf("tls", "reality"),
                tlsServerName = query["sni"].orEmpty(),
                transport = when (query["type"]?.lowercase()) {
                    "ws" -> TransportType.WEBSOCKET
                    "grpc" -> TransportType.GRPC
                    "quic" -> TransportType.QUIC
                    else -> TransportType.TCP
                },
                category = "Núcleo Nativo",
                tags = listOf(finalProtocol.badgeCode, "V2Ray-URI"),
                rawNativeConfigJson = raw
            ),
            secrets = ProfileSecrets()
        )
    }

    private fun parseShadowsocksUri(raw: String): ImportedProfileEntry? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port in 1..65535) uri.port else 8388
        val label = uri.fragment?.let { urlDecode(it) }?.takeIf { it.isNotBlank() } ?: "SS $host"
        return ImportedProfileEntry(
            profile = TunnelProfile(
                name = label,
                protocol = TunnelProtocol.SHADOWSOCKS,
                host = host,
                port = port,
                category = "Núcleo Nativo",
                tags = listOf("SS"),
                rawNativeConfigJson = raw
            ),
            secrets = ProfileSecrets()
        )
    }

    private fun parseVmessUri(raw: String): ImportedProfileEntry? {
        val encoded = raw.removePrefix("vmess://").trim()
        val decoded = runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        val obj = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return null
        val host = obj["add"]?.jsonPrimitive?.content ?: return null
        val port = obj["port"]?.jsonPrimitive?.intOrNull
            ?: obj["port"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return null
        val label = obj["ps"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "VMess $host"
        return ImportedProfileEntry(
            profile = TunnelProfile(
                name = label,
                protocol = TunnelProtocol.VMESS,
                host = host,
                port = port,
                tlsEnabled = obj["tls"]?.jsonPrimitive?.content == "tls",
                tlsServerName = obj["sni"]?.jsonPrimitive?.content.orEmpty(),
                category = "Núcleo Nativo",
                tags = listOf("VMESS"),
                rawNativeConfigJson = decoded
            ),
            secrets = ProfileSecrets()
        )
    }

    private fun validateImportedEntries(
        entries: List<ImportedProfileEntry>
    ): Result<List<ImportedProfileEntry>> {
        for (entry in entries) {
            val p = entry.profile
            if (p.host.isBlank() || p.host.any { it.isWhitespace() }) {
                return Result.failure(IllegalArgumentException("Host inválido en perfil '${p.name}'."))
            }
            if (p.port !in 1..65535) {
                return Result.failure(IllegalArgumentException("Puerto fuera de rango (1-65535) en '${p.name}'."))
            }
            if (p.mtu !in 1200..9000) {
                return Result.failure(IllegalArgumentException("MTU inválido (${p.mtu}) en '${p.name}'."))
            }
        }
        return Result.success(entries)
    }

    private fun splitUserPass(rawUserInfo: String): Pair<String, String> {
        if (rawUserInfo.isEmpty()) return "" to ""
        val idx = rawUserInfo.indexOf(':')
        return if (idx < 0) {
            urlDecode(rawUserInfo) to ""
        } else {
            urlDecode(rawUserInfo.substring(0, idx)) to urlDecode(rawUserInfo.substring(idx + 1))
        }
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else urlDecode(pair.substring(0, idx)) to urlDecode(pair.substring(idx + 1))
        }.toMap()
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
