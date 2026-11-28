package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.models.FontColorMode
import com.example.domain.models.TextureScaleMode

private fun TextStyle.applyFontAndScale(
    fontFamily: FontFamily,
    scale: Float,
    shadow: Shadow?
): TextStyle {
    val clampedScale = scale.coerceIn(0.80f, 1.35f)
    val scaledSize: TextUnit = if (fontSize != TextUnit.Unspecified) {
        (fontSize.value * clampedScale).sp
    } else {
        fontSize
    }
    val scaledLineHeight: TextUnit = if (lineHeight != TextUnit.Unspecified) {
        (lineHeight.value * clampedScale).sp
    } else {
        lineHeight
    }
    return copy(
        fontFamily = fontFamily,
        fontSize = scaledSize,
        lineHeight = scaledLineHeight,
        shadow = shadow
    )
}

private fun buildAdaptiveTypography(
    shadow: Shadow?,
    titleFontFamily: FontFamily,
    titleFontScale: Float,
    bodyFontFamily: FontFamily,
    bodyFontScale: Float,
    terminalFontFamily: FontFamily,
    terminalFontScale: Float
): Typography {
    return Typography(
        displayLarge = AioTypography.displayLarge.applyFontAndScale(titleFontFamily, titleFontScale, shadow),
        displayMedium = AioTypography.displayMedium.applyFontAndScale(titleFontFamily, titleFontScale, shadow),
        displaySmall = AioTypography.displaySmall.applyFontAndScale(titleFontFamily, titleFontScale, shadow),
        headlineLarge = AioTypography.headlineLarge.applyFontAndScale(titleFontFamily, titleFontScale, shadow),
        headlineMedium = AioTypography.headlineMedium.applyFontAndScale(titleFontFamily, titleFontScale, shadow),
        headlineSmall = AioTypography.headlineSmall.applyFontAndScale(titleFontFamily, titleFontScale, shadow),
        titleLarge = AioTypography.titleLarge.applyFontAndScale(titleFontFamily, titleFontScale, shadow),
        titleMedium = AioTypography.titleMedium.applyFontAndScale(titleFontFamily, titleFontScale, shadow),
        titleSmall = AioTypography.titleSmall.applyFontAndScale(titleFontFamily, titleFontScale, shadow),
        bodyLarge = AioTypography.bodyLarge.applyFontAndScale(bodyFontFamily, bodyFontScale, shadow),
        bodyMedium = AioTypography.bodyMedium.applyFontAndScale(bodyFontFamily, bodyFontScale, shadow),
        bodySmall = AioTypography.bodySmall.applyFontAndScale(bodyFontFamily, bodyFontScale, shadow),
        labelLarge = AioTypography.labelLarge.applyFontAndScale(bodyFontFamily, bodyFontScale, shadow),
        labelMedium = AioTypography.labelMedium.applyFontAndScale(terminalFontFamily, terminalFontScale, shadow),
        labelSmall = AioTypography.labelSmall.applyFontAndScale(bodyFontFamily, bodyFontScale, shadow)
    )
}

private fun buildSpaceGlassDarkColorScheme(
    primaryAccent: Color,
    isLightForeground: Boolean
) = darkColorScheme(
    primary = primaryAccent,
    onPrimary = computeContrastingOnAccent(primaryAccent),
    primaryContainer = lerp(Color(0xFF14182B), primaryAccent, 0.28f),
    onPrimaryContainer = lerp(primaryAccent, Color.White, 0.72f),
    secondary = SpaceEmerald,
    onSecondary = Color(0xFF021F17),
    secondaryContainer = Color(0xFF09382C),
    onSecondaryContainer = SpaceEmerald,
    tertiary = SpaceCyan,
    onTertiary = Color(0xFF031E2E),
    tertiaryContainer = Color(0xFF112C44),
    onTertiaryContainer = Color(0xFFC2EBFF),
    background = SpaceBgDeep,
    onBackground = if (isLightForeground) Color(0xFFF8FAFC) else Color(0xFF0F172A),
    surface = SpaceBgNavy,
    onSurface = if (isLightForeground) Color(0xFFF8FAFC) else Color(0xFF0F172A),
    surfaceVariant = SpaceBgElevated,
    onSurfaceVariant = if (isLightForeground) Color(0xFFCBD5E1) else Color(0xFF334155),
    surfaceContainerLowest = SpaceBgDeep,
    surfaceContainerLow = SpaceBgNavy,
    surfaceContainer = Color(0xFF121524),
    surfaceContainerHigh = SpaceBgElevated,
    surfaceContainerHighest = Color(0xFF1E243D),
    outline = SpaceBorderLight,
    outlineVariant = SpaceDivider,
    error = SpaceCoral,
    onError = Color.White,
    errorContainer = Color(0xFF421220),
    onErrorContainer = Color(0xFFFFC2CE)
)

