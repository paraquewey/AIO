package com.example.presentation.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.models.ConnectionState
import com.example.domain.models.TunnelProfile
import com.example.presentation.components.GlassCard
import com.example.presentation.components.ProtocolBadge
import com.example.presentation.components.formatDuration
import com.example.presentation.components.inferCountryFlagForProfile
import com.example.ui.theme.LocalAioUiStyle
import com.example.ui.theme.SpaceAmber
import com.example.ui.theme.SpaceCoral
import com.example.ui.theme.SpaceEmerald
import com.example.ui.theme.SpaceGlassHighlight
import com.example.vpn.tun.DnsManager

@Composable
fun DashboardScreen(
    connectionState: ConnectionState,
    profiles: List<TunnelProfile>,
    selectedProfile: TunnelProfile?,
    sessionDurationSeconds: Long,
    isRefreshingExitIp: Boolean,
    onToggleConnection: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onRefreshExitIp: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenCreateConfig: () -> Unit,
    onOpenConsole: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState is ConnectionState.Connected
    val isTransitioning = connectionState is ConnectionState.Preparing ||
        connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.Reconnecting ||
        connectionState is ConnectionState.Disconnecting

    val uiStyle = LocalAioUiStyle.current
    val primaryAccent = MaterialTheme.colorScheme.primary

    val (statusLabel, accentColor, statusSubLabel) = when (connectionState) {
        is ConnectionState.Disconnected -> Triple(
            "DESCONECTADO",
            MaterialTheme.colorScheme.onSurfaceVariant,
            selectedProfile?.let { "Listo · ${it.name}" } ?: "Selecciona o crea un perfil para conectar"
        )
        is ConnectionState.Preparing -> Triple("PREPARANDO", SpaceAmber, "Inicializando motor...")
        is ConnectionState.Connecting -> Triple(
            "CONECTANDO",
            primaryAccent,
            connectionState.detail.ifBlank { "Estableciendo túnel cifrado..." }
        )
        is ConnectionState.Connected -> Triple(
            "PROTEGIDO",
            SpaceEmerald,
            "Túnel cifrado activo · ${connectionState.profileName}"
        )
        is ConnectionState.Reconnecting -> Triple(
            "RECONECTANDO",
            SpaceAmber,
            "Intento #${connectionState.attempt} (${connectionState.reason})"
        )
        is ConnectionState.Disconnecting -> Triple("DESCONECTANDO", SpaceAmber, "Cerrando interfaz TUN...")
        is ConnectionState.Error -> Triple("ERROR", SpaceCoral, "Toca el botón para reintentar")
    }

    val animatedAccent by animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(320),
        label = "dashboardAccent"
    )

    val pulseTransition = rememberInfiniteTransition(label = "powerPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isConnected || isTransitioning) 1.12f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1250, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "powerPulseScale"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Minimalist Space Top Header + Console Quick-Access Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_aio_launcher_fg),
                        contentDescription = "AIO Tunnel VPN",
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, primaryAccent.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Column {
                        Text(
                            text = "AIO Tunnel VPN",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Space Glass Engine",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = uiStyle.cardBackgroundColor,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.35f))
                ) {
                    IconButton(
                        onClick = onOpenConsole,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("home_console_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Abrir consola",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Centered Status Capsule + Monospace Timer
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = animatedAccent.copy(alpha = 0.16f),
                    contentColor = animatedAccent,
                    border = BorderStroke(1.dp, animatedAccent.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(animatedAccent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp
                            ),
                            color = animatedAccent,
                            modifier = Modifier.testTag("dashboard_status_label")
                        )
                    }
                }

                Text(
                    text = formatDuration(sessionDurationSeconds),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp
                    ),
                    color = if (isConnected) SpaceEmerald else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.testTag("dashboard_timer")
                )

                Text(
                    text = statusSubLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Hero Circular Glass Power Button
            val buttonBorderBrush = Brush.linearGradient(
                colors = if (isConnected) {
                    listOf(SpaceEmerald, primaryAccent)
                } else {
                    listOf(primaryAccent, primaryAccent.copy(alpha = 0.75f), animatedAccent.copy(alpha = 0.7f))
                }
            )
            Box(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .size(188.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer radial halo
                Box(
                    modifier = Modifier
                        .size(172.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    (if (isConnected) SpaceEmerald else primaryAccent).copy(alpha = 0.28f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Inner ring + interactive circular Glass core
                Surface(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .border(2.dp, buttonBorderBrush, CircleShape)
                        .clickable(onClick = onToggleConnection)
                        .testTag("dashboard_connect_button"),
                    shape = CircleShape,
                    color = if (isConnected) {
                        SpaceEmerald.copy(alpha = 0.14f)
                    } else {
                        uiStyle.cardElevatedColor
                    },
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(SpaceGlassHighlight, Color.Transparent)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = if (connectionState.isActiveOrTransitioning) {
                                    "Desconectar VPN"
                                } else {
                                    "Conectar VPN"
                                },
                                tint = if (isConnected) SpaceEmerald else primaryAccent,
                                modifier = Modifier.size(46.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (connectionState.isActiveOrTransitioning) "DETENER" else "CONECTAR",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = if (isConnected) SpaceEmerald else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Error Banner (if connection fails)
            AnimatedVisibility(visible = connectionState is ConnectionState.Error) {
                val errMsg = (connectionState as? ConnectionState.Error)?.message.orEmpty()
                GlassCard(borderColor = SpaceCoral.copy(alpha = 0.65f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Error de conexión",
                            tint = SpaceCoral
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Fallo en la conexión",
                            style = MaterialTheme.typography.titleMedium,
                            color = SpaceCoral
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = errMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onOpenConsole) {
                            Text("Abrir consola", color = primaryAccent)
                        }
                        TextButton(onClick = onDismissError) {
                            Text("Cerrar", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Compact Horizontal Quick Profile Selector
            if (profiles.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PERFILES RÁPIDOS",
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = onOpenServers,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("dashboard_quick_servers_button")
                        ) {
                            Text("Ver todos", color = primaryAccent)
                        }
                    }

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(profiles, key = { it.id }) { profile ->
                            val isSelected = profile.id == selectedProfile?.id
                            val flagEmoji = inferCountryFlagForProfile(profile)
                            val pillAccent = when {
                                isSelected && isConnected -> SpaceEmerald
                                isSelected -> primaryAccent
                                else -> uiStyle.cardBorderColor
                            }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) {
                                    pillAccent.copy(alpha = 0.18f)
                                } else {
                                    uiStyle.cardBackgroundColor
                                },
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = pillAccent.copy(alpha = if (isSelected) 0.75f else 0.45f)
                                ),
                                modifier = Modifier
                                    .sizeIn(minHeight = 52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onSelectProfile(profile.id) }
                                    .testTag("dashboard_profile_chip_${profile.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (flagEmoji != null) {
                                        Text(
                                            text = flagEmoji,
                                            fontSize = 18.sp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Public,
                                            contentDescription = null,
                                            tint = if (isSelected) pillAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 148.dp)
                                    )
                                    ProtocolBadge(profile.protocol)
                                }
                            }
                        }
                    }
                }
            } else {
                GlassCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Sin perfiles configurados",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Añade un servidor SSH, HTTP, HTTPS o SOCKS5 real o importa una URI/JSON.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onOpenCreateConfig,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryAccent,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.testTag("dashboard_create_first_profile_button")
                        ) {
                            Text("Crear o importar perfil")
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Glass Exit IP Card (Country Flag as Primary Circular Icon when Connected)
            val connected = connectionState as? ConnectionState.Connected
            val exitFlag = if (connected != null) {
                connected.exitCountryCode?.takeIf { it.length == 2 }?.let {
                    DnsManager.countryCodeToFlagEmoji(it)
                } ?: selectedProfile?.let { inferCountryFlagForProfile(it) } ?: "🌐"
            } else {
                null
            }
            val locationParts = if (connected != null) {
                listOfNotNull(
                    connected.exitCountry?.takeIf { it.isNotBlank() },
                    connected.exitCity?.takeIf { it.isNotBlank() }
                )
            } else {
                emptyList()
            }
            val locationSummary = when {
                connected == null -> "Conecta el túnel para consultar tu IP pública de salida"
                locationParts.isNotEmpty() -> locationParts.joinToString(" • ")
                else -> "Consultando ubicación del servidor de salida..."
            }
            val nodeLabel = if (connected != null) {
                "${connected.protocol.badgeCode} • ${connected.serverEndpoint}"
            } else {
                selectedProfile?.let { "${it.protocol.badgeCode} • Puerto ${it.port}" }
            }

            GlassCard(
                borderColor = if (connected != null) SpaceEmerald.copy(alpha = 0.50f) else null,
                modifier = Modifier.testTag("dashboard_exit_ip_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Country flag badge (connected) or shielded glass icon (disconnected)
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(
                                if (connected != null) {
                                    SpaceEmerald.copy(alpha = 0.14f)
                                } else {
                                    primaryAccent.copy(alpha = 0.14f)
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (connected != null) {
                                    SpaceEmerald.copy(alpha = 0.45f)
                                } else {
                                    primaryAccent.copy(alpha = 0.40f)
                                },
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (exitFlag != null) {
                            Text(
                                text = exitFlag,
                                fontSize = 28.sp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Estado protegido",
                                tint = primaryAccent,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "IP DE SALIDA",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = when {
                                connected?.exitIp != null -> connected.exitIp
                                connected != null -> "Consultando..."
                                else -> "Tu IP aparecerá al conectar"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = if (connected?.exitIp != null) FontFamily.Monospace else FontFamily.Default,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = locationSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connected != null) SpaceEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (nodeLabel != null) {
                            Text(
                                text = nodeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onRefreshExitIp,
                        enabled = connected != null && !isRefreshingExitIp,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f))
                            .testTag("dashboard_refresh_ip_button")
                    ) {
                        if (isRefreshingExitIp) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = SpaceEmerald
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refrescar IP de salida",
                                tint = if (connected != null) SpaceEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
