package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val AioAmoledColorScheme = darkColorScheme(
    primary = GruvboxOrange,
    onPrimary = AmoledBlack,
    primaryContainer = Color(0xFF3A210D),
    onPrimaryContainer = GruvboxYellow,
    secondary = GruvboxGreen,
    onSecondary = AmoledBlack,
    secondaryContainer = Color(0xFF23280D),
    onSecondaryContainer = GruvboxAqua,
    tertiary = GruvboxYellow,
    onTertiary = AmoledBlack,
    tertiaryContainer = Color(0xFF352A0E),
    onTertiaryContainer = GruvboxFg0,
    background = AmoledBlack,
    onBackground = GruvboxFg0,
    surface = GruvboxBg0,
    onSurface = GruvboxFg1,
    surfaceVariant = GruvboxBg1,
    onSurfaceVariant = GruvboxFg3,
    surfaceContainerLowest = AmoledBlack,
    surfaceContainerLow = GruvboxBgHard,
    surfaceContainer = GruvboxBg1,
    surfaceContainerHigh = GruvboxBg2,
    surfaceContainerHighest = GruvboxBg3,
    outline = GruvboxBorder,
    outlineVariant = GruvboxBg3,
    error = GruvboxRed,
    onError = AmoledBlack,
    errorContainer = Color(0xFF3E1411),
    onErrorContainer = Color(0xFFFFB4AB)
)

private val AioWarmLightColorScheme = lightColorScheme(
    primary = GruvboxOrangeDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0C2),
    onPrimaryContainer = Color(0xFF3B1A00),
    secondary = Color(0xFF687111),
    onSecondary = Color.White,
    background = Color(0xFFFBF1C7),
    onBackground = Color(0xFF282828),
    surface = Color(0xFFF2E5BC),
    onSurface = Color(0xFF282828),
    surfaceVariant = Color(0xFFEBDBB2),
    onSurfaceVariant = Color(0xFF504945),
    outline = Color(0xFFA89984),
    error = Color(0xFF9D0006),
    onError = Color.White
)

val AioShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AioTunnelTheme(
    useLightTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (useLightTheme) AioWarmLightColorScheme else AioAmoledColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AioTypography,
        shapes = AioShapes,
        content = content
    )
}