private fun buildSpaceGlassLightColorScheme(
    primaryAccent: Color,
    isLightForeground: Boolean
) = lightColorScheme(
    primary = primaryAccent,
    onPrimary = computeContrastingOnAccent(primaryAccent),
    primaryContainer = lerp(Color.White, primaryAccent, 0.16f),
    onPrimaryContainer = lerp(primaryAccent, Color.Black, 0.55f),
    secondary = SpaceEmeraldDim,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF04402D),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color.White,
    background = Color(0xFFF3F5FB),
    onBackground = if (isLightForeground) Color(0xFFF8FAFC) else Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = if (isLightForeground) Color(0xFFF8FAFC) else Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE6EAF5),
    onSurfaceVariant = if (isLightForeground) Color(0xFFCBD5E1) else Color(0xFF334155),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FD),
    surfaceContainer = Color(0xFFEDF0F8),
    surfaceContainerHigh = Color(0xFFE3E7F3),
    surfaceContainerHighest = Color(0xFFD9DFEE),
    outline = Color(0xFFC0C8DE),
    outlineVariant = Color(0xFFDCE1F0),
    error = Color(0xFFD92D51),
    onError = Color.White,
    errorContainer = Color(0xFFFFDCE3),
    onErrorContainer = Color(0xFF630A1E)
)

val AioShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun AioTunnelTheme(
    useLightTheme: Boolean = false,
    primaryAccent: Color = SpaceViolet,
    fontColorMode: FontColorMode = FontColorMode.ADAPTIVE,
    effectiveBackgroundLuminance: Float = if (useLightTheme) 0.92f else 0.04f,
    hasCustomBackground: Boolean = false,
    cardTexturePath: String? = null,
    cardTextureMode: TextureScaleMode = TextureScaleMode.CROP,
    cardTextureAlpha: Float = 0.45f,
    cardTextureVersion: Long = 0L,
    heroButtonTexturePath: String? = null,
    heroButtonTextureAlpha: Float = 0.65f,
    heroButtonTextureVersion: Long = 0L,
    actionButtonTexturePath: String? = null,
    actionButtonTextureAlpha: Float = 0.55f,
    actionButtonTextureVersion: Long = 0L,
    navBarTexturePath: String? = null,
    navBarTextureAlpha: Float = 0.60f,
    navBarTextureVersion: Long = 0L,
    titleFontPath: String? = null,
    titleFontScale: Float = 1.0f,
    titleFontVersion: Long = 0L,
    bodyFontPath: String? = null,
    bodyFontScale: Float = 1.0f,
    bodyFontVersion: Long = 0L,
    terminalFontPath: String? = null,
    terminalFontScale: Float = 1.0f,
    terminalFontVersion: Long = 0L,
    onFontWarning: (String) -> Unit = {},
    content: @Composable () -> Unit
) {
    val isLightForeground = remember(fontColorMode, effectiveBackgroundLuminance) {
        when (fontColorMode) {
            FontColorMode.ADAPTIVE -> effectiveBackgroundLuminance < 0.5f
            FontColorMode.FORCED_WHITE -> true
            FontColorMode.FORCED_BLACK -> false
        }
    }

    val colorScheme = remember(useLightTheme, primaryAccent, isLightForeground) {
        if (!isLightForeground) {
            buildSpaceGlassLightColorScheme(primaryAccent = primaryAccent, isLightForeground = false)
        } else {
            buildSpaceGlassDarkColorScheme(primaryAccent = primaryAccent, isLightForeground = true)
        }
    }

    val textShadow = remember(isLightForeground, hasCustomBackground) {
        buildForegroundTextShadow(isLightForeground = isLightForeground, hasCustomBackground = hasCustomBackground)
    }

    val titleFontResult = remember(titleFontPath, titleFontVersion) {
        loadSafeFontFamily(titleFontPath)
    }
    val bodyFontResult = remember(bodyFontPath, bodyFontVersion) {
        loadSafeFontFamily(bodyFontPath)
    }
    val terminalFontResult = remember(terminalFontPath, terminalFontVersion) {
        loadSafeFontFamily(terminalFontPath)
    }

    LaunchedEffect(titleFontResult.errorMessage) {
        titleFontResult.errorMessage?.let(onFontWarning)
    }
    LaunchedEffect(bodyFontResult.errorMessage) {
        bodyFontResult.errorMessage?.let(onFontWarning)
    }
    LaunchedEffect(terminalFontResult.errorMessage) {
        terminalFontResult.errorMessage?.let(onFontWarning)
    }

    val resolvedTitleFont = titleFontResult.fontFamily ?: SpaceGroteskFamily
    val resolvedBodyFont = bodyFontResult.fontFamily ?: PlusJakartaFamily
    val resolvedTerminalFont = terminalFontResult.fontFamily ?: JetBrainsMonoFamily

    val typography = remember(
        textShadow,
        resolvedTitleFont,
        titleFontScale,
        resolvedBodyFont,
        bodyFontScale,
        resolvedTerminalFont,
        terminalFontScale
    ) {
        buildAdaptiveTypography(
            shadow = textShadow,
            titleFontFamily = resolvedTitleFont,
            titleFontScale = titleFontScale,
            bodyFontFamily = resolvedBodyFont,
            bodyFontScale = bodyFontScale,
            terminalFontFamily = resolvedTerminalFont,
            terminalFontScale = terminalFontScale
        )
    }

    val uiStyle = remember(
        isLightForeground,
        hasCustomBackground,
        effectiveBackgroundLuminance,
        primaryAccent,
        textShadow,
        cardTexturePath,
        cardTextureMode,
        cardTextureAlpha,
        cardTextureVersion,
        heroButtonTexturePath,
        heroButtonTextureAlpha,
        heroButtonTextureVersion,
        actionButtonTexturePath,
        actionButtonTextureAlpha,
        actionButtonTextureVersion,
        navBarTexturePath,
        navBarTextureAlpha,
        navBarTextureVersion,
        resolvedTitleFont,
        titleFontScale,
        resolvedBodyFont,
        bodyFontScale,
        resolvedTerminalFont,
        terminalFontScale
    ) {
        val cardBg = if (isLightForeground) {
            if (hasCustomBackground) Color(0xCC0D111E) else SpaceGlassSurface
        } else {
            if (hasCustomBackground) Color(0xE0F4F7FC) else Color(0xEBFFFFFF)
        }
        val cardElevated = if (isLightForeground) {
            if (hasCustomBackground) Color(0xE0111629) else SpaceGlassSurfaceElevated
        } else {
            Color(0xF2F8FAFC)
        }
        val cardBorder = if (isLightForeground) {
            SpaceBorderLight
        } else {
            Color(0x330F172A)
        }
        val cardBorderSubtle = if (isLightForeground) {
            SpaceBorderSubtle
        } else {
            Color(0x140F172A)
        }
        AioUiStyle(
            isLightForeground = isLightForeground,
            hasCustomBackground = hasCustomBackground,
            effectiveLuminance = effectiveBackgroundLuminance,
            primaryAccent = primaryAccent,
            cardBackgroundColor = cardBg,
            cardElevatedColor = cardElevated,
            cardBorderColor = cardBorder,
            cardBorderSubtleColor = cardBorderSubtle,
            textShadow = textShadow,
            cardTexturePath = cardTexturePath,
            cardTextureMode = cardTextureMode,
            cardTextureAlpha = cardTextureAlpha.coerceIn(0f, 1f),
            cardTextureVersion = cardTextureVersion,
            heroButtonTexturePath = heroButtonTexturePath,
            heroButtonTextureAlpha = heroButtonTextureAlpha.coerceIn(0f, 1f),
            heroButtonTextureVersion = heroButtonTextureVersion,
            actionButtonTexturePath = actionButtonTexturePath,
            actionButtonTextureAlpha = actionButtonTextureAlpha.coerceIn(0f, 1f),
            actionButtonTextureVersion = actionButtonTextureVersion,
            navBarTexturePath = navBarTexturePath,
            navBarTextureAlpha = navBarTextureAlpha.coerceIn(0f, 1f),
            navBarTextureVersion = navBarTextureVersion,
            titleFontFamily = resolvedTitleFont,
            titleFontScale = titleFontScale.coerceIn(0.80f, 1.35f),
            bodyFontFamily = resolvedBodyFont,
            bodyFontScale = bodyFontScale.coerceIn(0.80f, 1.35f),
            terminalFontFamily = resolvedTerminalFont,
            terminalFontScale = terminalFontScale.coerceIn(0.80f, 1.35f)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = AioShapes
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colorScheme.onBackground,
            LocalAioUiStyle provides uiStyle,
            content = content
        )
    }
}
