package com.example.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

val ACCENT_PRESET_COLORS: List<Long> = listOf(
    0xFF7B61FFL, // Space Violet (default)
    0xFF00E5A8L, // Emerald Glow
    0xFF38BDF8L, // Nebula Cyan
    0xFF6366F1L, // Cosmic Indigo
    0xFFFF4D6DL, // Nova Coral
    0xFFFFB800L, // Solar Amber
    0xFFEC4899L, // Magenta Pulse
    0xFF2DD4BFL  // Aurora Teal
)

@Immutable
data class AioUiStyle(
    val isLightForeground: Boolean = true,
    val hasCustomBackground: Boolean = false,
    val effectiveLuminance: Float = 0.04f,
    val primaryAccent: Color = SpaceViolet,
    val cardBackgroundColor: Color = SpaceGlassSurface,
    val cardElevatedColor: Color = SpaceGlassSurfaceElevated,
    val cardBorderColor: Color = SpaceBorderLight,
    val cardBorderSubtleColor: Color = SpaceBorderSubtle,
    val textShadow: Shadow? = null
)

val LocalAioUiStyle = staticCompositionLocalOf { AioUiStyle() }

fun computeRelativeLuminance(color: Color): Float {
    return (0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue).coerceIn(0f, 1f)
}

fun computeContrastingOnAccent(accent: Color): Color {
    return if (computeRelativeLuminance(accent) > 0.56f) {
        Color(0xFF0B1020)
    } else {
        Color.White
    }
}

fun computeEffectiveBackgroundLuminance(
    hasCustomBg: Boolean,
    rawBgLuminance: Float,
    dimmerAlpha: Float,
    useLightTheme: Boolean
): Float {
    return if (hasCustomBg) {
        val clampedRaw = rawBgLuminance.coerceIn(0f, 1f)
        val clampedDimmer = dimmerAlpha.coerceIn(0f, 0.85f)
        // Blended over black dimmer overlay: final L = rawLuminance * (1 - dimmerAlpha)
        (clampedRaw * (1f - clampedDimmer)).coerceIn(0f, 1f)
    } else {
        if (useLightTheme) 0.92f else 0.04f
    }
}

fun computeBitmapAverageLuminance(bitmap: Bitmap): Float {
    val maxSampleSide = 48
    val width = bitmap.width.coerceAtLeast(1)
    val height = bitmap.height.coerceAtLeast(1)
    val scale = minOf(1f, maxSampleSide.toFloat() / max(width, height).toFloat())
    val targetW = (width * scale).roundToInt().coerceAtLeast(1)
    val targetH = (height * scale).roundToInt().coerceAtLeast(1)

    val sampled = if (targetW < width || targetH < height) {
        Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    } else {
        bitmap
    }

    return try {
        val pixels = IntArray(sampled.width * sampled.height)
        sampled.getPixels(pixels, 0, sampled.width, 0, 0, sampled.width, sampled.height)
        if (pixels.isEmpty()) return 0.5f
        var lumSum = 0.0
        for (px in pixels) {
            val r = AndroidColor.red(px) / 255.0
            val g = AndroidColor.green(px) / 255.0
            val b = AndroidColor.blue(px) / 255.0
            lumSum += (0.2126 * r + 0.7152 * g + 0.0722 * b)
        }
        (lumSum / pixels.size).toFloat().coerceIn(0f, 1f)
    } finally {
        if (sampled !== bitmap && !sampled.isRecycled) {
            sampled.recycle()
        }
    }
}

fun decodeSampledBackgroundBitmap(path: String?, maxDimensionPx: Int = 1440): Bitmap? {
    if (path.isNullOrBlank()) return null
    val file = File(path)
    if (!file.exists() || !file.isFile || file.length() <= 0L) return null

    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimensionPx || bounds.outHeight / sampleSize > maxDimensionPx) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()
}

fun buildForegroundTextShadow(
    isLightForeground: Boolean,
    hasCustomBackground: Boolean
): Shadow? {
    return when {
        hasCustomBackground && isLightForeground -> Shadow(
            color = Color.Black.copy(alpha = 0.70f),
            offset = Offset(0f, 1.5f),
            blurRadius = 5f
        )
        hasCustomBackground && !isLightForeground -> Shadow(
            color = Color.White.copy(alpha = 0.55f),
            offset = Offset(0f, 1f),
            blurRadius = 4f
        )
        isLightForeground -> Shadow(
            color = Color.Black.copy(alpha = 0.35f),
            offset = Offset(0f, 1f),
            blurRadius = 2.5f
        )
        else -> null
    }
}
