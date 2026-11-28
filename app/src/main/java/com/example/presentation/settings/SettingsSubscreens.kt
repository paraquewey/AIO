package com.example.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.BuildConfig
import com.example.R
import com.example.core.network.LocalNetworkHelper
import com.example.data.security.SshHostKeyStore
import com.example.domain.models.AppSettings
import com.example.domain.models.DnsMode
import com.example.domain.models.NetworkPreset
import com.example.presentation.components.GlassCard
import com.example.presentation.components.TexturedNavContainer
import com.example.ui.theme.LocalAioUiStyle
import com.example.ui.theme.SpaceAmber
import com.example.ui.theme.SpaceCoral
import com.example.ui.theme.SpaceCyan
import com.example.ui.theme.SpaceEmerald

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSubscreenScaffold(
    title: String,
    onBack: () -> Unit,
    backTag: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = onBack)
    val uiStyle = LocalAioUiStyle.current

    Column(modifier = modifier.fillMaxSize()) {
        TexturedNavContainer(
            backgroundColor = uiStyle.cardBackgroundColor.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxWidth()
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag(backTag)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver a Ajustes",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkResilienceSettingsScreen(
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var timeoutInput by remember { mutableStateOf(settings.connectionTimeoutSeconds.toString()) }
    var timeoutError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.connectionTimeoutSeconds) {
        val currentParsed = timeoutInput.toIntOrNull()
        if (currentParsed != settings.connectionTimeoutSeconds && timeoutError == null) {
            timeoutInput = settings.connectionTimeoutSeconds.toString()
        }
    }

    val primaryAccent = MaterialTheme.colorScheme.primary

    SettingsSubscreenScaffold(
        title = "Red, Túnel y Resiliencia",
        onBack = onBack,
        backTag = "network_settings_back_button",
        modifier = modifier
    ) {
        GlassCard {
            Text(
                text = "Conexión y resiliencia",
                style = MaterialTheme.typography.titleMedium,
                color = primaryAccent
            )
            Spacer(Modifier.height(6.dp))

            SettingSwitchRow(
                title = "Seguir intentando conectarse (Reconexión automática)",
                subtitle = "Reintenta cada 2.5 s si la conexión falla o se cae inesperadamente",
                checked = settings.keepTryingToConnect,
                testTag = "settings_keep_trying_switch"
            ) { v -> onUpdateSettings { it.copy(keepTryingToConnect = v) } }

            SettingSwitchRow(
                title = "Reconexión automática al cambiar de red",
                subtitle = "Reinicia el enlace SSH cuando cambias entre Wi-Fi y datos móviles",
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
                        color = if (timeoutError != null) SpaceCoral else MaterialTheme.colorScheme.onSurfaceVariant
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
            ) { v -> onUpdateSettings { it.copy(bypassLan = v, networkPreset = NetworkPreset.CUSTOM) } }

            SettingSwitchRow(
                title = "Habilitar enrutamiento IPv6",
                checked = settings.ipv6Enabled,
                testTag = "settings_ipv6_switch"
            ) { v -> onUpdateSettings { it.copy(ipv6Enabled = v) } }
        }

        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Dns, contentDescription = null, tint = SpaceCyan)
                Spacer(Modifier.width(8.dp))
                Text("DNS predeterminado", style = MaterialTheme.typography.titleMedium, color = SpaceCyan)
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DnsMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.defaultDnsMode == mode,
                        onClick = {
                            onUpdateSettings {
                                it.copy(defaultDnsMode = mode, networkPreset = NetworkPreset.CUSTOM)
                            }
                        },
                        label = {
                            Text(
                                when (mode) {
                                    DnsMode.DNS_OVER_HTTPS -> "DoH"
                                    DnsMode.PLAIN_DNS -> "DNS UDP"
                                    DnsMode.SYSTEM_DEFAULT -> "Sistema"
                                }
                            )
                        },
                        modifier = Modifier.testTag("settings_dns_mode_${mode.name.lowercase()}")
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.defaultDohUrl,
                onValueChange = { url -> onUpdateSettings { it.copy(defaultDohUrl = url) } },
                label = { Text("Endpoint DNS-over-HTTPS (RFC 8484)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_doh_url_input")
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
    }
}

@Composable
fun SharingBackgroundSettingsScreen(
    settings: AppSettings,
    wifiProxyActivePort: Int?,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onShowMessage: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiStyle = LocalAioUiStyle.current

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

    SettingsSubscreenScaffold(
        title = "Compartir VPN y Segundo Plano",
        onBack = onBack,
        backTag = "sharing_settings_back_button",
        modifier = modifier
    ) {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WifiTethering, contentDescription = null, tint = SpaceCyan)
                Spacer(Modifier.width(8.dp))
                Text("Compartir VPN (Wi-Fi / Hotspot)", style = MaterialTheme.typography.titleMedium, color = SpaceCyan)
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
                        color = if (proxyPortError != null) SpaceCoral else MaterialTheme.colorScheme.onSurfaceVariant
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
            val proxyStatusColor = if (wifiProxyActivePort != null) SpaceEmerald else MaterialTheme.colorScheme.onSurfaceVariant

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = uiStyle.cardElevatedColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
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
                                },
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
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
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag("settings_copy_proxy_ip_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copiar IP y puerto proxy",
                                    tint = SpaceCyan
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
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copiar $fullAddr",
                                        tint = SpaceCyan,
                                        modifier = Modifier.size(18.dp)
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
                        tint = if (isIgnoringBatteryOpt) SpaceEmerald else SpaceAmber
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Ejecución en segundo plano",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = (if (isIgnoringBatteryOpt) SpaceEmerald else SpaceAmber).copy(alpha = 0.16f),
                    contentColor = if (isIgnoringBatteryOpt) SpaceEmerald else SpaceAmber,
                    border = BorderStroke(
                        1.dp,
                        (if (isIgnoringBatteryOpt) SpaceEmerald else SpaceAmber).copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        text = if (isIgnoringBatteryOpt) {
                            "Desactivada (Protegido)"
                        } else {
                            "Activa (Puede detenerse)"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isIgnoringBatteryOpt) SpaceEmerald else SpaceAmber,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("settings_battery_badge")
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            SettingSwitchRow(
                title = "WakeLock (Evitar suspensión de CPU)",
                subtitle = "Mantiene estable el túnel VPN y el Proxy Wi-Fi con la pantalla apagada",
                checked = settings.wakeLockEnabled,
                testTag = "settings_wakelock_switch"
            ) { v -> onUpdateSettings { it.copy(wakeLockEnabled = v) } }
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
                    contentColor = if (isIgnoringBatteryOpt) SpaceEmerald else SpaceAmber
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
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
    }
}

