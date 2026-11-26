package com.example.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
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
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onClearKnownHosts: () -> Unit,
    modifier: Modifier = Modifier
) {
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

            // Connection & Resilience
            GlassCard {
                Text("Conexión y resiliencia", style = MaterialTheme.typography.titleMedium, color = GruvboxOrange)
                Spacer(Modifier.height(6.dp))
                SettingSwitchRow(
                    title = "Reconexión automática al cambiar de red",
                    checked = settings.autoReconnectOnNetworkChange,
                    testTag = "settings_auto_reconnect_switch"
                ) { v -> onUpdateSettings { it.copy(autoReconnectOnNetworkChange = v) } }

                SettingSwitchRow(
                    title = "Mostrar velocidad en notificación",
                    checked = settings.showSpeedInNotification,
                    testTag = "settings_notif_speed_switch"
                ) { v -> onUpdateSettings { it.copy(showSpeedInNotification = v) } }

                SettingSwitchRow(
                    title = "Omitir redes locales (LAN Bypass)",
                    checked = settings.bypassLan,
                    testTag = "settings_bypass_lan_switch"
                ) { v -> onUpdateSettings { it.copy(bypassLan = v) } }

                SettingSwitchRow(
                    title = "Habilitar enrutamiento IPv6",
                    checked = settings.ipv6Enabled,
                    testTag = "settings_ipv6_switch"
                ) { v -> onUpdateSettings { it.copy(ipv6Enabled = v) } }
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
                    label = { Text("Servidor DNS IPv4") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
