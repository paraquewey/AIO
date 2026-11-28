package com.example.presentation.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.models.AppSettings
import com.example.domain.models.FontColorMode
import com.example.presentation.components.GlassCard
import com.example.ui.theme.ACCENT_PRESET_COLORS
import com.example.ui.theme.LocalAioUiStyle
import com.example.ui.theme.SpaceEmerald
import com.example.ui.theme.computeContrastingOnAccent
import com.example.ui.theme.computeEffectiveBackgroundLuminance
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UiCustomizationScreen(
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onPickCustomBackground: (Uri) -> Unit,
    onResetCustomBackground: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val uiStyle = LocalAioUiStyle.current
    val primaryAccent = MaterialTheme.colorScheme.primary
    val hasCustomBg = !settings.customBackgroundPath.isNullOrBlank()
    val effectiveLum = computeEffectiveBackgroundLuminance(
        hasCustomBg = hasCustomBg,
        rawBgLuminance = settings.customBackgroundLuminance,
        dimmerAlpha = settings.backgroundDimmerAlpha,
        useLightTheme = settings.useLightTheme
    )

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onPickCustomBackground(uri)
        }
    }

    val currentHsv = remember(settings.primaryAccentArgb) {
        FloatArray(3).also { hsv ->
            AndroidColor.colorToHSV(Color(settings.primaryAccentArgb).toArgb(), hsv)
        }
    }
    val currentHue = currentHsv[0].coerceIn(0f, 360f)
    val currentSat = currentHsv[1].coerceIn(0f, 1f)
    val currentVal = currentHsv[2].coerceIn(0.65f, 1f)

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "Personalización de Interfaz",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("ui_custom_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver a Ajustes",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = uiStyle.cardBackgroundColor.copy(alpha = 0.72f),
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground
            )
        )

        Box(
            modifier = Modifier.fillMaxSize(),
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
                // 1. Font Color / Contrast Mode
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Contrast,
                            contentDescription = null,
                            tint = primaryAccent
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Color de fuente (Contraste)",
                            style = MaterialTheme.typography.titleMedium,
                            color = primaryAccent
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "En modo Adaptable, el texto cambia automáticamente a blanco (#F8FAFC) o negro (#0F172A) según la luminancia real del fondo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    val modeItems = listOf(
                        FontColorMode.ADAPTIVE to "font_mode_adaptive",
                        FontColorMode.FORCED_WHITE to "font_mode_white",
                        FontColorMode.FORCED_BLACK to "font_mode_black"
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        modeItems.forEachIndexed { index, (mode, tag) ->
                            SegmentedButton(
                                selected = settings.fontColorMode == mode,
                                onClick = {
                                    onUpdateSettings { it.copy(fontColorMode = mode) }
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = modeItems.size
                                ),
                                modifier = Modifier
                                    .sizeIn(minHeight = 48.dp)
                                    .testTag(tag)
                            ) {
                                Text(mode.label, maxLines = 1)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    val lumPct = (effectiveLum * 100f).roundToInt()
                    Text(
                        text = "Luminancia efectiva del fondo: $lumPct% · Texto activo: ${
                            if (uiStyle.isLightForeground) "Claro (#F8FAFC)" else "Oscuro (#0F172A)"
                        }",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("ui_custom_luminance_label")
                    )
                }

                // 2. Custom Wallpaper & Dimmer
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Wallpaper,
                            contentDescription = null,
                            tint = SpaceEmerald
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Fondo de pantalla personalizado",
                            style = MaterialTheme.typography.titleMedium,
                            color = SpaceEmerald
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (hasCustomBg) {
                            "Imagen de galería activa detrás de las tarjetas Space Glass."
                        } else {
                            "Usando el fondo espacial Space Glass predeterminado."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { galleryLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryAccent,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .sizeIn(minHeight = 48.dp)
                                .testTag("ui_custom_pick_bg_button")
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Seleccionar imagen de galería")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onResetCustomBackground,
                        enabled = hasCustomBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .testTag("ui_custom_reset_bg_button")
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Restaurar fondo espacial")
                    }

                    Spacer(Modifier.height(12.dp))
                    val dimmerPct = (settings.backgroundDimmerAlpha * 100f).roundToInt()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Opacidad del atenuador (Dimmer)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "$dimmerPct%",
                            style = MaterialTheme.typography.labelLarge,
                            color = primaryAccent
                        )
                    }
                    Slider(
                        value = settings.backgroundDimmerAlpha.coerceIn(0f, 0.85f),
                        onValueChange = { alpha ->
                            onUpdateSettings {
                                it.copy(backgroundDimmerAlpha = alpha.coerceIn(0f, 0.85f))
                            }
                        },
                        valueRange = 0f..0.85f,
                        modifier = Modifier.testTag("ui_custom_dimmer_slider")
                    )
                }

                // 3. Primary Accent Color Picker (Swatches + Hue/Saturation Sliders)
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ColorLens,
                            contentDescription = null,
                            tint = primaryAccent
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Color principal (Acento de la UI)",
                            style = MaterialTheme.typography.titleMedium,
                            color = primaryAccent
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Personaliza el tono de botones, bordes luminosos, interruptores activos e iconos seleccionados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ACCENT_PRESET_COLORS.forEachIndexed { index, argb ->
                            val swatchColor = Color(argb)
                            val isSelected = settings.primaryAccentArgb == argb
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.5.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.35f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        onUpdateSettings { it.copy(primaryAccentArgb = argb) }
                                    }
                                    .testTag("accent_swatch_$index"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Acento seleccionado",
                                        tint = computeContrastingOnAccent(swatchColor),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Tono libre (Hue)", style = MaterialTheme.typography.bodyMedium)
                        Text("${currentHue.roundToInt()}°", style = MaterialTheme.typography.labelLarge, color = primaryAccent)
                    }
                    Slider(
                        value = currentHue,
                        onValueChange = { hue ->
                            val newArgb = AndroidColor.HSVToColor(
                                floatArrayOf(hue.coerceIn(0f, 360f), currentSat.coerceAtLeast(0.25f), currentVal)
                            ).toLong() and 0xFFFFFFFFL
                            onUpdateSettings { it.copy(primaryAccentArgb = newArgb) }
                        },
                        valueRange = 0f..360f,
                        modifier = Modifier.testTag("accent_hue_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Saturación", style = MaterialTheme.typography.bodyMedium)
                        Text("${(currentSat * 100f).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = primaryAccent)
                    }
                    Slider(
                        value = currentSat,
                        onValueChange = { sat ->
                            val newArgb = AndroidColor.HSVToColor(
                                floatArrayOf(currentHue, sat.coerceIn(0.10f, 1f), currentVal)
                            ).toLong() and 0xFFFFFFFFL
                            onUpdateSettings { it.copy(primaryAccentArgb = newArgb) }
                        },
                        valueRange = 0.10f..1f,
                        modifier = Modifier.testTag("accent_sat_slider")
                    )

                    Spacer(Modifier.height(8.dp))
                    // Live accent preview strip
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = primaryAccent.copy(alpha = 0.14f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Vista previa de acento",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = primaryAccent
                                )
                                Text(
                                    text = String.format("#%06X", settings.primaryAccentArgb and 0xFFFFFFL),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = true, onCheckedChange = {})
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            onUpdateSettings { it.copy(primaryAccentArgb = 0xFF7B61FFL) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .testTag("ui_custom_reset_accent_button")
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Restaurar acento Space (#7B61FF)")
                    }
                }
            }
        }
    }
}
