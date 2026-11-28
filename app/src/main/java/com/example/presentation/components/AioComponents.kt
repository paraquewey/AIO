package com.example.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.models.SshHostKeyChallenge
import com.example.domain.models.TextureScaleMode
import com.example.domain.models.TunnelProtocol
import com.example.ui.theme.LocalAioUiStyle
import com.example.ui.theme.SpaceAmber
import com.example.ui.theme.SpaceCoral
import com.example.ui.theme.SpaceCyan
import com.example.ui.theme.SpaceEmerald
import com.example.ui.theme.TextureOverlayLayer
import com.example.ui.theme.TextureSlot

@Composable
fun AioActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    shape: Shape = RoundedCornerShape(50),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val uiStyle = LocalAioUiStyle.current
    val resolvedContainer = if (enabled) {
        containerColor
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val resolvedContent = if (enabled) {
        contentColor
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = resolvedContainer,
        contentColor = resolvedContent,
        modifier = modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
            .semantics { role = Role.Button }
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (enabled) {
                TextureOverlayLayer(
                    texturePath = uiStyle.actionButtonTexturePath,
                    textureVersion = uiStyle.actionButtonTextureVersion,
                    textureAlpha = uiStyle.actionButtonTextureAlpha,
                    scaleMode = TextureScaleMode.CROP,
                    maxDimensionPx = TextureSlot.ACTION_BUTTON.maxDimensionPx,
                    isLightForeground = uiStyle.isLightForeground,
                    modifier = Modifier.matchParentSize()
                )
            }
            CompositionLocalProvider(LocalContentColor provides resolvedContent) {
                ProvideTextStyle(value = MaterialTheme.typography.labelLarge) {
                    Row(
                        modifier = Modifier.padding(contentPadding),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        content = content
                    )
                }
            }
        }
    }
}

@Composable
fun TexturedNavContainer(
    modifier: Modifier = Modifier,
    backgroundColor: Color = LocalAioUiStyle.current.cardElevatedColor,
    content: @Composable BoxScope.() -> Unit
) {
    val uiStyle = LocalAioUiStyle.current
    Box(
        modifier = modifier.background(backgroundColor)
    ) {
        TextureOverlayLayer(
            texturePath = uiStyle.navBarTexturePath,
            textureVersion = uiStyle.navBarTextureVersion,
            textureAlpha = uiStyle.navBarTextureAlpha,
            scaleMode = TextureScaleMode.CROP,
            maxDimensionPx = TextureSlot.NAV_BAR.maxDimensionPx,
            isLightForeground = uiStyle.isLightForeground,
            modifier = Modifier.matchParentSize()
        )
        content()
    }
}

@Composable
fun ProtocolBadge(protocol: TunnelProtocol, modifier: Modifier = Modifier) {
    val uiStyle = LocalAioUiStyle.current
    val accent = when {
        !protocol.isBuiltInEngine -> SpaceAmber
        protocol == TunnelProtocol.SSH || protocol == TunnelProtocol.SSH_JUMP -> SpaceEmerald
        protocol == TunnelProtocol.SOCKS5 -> SpaceCyan
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.16f),
        contentColor = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Text(
            text = protocol.badgeCode,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = uiStyle.terminalFontFamily
            ),
            color = accent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun LatencyPill(latencyMs: Long?, modifier: Modifier = Modifier) {
    val uiStyle = LocalAioUiStyle.current
    val (label, color) = when {
        latencyMs == null -> "Sin medir" to MaterialTheme.colorScheme.onSurfaceVariant
        latencyMs < 120 -> "$latencyMs ms" to SpaceEmerald
        latencyMs < 280 -> "$latencyMs ms" to SpaceAmber
        else -> "$latencyMs ms" to SpaceCoral
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
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
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = uiStyle.terminalFontFamily
            ),
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
                tint = if (challenge.isMismatchWarning) SpaceCoral else SpaceAmber,
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
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Tipo: ${challenge.keyType}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SpaceAmber
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
                                color = SpaceCoral
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
                    containerColor = if (challenge.isMismatchWarning) SpaceCoral else SpaceEmerald
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
