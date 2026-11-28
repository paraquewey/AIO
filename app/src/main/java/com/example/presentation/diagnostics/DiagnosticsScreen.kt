package com.example.presentation.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.security.SshHostKeyStore
import com.example.domain.models.ConnectionState
import com.example.domain.models.DiagnosticCheckResult
import com.example.domain.models.DiagnosticStatus
import com.example.domain.models.LogEntry
import com.example.domain.models.LogLevel
import com.example.domain.models.ServerMessageMode
import com.example.presentation.components.ServerMessageWebView
import com.example.presentation.components.formatServerBannerAsHtml
import com.example.ui.theme.GruvboxAqua
import com.example.ui.theme.GruvboxGreen
import com.example.ui.theme.GruvboxOrange
import com.example.ui.theme.GruvboxRed
import com.example.ui.theme.GruvboxYellow
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalDivider
import com.example.ui.theme.TerminalError
import com.example.ui.theme.TerminalSuccess
import com.example.ui.theme.TerminalTextInfo
import com.example.ui.theme.TerminalTimestamp
import com.example.ui.theme.TerminalTopBarBg
import com.example.ui.theme.TerminalWarn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
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
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var autoScrollEnabled by rememberSaveable { mutableStateOf(true) }
    var menuSheetOpen by rememberSaveable { mutableStateOf(false) }

    val connectedState = connectionState as? ConnectionState.Connected
    val serverBannerHtml = remember(connectedState) {
        when {
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
    }

    // Locate the SSH establishment log so the server banner renders chronologically right after it
    val bannerAnchorIndex = remember(logs, serverBannerHtml) {
        if (serverBannerHtml == null || logs.isEmpty()) {
            -1
        } else {
            logs.indexOfLast {
                it.message.contains("Sesión SSH establecida", ignoreCase = true) ||
                    it.message.contains("Túnel activo", ignoreCase = true)
            }
        }
    }

    val totalTerminalItems = logs.size + if (serverBannerHtml != null) 1 else 0

    // Pause auto-scroll if user drags upward; resume automatically when scrolled back to bottom
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .distinctUntilChanged()
            .collect { (inProgress, canScrollForward) ->
                if (inProgress && canScrollForward && autoScrollEnabled) {
                    autoScrollEnabled = false
                } else if (!inProgress && !canScrollForward && !autoScrollEnabled) {
                    autoScrollEnabled = true
                }
            }
    }

    val lastLogId = logs.lastOrNull()?.id ?: 0L
    LaunchedEffect(lastLogId, logs.size, serverBannerHtml, autoScrollEnabled) {
        if (autoScrollEnabled && totalTerminalItems > 0) {
            listState.animateScrollToItem(totalTerminalItems - 1)
        }
    }

    val (statusLabel, statusDotColor) = when (connectionState) {
        is ConnectionState.Connected -> "Conectado" to TerminalSuccess
        is ConnectionState.Connecting,
        is ConnectionState.Preparing,
        is ConnectionState.Reconnecting -> "Conectando" to TerminalWarn
        is ConnectionState.Disconnecting -> "Deteniendo" to TerminalWarn
        is ConnectionState.Error -> "Error" to TerminalError
        ConnectionState.Disconnected -> "Inactivo" to TerminalTimestamp
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBg)
    ) {
        // Minimalist TopBar (Title + subtle status/line badge on left; Copy + Clear + Sandwich Menu on right)
        Surface(
            color = TerminalTopBarBg,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Consola",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = TerminalTextInfo
                            ),
                            modifier = Modifier.testTag("console_title")
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = statusDotColor.copy(alpha = 0.14f),
                            border = BorderStroke(0.5.dp, statusDotColor.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(statusDotColor, CircleShape)
                                )
                                Text(
                                    text = "$statusLabel · ${logs.size}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.5.sp,
                                        color = TerminalTextInfo
                                    ),
                                    modifier = Modifier.testTag("console_status_badge")
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val text = logs.joinToString("\n") { entry ->
                                    "[${timeFormat.format(Date(entry.timestampMillis))}] ${entry.level}/${entry.tag}: ${entry.message}"
                                }
                                clipboard.setText(AnnotatedString(text))
                                onNotify("Registros sanitizados copiados al portapapeles.")
                            },
                            modifier = Modifier.testTag("console_copy_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copiar logs",
                                tint = TerminalTextInfo,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier.testTag("console_clear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Limpiar consola",
                                tint = TerminalTextInfo,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = { menuSheetOpen = true },
                            modifier = Modifier.testTag("console_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menú de pruebas, claves SSH y auto-scroll",
                                tint = GruvboxOrange
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = TerminalDivider)
            }
        }

        // Maximized Full-Screen Terminal Viewport (natural chronological order: top -> bottom)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TerminalBg)
        ) {
            if (logs.isEmpty() && serverBannerHtml == null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = TerminalTimestamp.copy(alpha = 0.6f),
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = "Sin eventos registrados.\nConecta un perfil o ejecuta pruebas desde el menú ☰.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TerminalTimestamp
                        ),
                        modifier = Modifier.testTag("console_empty_state")
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("console_terminal_list"),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
                ) {
                    if (serverBannerHtml != null && logs.isEmpty()) {
                        item(key = "server_message_banner_only") {
                            TerminalServerBannerItem(serverBannerHtml)
                        }
                    }

                    itemsIndexed(
                        items = logs,
                        key = { _, entry -> entry.id }
                    ) { index, entry ->
                        TerminalLogRow(
                            entry = entry,
                            formattedTime = timeFormat.format(Date(entry.timestampMillis))
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = TerminalDivider)

                        if (serverBannerHtml != null && index == bannerAnchorIndex) {
                            TerminalServerBannerItem(serverBannerHtml)
                        }
                    }

                    // If connected with banner but anchor wasn't matched (e.g. user cleared logs while connected)
                    if (serverBannerHtml != null && logs.isNotEmpty() && bannerAnchorIndex < 0) {
                        item(key = "server_message_banner_tail") {
                            TerminalServerBannerItem(serverBannerHtml)
                        }
                    }
                }
            }

            // Quick jump-to-bottom pill when user paused auto-scroll to read history
            androidx.compose.animation.AnimatedVisibility(
                visible = !autoScrollEnabled && totalTerminalItems > 0 && listState.canScrollForward,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        autoScrollEnabled = true
                        coroutineScope.launch {
                            if (totalTerminalItems > 0) {
                                listState.animateScrollToItem(totalTerminalItems - 1)
                            }
                        }
                    },
                    containerColor = TerminalTopBarBg,
                    contentColor = TerminalSuccess,
                    modifier = Modifier.testTag("console_scroll_bottom_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Ir al final de la consola"
                    )
                }
            }
        }
    }

    if (menuSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { menuSheetOpen = false },
            sheetState = sheetState,
            containerColor = TerminalTopBarBg,
            contentColor = TerminalTextInfo
        ) {
            ConsoleToolsBottomSheetContent(
                checks = checks,
                knownHosts = knownHosts,
                isTunnelConnected = connectionState is ConnectionState.Connected,
                autoScrollEnabled = autoScrollEnabled,
                onToggleAutoScroll = { enabled ->
                    autoScrollEnabled = enabled
                    if (enabled && totalTerminalItems > 0) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(totalTerminalItems - 1)
                        }
                    }
                },
                onRunDiagnostics = onRunDiagnostics,
                onRemoveKnownHost = onRemoveKnownHost
            )
        }
    }
}

