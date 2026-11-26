package com.example.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.models.SshHostKeyChallenge
import com.example.domain.models.TunnelProtocol
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassSurface
import com.example.ui.theme.GruvboxAqua
import com.example.ui.theme.GruvboxGreen
import com.example.ui.theme.GruvboxOrange
import com.example.ui.theme.GruvboxRed
import com.example.ui.theme.GruvboxYellow

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color = GlassBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.04f),
                            Color.Transparent
                        )
                    )
                )
                .padding(16.dp),
            content = content
        )
    }
}

@Composable
fun ProtocolBadge(protocol: TunnelProtocol, modifier: Modifier = Modifier) {
    val accent = when {
        !protocol.isBuiltInEngine -> GruvboxYellow
        protocol == TunnelProtocol.SSH || protocol == TunnelProtocol.SSH_JUMP -> GruvboxGreen
        protocol == TunnelProtocol.SOCKS5 -> GruvboxAqua
        else -> GruvboxOrange
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Text(
            text = protocol.badgeCode,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun LatencyPill(latencyMs: Long?, modifier: Modifier = Modifier) {
    val (label, color) = when {
        latencyMs == null -> "Sin medir" to MaterialTheme.colorScheme.onSurfaceVariant
        latencyMs < 120 -> "$latencyMs ms" to GruvboxGreen
        latencyMs < 280 -> "$latencyMs ms" to GruvboxYellow
        else -> "$latencyMs ms" to GruvboxRed
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SshHostKeyDialog(
    challenge: SshHostKeyChallenge,
    onTrust: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(
                imageVector = if (challenge.isMismatchWarning) Icons.Default.Warning else Icons.Default.Fingerprint,
                contentDescription = "Verificación de clave SSH",
                tint = if (challenge.isMismatchWarning) GruvboxRed else GruvboxYellow,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = if (challenge.isMismatchWarning) {
                    "Advertencia: Clave SSH cambiada"
                } else {
                    "Verificar huella de servidor SSH"
                },
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (challenge.isMismatchWarning) {
                        "La clave pública de ${challenge.host}:${challenge.port} NO coincide con la registrada anteriormente. Podría tratarse de un ataque MITM."
                    } else {
                        "Primera conexión a ${challenge.host}:${challenge.port}. Confirma explícitamente la huella SHA-256 antes de confiar en este host."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Tipo: ${challenge.keyType}",
                            style = MaterialTheme.typography.labelMedium,
                            color = GruvboxYellow
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = challenge.sha256Fingerprint,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (challenge.previousFingerprint != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Anterior: ${challenge.previousFingerprint}",
                                style = MaterialTheme.typography.labelSmall,
                                color = GruvboxRed
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onTrust,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (challenge.isMismatchWarning) GruvboxRed else GruvboxGreen
                ),
                modifier = Modifier.testTag("ssh_host_key_trust_button")
            ) {
                Text(if (challenge.isMismatchWarning) "Aceptar cambio" else "Confiar y conectar")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.testTag("ssh_host_key_reject_button")
            ) {
                Text("Rechazar")
            }
        }
    )
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format("%.2f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1_048_576 -> String.format("%.2f MB/s", bytesPerSec / 1_048_576.0)
    bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
    else -> "$bytesPerSec B/s"
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
