package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SpaceGlassDarkColorScheme = darkColorScheme(
    primary = SpaceViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF281F5C),
    onPrimaryContainer = Color(0xFFDCD6FF),
    secondary = SpaceEmerald,
    onSecondary = Color(0xFF021F17),
    secondaryContainer = Color(0xFF09382C),
    onSecondaryContainer = SpaceEmerald,
    tertiary = SpaceCyan,
    onTertiary = Color(0xFF031E2E),
    tertiaryContainer = Color(0xFF112C44),
    onTertiaryContainer = Color(0xFFC2EBFF),
    background = SpaceBgDeep,
    onBackground = SpaceTextPrimary,
    surface = SpaceBgNavy,
    onSurface = SpaceTextPrimary,
    surfaceVariant = SpaceBgElevated,
    onSurfaceVariant = SpaceTextSecondary,
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

private val SpaceGlassLightColorScheme = lightColorScheme(
    primary = SpaceIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E2FF),
    onPrimaryContainer = Color(0xFF211466),
    secondary = SpaceEmeraldDim,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF04402D),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color.White,
    background = Color(0xFFF3F5FB),
    onBackground = Color(0xFF101322),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101322),
    surfaceVariant = Color(0xFFE6EAF5),
    onSurfaceVariant = Color(0xFF4B5470),
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
    content: @Composable () -> Unit
) {
    val colorScheme = if (useLightTheme) SpaceGlassLightColorScheme else SpaceGlassDarkColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AioTypography,
        shapes = AioShapes,
        content = content
    )
}
