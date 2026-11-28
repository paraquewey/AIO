package com.example.presentation.settings

import android.content.Intent
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.models.AppSettings
import com.example.domain.models.FontColorMode
import com.example.presentation.components.AioActionButton
import com.example.presentation.components.TexturedNavContainer
import com.example.ui.theme.ACCENT_PRESET_COLORS
import com.example.ui.theme.FontSlot
import com.example.ui.theme.LocalAioUiStyle
import com.example.ui.theme.SpaceAmber
import com.example.ui.theme.SpaceCoral
import com.example.ui.theme.SpaceCyan
import com.example.ui.theme.SpaceEmerald
import com.example.ui.theme.TextureSlot
import com.example.ui.theme.computeEffectiveBackgroundLuminance
import com.example.ui.theme.computeContrastingOnAccent
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UiCustomizationScreen(
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onPickCustomBackground: (Uri) -> Unit,
    onResetCustomBackground: () -> Unit,
    onImportTexture: (TextureSlot, Uri) -> Unit = { _, _ -> },
    onClearTexture: (TextureSlot) -> Unit = {},
    onImportFont: (FontSlot, Uri) -> Unit = { _, _ -> },
    onClearFont: (FontSlot) -> Unit = {},
    onExportThemePackage: ((Uri) -> Unit) -> Unit = {},
    onImportThemePackage: (Uri) -> Unit = {},
    onRestoreFactoryDefaults: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val uiStyle = LocalAioUiStyle.current
    val primaryAccent = MaterialTheme.colorScheme.primary
    val hasCustomBg = !settings.customBackgroundPath.isNullOrBlank()
    val effectiveLum = computeEffectiveBackgroundLuminance(
        hasCustomBg = hasCustomBg,
        rawBgLuminance = settings.customBackgroundLuminance,
        dimmerAlpha = settings.backgroundDimmerAlpha,
        useLightTheme = settings.useLightTheme
    )

    var colorsExpanded by rememberSaveable { mutableStateOf(true) }
    var texturesExpanded by rememberSaveable { mutableStateOf(false) }
    var typographyExpanded by rememberSaveable { mutableStateOf(false) }
    var themePackExpanded by rememberSaveable { mutableStateOf(false) }

    var pendingTextureSlot by remember { mutableStateOf<TextureSlot?>(null) }
    var pendingFontSlot by remember { mutableStateOf<FontSlot?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val slot = pendingTextureSlot
        pendingTextureSlot = null
        if (uri != null) {
            if (slot == null) {
                onPickCustomBackground(uri)
            } else {
                onImportTexture(slot, uri)
            }
        }
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val slot = pendingFontSlot
        pendingFontSlot = null
        if (uri != null && slot != null) {
            onImportFont(slot, uri)
        }
    }

    val themeImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportThemePackage(uri)
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
        TexturedNavContainer(
            backgroundColor = uiStyle.cardBackgroundColor.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxWidth()
        ) {
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
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }

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
                // Top Live Theme Preview
                LiveThemePreviewCard()

                // Section 1: Colors & Background
                AccordionCustomizationSection(
                    title = "Colores y Fondo",
                    subtitle = "Contraste adaptable, fondo de galería y color de acento",
                    icon = Icons.Default.ColorLens,
                    iconTint = primaryAccent,
                    expanded = colorsExpanded,
                    onToggle = { colorsExpanded = !colorsExpanded },
                    headerTag = "section_colors_bg"
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Contrast, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Color de fuente (Contraste)",
                            style = MaterialTheme.typography.titleSmall,
                            color = primaryAccent
                        )
                    }
                    Text(
                        text = "En modo Adaptable, el texto cambia automáticamente a blanco (#F8FAFC) o negro (#0F172A) según la luminancia real del fondo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

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

                    val lumPct = (effectiveLum * 100f).roundToInt()
                    Text(
                        text = "Luminancia efectiva del fondo: $lumPct% · Texto activo: ${
                            if (uiStyle.isLightForeground) "Claro (#F8FAFC)" else "Oscuro (#0F172A)"
                        }",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("ui_custom_luminance_label")
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Wallpaper, contentDescription = null, tint = SpaceEmerald, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Fondo de pantalla personalizado",
                            style = MaterialTheme.typography.titleSmall,
                            color = SpaceEmerald
                        )
                    }

                    AioActionButton(
                        onClick = {
                            pendingTextureSlot = null
                            imagePickerLauncher.launch("image/*")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ui_custom_pick_bg_button")
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Seleccionar imagen de galería")
                    }

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

                    val dimmerPct = (settings.backgroundDimmerAlpha * 100f).roundToInt()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Opacidad del atenuador (Dimmer)", style = MaterialTheme.typography.bodyMedium)
                        Text("$dimmerPct%", style = MaterialTheme.typography.labelLarge, color = primaryAccent)
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

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        text = "Color principal (Acento de la UI)",
                        style = MaterialTheme.typography.titleSmall,
                        color = primaryAccent
                    )
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

                // Section 2: Component Textures
                val activeTextureCount = listOfNotNull(
                    settings.cardTexturePath?.takeIf { it.isNotBlank() },
                    settings.heroButtonTexturePath?.takeIf { it.isNotBlank() },
                    settings.actionButtonTexturePath?.takeIf { it.isNotBlank() },
                    settings.navBarTexturePath?.takeIf { it.isNotBlank() }
                ).size
                AccordionCustomizationSection(
                    title = "Texturas de Interfaz",
                    subtitle = "$activeTextureCount de 4 componentes texturizados (Ajustar/Rellenar/Mosaico)",
                    icon = Icons.Default.Layers,
                    iconTint = SpaceCyan,
                    expanded = texturesExpanded,
                    onToggle = { texturesExpanded = !texturesExpanded },
                    headerTag = "section_textures"
                ) {
                    TextureSlotControlBlock(
                        slot = TextureSlot.CARD,
                        texturePath = settings.cardTexturePath,
                        textureAlpha = settings.cardTextureAlpha,
                        scaleMode = settings.cardTextureMode,
                        onPickTexture = {
                            pendingTextureSlot = TextureSlot.CARD
                            imagePickerLauncher.launch("image/*")
                        },
                        onClearTexture = { onClearTexture(TextureSlot.CARD) },
                        onAlphaChange = { alpha ->
                            onUpdateSettings { it.copy(cardTextureAlpha = alpha) }
                        },
                        onScaleModeChange = { mode ->
                            onUpdateSettings { it.copy(cardTextureMode = mode) }
                        }
                    )

                    TextureSlotControlBlock(
                        slot = TextureSlot.HERO_BUTTON,
                        texturePath = settings.heroButtonTexturePath,
                        textureAlpha = settings.heroButtonTextureAlpha,
                        scaleMode = null,
                        onPickTexture = {
                            pendingTextureSlot = TextureSlot.HERO_BUTTON
                            imagePickerLauncher.launch("image/*")
                        },
                        onClearTexture = { onClearTexture(TextureSlot.HERO_BUTTON) },
                        onAlphaChange = { alpha ->
                            onUpdateSettings { it.copy(heroButtonTextureAlpha = alpha) }
                        }
                    )

                    TextureSlotControlBlock(
                        slot = TextureSlot.ACTION_BUTTON,
                        texturePath = settings.actionButtonTexturePath,
                        textureAlpha = settings.actionButtonTextureAlpha,
                        scaleMode = null,
                        onPickTexture = {
                            pendingTextureSlot = TextureSlot.ACTION_BUTTON
                            imagePickerLauncher.launch("image/*")
                        },
                        onClearTexture = { onClearTexture(TextureSlot.ACTION_BUTTON) },
                        onAlphaChange = { alpha ->
                            onUpdateSettings { it.copy(actionButtonTextureAlpha = alpha) }
                        }
                    )

                    TextureSlotControlBlock(
                        slot = TextureSlot.NAV_BAR,
                        texturePath = settings.navBarTexturePath,
                        textureAlpha = settings.navBarTextureAlpha,
                        scaleMode = null,
                        onPickTexture = {
                            pendingTextureSlot = TextureSlot.NAV_BAR
                            imagePickerLauncher.launch("image/*")
                        },
                        onClearTexture = { onClearTexture(TextureSlot.NAV_BAR) },
                        onAlphaChange = { alpha ->
                            onUpdateSettings { it.copy(navBarTextureAlpha = alpha) }
                        }
                    )
                }

                // Section 3: Granular Typography & Sizing
                AccordionCustomizationSection(
                    title = "Tipografía y Tamaños",
                    subtitle = "Fuentes .ttf/.otf y escala (80%–135%) para títulos, cuerpo y consola",
                    icon = Icons.Default.FontDownload,
                    iconTint = SpaceEmerald,
                    expanded = typographyExpanded,
                    onToggle = { typographyExpanded = !typographyExpanded },
                    headerTag = "section_typography"
                ) {
                    val fontMimeTypes = remember {
                        arrayOf(
                            "font/ttf",
                            "font/otf",
                            "application/x-font-ttf",
                            "application/x-font-opentype",
                            "application/octet-stream",
                            "*/*"
                        )
                    }

                    FontSlotControlBlock(
                        slot = FontSlot.TITLE,
                        fontPath = settings.titleFontPath,
                        fontScale = settings.titleFontScale,
                        onPickFont = {
                            pendingFontSlot = FontSlot.TITLE
                            fontPickerLauncher.launch(fontMimeTypes)
                        },
                        onResetFont = { onClearFont(FontSlot.TITLE) },
                        onScaleChange = { scale ->
                            onUpdateSettings { it.copy(titleFontScale = scale) }
                        }
                    )

                    FontSlotControlBlock(
                        slot = FontSlot.BODY,
                        fontPath = settings.bodyFontPath,
                        fontScale = settings.bodyFontScale,
                        onPickFont = {
                            pendingFontSlot = FontSlot.BODY
                            fontPickerLauncher.launch(fontMimeTypes)
                        },
                        onResetFont = { onClearFont(FontSlot.BODY) },
                        onScaleChange = { scale ->
                            onUpdateSettings { it.copy(bodyFontScale = scale) }
                        }
                    )

                    FontSlotControlBlock(
                        slot = FontSlot.TERMINAL,
                        fontPath = settings.terminalFontPath,
                        fontScale = settings.terminalFontScale,
                        onPickFont = {
                            pendingFontSlot = FontSlot.TERMINAL
                            fontPickerLauncher.launch(fontMimeTypes)
                        },
                        onResetFont = { onClearFont(FontSlot.TERMINAL) },
                        onScaleChange = { scale ->
                            onUpdateSettings { it.copy(terminalFontScale = scale) }
                        }
                    )
                }

                // Section 4: Export / Import (.aiotheme) & Factory Reset
                AccordionCustomizationSection(
                    title = "Exportar e Importar Tema (.aiotheme)",
                    subtitle = "Empaqueta colores, texturas y fuentes para compartir o restaurar",
                    icon = Icons.Default.Share,
                    iconTint = SpaceAmber,
                    expanded = themePackExpanded,
                    onToggle = { themePackExpanded = !themePackExpanded },
                    headerTag = "section_theme_pack"
                ) {
                    Text(
                        text = "Comparte tu tema completo como paquete .aiotheme (incluye colores, fondo, texturas y fuentes activas).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    AioActionButton(
                        onClick = {
                            onExportThemePackage { shareUri ->
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    putExtra(Intent.EXTRA_SUBJECT, "Tema AIO Tunnel VPN (.aiotheme)")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(sendIntent, "Exportar tema (.aiotheme)")
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("theme_export_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Exportar tema (.aiotheme)")
                    }

                    OutlinedButton(
                        onClick = {
                            themeImportLauncher.launch(
                                arrayOf("application/zip", "application/octet-stream", "*/*")
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .testTag("theme_import_button")
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Importar tema (.aiotheme)")
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    OutlinedButton(
                        onClick = onRestoreFactoryDefaults,
                        border = BorderStroke(1.dp, SpaceCoral.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SpaceCoral),
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .testTag("ui_factory_reset_button")
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Restaurar aspecto de fábrica")
                    }
                }
            }
        }
    }
}