@Composable
fun SecuritySettingsScreen(
    settings: AppSettings,
    keystoreAvailable: Boolean,
    knownHosts: List<SshHostKeyStore.KnownHostRecord>,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onRemoveKnownHost: (String, Int) -> Unit,
    onClearKnownHosts: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiStyle = LocalAioUiStyle.current

    SettingsSubscreenScaffold(
        title = "Seguridad Criptográfica",
        onBack = onBack,
        backTag = "security_settings_back_button",
        modifier = modifier
    ) {
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
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = if (keystoreAvailable) SpaceEmerald else SpaceAmber
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Android Keystore (AES-256-GCM)",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = (if (keystoreAvailable) SpaceEmerald else SpaceAmber).copy(alpha = 0.16f),
                    border = BorderStroke(
                        1.dp,
                        (if (keystoreAvailable) SpaceEmerald else SpaceAmber).copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        text = if (keystoreAvailable) "Hardware OK" else "Fallback local",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (keystoreAvailable) SpaceEmerald else SpaceAmber,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("settings_keystore_status_badge")
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Todas las contraseñas SSH, llaves privadas PEM y frases de paso se cifran localmente en bóveda aislada mediante AES-256-GCM respaldado por Keystore.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = SpaceEmerald)
                Spacer(Modifier.width(8.dp))
                Text("Validación TLS y registros", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            SettingSwitchRow(
                title = "Exigir verificación TLS estricta",
                subtitle = "Valida certificados y hostname SNI en túneles TLS y consultas DoH",
                checked = settings.strictTlsVerification,
                testTag = "settings_strict_tls_switch"
            ) { v -> onUpdateSettings { it.copy(strictTlsVerification = v, networkPreset = NetworkPreset.CUSTOM) } }

            SettingSwitchRow(
                title = "Registros detallados de depuración (sin secretos)",
                subtitle = "Registra eventos técnicos adicionales en la consola omitiendo credenciales",
                checked = settings.verboseDebugLogs,
                testTag = "settings_verbose_logs_switch"
            ) { v -> onUpdateSettings { it.copy(verboseDebugLogs = v) } }
        }

        GlassCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = SpaceCyan)
                    Spacer(Modifier.width(8.dp))
                    Text("Claves de host SSH confiables", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = "${knownHosts.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = SpaceCyan
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Verificación TOFU (Trust On First Use) para protegerte contra ataques Man-in-the-Middle.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (knownHosts.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Aún no hay huellas SSH guardadas. Se solicitará confirmación en la primera conexión a cada servidor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    knownHosts.forEach { hostRecord ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = uiStyle.cardElevatedColor,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${hostRecord.endpoint} (${hostRecord.keyType})",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = hostRecord.sha256Fingerprint,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SpaceEmerald,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val parts = hostRecord.endpoint.split(":")
                                        val host = parts.dropLast(1).joinToString(":").ifBlank { hostRecord.endpoint }
                                        val port = parts.lastOrNull()?.toIntOrNull() ?: 22
                                        onRemoveKnownHost(host, port)
                                    },
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Revocar huella de ${hostRecord.endpoint}",
                                        tint = SpaceCoral
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onClearKnownHosts,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .testTag("settings_clear_known_hosts_button")
            ) {
                Text("Restablecer claves de host SSH confiables", color = SpaceCoral)
            }
        }
    }
}

@Composable
fun AboutAppSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryAccent = MaterialTheme.colorScheme.primary

    SettingsSubscreenScaffold(
        title = "Sobre la App",
        onBack = onBack,
        backTag = "about_settings_back_button",
        modifier = modifier
    ) {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = SpaceAmber)
                Spacer(Modifier.width(8.dp))
                Text("Privacidad y transparencia", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.privacy_notice_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "• Sin publicidad, sin rastreadores y sin telemetría.\n" +
                    "• Credenciales protegidas localmente con Android Keystore (AES-256-GCM).\n" +
                    "• Sin servidores ocultos: tú controlas todos tus endpoints, DNS y payloads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        GlassCard(borderColor = primaryAccent.copy(alpha = 0.45f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = primaryAccent)
                    Spacer(Modifier.width(8.dp))
                    Text("AIO Tunnel VPN", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = SpaceEmerald,
                    modifier = Modifier.testTag("about_version_label")
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Motor multiprotocolo nativo en Kotlin sobre VpnService:\n" +
                    "• Enrutamiento TUN de baja latencia con backpressure adaptativo.\n" +
                    "• Multiplexación SSH simultánea, DoH RFC 8484 con caché SWR y proxy Wi-Fi/Hotspot.\n" +
                    "• Interfaz 100% en español con motor visual Space Glass personalizable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    testTag: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.55f),
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
            enabled = enabled,
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