@Composable
private fun TerminalLogRow(
    entry: LogEntry,
    formattedTime: String
) {
    val msgColor = remember(entry.level, entry.message) {
        classifyTerminalLogColor(entry)
    }

    val lineText = remember(entry.id, formattedTime, msgColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = TerminalTimestamp)) {
                append("[$formattedTime] ")
            }
            withStyle(SpanStyle(color = msgColor)) {
                append("[${entry.tag}] ${entry.message}")
            }
        }
    }

    Text(
        text = lineText,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun TerminalServerBannerItem(serverBannerHtml: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalTopBarBg.copy(alpha = 0.65f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .background(TerminalSuccess, RoundedCornerShape(2.dp))
            )
            Text(
                text = "MENSAJE DEL SERVIDOR",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TerminalSuccess
                )
            )
        }
        ServerMessageWebView(
            htmlContent = serverBannerHtml,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag("diagnostics_server_message_webview")
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(thickness = 0.5.dp, color = TerminalDivider)
    }
}

@Composable
private fun ConsoleToolsBottomSheetContent(
    checks: List<DiagnosticCheckResult>,
    knownHosts: List<SshHostKeyStore.KnownHostRecord>,
    isTunnelConnected: Boolean,
    autoScrollEnabled: Boolean,
    onToggleAutoScroll: (Boolean) -> Unit,
    onRunDiagnostics: () -> Unit,
    onRemoveKnownHost: (String, Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Auto-scroll quick toggle
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = TerminalBg,
            border = BorderStroke(0.5.dp, TerminalDivider)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-scroll",
                        style = MaterialTheme.typography.titleSmall,
                        color = TerminalTextInfo
                    )
                    Text(
                        text = "Desplaza suavemente al último registro al recibir eventos",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTimestamp
                    )
                }
                Switch(
                    checked = autoScrollEnabled,
                    onCheckedChange = onToggleAutoScroll,
                    modifier = Modifier.testTag("console_autoscroll_switch")
                )
            }
        }

        // 2. Network & Security Probes
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = TerminalBg,
            border = BorderStroke(0.5.dp, TerminalDivider)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = null,
                            tint = GruvboxYellow,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Pruebas de red y seguridad",
                            style = MaterialTheme.typography.titleSmall,
                            color = GruvboxYellow
                        )
                    }
                    Button(
                        onClick = onRunDiagnostics,
                        colors = ButtonDefaults.buttonColors(containerColor = GruvboxOrange),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("diagnostics_run_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Ejecutar pruebas", fontSize = 12.sp)
                    }
                }

                Text(
                    text = if (isTunnelConnected) {
                        "Modo conectado: DNS, DoH y TTFB se miden a través del túnel activo."
                    } else {
                        "Modo desconectado: pruebas directas de red y endpoint."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isTunnelConnected) TerminalSuccess else TerminalTimestamp,
                    modifier = Modifier.testTag("diagnostics_mode_label")
                )

                HorizontalDivider(thickness = 0.5.dp, color = TerminalDivider)

                checks.forEach { check ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (check.status) {
                            DiagnosticStatus.IDLE -> Icon(
                                Icons.Default.Schedule,
                                contentDescription = "Pendiente",
                                tint = TerminalTimestamp,
                                modifier = Modifier.size(18.dp)
                            )
                            DiagnosticStatus.RUNNING -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = GruvboxOrange
                            )
                            DiagnosticStatus.PASSED -> Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Correcto",
                                tint = TerminalSuccess,
                                modifier = Modifier.size(18.dp)
                            )
                            DiagnosticStatus.FAILED -> Icon(
                                Icons.Default.Error,
                                contentDescription = "Error",
                                tint = TerminalError,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = check.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = TerminalTextInfo
                            )
                            Text(
                                text = check.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = TerminalTimestamp
                            )
                        }
                        if (check.latencyMs != null) {
                            Text(
                                text = "${check.latencyMs} ms",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = GruvboxAqua
                            )
                        }
                    }
                }
            }
        }

        // 3. Trusted SSH Host Keys (Fingerprints)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = TerminalBg,
            border = BorderStroke(0.5.dp, TerminalDivider)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = TerminalSuccess,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Claves SSH confiables (${knownHosts.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = TerminalTextInfo
                    )
                }

                if (knownHosts.isEmpty()) {
                    Text(
                        text = "No hay huellas SSH registradas aún. Se solicitará confirmación al conectar por primera vez.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTimestamp
                    )
                } else {
                    knownHosts.forEach { rec ->
                        val parts = rec.endpoint.split(':')
                        val host = parts.getOrElse(0) { rec.endpoint }
                        val port = parts.getOrNull(1)?.toIntOrNull() ?: 22
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${rec.endpoint} (${rec.keyType})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TerminalTextInfo
                                )
                                Text(
                                    text = rec.sha256Fingerprint,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = TerminalTimestamp
                                )
                            }
                            TextButton(onClick = { onRemoveKnownHost(host, port) }) {
                                Text("Revocar", color = TerminalError)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun classifyTerminalLogColor(entry: LogEntry): Color {
    val msg = entry.message
    return when {
        entry.level == LogLevel.ERROR ||
            msg.contains("error", ignoreCase = true) ||
            msg.contains("fallo", ignoreCase = true) ||
            msg.contains("falló", ignoreCase = true) ||
            msg.contains("fallida", ignoreCase = true) ||
            msg.contains("rechazado", ignoreCase = true) ||
            msg.contains("revocado", ignoreCase = true) -> TerminalError

        entry.level == LogLevel.WARN ||
            msg.contains("reintento", ignoreCase = true) ||
            msg.contains("reconex", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) -> TerminalWarn

        msg.contains("HTTP/1.1 101", ignoreCase = true) ||
            msg.contains("200 OK", ignoreCase = true) ||
            msg.contains("Sesión SSH establecida", ignoreCase = true) ||
            msg.contains("Túnel activo", ignoreCase = true) ||
            msg.contains("Auto-Replace", ignoreCase = true) ||
            msg.contains("Payload SSH/WS aceptado", ignoreCase = true) ||
            msg.contains("WakeLock acquire", ignoreCase = true) ||
            msg.contains("UDPGW conectado", ignoreCase = true) -> TerminalSuccess

        entry.level == LogLevel.DEBUG -> TerminalTimestamp
        else -> TerminalTextInfo
    }
}
