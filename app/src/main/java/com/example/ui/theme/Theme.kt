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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.example.domain.models.FontColorMode

private fun buildAdaptiveTypography(shadow: Shadow?): Typography {
    if (shadow == null) return AioTypography
    return Typography(
        displayLarge = AioTypography.displayLarge.copy(shadow = shadow),
        displayMedium = AioTypography.displayMedium.copy(shadow = shadow),
        displaySmall = AioTypography.displaySmall.copy(shadow = shadow),
        headlineLarge = AioTypography.headlineLarge.copy(shadow = shadow),
        headlineMedium = AioTypography.headlineMedium.copy(shadow = shadow),
        headlineSmall = AioTypography.headlineSmall.copy(shadow = shadow),
        titleLarge = AioTypography.titleLarge.copy(shadow = shadow),
        titleMedium = AioTypography.titleMedium.copy(shadow = shadow),
        titleSmall = AioTypography.titleSmall.copy(shadow = shadow),
        bodyLarge = AioTypography.bodyLarge.copy(shadow = shadow),
        bodyMedium = AioTypography.bodyMedium.copy(shadow = shadow),
        bodySmall = AioTypography.bodySmall.copy(shadow = shadow),
        labelLarge = AioTypography.labelLarge.copy(shadow = shadow),
        labelMedium = AioTypography.labelMedium.copy(shadow = shadow),
        labelSmall = AioTypography.labelSmall.copy(shadow = shadow)
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

    val typography = remember(textShadow) {
        buildAdaptiveTypography(textShadow)
    }

    val uiStyle = remember(
        isLightForeground,
        hasCustomBackground,
        effectiveBackgroundLuminance,
        primaryAccent,
        textShadow
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
            textShadow = textShadow
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

