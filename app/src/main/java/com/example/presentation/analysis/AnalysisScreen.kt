package com.example.presentation.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.models.ConnectionState
import com.example.domain.models.DnsLeakReport
import com.example.domain.models.LatencyStabilityState
import com.example.domain.models.PortScannerState
import com.example.domain.models.SpeedSample
import com.example.domain.models.SpeedTestState
import com.example.domain.models.TunnelProfile
import com.example.presentation.components.GlassCard
import com.example.ui.theme.LocalAioUiStyle
import com.example.ui.theme.SpaceAmber
import com.example.ui.theme.SpaceCoral
import com.example.ui.theme.SpaceCyan
import com.example.ui.theme.SpaceEmerald
import com.example.vpn.tun.DnsManager
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisScreen(
    connectionState: ConnectionState,
    selectedProfile: TunnelProfile?,
    speedTestState: SpeedTestState,
    stabilityState: LatencyStabilityState,
    portScannerState: PortScannerState,
    dnsLeakReport: DnsLeakReport,
    onRunSpeedTest: () -> Unit,
    onRunStabilityTest: () -> Unit,
    onRunPortScan: (String) -> Unit,
    onRunDnsAudit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState is ConnectionState.Connected
    var portHostInput by remember { mutableStateOf(selectedProfile?.host.orEmpty()) }
    LaunchedEffect(selectedProfile?.id) {
        val host = selectedProfile?.host
        if (portHostInput.isBlank() && !host.isNullOrBlank()) {
            portHostInput = host
        }
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Análisis de Red", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = "Pruebas reales bajo demanda · Cero consumo en segundo plano",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ModeBadge(isTunnelActive = isConnected)
            }

            // 1. Speed & Burst Acceleration Scanner
            GlassCard(borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = SpaceEmerald)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Velocidad y aceleración", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = speedTestState.phaseLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (speedTestState.samples.isNotEmpty() || speedTestState.downloadMbps != null) {
                        ModeBadge(isTunnelActive = speedTestState.usedTunnel)
                    }
                }

                Spacer(Modifier.height(12.dp))
                SpeedCurveCanvas(samples = speedTestState.samples)
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnalysisStatPill(
                        label = "Descarga",
                        value = speedTestState.downloadMbps?.let { String.format("%.2f Mbps", it) } ?: "—",
                        accent = SpaceEmerald,
                        modifier = Modifier.weight(1f)
                    )
                    AnalysisStatPill(
                        label = "Pico",
                        value = speedTestState.peakDownloadMbps?.let { String.format("%.2f Mbps", it) } ?: "—",
                        accent = SpaceCyan,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnalysisStatPill(
                        label = "Subida",
                        value = speedTestState.uploadMbps?.let { String.format("%.2f Mbps", it) } ?: "—",
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    AnalysisStatPill(
                        label = "Aceleración a pico",
                        value = speedTestState.timeToPeakSec?.let { String.format("%.1f s", it) } ?: "—",
                        accent = SpaceAmber,
                        modifier = Modifier.weight(1f)
                    )
                }

                speedTestState.errorMessage?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(text = err, style = MaterialTheme.typography.labelSmall, color = SpaceCoral)
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onRunSpeedTest,
                    enabled = !speedTestState.isRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("analysis_speed_button")
                ) {
                    if (speedTestState.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Midiendo velocidad real...")
                    } else {
                        Text("Iniciar Test de Velocidad")
                    }
                }
            }

            // 2. Stability, Latency & Jitter Scanner
            GlassCard(borderColor = SpaceEmerald.copy(alpha = 0.40f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = SpaceCyan)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Estabilidad, latencia y jitter", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Ráfaga de 20 sondeos secuenciales sin carga en segundo plano",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    stabilityState.qualityGrade?.let { grade ->
                        val gradeColor = when (grade) {
                            "Excelente" -> SpaceEmerald
                            "Estable" -> SpaceAmber
                            else -> SpaceCoral
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = gradeColor.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, gradeColor.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = grade,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = gradeColor,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                LatencyBarsCanvas(samples = stabilityState.pingSamplesMs)
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnalysisStatPill(
                        label = "Mín",
                        value = stabilityState.minMs?.let { "$it ms" } ?: "—",
                        accent = SpaceEmerald,
                        modifier = Modifier.weight(1f)
                    )
                    AnalysisStatPill(
                        label = "Prom",
                        value = stabilityState.avgMs?.let { "$it ms" } ?: "—",
                        accent = SpaceCyan,
                        modifier = Modifier.weight(1f)
                    )
                    AnalysisStatPill(
                        label = "Máx",
                        value = stabilityState.maxMs?.let { "$it ms" } ?: "—",
                        accent = SpaceAmber,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnalysisStatPill(
                        label = "Jitter",
                        value = stabilityState.jitterMs?.let { "$it ms" } ?: "—",
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    AnalysisStatPill(
                        label = "Pérdida",
                        value = stabilityState.packetLossPercent?.let { String.format("%.0f%%", it) } ?: "—",
                        accent = if ((stabilityState.packetLossPercent ?: 0f) > 5f) SpaceCoral else SpaceEmerald,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRunStabilityTest,
                    enabled = !stabilityState.isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("analysis_stability_button")
                ) {
                    if (stabilityState.isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Sondeando (${stabilityState.pingSamplesMs.size}/20)...")
                    } else {
                        Text("Analizar Estabilidad")
                    }
                }
            }

            // 3. Bug Host & SNI Port Scanner
            GlassCard(borderColor = SpaceCyan.copy(alpha = 0.40f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Radar, contentDescription = null, tint = SpaceAmber)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Escáner Bug Host y puertos SNI", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Comprueba TCP + TLS/SNI y código HTTP en 80, 443, 8080, 8443, 2052 y 2082",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = portHostInput,
                    onValueChange = { portHostInput = it },
                    label = { Text("Host, dominio SNI o IP objetivo") },
                    placeholder = { Text("ej. m.cloudflare.com o 198.51.100.10") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("analysis_porthost_input")
                )
                portScannerState.errorMessage?.let { err ->
                    Spacer(Modifier.height(6.dp))
                    Text(text = err, style = MaterialTheme.typography.labelSmall, color = SpaceCoral)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { onRunPortScan(portHostInput) },
                    enabled = !portScannerState.isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("analysis_port_scan_button")
                ) {
                    if (portScannerState.isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Escaneando puertos...")
                    } else {
                        Text("Escanear Puertos")
                    }
                }

                if (portScannerState.results.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        portScannerState.results.forEach { entry ->
                            val portColor = if (entry.isOpen) SpaceEmerald else SpaceCoral
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = portColor.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, portColor.copy(alpha = 0.45f))
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(
                                        text = "Puerto ${entry.port} · ${if (entry.isOpen) "ABIERTO" else "CERRADO"}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = portColor
                                    )
                                    val detail = buildString {
                                        entry.httpCode?.let { append("HTTP $it") }
                                        entry.tlsOk?.let { ok ->
                                            if (isNotEmpty()) append(" · ")
                                            append(if (ok) "SNI OK" else "TLS fallo")
                                        }
                                        entry.latencyMs?.let { ms ->
                                            if (isNotEmpty()) append(" · ")
                                            append("${ms}ms")
                                        }
                                    }.ifBlank { entry.note }
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. DNS Leak & IP / ASN / ISP Audit
            GlassCard(borderColor = SpaceAmber.copy(alpha = 0.40f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = SpaceAmber)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Auditoría de fugas DNS e IP / ISP", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Verifica resolvers activos, estado DoH RFC 8484, ISP y ASN",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (dnsLeakReport.leakStatus.isNotBlank()) {
                        ModeBadge(isTunnelActive = dnsLeakReport.usedTunnel)
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onRunDnsAudit,
                    enabled = !dnsLeakReport.isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("analysis_dns_audit_button")
                ) {
                    if (dnsLeakReport.isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Auditando DNS e IP...")
                    } else {
                        Text("Auditar DNS e IP")
                    }
                }

                if (dnsLeakReport.activeModeLabel.isNotBlank() || dnsLeakReport.leakStatus.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    val uiStyle = LocalAioUiStyle.current
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = uiStyle.cardElevatedColor,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (dnsLeakReport.leakStatus.isNotBlank()) {
                                val safe = dnsLeakReport.leakStatus.startsWith("Sin fugas")
                                Text(
                                    text = dnsLeakReport.leakStatus,
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = if (safe) SpaceEmerald else SpaceAmber
                                )
                            }
                            AuditRow("Modo DNS", dnsLeakReport.activeModeLabel)
                            AuditRow("Resolvers", "${dnsLeakReport.primaryResolver} / ${dnsLeakReport.secondaryResolver}")
                            AuditRow("Estado DoH", dnsLeakReport.dohStatus.ifBlank { "—" })
                            dnsLeakReport.exitIpInfo?.let { info ->
                                val flag = DnsManager.countryCodeToFlagEmoji(info.countryCode)
                                val loc = listOfNotNull(info.country, info.city).joinToString(" • ")
                                AuditRow("IP Pública", "$flag ${info.ip} ${if (loc.isNotBlank()) "($loc)" else ""}")
                                info.isp?.takeIf { it.isNotBlank() }?.let { AuditRow("ISP", it) }
                                info.asn?.takeIf { it.isNotBlank() }?.let { AuditRow("ASN", it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeBadge(isTunnelActive: Boolean) {
    val color = if (isTunnelActive) SpaceEmerald else SpaceAmber
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = if (isTunnelActive) "Por túnel" else "Red directa",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AnalysisStatPill(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val uiStyle = LocalAioUiStyle.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = uiStyle.cardElevatedColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AuditRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(105.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SpeedCurveCanvas(samples: List<SpeedSample>) {
    val primaryAccent = MaterialTheme.colorScheme.primary
    val uiStyle = LocalAioUiStyle.current
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = uiStyle.cardElevatedColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.28f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(145.dp)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (samples.isEmpty()) {
                Text(
                    text = "Pulsa el botón para medir la curva de aceleración y velocidad real",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    for (i in 1..3) {
                        val y = h * (i / 4f)
                        drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
                    }

                    val maxMbps = max(1f, samples.maxOf { it.downloadMbps } * 1.15f)
                    val points = if (samples.size == 1) {
                        val y = h - (samples[0].downloadMbps / maxMbps) * h
                        listOf(Offset(0f, h * 0.85f), Offset(w, y))
                    } else {
                        samples.mapIndexed { idx, s ->
                            val x = (idx.toFloat() / (samples.size - 1).toFloat()) * w
                            val y = h - (s.downloadMbps / maxMbps).coerceIn(0f, 1f) * (h * 0.9f)
                            Offset(x, y)
                        }
                    }

                    val linePath = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 0 until points.size - 1) {
                            val p0 = points[i]
                            val p1 = points[i + 1]
                            val cx = (p0.x + p1.x) / 2f
                            cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                        }
                    }

                    val fillPath = Path().apply {
                        addPath(linePath)
                        lineTo(points.last().x, h)
                        lineTo(points.first().x, h)
                        close()
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                SpaceEmerald.copy(alpha = 0.34f),
                                primaryAccent.copy(alpha = 0.14f),
                                Color.Transparent
                            )
                        )
                    )
                    drawPath(
                        path = linePath,
                        brush = Brush.horizontalGradient(listOf(primaryAccent, SpaceEmerald)),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )

                    val peakPoint = points.minByOrNull { it.y } ?: points.last()
                    drawCircle(
                        color = SpaceEmerald,
                        radius = 5.dp.toPx(),
                        center = peakPoint
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyBarsCanvas(samples: List<Long?>) {
    val uiStyle = LocalAioUiStyle.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = uiStyle.cardElevatedColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, SpaceCyan.copy(alpha = 0.25f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(106.dp)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (samples.isEmpty()) {
                Text(
                    text = "Los 20 sondeos de latencia y jitter aparecerán aquí",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val totalSlots = 20
                    val slotWidth = size.width / totalSlots
                    val barWidth = (slotWidth * 0.62f).coerceAtLeast(3.dp.toPx())
                    val maxLat = max(120L, samples.filterNotNull().maxOrNull() ?: 120L).toFloat()

                    samples.take(totalSlots).forEachIndexed { idx, ms ->
                        val left = idx * slotWidth + (slotWidth - barWidth) / 2f
                        if (ms == null) {
                            val barH = size.height * 0.20f
                            drawRoundRect(
                                color = SpaceCoral.copy(alpha = 0.85f),
                                topLeft = Offset(left, size.height - barH),
                                size = Size(barWidth, barH),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                            )
                        } else {
                            val ratio = (ms.toFloat() / maxLat).coerceIn(0.10f, 1f)
                            val barH = size.height * ratio
                            val barColor = when {
                                ms < 120L -> SpaceEmerald
                                ms < 260L -> SpaceAmber
                                else -> SpaceCoral
                            }
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(left, size.height - barH),
                                size = Size(barWidth, barH),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                            )
                        }
                    }
                }
            }
        }
    }
}
