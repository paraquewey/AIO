package com.example.presentation.diagnostics

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.data.security.SshHostKeyStore
import com.example.domain.models.ConnectionState
import com.example.domain.models.DiagnosticCheckResult
import com.example.domain.models.DiagnosticStatus
import com.example.domain.models.LogEntry
import com.example.domain.models.LogLevel
import com.example.domain.models.ServerMessageMode
import com.example.presentation.components.GlassCard
import com.example.presentation.components.ServerMessageWebView
import com.example.presentation.components.formatServerBannerAsHtml
import com.example.ui.theme.GruvboxAqua
import com.example.ui.theme.GruvboxGreen
import com.example.ui.theme.GruvboxOrange
import com.example.ui.theme.GruvboxRed
import com.example.ui.theme.GruvboxYellow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    checks: List<DiagnosticCheckResult>,
    logs: List<LogEntry>,
    knownHosts: List<SshHostKeyStore.KnownHostRecord>,
    connectionState: ConnectionState = ConnectionState.Disconnected,
    onRunDiagnostics: () -> Unit,
    onClearLogs: () -> Unit,
    onRemoveKnownHost: (String, Int) -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Diagnóstico", style = MaterialTheme.typography.headlineMedium)
                Button(
                    onClick = onRunDiagnostics,
                    colors = ButtonDefaults.buttonColors(containerColor = GruvboxOrange),
                    modifier = Modifier.testTag("diagnostics_run_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ejecutar pruebas")
                }
            }

            // Real Probe Cards
            GlassCard {
                Text("Pruebas reales de red y seguridad", style = MaterialTheme.typography.titleMedium, color = GruvboxYellow)
                Spacer(Modifier.height(4.dp))
                val isTunnelConnected = connectionState is ConnectionState.Connected
                Text(
                    text = if (isTunnelConnected) {
                        "Modo conectado: DNS y DoH se ejecutan estrictamente a través del túnel VPN activo."
                    } else {
                        "Modo desconectado: se prueba el endpoint respetando TLS y el acceso directo de la red actual."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isTunnelConnected) GruvboxGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("diagnostics_mode_label")
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    checks.forEach { check ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (check.status) {
                                DiagnosticStatus.IDLE -> Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = "Pendiente",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                DiagnosticStatus.RUNNING -> CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                DiagnosticStatus.PASSED -> Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Correcto",
                                    tint = GruvboxGreen
                                )
                                DiagnosticStatus.FAILED -> Icon(
                                    Icons.Default.Error,
                                    contentDescription = "Error",
                                    tint = GruvboxRed
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(check.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = check.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (check.latencyMs != null) {
                                Text(
                                    text = "${check.latencyMs} ms",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = GruvboxAqua
                                )
                            }
                        }
                    }
                }
            }

            // SSH Known Hosts Fingerprints
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, tint = GruvboxGreen)
                    Spacer(Modifier.width(8.dp))
                    Text("Claves SSH confiables (${knownHosts.size})", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                if (knownHosts.isEmpty()) {
                    Text(
                        text = "No hay huellas SSH registradas aún. Se solicitará confirmación al conectar por primera vez.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    knownHosts.forEach { rec ->
                        val parts = rec.endpoint.split(':')
                        val host = parts.getOrElse(0) { rec.endpoint }
                        val port = parts.getOrNull(1)?.toIntOrNull() ?: 22
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${rec.endpoint} (${rec.keyType})", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    rec.sha256Fingerprint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onRemoveKnownHost(host, port) }) {
                                Text("Revocar", color = GruvboxRed)
                            }
                        }
                    }
                }
            }

            // Sanitized Event Log
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Registro sanitizado (${logs.size})", style = MaterialTheme.typography.titleMedium)
                    Row {
                        IconButton(
                            onClick = {
                                val text = logs.joinToString("\n") { entry ->
                                    "[${timeFormat.format(Date(entry.timestampMillis))}] ${entry.level}/${entry.tag}: ${entry.message}"
                                }
                                clipboard.setText(AnnotatedString(text))
                                onNotify("Registros sanitizados copiados al portapapeles.")
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copiar registros")
                        }
                        IconButton(onClick = onClearLogs) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Limpiar registros")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                val connectedState = connectionState as? ConnectionState.Connected
                val serverBannerHtml = when {
                    connectedState == null -> null
                    connectedState.serverMessageMode == ServerMessageMode.HIDDEN -> null
                    connectedState.serverMessageMode == ServerMessageMode.CUSTOM_HTML ->
                        connectedState.customServerMessageHtml.takeIf { it.isNotBlank() }
                    connectedState.serverMessageMode == ServerMessageMode.ORIGINAL ->
                        connectedState.originalServerBanner?.takeIf { it.isNotBlank() }?.let {
                            formatServerBannerAsHtml(it)
                        }
                    else -> null
                }
                if (serverBannerHtml != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, GruvboxGreen.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "Mensaje del servidor",
                                style = MaterialTheme.typography.labelSmall,
                                color = GruvboxGreen
                            )
                            Spacer(Modifier.height(4.dp))
                            ServerMessageWebView(
                                htmlContent = serverBannerHtml,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("diagnostics_server_message_webview")
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (logs.isEmpty()) {
                    Text(
                        text = "Sin eventos registrados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            logs.takeLast(40).asReversed().forEach { entry ->
                                val color = when (entry.level) {
                                    LogLevel.ERROR -> GruvboxRed
                                    LogLevel.WARN -> GruvboxYellow
                                    LogLevel.INFO -> GruvboxAqua
                                    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Text(
                                    text = "${timeFormat.format(Date(entry.timestampMillis))} [${entry.tag}] ${entry.message}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = color
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
