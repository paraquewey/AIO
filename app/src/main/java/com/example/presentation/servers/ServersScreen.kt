package com.example.presentation.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.models.TunnelProfile
import com.example.presentation.components.GlassCard
import com.example.presentation.components.LatencyPill
import com.example.presentation.components.ProtocolBadge
import com.example.ui.theme.LocalAioUiStyle
import com.example.ui.theme.SpaceAmber
import com.example.ui.theme.SpaceEmerald

private enum class ServerFilter(val label: String) {
    ALL("Todos"),
    FAVORITES("Favoritos"),
    BUILTIN("Integrados"),
    NATIVE("Núcleo nativo")
}

@Composable
fun ServersScreen(
    profiles: List<TunnelProfile>,
    selectedProfileId: String?,
    isMeasuringLatency: Boolean,
    onSelectProfile: (String) -> Unit,
    onToggleFavorite: (TunnelProfile) -> Unit,
    onMeasureAllLatency: () -> Unit,
    onAutoSelectFastest: () -> Unit,
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ServerFilter.ALL) }

    val filtered = remember(profiles, query, filter) {
        profiles.filter { p ->
            val matchesQuery = query.isBlank() ||
                p.name.contains(query, ignoreCase = true) ||
                p.host.contains(query, ignoreCase = true) ||
                p.category.contains(query, ignoreCase = true) ||
                p.protocol.badgeCode.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                ServerFilter.ALL -> true
                ServerFilter.FAVORITES -> p.isFavorite
                ServerFilter.BUILTIN -> p.protocol.isBuiltInEngine
                ServerFilter.NATIVE -> !p.protocol.isBuiltInEngine
            }
            matchesQuery && matchesFilter
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
            Text(
                text = "Servidores",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar por nombre, host o protocolo...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("servers_search_input")
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ServerFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.label, maxLines = 1) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onMeasureAllLatency,
                    enabled = !isMeasuringLatency && profiles.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("servers_measure_latency_button")
                ) {
                    if (isMeasuringLatency) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.NetworkPing, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Medir latencia")
                }
                Button(
                    onClick = onAutoSelectFastest,
                    enabled = !isMeasuringLatency && profiles.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("servers_auto_select_button")
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Más rápido")
                }
            }
            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                GlassCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (profiles.isEmpty()) {
                                "Aún no tienes servidores guardados"
                            } else {
                                "Sin coincidencias para tu filtro"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "AIO Tunnel VPN no incluye servidores ocultos ni remotos preconfigurados. Añade tu propio servidor autorizado.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onAddProfile,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Añadir servidor")
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filtered, key = { it.id }) { profile ->
                        val isSelected = profile.id == selectedProfileId
                        ServerItemCard(
                            profile = profile,
                            isSelected = isSelected,
                            onSelect = { onSelectProfile(profile.id) },
                            onToggleFavorite = { onToggleFavorite(profile) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerItemCard(
    profile: TunnelProfile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val uiStyle = LocalAioUiStyle.current
    val borderColor = if (isSelected) SpaceEmerald else uiStyle.cardBorderColor
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("server_card_${profile.id}"),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) {
            if (uiStyle.isLightForeground) SpaceEmerald.copy(alpha = 0.14f) else uiStyle.cardBackgroundColor
        } else {
            uiStyle.cardBackgroundColor
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(profile.colorTagHex))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Servidor activo",
                            tint = SpaceEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${profile.host}:${profile.port} · ${profile.category}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProtocolBadge(profile.protocol)
                    LatencyPill(profile.lastLatencyMs)
                }
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.testTag("server_favorite_${profile.id}")
            ) {
                Icon(
                    imageVector = if (profile.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (profile.isFavorite) "Quitar de favoritos" else "Marcar como favorito",
                    tint = if (profile.isFavorite) SpaceAmber else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
