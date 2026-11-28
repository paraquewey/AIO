package com.example.presentation.configs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.domain.models.AppSettings
import com.example.domain.models.DnsMode
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.ServerMessageMode
import com.example.domain.models.SplitTunnelMode
import com.example.domain.models.SshAuthMethod
import com.example.domain.models.TransportType
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import com.example.presentation.components.GlassCard
import com.example.presentation.state.InstalledAppInfo
import com.example.vpn.tun.DnsManager
import com.example.ui.theme.SpaceAmber
import com.example.ui.theme.SpaceCoral
import com.example.ui.theme.SpaceCyan
import com.example.ui.theme.SpaceEmerald
import com.example.ui.theme.SpaceViolet
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorScreen(
    existingProfile: TunnelProfile?,
    existingSecrets: ProfileSecrets,
    appSettings: AppSettings = AppSettings(),
    installedApps: List<InstalledAppInfo>,
    onLoadInstalledApps: () -> Unit,
    onSave: (TunnelProfile, ProfileSecrets) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) { onLoadInstalledApps() }

    val colorChoices = listOf(0xFF7B61FFL, 0xFF00E5A8L, 0xFF38BDF8L, 0xFFFFB800L, 0xFFFF4D6DL, 0xFFA78BFAL)

    var name by remember { mutableStateOf(existingProfile?.name ?: "") }
    var category by remember { mutableStateOf(existingProfile?.category ?: "General") }
    var tagsRaw by remember { mutableStateOf(existingProfile?.tags?.joinToString(", ") ?: "") }
    var colorTagHex by remember { mutableLongStateOf(existingProfile?.colorTagHex ?: 0xFF7B61FFL) }
    var protocol by remember { mutableStateOf(existingProfile?.protocol ?: TunnelProtocol.HTTPS_PROXY) }
    var host by remember { mutableStateOf(existingProfile?.host ?: "") }
    var portStr by remember { mutableStateOf((existingProfile?.port ?: TunnelProtocol.HTTPS_PROXY.defaultPort).toString()) }
    var username by remember { mutableStateOf(existingProfile?.username ?: "") }
    var password by remember { mutableStateOf(existingSecrets.password) }
    var authMethod by remember { mutableStateOf(existingProfile?.authMethod ?: SshAuthMethod.PASSWORD) }
    var privateKeyPem by remember { mutableStateOf(existingSecrets.privateKeyPem) }
    var keyPassphrase by remember { mutableStateOf(existingSecrets.keyPassphrase) }

    var jumpEnabled by remember { mutableStateOf(existingProfile?.jumpHostEnabled ?: false) }
    var jumpHost by remember { mutableStateOf(existingProfile?.jumpHost ?: "") }
    var jumpPortStr by remember { mutableStateOf((existingProfile?.jumpPort ?: 22).toString()) }
    var jumpUser by remember { mutableStateOf(existingProfile?.jumpUsername ?: "") }
    var jumpAuthMethod by remember { mutableStateOf(existingProfile?.jumpAuthMethod ?: SshAuthMethod.PASSWORD) }
    var jumpPassword by remember { mutableStateOf(existingSecrets.jumpPassword) }
    var jumpKeyPem by remember { mutableStateOf(existingSecrets.jumpPrivateKeyPem) }
    var udpgwPortStr by remember { mutableStateOf((existingProfile?.udpgwPort ?: 7300).toString()) }

    var transport by remember { mutableStateOf(existingProfile?.transport ?: TransportType.TCP) }
    var tlsEnabled by remember {
        mutableStateOf(existingProfile?.tlsEnabled ?: (protocol == TunnelProtocol.HTTPS_PROXY))
    }
    var tlsSni by remember { mutableStateOf(existingProfile?.tlsServerName ?: "") }
    var verifyTls by remember { mutableStateOf(existingProfile?.verifyTlsCertificate ?: true) }
    var rawHttpPayload by remember { mutableStateOf(existingProfile?.rawHttpPayload ?: "") }

    var dnsMode by remember { mutableStateOf(existingProfile?.dnsMode ?: appSettings.defaultDnsMode) }
    var dnsServer by remember {
        mutableStateOf(
            existingProfile?.let {
                if (it.useCustomDns || it.dnsServer != DnsManager.DEFAULT_PRIMARY_DNS) it.dnsServer
                else appSettings.defaultDnsServer
            } ?: appSettings.defaultDnsServer
        )
    }
    var dnsServerSecondary by remember {
        mutableStateOf(
            existingProfile?.let {
                if (it.useCustomDns || it.dnsServerSecondary != DnsManager.DEFAULT_SECONDARY_DNS) it.dnsServerSecondary
                else appSettings.defaultDnsServerSecondary
            } ?: appSettings.defaultDnsServerSecondary
        )
    }
    var dohUrl by remember {
        mutableStateOf(
            existingProfile?.let {
                if (it.useCustomDns || it.dohUrl != DnsManager.DEFAULT_DOH_URL) it.dohUrl
                else appSettings.defaultDohUrl
            } ?: appSettings.defaultDohUrl
        )
    }
    var enableIpv6 by remember { mutableStateOf(existingProfile?.enableIpv6 ?: false) }
    var bypassLan by remember { mutableStateOf(existingProfile?.bypassLan ?: true) }
    var mtuStr by remember { mutableStateOf((existingProfile?.mtu ?: 1500).toString()) }
    var splitMode by remember { mutableStateOf(existingProfile?.splitTunnelMode ?: SplitTunnelMode.ALL_APPS) }
    val selectedPackages = remember {
        mutableStateListOf<String>().apply {
            addAll(existingProfile?.splitTunnelPackages ?: emptyList())
        }
    }
    var serverMessageMode by remember {
        mutableStateOf(existingProfile?.serverMessageMode ?: ServerMessageMode.ORIGINAL)
    }
    var customServerMessageHtml by remember {
        mutableStateOf(existingProfile?.customServerMessageHtml ?: "")
    }
    var showAppPicker by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingProfile == null) "Nuevo perfil" else "Editar perfil") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("editor_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            val parsedPort = portStr.toIntOrNull()
                            val parsedMtu = mtuStr.toIntOrNull() ?: 1500
                            val parsedJumpPort = jumpPortStr.toIntOrNull() ?: 22
                            val parsedUdpgwPort = if (udpgwPortStr.isBlank()) 7300 else udpgwPortStr.toIntOrNull()
                            if (parsedUdpgwPort == null || parsedUdpgwPort !in 0..65535) {
                                validationError = "El puerto UDPGW debe ser 0 (desactivado) o entre 1 y 65535."
                                return@Button
                            }
                            if (name.isBlank()) {
                                validationError = "Asigna un nombre al perfil."
                                return@Button
                            }
                            if (host.isBlank() || host.any { it.isWhitespace() }) {
                                validationError = "Introduce un host o IP válido."
                                return@Button
                            }
                            if (parsedPort == null || parsedPort !in 1..65535) {
                                validationError = "El puerto debe estar entre 1 y 65535."
                                return@Button
                            }
                            if (parsedMtu !in 1200..9000) {
                                validationError = "El MTU debe estar entre 1200 y 9000."
                                return@Button
                            }
                            val tags = tagsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            val cleanDns1 = dnsServer.trim().ifBlank { appSettings.defaultDnsServer.ifBlank { "1.1.1.1" } }
                            val cleanDns2 = dnsServerSecondary.trim().ifBlank { appSettings.defaultDnsServerSecondary.ifBlank { "1.0.0.1" } }
                            if (!DnsManager.isValidIpv4(cleanDns1)) {
                                validationError = "Servidor DNS 1 inválido (debe ser una IPv4 válida)."
                                return@Button
                            }
                            if (!DnsManager.isValidIpv4(cleanDns2)) {
                                validationError = "Servidor DNS 2 inválido (debe ser una IPv4 válida)."
                                return@Button
                            }
                            val cleanDoh = dohUrl.trim().ifBlank { appSettings.defaultDohUrl.ifBlank { "https://cloudflare-dns.com/dns-query" } }
                            val isCustomDns = (existingProfile?.useCustomDns == true) ||
                                cleanDns1 != appSettings.defaultDnsServer ||
                                cleanDns2 != appSettings.defaultDnsServerSecondary ||
                                cleanDoh != appSettings.defaultDohUrl
                            val hasWebSocketUpgrade = rawHttpPayload.contains("upgrade:", ignoreCase = true) &&
                                rawHttpPayload.contains("websocket", ignoreCase = true)
                            val profile = TunnelProfile(
                                id = existingProfile?.id ?: UUID.randomUUID().toString(),
                                name = name.trim(),
                                protocol = protocol,
                                host = host.trim(),
                                port = parsedPort,
                                username = username.trim(),
                                authMethod = authMethod,
                                jumpHostEnabled = jumpEnabled || protocol == TunnelProtocol.SSH_JUMP,
                                jumpHost = jumpHost.trim(),
                                jumpPort = parsedJumpPort,
                                jumpUsername = jumpUser.trim(),
                                jumpAuthMethod = jumpAuthMethod,
                                udpgwPort = parsedUdpgwPort,
                                transport = if (hasWebSocketUpgrade) TransportType.WEBSOCKET else transport,
                                tlsEnabled = tlsEnabled || protocol == TunnelProtocol.HTTPS_PROXY,
                                tlsServerName = tlsSni.trim(),
                                verifyTlsCertificate = verifyTls,
                                rawHttpPayload = rawHttpPayload,
                                dnsMode = dnsMode,
                                useCustomDns = isCustomDns,
                                dnsServer = cleanDns1,
                                dnsServerSecondary = cleanDns2,
                                dohUrl = cleanDoh,
                                enableIpv6 = enableIpv6,
                                bypassLan = bypassLan,
                                mtu = parsedMtu,
                                splitTunnelMode = splitMode,
                                splitTunnelPackages = selectedPackages.toList(),
                                serverMessageMode = serverMessageMode,
                                customServerMessageHtml = customServerMessageHtml,
                                category = category.trim().ifBlank { "General" },
                                colorTagHex = colorTagHex,
                                tags = tags,
                                isFavorite = existingProfile?.isFavorite ?: false,
                                lastLatencyMs = existingProfile?.lastLatencyMs,
                                createdAtMillis = existingProfile?.createdAtMillis ?: System.currentTimeMillis()
                            )
                            val secrets = ProfileSecrets(
                                password = password,
                                privateKeyPem = privateKeyPem,
                                keyPassphrase = keyPassphrase,
                                jumpPassword = jumpPassword,
                                jumpPrivateKeyPem = jumpKeyPem
                            )
                            onSave(profile, secrets)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SpaceEmerald),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("editor_save_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Guardar")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                validationError?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // Identity & Category
                GlassCard {
                    Text("Identificación", style = MaterialTheme.typography.titleMedium, color = SpaceAmber)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nombre del perfil") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_name_input")
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it },
                            label = { Text("Categoría") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = tagsRaw,
                            onValueChange = { tagsRaw = it },
                            label = { Text("Etiquetas (coma)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Color:", style = MaterialTheme.typography.bodySmall)
                        colorChoices.forEach { hex ->
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(hex))
                                    .clickable { colorTagHex = hex }
                                    .padding(3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (colorTagHex == hex) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Color seleccionado",
                                        tint = Color.Black,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Protocol Selection (Phase A built-in vs Phase C adapter)
                GlassCard {
                    Text("Protocolo de túnel", style = MaterialTheme.typography.titleMedium, color = SpaceViolet)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Motores integrados listos: HTTP, HTTPS, SOCKS5, SSH y SSH Jump Host.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TunnelProtocol.entries.forEach { item ->
                            FilterChip(
                                selected = protocol == item,
                                onClick = {
                                    val oldDefault = protocol.defaultPort.toString()
                                    protocol = item
                                    if (portStr == oldDefault || portStr.isBlank()) {
                                        portStr = item.defaultPort.toString()
                                    }
                                    if (item == TunnelProtocol.HTTPS_PROXY) tlsEnabled = true
                                    if (item == TunnelProtocol.SSH_JUMP) jumpEnabled = true
                                },
                                label = { Text(item.badgeCode) }
                            )
                        }
                    }
                    if (!protocol.isBuiltInEngine && protocol.requirementNote != null) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = SpaceAmber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, SpaceAmber.copy(alpha = 0.5f))
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = SpaceAmber, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = protocol.requirementNote!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpaceAmber
                                )
                            }
                        }
                    }
                }

                // Endpoint & Auth
                GlassCard {
                    Text("Servidor y credenciales", style = MaterialTheme.typography.titleMedium, color = SpaceEmerald)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Host o IP") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(2f)
                                .testTag("editor_host_input")
                        )
                        OutlinedTextField(
                            value = portStr,
                            onValueChange = { portStr = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Puerto") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("editor_port_input")
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Usuario (opcional en proxy abierto)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (protocol == TunnelProtocol.SSH || protocol == TunnelProtocol.SSH_JUMP) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = authMethod == SshAuthMethod.PASSWORD,
                                onClick = { authMethod = SshAuthMethod.PASSWORD },
                                label = { Text("Contraseña") }
                            )
                            FilterChip(
                                selected = authMethod == SshAuthMethod.PRIVATE_KEY,
                                onClick = { authMethod = SshAuthMethod.PRIVATE_KEY },
                                label = { Text("Clave privada PEM") }
                            )
                        }
                    }

                    if (authMethod == SshAuthMethod.PASSWORD || (protocol != TunnelProtocol.SSH && protocol != TunnelProtocol.SSH_JUMP)) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Contraseña (cifrada con Keystore AES-GCM)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = privateKeyPem,
                            onValueChange = { privateKeyPem = it },
                            label = { Text("Clave privada OpenSSH / PEM") },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = keyPassphrase,
                            onValueChange = { keyPassphrase = it },
                            label = { Text("Passphrase de clave (opcional)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (protocol == TunnelProtocol.SSH || protocol == TunnelProtocol.SSH_JUMP) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = udpgwPortStr,
                            onValueChange = { udpgwPortStr = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Puerto UDPGW (BadVPN, por defecto 7300; 0 = desactivar)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_udpgw_port_input")
                        )
                    }
                }

                // Jump Host (SSH Multi-hop)
                if (protocol == TunnelProtocol.SSH || protocol == TunnelProtocol.SSH_JUMP) {
                    GlassCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Jump Host SSH (Multi-salto)", style = MaterialTheme.typography.titleMedium)
                            Switch(
                                checked = jumpEnabled || protocol == TunnelProtocol.SSH_JUMP,
                                onCheckedChange = { jumpEnabled = it }
                            )
                        }
                        if (jumpEnabled || protocol == TunnelProtocol.SSH_JUMP) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = jumpHost,
                                    onValueChange = { jumpHost = it },
                                    label = { Text("Host Jump") },
                                    singleLine = true,
                                    modifier = Modifier.weight(2f)
                                )
                                OutlinedTextField(
                                    value = jumpPortStr,
                                    onValueChange = { jumpPortStr = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("Puerto") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = jumpUser,
                                onValueChange = { jumpUser = it },
                                label = { Text("Usuario Jump Host") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = jumpPassword,
                                onValueChange = { jumpPassword = it },
                                label = { Text("Contraseña Jump Host") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // TLS, Transport & Raw HTTP / WebSocket Payload
                GlassCard {
                    Text("TLS, transporte y Payload", style = MaterialTheme.typography.titleMedium, color = SpaceCyan)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Capa TLS / HTTPS")
                        Switch(checked = tlsEnabled, onCheckedChange = { tlsEnabled = it })
                    }
                    if (tlsEnabled || protocol == TunnelProtocol.HTTPS_PROXY) {
                        OutlinedTextField(
                            value = tlsSni,
                            onValueChange = { tlsSni = it },
                            label = { Text("SNI / Dominio de certificado (opcional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = verifyTls, onCheckedChange = { verifyTls = it })
                            Text(
                                text = "Verificar certificado, dominio y vigencia TLS (recomendado)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (verifyTls) MaterialTheme.colorScheme.onSurface else SpaceCoral
                            )
                        }
                    }
                    if (protocol == TunnelProtocol.HTTP_PROXY ||
                        protocol == TunnelProtocol.HTTPS_PROXY ||
                        protocol == TunnelProtocol.SSH ||
                        protocol == TunnelProtocol.SSH_JUMP
                    ) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = rawHttpPayload,
                            onValueChange = { rawHttpPayload = it },
                            label = { Text("Payload HTTP / WebSocket (Crudo)") },
                            placeholder = {
                                Text("Ej: GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf][split]")
                            },
                            supportingText = {
                                Text(
                                    "Acepta peticiones múltiples y macros: [crlf] [lf] [cr] [lfcr] [host] [port] [host_port] [ip] [ssh] [raw] [real_raw] [netData] [auth] [ua] [rotate=a;b] [random=a;b] [split] [instant_split] [splitNoDelay] [delay_split] [split_delay=X]",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            singleLine = false,
                            minLines = 6,
                            maxLines = 14,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_raw_payload_input")
                        )
                    }
                }

                // DNS & TUN Routing & Split Tunneling
                GlassCard {
                    Text("DNS, enrutamiento y Split Tunneling", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DnsMode.entries.forEach { mode ->
                            FilterChip(
                                selected = dnsMode == mode,
                                onClick = { dnsMode = mode },
                                label = {
                                    Text(
                                        when (mode) {
                                            DnsMode.DNS_OVER_HTTPS -> "DoH"
                                            DnsMode.PLAIN_DNS -> "DNS TCP"
                                            DnsMode.SYSTEM_DEFAULT -> "Sistema"
                                        }
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (dnsMode == DnsMode.DNS_OVER_HTTPS) {
                        OutlinedTextField(
                            value = dohUrl,
                            onValueChange = { dohUrl = it },
                            label = { Text("URL DNS-over-HTTPS (RFC 8484)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = dnsServer,
                        onValueChange = { dnsServer = it },
                        label = { Text("Servidor DNS 1 (Primario)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_dns1_input")
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dnsServerSecondary,
                        onValueChange = { dnsServerSecondary = it },
                        label = { Text("Servidor DNS 2 (Secundario)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_dns2_input")
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = mtuStr,
                        onValueChange = { mtuStr = it.filter { ch -> ch.isDigit() } },
                        label = { Text("MTU de interfaz TUN (1280-9000)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Enrutar IPv6 cuando sea compatible")
                        Switch(checked = enableIpv6, onCheckedChange = { enableIpv6 = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Split Tunneling por aplicación")
                        OutlinedButton(onClick = { showAppPicker = true }) {
                            Text("${selectedPackages.size} apps")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SplitTunnelMode.entries.forEach { mode ->
                            FilterChip(
                                selected = splitMode == mode,
                                onClick = { splitMode = mode },
                                label = {
                                    Text(
                                        when (mode) {
                                            SplitTunnelMode.ALL_APPS -> "Todas"
                                            SplitTunnelMode.INCLUDE_SELECTED -> "Solo elegidas"
                                            SplitTunnelMode.EXCLUDE_SELECTED -> "Excluir elegidas"
                                        }
                                    )
                                }
                            )
                        }
                    }
                }

                // Custom Server Message (HTML / CSS / JS)
                GlassCard {
                    Text("Mensaje del Servidor (Server Message)", style = MaterialTheme.typography.titleMedium, color = SpaceViolet)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Controla cómo se muestra el banner al conectarse en la consola de registros.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ServerMessageMode.entries.forEach { mode ->
                            val chipTag = when (mode) {
                                ServerMessageMode.ORIGINAL -> "editor_server_msg_mode_original"
                                ServerMessageMode.HIDDEN -> "editor_server_msg_mode_hidden"
                                ServerMessageMode.CUSTOM_HTML -> "editor_server_msg_mode_custom"
                            }
                            FilterChip(
                                selected = serverMessageMode == mode,
                                onClick = { serverMessageMode = mode },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag(chipTag)
                            )
                        }
                    }
                    if (serverMessageMode == ServerMessageMode.CUSTOM_HTML) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = customServerMessageHtml,
                            onValueChange = { customServerMessageHtml = it },
                            label = { Text("Código HTML / CSS del mensaje") },
                            placeholder = {
                                Text("<style>@keyframes pulse{0%{opacity:.6}50%{opacity:1}100%{opacity:.6}}</style><div style=\"animation:pulse 2s infinite;color:#B8BB26;\">Servidor conectado</div>")
                            },
                            supportingText = {
                                Text(
                                    "Admite HTML, estilos <style> y animaciones CSS @keyframes en WebView sin acceso externo a red.",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            singleLine = false,
                            minLines = 5,
                            maxLines = 12,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_server_msg_html_input")
                        )
                    }
                }
            }
        }
    }

    if (showAppPicker) {
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = { Text("Split Tunneling (${selectedPackages.size})") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (installedApps.isEmpty()) {
                        Text("No se detectaron aplicaciones instaladas en este entorno.")
                    } else {
                        installedApps.forEach { appInfo ->
                            val checked = appInfo.packageName in selectedPackages
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (checked) selectedPackages.remove(appInfo.packageName)
                                        else selectedPackages.add(appInfo.packageName)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        if (isChecked) {
                                            if (appInfo.packageName !in selectedPackages) selectedPackages.add(appInfo.packageName)
                                        } else selectedPackages.remove(appInfo.packageName)
                                    }
                                )
                                Column {
                                    Text(appInfo.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        appInfo.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) { Text("Listo") }
            }
        )
    }
}
