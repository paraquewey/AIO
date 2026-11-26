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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.models.ConnectionState
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelStatistics
import com.example.presentation.components.GlassCard
import com.example.presentation.components.LatencyPill
import com.example.presentation.components.ProtocolBadge
import com.example.presentation.components.formatBytes
import com.example.presentation.components.formatDuration
import com.example.presentation.components.formatSpeed
import com.example.ui.theme.GruvboxAqua
import com.example.ui.theme.GruvboxGreen
import com.example.ui.theme.GruvboxOrange
import com.example.ui.theme.GruvboxRed
import com.example.ui.theme.GruvboxYellow

@Composable
fun DashboardScreen(
    connectionState: ConnectionState,
    selectedProfile: TunnelProfile?,
    statistics: TunnelStatistics,
    onToggleConnection: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenCreateConfig: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (statusLabel, accentColor, buttonSubLabel) = when (connectionState) {
        is ConnectionState.Disconnected -> Triple("Desconectado", MaterialTheme.colorScheme.onSurfaceVariant, "Toca para conectar")
        is ConnectionState.Preparing -> Triple("Preparando", GruvboxYellow, "Inicializando motor...")
        is ConnectionState.Connecting -> Triple("Conectando", GruvboxOrange, connectionState.detail.ifBlank { "Estableciendo túnel..." })
        is ConnectionState.Connected -> Triple("Conectado", GruvboxGreen, "Túnel cifrado activo")
        is ConnectionState.Reconnecting -> Triple("Reconectando", GruvboxYellow, "Intento #${connectionState.attempt} (${connectionState.reason})")
        is ConnectionState.Disconnecting -> Triple("Desconectando", GruvboxOrange, "Cerrando interfaz TUN...")
        is ConnectionState.Error -> Triple("Error", GruvboxRed, "Reintentar conexión")
    }

    val animatedAccent by animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(280),
        label = "statusAccent"
    )

    val isTransitioning = connectionState is ConnectionState.Preparing ||
        connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.Reconnecting ||
        connectionState is ConnectionState.Disconnecting

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isTransitioning || connectionState is ConnectionState.Connected) 1.09f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Hero Header Card with AIO Logo + Network Banner
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, animatedAccent.copy(alpha = 0.35f))
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_aio_tunnel_banner),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(0.24f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
                                    )
                                )
                            )
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_aio_launcher_fg),
                            contentDescription = "Logotipo AIO Tunnel VPN",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AIO Tunnel VPN",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Cliente multiprotocolo nativo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = animatedAccent.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, animatedAccent.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(animatedAccent)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = animatedAccent,
                                    modifier = Modifier.testTag("dashboard_status_label")
                                )
                            }
                        }
                    }
                }
            }

            // Error Banner (only shown when ConnectionState.Error)
            AnimatedVisibility(visible = connectionState is ConnectionState.Error) {
                val errMsg = (connectionState as? ConnectionState.Error)?.message.orEmpty()
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    border = BorderStroke(1.dp, GruvboxRed.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Error de conexión",
                                tint = GruvboxRed
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Fallo en la conexión",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = errMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onOpenDiagnostics) {
                                Text("Abrir diagnóstico")
                            }
                            TextButton(onClick = onDismissError) {
                                Text("Cerrar")
                            }
                        }
                    }
                }
            }

            // Primary Power Button Centerpiece
            GlassCard(borderColor = animatedAccent.copy(alpha = 0.45f)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(164.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(152.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(animatedAccent.copy(alpha = 0.12f))
                        )
                        Surface(
                            modifier = Modifier
                                .size(128.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onToggleConnection)
                                .testTag("dashboard_connect_button"),
                            shape = CircleShape,
                            color = if (connectionState is ConnectionState.Connected) {
                                GruvboxGreen.copy(alpha = 0.22f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            border = BorderStroke(2.5.dp, animatedAccent)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = if (connectionState.isActiveOrTransitioning) {
                                        "Desconectar VPN"
                                    } else {
                                        "Conectar VPN"
                                    },
                                    tint = animatedAccent,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (connectionState.isActiveOrTransitioning) "DETENER" else "CONECTAR",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = animatedAccent
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = buttonSubLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Tiempo de sesión",
                            tint = GruvboxYellow,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = formatDuration(statistics.sessionDurationSeconds),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Active Server & Protocol Card + Quick Profile Access
            GlassCard {
                if (selectedProfile != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SERVIDOR SELECCIONADO",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = selectedProfile.name,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${selectedProfile.host}:${selectedProfile.port}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ProtocolBadge(selectedProfile.protocol)
                            val activeLatency = (connectionState as? ConnectionState.Connected)?.latencyMs
                                ?: selectedProfile.lastLatencyMs
                            LatencyPill(activeLatency)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenServers,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dashboard_quick_servers_button")
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cambiar perfil")
                        }
                        OutlinedButton(
                            onClick = onOpenDiagnostics,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dashboard_quick_diagnostics_button")
                        ) {
                            Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Probar red")
                        }
                    }
                } else {
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
                            text = "Añade un servidor HTTP, HTTPS, SOCKS5 o SSH real o importa una URI/JSON.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onOpenCreateConfig,
                            colors = ButtonDefaults.buttonColors(containerColor = GruvboxOrange),
                            modifier = Modifier.testTag("dashboard_create_first_profile_button")
                        ) {
                            Text("Crear o importar perfil")
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Real Telemetry Grid (Zero Invented Numbers)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    icon = Icons.Default.ArrowDownward,
                    label = "Descarga",
                    value = formatSpeed(statistics.downloadBytesPerSec),
                    subValue = "Recibido: ${formatBytes(statistics.bytesReceived)}",
                    accent = GruvboxGreen,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    icon = Icons.Default.ArrowUpward,
                    label = "Subida",
                    value = formatSpeed(statistics.uploadBytesPerSec),
                    subValue = "Enviado: ${formatBytes(statistics.bytesSent)}",
                    accent = GruvboxOrange,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val exitIp = (connectionState as? ConnectionState.Connected)?.exitIp
                MetricTile(
                    icon = Icons.Default.Public,
                    label = "IP de salida",
                    value = exitIp ?: "No disponible",
                    subValue = if (connectionState is ConnectionState.Connected) {
                        "Flujos TCP: ${statistics.activeTcpConnections}"
                    } else {
                        "Requiere túnel activo"
                    },
                    accent = GruvboxAqua,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    icon = Icons.Default.Speed,
                    label = "Paquetes / DNS",
                    value = "${statistics.packetsPerSec} pkt/s · ${statistics.totalPacketsForwarded}",
                    subValue = "Consultas DNS: ${statistics.dnsQueriesResolved}",
                    accent = GruvboxYellow,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    subValue: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subValue,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
