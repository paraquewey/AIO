package com.example.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.network.AdaptiveTuningResult
import com.example.data.preferences.SettingsDataStore
import com.example.domain.models.AppSettings
import com.example.domain.models.DnsMode
import com.example.domain.models.NetworkPreset
import com.example.presentation.components.GlassCard
import com.example.ui.theme.LocalAioUiStyle
import com.example.ui.theme.SpaceAmber
import com.example.ui.theme.SpaceCyan
import com.example.ui.theme.SpaceEmerald
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    wifiProxyActivePort: Int?,
    adaptiveTuning: AdaptiveTuningResult?,
    knownHostsCount: Int,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onOpenUiCustomization: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onOpenSharingBackgroundSettings: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onOpenAboutSettings: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var advancedExpanded by remember { mutableStateOf(false) }
    val primaryAccent = MaterialTheme.colorScheme.primary
    val uiStyle = LocalAioUiStyle.current

    val effectiveConfig = if (settings.adaptiveAutoTuningEnabled && adaptiveTuning != null) {
        adaptiveTuning.effectiveConfig
    } else {
        settings.toEngineTuningConfig()
    }
    val manualEnabled = !settings.adaptiveAutoTuningEnabled

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Ajustes", style = MaterialTheme.typography.headlineMedium)

            // 1. UI Customization
            val bgSummary = if (!settings.customBackgroundPath.isNullOrBlank()) "Fondo propio" else "Fondo Space"
            SettingsNavigationCard(
                title = "Personalización de Interfaz",
                subtitle = "Fuente: ${settings.fontColorMode.label} · $bgSummary · Texturas y .aiotheme",
                icon = Icons.Default.Palette,
                accentColor = primaryAccent,
                testTag = "settings_ui_customization_card",
                onClick = onOpenUiCustomization
            )

            // 2. Network, Tunnel & Resilience
            val dnsLabel = when (settings.defaultDnsMode) {
                DnsMode.DNS_OVER_HTTPS -> "DoH"
                DnsMode.PLAIN_DNS -> "DNS UDP"
                DnsMode.SYSTEM_DEFAULT -> "Sistema"
            }
            val lanLabel = if (settings.bypassLan) "Bypass LAN" else "Sin Bypass"
            SettingsNavigationCard(
                title = "Red, Túnel y Resiliencia",
                subtitle = "Timeout ${settings.connectionTimeoutSeconds}s · $dnsLabel (${settings.defaultDnsServer}) · $lanLabel",
                icon = Icons.Default.Router,
                accentColor = SpaceCyan,
                testTag = "settings_card_network",
                onClick = onOpenNetworkSettings
            )

            // 3. VPN Sharing & Background Execution
            val proxySummary = when {
                wifiProxyActivePort != null -> "Proxy activo :$wifiProxyActivePort"
                settings.wifiProxyShareEnabled -> "Proxy :${settings.wifiProxySharePort}"
                else -> "Proxy apagado"
            }
            val wakeSummary = if (settings.wakeLockEnabled) "WakeLock activo" else "WakeLock inactivo"
            SettingsNavigationCard(
                title = "Compartir VPN y Segundo Plano",
                subtitle = "$proxySummary · $wakeSummary y batería",
                icon = Icons.Default.WifiTethering,
                accentColor = SpaceEmerald,
                testTag = "settings_card_sharing_bg",
                onClick = onOpenSharingBackgroundSettings
            )

            // 4. Cryptographic Security
            val tlsSummary = if (settings.strictTlsVerification) "TLS estricto" else "TLS flexible"
            SettingsNavigationCard(
                title = "Seguridad Criptográfica",
                subtitle = "Keystore AES-256-GCM · $tlsSummary · $knownHostsCount clave(s) SSH",
                icon = Icons.Default.Security,
                accentColor = SpaceEmerald,
                testTag = "settings_card_security",
                onClick = onOpenSecuritySettings
            )

            // 5. About & Privacy
            SettingsNavigationCard(
                title = "Sobre la App",
                subtitle = "Privacidad, transparencia local y arquitectura técnica",
                icon = Icons.Default.Info,
                accentColor = SpaceAmber,
                testTag = "settings_card_about",
                onClick = onOpenAboutSettings
            )

            // Expandable Advanced Engine & Adaptive Auto-Tuning Drawer
            GlassCard(borderColor = primaryAccent.copy(alpha = 0.45f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { advancedExpanded = !advancedExpanded }
                        .testTag("settings_advanced_drawer_header"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = primaryAccent)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Ajustes Avanzados del Motor y Rendimiento",
                                style = MaterialTheme.typography.titleMedium
                            )
                            val modeTag = if (settings.adaptiveAutoTuningEnabled) "Adaptativo" else "Manual"
                            Text(
                                text = "Perfil actual: ${settings.networkPreset.label} ($modeTag)",
                                style = MaterialTheme.typography.labelSmall,
                                color = SpaceEmerald
                            )
                        }
                    }
                    Icon(
                        imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (advancedExpanded) "Contraer ajustes avanzados" else "Expandir ajustes avanzados",
                        tint = primaryAccent
                    )
                }

                AnimatedVisibility(visible = advancedExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                        // Main Smart Adaptive Auto-Tuning Toggle
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (settings.adaptiveAutoTuningEnabled) {
                                SpaceEmerald.copy(alpha = 0.12f)
                            } else {
                                uiStyle.cardElevatedColor
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (settings.adaptiveAutoTuningEnabled) {
                                    SpaceEmerald.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = if (settings.adaptiveAutoTuningEnabled) SpaceEmerald else primaryAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Optimización Automática del Motor",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = if (settings.adaptiveAutoTuningEnabled) SpaceEmerald else MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                SettingSwitchRow(
                                    title = "Auto-Ajuste Inteligente (Adaptativo)",
                                    subtitle = "Calibra ventana SSH, búfer, cola TUN y Keep-Alive según señal, RAM/CPU y preset",
                                    checked = settings.adaptiveAutoTuningEnabled,
                                    testTag = "settings_adaptive_tuning_switch"
                                ) { enabled ->
                                    onUpdateSettings { it.copy(adaptiveAutoTuningEnabled = enabled) }
                                }

                                if (settings.adaptiveAutoTuningEnabled) {
                                    val summaryText = adaptiveTuning?.summaryText
                                        ?: "Activo • Calibrando según red y hardware -> Ventana ${effectiveConfig.sshWindowKb} KB, Búfer ${effectiveConfig.transferBufferKb} KB, Keep-Alive ${effectiveConfig.sshKeepAliveSeconds}s"
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = SpaceEmerald.copy(alpha = 0.16f),
                                        border = BorderStroke(1.dp, SpaceEmerald.copy(alpha = 0.45f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = summaryText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = SpaceEmerald,
                                            modifier = Modifier
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                                .testTag("settings_adaptive_summary_badge")
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = if (settings.adaptiveAutoTuningEnabled) {
                                "Planes objetivo (1 toque — el motor adaptativo respeta el perfil elegido)"
                            } else {
                                "Presets de red (1 toque)"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = SpaceCyan
                        )

                        val presetItems = listOf(
                            NetworkPreset.BALANCED to "preset_balanced",
                            NetworkPreset.MAX_STABILITY to "preset_stability",
                            NetworkPreset.MAX_SPEED to "preset_speed",
                            NetworkPreset.MAX_SECURITY_PRIVACY to "preset_security"
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            presetItems.forEach { (preset, tag) ->
                                val selected = settings.networkPreset == preset
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) {
                                        primaryAccent.copy(alpha = 0.16f)
                                    } else {
                                        uiStyle.cardElevatedColor
                                    },
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    border = BorderStroke(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) primaryAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            onUpdateSettings { it.applyNetworkPreset(preset) }
                                        }
                                        .testTag(tag)
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                        Text(
                                            text = preset.label,
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            color = if (selected) SpaceEmerald else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = preset.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                        if (settings.adaptiveAutoTuningEnabled) {
                            Text(
                                text = "Controles manuales bloqueados mientras Auto-Ajuste Inteligente está activo. Desactívalo arriba para editar parámetros manualmente.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("settings_manual_locked_hint")
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (manualEnabled) 1f else 0.52f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Tamaño de buffer de transferencia (${effectiveConfig.transferBufferKb} KB)",
                                style = MaterialTheme.typography.labelLarge
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SettingsDataStore.ALLOWED_BUFFER_KB.forEach { kb ->
                                    FilterChip(
                                        enabled = manualEnabled,
                                        selected = effectiveConfig.transferBufferKb == kb,
                                        onClick = {
                                            onUpdateSettings {
                                                it.copy(
                                                    transferBufferKb = kb,
                                                    networkPreset = NetworkPreset.CUSTOM
                                                )
                                            }
                                        },
                                        label = { Text("$kb KB") },
                                        modifier = Modifier.testTag("chip_buffer_$kb")
                                    )
                                }
                            }

                            SettingSwitchRow(
                                title = "Flush inmediato en túnel y proxy",
                                subtitle = "Reduce tirones de carga web enviando cada bloque sin espera adicional",
                                checked = effectiveConfig.immediateFlush,
                                enabled = manualEnabled,
                                testTag = "settings_immediate_flush_switch"
                            ) { v ->
                                onUpdateSettings {
                                    it.copy(immediateFlush = v, networkPreset = NetworkPreset.CUSTOM)
                                }
                            }

                            Text(
                                text = "Ventana de canal SSH (${effectiveConfig.sshWindowKb} KB)",
                                style = MaterialTheme.typography.labelLarge
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SettingsDataStore.ALLOWED_SSH_WINDOW_KB.forEach { kb ->
                                    FilterChip(
                                        enabled = manualEnabled,
                                        selected = effectiveConfig.sshWindowKb == kb,
                                        onClick = {
                                            onUpdateSettings {
                                                it.copy(
                                                    sshWindowKb = kb,
                                                    networkPreset = NetworkPreset.CUSTOM
                                                )
                                            }
                                        },
                                        label = { Text("$kb KB") },
                                        modifier = Modifier.testTag("chip_ssh_window_$kb")
                                    )
                                }
                            }

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Keep-Alive SSH", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "${effectiveConfig.sshKeepAliveSeconds} s",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = SpaceCyan
                                    )
                                }
                                Slider(
                                    enabled = manualEnabled,
                                    value = effectiveConfig.sshKeepAliveSeconds.coerceIn(5, 60).toFloat(),
                                    onValueChange = { raw ->
                                        val sec = raw.roundToInt().coerceIn(5, 60)
                                        onUpdateSettings {
                                            it.copy(
                                                sshKeepAliveSeconds = sec,
                                                networkPreset = NetworkPreset.CUSTOM
                                            )
                                        }
                                    },
                                    valueRange = 5f..60f,
                                    modifier = Modifier.testTag("settings_keepalive_slider")
                                )
                            }

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Canales SSH simultáneos", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "${effectiveConfig.maxConcurrentSshChannels.coerceIn(8, 64)}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = SpaceCyan
                                    )
                                }
                                Slider(
                                    enabled = manualEnabled,
                                    value = effectiveConfig.maxConcurrentSshChannels.coerceIn(8, 64).toFloat(),
                                    onValueChange = { raw ->
                                        val count = raw.roundToInt().coerceIn(8, 64)
                                        onUpdateSettings {
                                            it.copy(
                                                maxConcurrentSshChannels = count,
                                                networkPreset = NetworkPreset.CUSTOM
                                            )
                                        }
                                    },
                                    valueRange = 8f..64f,
                                    modifier = Modifier.testTag("settings_concurrency_slider")
                                )
                            }

                            SettingSwitchRow(
                                title = "TCP_NODELAY (Desactivar algoritmo de Nagle)",
                                subtitle = "Prioriza respuesta instantánea para DNS, TLS y navegación web",
                                checked = effectiveConfig.tcpNoDelay,
                                enabled = manualEnabled,
                                testTag = "settings_tcp_nodelay_switch"
                            ) { v ->
                                onUpdateSettings {
                                    it.copy(tcpNoDelay = v, networkPreset = NetworkPreset.CUSTOM)
                                }
                            }

                            SettingSwitchRow(
                                title = "Compresión SSH (zlib)",
                                subtitle = "Reduce ancho de banda en texto; desactivar en conexiones muy rápidas",
                                checked = effectiveConfig.sshCompression,
                                enabled = manualEnabled,
                                testTag = "settings_ssh_compression_switch"
                            ) { v ->
                                onUpdateSettings {
                                    it.copy(sshCompression = v, networkPreset = NetworkPreset.CUSTOM)
                                }
                            }

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Cola del TUN (Backpressure Queue)", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "${effectiveConfig.tunQueueCapacity.coerceIn(32, 256)} paq.",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = SpaceCyan
                                    )
                                }
                                Slider(
                                    enabled = manualEnabled,
                                    value = effectiveConfig.tunQueueCapacity.coerceIn(32, 256).toFloat(),
                                    onValueChange = { raw ->
                                        val cap = raw.roundToInt().coerceIn(32, 256)
                                        onUpdateSettings {
                                            it.copy(
                                                tunQueueCapacity = cap,
                                                networkPreset = NetworkPreset.CUSTOM
                                            )
                                        }
                                    },
                                    valueRange = 32f..256f,
                                    modifier = Modifier.testTag("settings_tun_queue_slider")
                                )
                            }

                            SettingSwitchRow(
                                title = "Caché DNS Inteligente (Stale-While-Revalidate)",
                                subtitle = "Responde dominios frecuentes al instante (0 ms) y refresca TTL en segundo plano",
                                checked = effectiveConfig.smartDnsCacheEnabled,
                                enabled = manualEnabled,
                                testTag = "settings_smart_dns_cache_switch"
                            ) { v ->
                                onUpdateSettings {
                                    it.copy(smartDnsCacheEnabled = v, networkPreset = NetworkPreset.CUSTOM)
                                }
                            }

                            SettingSwitchRow(
                                title = "Kill-Switch estricto",
                                subtitle = "Bloquea fugas hacia LAN local y refuerza aislamiento cuando la VPN está activa",
                                checked = settings.strictKillSwitch,
                                enabled = manualEnabled,
                                testTag = "settings_kill_switch"
                            ) { v ->
                                onUpdateSettings {
                                    it.copy(strictKillSwitch = v, networkPreset = NetworkPreset.CUSTOM)
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                onUpdateSettings { it.restoreOptimalEngineDefaults() }
                                onShowMessage("Valores óptimos y Auto-Ajuste restaurados.")
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SpaceEmerald),
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 48.dp)
                                .testTag("settings_restore_optimal_button")
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Restaurar valores óptimos")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    GlassCard(
        borderColor = accentColor.copy(alpha = 0.40f),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.16f))
                        .border(1.dp, accentColor.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Abrir $title",
                tint = accentColor
            )
        }
    }
}
