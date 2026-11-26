package com.example.presentation.configs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.models.TunnelProfile
import com.example.presentation.components.GlassCard
import com.example.presentation.components.ProtocolBadge
import com.example.ui.theme.GruvboxOrange
import com.example.ui.theme.GruvboxRed
import com.example.ui.theme.GruvboxYellow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

@Composable
fun ConfigsScreen(
    profiles: List<TunnelProfile>,
    onCreateNew: () -> Unit,
    onEditProfile: (String) -> Unit,
    onDuplicateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onImportRawText: (String) -> Unit,
    onBuildExportJson: suspend (List<String>?, Boolean) -> String,
    onExportUri: (TunnelProfile) -> String,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Todas") }
    var showPasteImportDialog by remember { mutableStateOf(false) }
    var showBackupExportDialog by remember { mutableStateOf(false) }
    var includeSecretsOnExport by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var pendingDeleteProfile by remember { mutableStateOf<TunnelProfile?>(null) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(StandardCharsets.UTF_8)
                }
            }.onSuccess { content ->
                if (content != null) onImportRawText(content)
            }.onFailure { err ->
                onShowMessage("No se pudo leer el archivo: ${err.message}")
            }
        }
    }

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val payload = onBuildExportJson(null, includeSecretsOnExport)
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(payload.toByteArray(StandardCharsets.UTF_8))
                    }
                }.onSuccess {
                    onShowMessage("Copia de seguridad exportada correctamente.")
                }.onFailure { err ->
                    onShowMessage("Error al guardar copia de seguridad: ${err.message}")
                }
            }
        }
    }

    val categories = remember(profiles) {
        listOf("Todas") + profiles.map { it.category }.distinct().sorted()
    }

    val filtered = remember(profiles, searchQuery, selectedCategory) {
        profiles.filter { p ->
            val matchCat = selectedCategory == "Todas" || p.category == selectedCategory
            val matchSearch = searchQuery.isBlank() ||
                p.name.contains(searchQuery, ignoreCase = true) ||
                p.host.contains(searchQuery, ignoreCase = true) ||
                p.tags.any { it.contains(searchQuery, ignoreCase = true) }
            matchCat && matchSearch
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Gestor de perfiles",
                    style = MaterialTheme.typography.headlineMedium
                )
                Button(
                    onClick = onCreateNew,
                    colors = ButtonDefaults.buttonColors(containerColor = GruvboxOrange),
                    modifier = Modifier.testTag("configs_create_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nuevo")
                }
            }
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showPasteImportDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("configs_import_paste_button")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Importar URI/JSON", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = {
                        openDocumentLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("configs_import_file_button")
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Archivo", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { showBackupExportDialog = true },
                    enabled = profiles.isNotEmpty(),
                    modifier = Modifier.testTag("configs_backup_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Exportar copia", modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar por nombre, host o etiqueta...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (filtered.isEmpty()) {
                GlassCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Sin perfiles en esta vista",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Crea una configuración manual o importa JSON AIO, ssh://, socks5://, http(s)://, V2Ray URI, sing-box o Clash.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filtered, key = { it.id }) { profile ->
                        ConfigItemCard(
                            profile = profile,
                            onEdit = { onEditProfile(profile.id) },
                            onDuplicate = { onDuplicateProfile(profile.id) },
                            onCopyUri = {
                                val uri = onExportUri(profile)
                                clipboard.setText(AnnotatedString(uri))
                                onShowMessage("Configuración copiada sin credenciales sensibles.")
                            },
                            onDelete = { pendingDeleteProfile = profile }
                        )
                    }
                }
            }
        }
    }

    if (showPasteImportDialog) {
        AlertDialog(
            onDismissRequest = { showPasteImportDialog = false },
            title = { Text("Importar configuración") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Pega un JSON AIO v1, configuración sing-box/Clash o URIs (ssh://, socks5://, http://, https://, vless://, vmess://, trojan://, ss://).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        minLines = 5,
                        maxLines = 8,
                        placeholder = { Text("ssh://usuario@host:22#MiServidor") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_paste_textfield")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImportRawText(pasteText)
                        pasteText = ""
                        showPasteImportDialog = false
                    },
                    modifier = Modifier.testTag("import_paste_confirm_button")
                ) {
                    Text("Validar e importar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteImportDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showBackupExportDialog) {
        AlertDialog(
            onDismissRequest = { showBackupExportDialog = false },
            title = { Text("Exportar copia de seguridad") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Por seguridad, las contraseñas y claves privadas NO se incluyen en los archivos exportados por defecto.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeSecretsOnExport,
                            onCheckedChange = { includeSecretsOnExport = it }
                        )
                        Text(
                            text = "Incluir contraseñas y claves en claro (solo respaldo privado)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (includeSecretsOnExport) GruvboxYellow else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBackupExportDialog = false
                        createBackupLauncher.launch("aio_tunnel_backup.json")
                    }
                ) {
                    Text("Guardar JSON")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupExportDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    pendingDeleteProfile?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProfile = null },
            title = { Text("Eliminar perfil") },
            text = { Text("¿Eliminar '${target.name}' y borrar de forma segura sus secretos cifrados?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteProfile(target.id)
                        pendingDeleteProfile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GruvboxRed)
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProfile = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun ConfigItemCard(
    profile: TunnelProfile,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onCopyUri: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(profile.colorTagHex))
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${profile.host}:${profile.port} · ${profile.category}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ProtocolBadge(profile.protocol)
            }
            if (profile.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    profile.tags.take(4).forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onCopyUri) {
                    Icon(Icons.Default.Share, contentDescription = "Compartir sin credenciales")
                }
                IconButton(onClick = onDuplicate) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicar perfil")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar perfil")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Eliminar perfil",
                        tint = GruvboxRed
                    )
                }
            }
        }
    }
}
