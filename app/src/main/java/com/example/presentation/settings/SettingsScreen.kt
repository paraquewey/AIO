package com.example.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.core.network.LocalNetworkHelper
import com.example.domain.models.AppLanguage
import com.example.domain.models.AppSettings
import com.example.domain.models.DnsMode
import com.example.presentation.components.GlassCard
import com.example.ui.theme.GruvboxAqua
import com.example.ui.theme.GruvboxGreen
import com.example.ui.theme.GruvboxOrange
import com.example.ui.theme.GruvboxRed
import com.example.ui.theme.GruvboxYellow

@Composable
fun SettingsScreen(
    settings: AppSettings,
    wifiProxyActivePort: Int?,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onClearKnownHosts: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var timeoutInput by remember { mutableStateOf(settings.connectionTimeoutSeconds.toString()) }
    var timeoutError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.connectionTimeoutSeconds) {
        val currentParsed = timeoutInput.toIntOrNull()
        if (currentParsed != settings.connectionTimeoutSeconds && timeoutError == null) {
            timeoutInput = settings.connectionTimeoutSeconds.toString()
        }
    }

    var proxyPortInput by remember { mutableStateOf(settings.wifiProxySharePort.toString()) }
    var proxyPortError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.wifiProxySharePort) {
        val currentParsed = proxyPortInput.toIntOrNull()
        if (currentParsed != settings.wifiProxySharePort && proxyPortError == null) {
            proxyPortInput = settings.wifiProxySharePort.toString()
        }
    }

    var localInterfaceIps by remember { mutableStateOf(LocalNetworkHelper.getActiveInterfaceAddresses()) }
    var isIgnoringBatteryOpt by remember { mutableStateOf(checkIgnoringBatteryOptimizations(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOpt = checkIgnoringBatteryOptimizations(context)
                localInterfaceIps = LocalNetworkHelper.getActiveInterfaceAddresses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = modifier.fillMaxSize(),
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
            Text("Ajustes", style = MaterialTheme.typography.headlineMedium)

            // 1. Connection & Resilience (Auto-reconnect + Timeout)
            GlassCard {
                Text("Conexión y resiliencia", style = MaterialTheme.typography.titleMedium, color = GruvboxOrange)
                Spacer(Modifier.height(6.dp))

                SettingSwitchRow(
                    title = "Seguir intentando conectarse (Reconexión automática)",
                    subtitle = "Reintenta cada 2.5 s si la conexión falla o se cae inesperadamente",
                    checked = settings.keepTryingToConnect,
                    testTag = "settings_keep_trying_switch"
                ) { v -> onUpdateSettings { it.copy(keepTryingToConnect = v) } }

                SettingSwitchRow(
                    title = "Reconexión automática al cambiar de red",
                    checked = settings.autoReconnectOnNetworkChange,
                    testTag = "settings_auto_reconnect_switch"
                ) { v -> onUpdateSettings { it.copy(autoReconnectOnNetworkChange = v) } }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = timeoutInput,
                    onValueChange = { raw ->
                        val digitsOnly = raw.filter { it.isDigit() }.take(4)
                        timeoutInput = digitsOnly
                        val parsed = digitsOnly.toIntOrNull()
                        when {
                            digitsOnly.isEmpty() -> {
                                timeoutError = "Ingresa un valor entre 1 y 999 segundos."
                            }
                            parsed == null || parsed < 1 || parsed > 999 -> {
                                timeoutError = "Rango permitido: mínimo 1 s y máximo 999 s."
                            }
                            else -> {
                                timeoutError = null
                                onUpdateSettings { it.copy(connectionTimeoutSeconds = parsed) }
                            }
                        }
                    },
                    label = { Text("Tiempo de espera (Timeout en segundos, 1–999)") },
                    supportingText = {
                        Text(
                            text = timeoutError
                                ?: "Por defecto: 10 s · Aplicado a socket.connect y soTimeout (${settings.connectionTimeoutSeconds} s)",
                            color = if (timeoutError != null) GruvboxRed else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    isError = timeoutError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_timeout_input")
                )

                Spacer(Modifier.height(4.dp))
                SettingSwitchRow(
                    title = "Mostrar velocidad en notificación",
                    checked = settings.showSpeedInNotification,
                    testTag = "settings_notif_speed_switch"
                ) { v -> onUpdateSettings { it.copy(showSpeedInNotification = v) } }

                SettingSwitchRow(
                    title = "Permitir acceso a red local (Bypass LAN / CGNAT)",
                    subtitle = "Excluye 10.x, 172.16-31.x, 192.168.x y 100.64.0.0/10 del túnel VPN",
                    checked = settings.bypassLan,
                    testTag = "settings_bypass_lan_switch"
                ) { v -> onUpdateSettings { it.copy(bypassLan = v) } }

                SettingSwitchRow(
                    title = "Habilitar enrutamiento IPv6",
                    checked = settings.ipv6Enabled,
                    testTag = "settings_ipv6_switch"
                ) { v -> onUpdateSettings { it.copy(ipv6Enabled = v) } }
            }

            // 2. Wi-Fi / Hotspot Proxy Sharing (0.0.0.0:<port>)
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WifiTethering, contentDescription = null, tint = GruvboxAqua)
                    Spacer(Modifier.width(8.dp))
                    Text("Compartir VPN (Wi-Fi / Hotspot)", style = MaterialTheme.typography.titleMedium, color = GruvboxAqua)
                }
                Spacer(Modifier.height(6.dp))
                SettingSwitchRow(
                    title = "Compartir red (Proxy Wi-Fi/Hotspot)",
                    subtitle = "HTTP plano + HTTPS CONNECT + SOCKS5 en 0.0.0.0:${settings.wifiProxySharePort}",
                    checked = settings.wifiProxyShareEnabled,
                    testTag = "settings_wifi_proxy_switch"
                ) { enabled ->
                    localInterfaceIps = LocalNetworkHelper.getActiveInterfaceAddresses()
                    onUpdateSettings { it.copy(wifiProxyShareEnabled = enabled) }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = proxyPortInput,
                    onValueChange = { raw ->
                        val digitsOnly = raw.filter { it.isDigit() }.take(5)
                        proxyPortInput = digitsOnly
                        val parsed = digitsOnly.toIntOrNull()
                        when {
                            digitsOnly.isEmpty() -> {
                                proxyPortError = "Ingresa un puerto entre 1024 y 65535."
                            }
                            parsed == null || parsed !in 1024..65535 -> {
                                proxyPortError = "Puerto permitido: 1024 a 65535 (ej. 8080, 8181, 3128)."
                            }
                            else -> {
                                proxyPortError = null
                                onUpdateSettings { it.copy(wifiProxySharePort = parsed) }
                            }
                        }
                    },
                    label = { Text("Puerto del Proxy compartido") },
                    supportingText = {
                        Text(
                            text = proxyPortError
                                ?: "Por defecto: 8080 · Al cambiar se reinicia el proxy en el nuevo puerto",
                            color = if (proxyPortError != null) GruvboxRed else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    isError = proxyPortError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_wifi_proxy_port_input")
                )

                Spacer(Modifier.height(8.dp))
                val primaryIp = localInterfaceIps.firstOrNull()?.ipAddress
                val endpointText = if (primaryIp != null) {
                    "$primaryIp:${settings.wifiProxySharePort}"
                } else {
                    "0.0.0.0:${settings.wifiProxySharePort}"
                }
                val proxyStatusText = when {
                    wifiProxyActivePort != null -> "Activo y escuchando en 0.0.0.0:$wifiProxyActivePort"
                    settings.wifiProxyShareEnabled -> "Activado · Se iniciará al conectar el túnel VPN"
                    else -> "Inactivo · Activa el interruptor para compartir"
                }
                val proxyStatusColor = if (wifiProxyActivePort != null) GruvboxGreen else MaterialTheme.colorScheme.onSurfaceVariant

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, proxyStatusColor.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = proxyStatusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = proxyStatusColor
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "IP Principal y Puerto Proxy",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = endpointText,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("settings_wifi_proxy_address")
                                )
                            }
                            Row {
                                IconButton(
                                    onClick = {
                                        localInterfaceIps = LocalNetworkHelper.getActiveInterfaceAddresses()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Actualizar IPs locales"
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        copyToClipboard(context, "Proxy AIO Tunnel", endpointText)
                                        onShowMessage("Copiado: $endpointText")
                                    },
                                    modifier = Modifier.testTag("settings_copy_proxy_ip_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copiar IP y puerto proxy",
                                        tint = GruvboxAqua
                                    )
                                }
                            }
                        }

                        if (localInterfaceIps.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Interfaces activas disponibles:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            localInterfaceIps.forEach { iface ->
                                val fullAddr = "${iface.ipAddress}:${settings.wifiProxySharePort}"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${iface.categoryLabel} (${iface.interfaceName}): $fullAddr",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            copyToClipboard(context, "Proxy ${iface.categoryLabel}", fullAddr)
                                            onShowMessage("Copiado: $fullAddr")
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copiar $fullAddr",
                                            tint = GruvboxAqua,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Activa Wi-Fi, Hotspot o Tethering USB para detectar IPs (192.168.x.x / 100.x.x.x)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 3. Battery Optimization (Background Execution)
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = null,
                            tint = if (isIgnoringBatteryOpt) GruvboxGreen else GruvboxOrange
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Ejecución en segundo plano",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = (if (isIgnoringBatteryOpt) GruvboxGreen else GruvboxOrange).copy(alpha = 0.16f),
                        border = BorderStroke(
                            1.dp,
                            (if (isIgnoringBatteryOpt) GruvboxGreen else GruvboxOrange).copy(alpha = 0.6f)
                        )
                    ) {
                        Text(
                            text = if (isIgnoringBatteryOpt) {
                                "Desactivada (Segundo plano protegido)"
                            } else {
                                "Activa (Puede detenerse)"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isIgnoringBatteryOpt) GruvboxGreen else GruvboxOrange,
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .testTag("settings_battery_badge")
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Evita que Android suspenda el servicio VPN y el Proxy Wi-Fi cuando la pantalla está apagada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        launchBatteryOptimizationSettings(context)
                        isIgnoringBatteryOpt = checkIgnoringBatteryOptimizations(context)
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isIgnoringBatteryOpt) GruvboxGreen else GruvboxOrange
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_battery_optimization_button")
                ) {
                    Text(
                        if (isIgnoringBatteryOpt) {
                            "Revisar optimización de batería"
                        } else {
                            "Permitir ejecución sin restricciones"
                        }
                    )
                }
            }

            // Default DNS
            GlassCard {
                Text("DNS predeterminado", style = MaterialTheme.typography.titleMedium, color = GruvboxAqua)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DnsMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.defaultDnsMode == mode,
                            onClick = { onUpdateSettings { it.copy(defaultDnsMode = mode) } },
                            label = {
                                Text(
                                    when (mode) {
                                        DnsMode.DNS_OVER_HTTPS -> "DoH"
                                        DnsMode.PLAIN_DNS -> "DNS UDP"
                                        DnsMode.SYSTEM_DEFAULT -> "Sistema"
                                    }
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.defaultDohUrl,
                    onValueChange = { url -> onUpdateSettings { it.copy(defaultDohUrl = url) } },
                    label = { Text("Endpoint DNS-over-HTTPS") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.defaultDnsServer,
                    onValueChange = { ip -> onUpdateSettings { it.copy(defaultDnsServer = ip) } },
                    label = { Text("Servidor DNS 1 (Primario)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_dns1_input")
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.defaultDnsServerSecondary,
                    onValueChange = { ip -> onUpdateSettings { it.copy(defaultDnsServerSecondary = ip) } },
                    label = { Text("Servidor DNS 2 (Secundario)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_dns2_input")
                )
            }

            // Appearance & Language
            GlassCard {
                Text("Apariencia e idioma", style = MaterialTheme.typography.titleMedium, color = GruvboxYellow)
                Spacer(Modifier.height(6.dp))
                SettingSwitchRow(
                    title = "Tema claro cálido Gruvbox (desactivado = AMOLED puro)",
                    checked = settings.useLightTheme,
                    testTag = "settings_theme_switch"
                ) { v -> onUpdateSettings { it.copy(useLightTheme = v) } }

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = settings.language == lang,
                            onClick = { onUpdateSettings { it.copy(language = lang) } },
                            label = { Text(lang.label) }
                        )
                    }
                }
            }

            // Security & Logs
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = GruvboxGreen)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Seguridad criptográfica", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(6.dp))
                SettingSwitchRow(
                    title = "Exigir verificación TLS estricta",
                    checked = settings.strictTlsVerification,
                    testTag = "settings_strict_tls_switch"
                ) { v -> onUpdateSettings { it.copy(strictTlsVerification = v) } }

                SettingSwitchRow(
                    title = "Registros detallados de depuración (sin secretos)",
                    checked = settings.verboseDebugLogs,
                    testTag = "settings_verbose_logs_switch"
                ) { v -> onUpdateSettings { it.copy(verboseDebugLogs = v) } }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onClearKnownHosts,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Restablecer claves de host SSH confiables", color = GruvboxRed)
                }
            }

            // Mandatory Privacy Transparency Section
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = GruvboxYellow)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Privacidad y transparencia", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.privacy_notice_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "• Sin publicidad, sin rastreadores y sin telemetría.\n• Credenciales protegidas con Android Keystore (AES-256-GCM).\n• Sin servidores ocultos: tú controlas todos tus endpoints.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    testTag: String,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

private fun checkIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
}

private fun launchBatteryOptimizationSettings(context: Context) {
    val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val launched = runCatching {
        context.startActivity(requestIntent)
        true
    }.getOrDefault(false)

    if (!launched) {
        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(fallbackIntent) }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cb.setPrimaryClip(ClipData.newPlainText(label, text))
}
