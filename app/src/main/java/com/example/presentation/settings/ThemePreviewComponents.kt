package com.example.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.models.TextureScaleMode
import com.example.presentation.components.AioActionButton
import com.example.presentation.components.GlassCard
import com.example.ui.theme.FontSlot
import com.example.ui.theme.LocalAioUiStyle
import com.example.ui.theme.SpaceCyan
import com.example.ui.theme.SpaceEmerald
import com.example.ui.theme.SpaceGlassHighlight
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalDivider
import com.example.ui.theme.TerminalSuccess
import com.example.ui.theme.TextureOverlayLayer
import com.example.ui.theme.TextureSlot
import com.example.ui.theme.extractFontDisplayName
import kotlin.math.roundToInt

@Composable
fun LiveThemePreviewCard(
    modifier: Modifier = Modifier
) {
    val uiStyle = LocalAioUiStyle.current
    val primaryAccent = MaterialTheme.colorScheme.primary

    GlassCard(
        borderColor = primaryAccent.copy(alpha = 0.50f),
        modifier = modifier.testTag("ui_custom_preview_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VISTA EN VIVO DEL TEMA",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.9.sp),
                    color = primaryAccent
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "AIO Tunnel · Space Glass",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "SSH/TLS cifrado · Baja latencia activa",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Mini hero circular preview button
            Surface(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(listOf(SpaceEmerald, primaryAccent)),
                        shape = CircleShape
                    ),
                shape = CircleShape,
                color = uiStyle.cardElevatedColor
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(listOf(SpaceGlassHighlight, Color.Transparent))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    TextureOverlayLayer(
                        texturePath = uiStyle.heroButtonTexturePath,
                        textureVersion = uiStyle.heroButtonTextureVersion,
                        textureAlpha = uiStyle.heroButtonTextureAlpha,
                        scaleMode = TextureScaleMode.CROP,
                        maxDimensionPx = TextureSlot.HERO_BUTTON.maxDimensionPx,
                        isLightForeground = uiStyle.isLightForeground,
                        modifier = Modifier.fillMaxSize()
                    )
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = SpaceEmerald,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AioActionButton(
                onClick = {},
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.testTag("preview_action_button")
            ) {
                Text("Botón de acción", maxLines = 1)
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TerminalBg,
                border = BorderStroke(0.5.dp, TerminalDivider),
                modifier = Modifier.weight(1f)
            ) {
                val scaledLogSize = (10.5f * uiStyle.terminalFontScale.coerceIn(0.80f, 1.35f))
                    .coerceIn(8.5f, 14.5f)
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "[12:04] [SSH] Túnel activo",
                        fontFamily = uiStyle.terminalFontFamily,
                        fontSize = scaledLogSize.sp,
                        color = TerminalSuccess,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "[12:04] [DNS] DoH 38 ms",
                        fontFamily = uiStyle.terminalFontFamily,
                        fontSize = scaledLogSize.sp,
                        color = SpaceCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun AccordionCustomizationSection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    headerTag: String,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(contentPadding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 56.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag(headerTag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Contraer sección" else "Expandir sección",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                content()
            }
        }
    }
}

@Composable
fun TextureSlotControlBlock(
    slot: TextureSlot,
    texturePath: String?,
    textureAlpha: Float,
    scaleMode: TextureScaleMode?,
    onPickTexture: () -> Unit,
    onClearTexture: () -> Unit,
    onAlphaChange: (Float) -> Unit,
    onScaleModeChange: ((TextureScaleMode) -> Unit)? = null
) {
    val hasTexture = !texturePath.isNullOrBlank()
    val primaryAccent = MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = slot.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (hasTexture) "Activa" else "Sin textura",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasTexture) SpaceEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AioActionButton(
                    onClick = onPickTexture,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tex_${slot.id}_pick_button")
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Importar imagen", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onClearTexture,
                    enabled = hasTexture,
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("tex_${slot.id}_clear_button")
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Quitar textura", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Quitar")
                }
            }

            if (scaleMode != null && onScaleModeChange != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextureScaleMode.entries.forEach { mode ->
                        FilterChip(
                            selected = scaleMode == mode,
                            onClick = { onScaleModeChange(mode) },
                            label = { Text(mode.label, maxLines = 1) },
                            modifier = Modifier.testTag("tex_${slot.id}_mode_${mode.name.lowercase()}")
                        )
                    }
                }
            }

            val alphaPct = (textureAlpha.coerceIn(0f, 1f) * 100f).roundToInt()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Opacidad", style = MaterialTheme.typography.bodySmall)
                Text("$alphaPct%", style = MaterialTheme.typography.labelMedium, color = primaryAccent)
            }
            Slider(
                value = textureAlpha.coerceIn(0f, 1f),
                onValueChange = { onAlphaChange(it.coerceIn(0f, 1f)) },
                valueRange = 0f..1f,
                modifier = Modifier.testTag("tex_${slot.id}_alpha_slider")
            )
        }
    }
}

@Composable
fun FontSlotControlBlock(
    slot: FontSlot,
    fontPath: String?,
    fontScale: Float,
    onPickFont: () -> Unit,
    onResetFont: () -> Unit,
    onScaleChange: (Float) -> Unit
) {
    val activeFileName = extractFontDisplayName(fontPath)
    val primaryAccent = MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = slot.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = activeFileName ?: "Fuente predeterminada",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (activeFileName != null) SpaceEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("font_${slot.id}_name")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AioActionButton(
                    onClick = onPickFont,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("font_${slot.id}_pick_button")
                ) {
                    Icon(Icons.Default.FontDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Importar fuente (.ttf / .otf)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onResetFont,
                    enabled = activeFileName != null,
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("font_${slot.id}_reset_button")
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Restaurar fuente", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Restaurar fuente")
                }
            }

            val scalePct = (fontScale.coerceIn(0.80f, 1.35f) * 100f).roundToInt()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tamaño", style = MaterialTheme.typography.bodySmall)
                Text("$scalePct%", style = MaterialTheme.typography.labelMedium, color = primaryAccent)
            }
            Slider(
                value = fontScale.coerceIn(0.80f, 1.35f),
                onValueChange = { onScaleChange(it.coerceIn(0.80f, 1.35f)) },
                valueRange = 0.80f..1.35f,
                modifier = Modifier.testTag("font_${slot.id}_scale_slider")
            )
        }
    }
}
