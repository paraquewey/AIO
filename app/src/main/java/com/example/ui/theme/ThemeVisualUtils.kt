package com.example.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import com.example.domain.models.TextureScaleMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
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

enum class TextureSlot(
    val id: String,
    val label: String,
    val maxDimensionPx: Int
) {
    CARD("card", "Tarjetas / Contenedores", 1024),
    HERO_BUTTON("hero", "Botón circular CONECTAR", 512),
    ACTION_BUTTON("action", "Botones de acción", 512),
    NAV_BAR("navbar", "Barra de navegación y TopBar", 1024)
}

enum class FontSlot(
    val id: String,
    val label: String
) {
    TITLE("title", "Títulos y cronómetro"),
    BODY("body", "Subtítulos y cuerpo de UI"),
    TERMINAL("terminal", "Consola y datos técnicos")
}

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
    val textShadow: Shadow? = null,
    // Component textures
    val cardTexturePath: String? = null,
    val cardTextureMode: TextureScaleMode = TextureScaleMode.CROP,
    val cardTextureAlpha: Float = 0.45f,
    val cardTextureVersion: Long = 0L,
    val heroButtonTexturePath: String? = null,
    val heroButtonTextureAlpha: Float = 0.65f,
    val heroButtonTextureVersion: Long = 0L,
    val actionButtonTexturePath: String? = null,
    val actionButtonTextureAlpha: Float = 0.55f,
    val actionButtonTextureVersion: Long = 0L,
    val navBarTexturePath: String? = null,
    val navBarTextureAlpha: Float = 0.60f,
    val navBarTextureVersion: Long = 0L,
    // Granular typography
    val titleFontFamily: FontFamily = SpaceGroteskFamily,
    val titleFontScale: Float = 1.0f,
    val bodyFontFamily: FontFamily = PlusJakartaFamily,
    val bodyFontScale: Float = 1.0f,
    val terminalFontFamily: FontFamily = JetBrainsMonoFamily,
    val terminalFontScale: Float = 1.0f
)

val LocalAioUiStyle = staticCompositionLocalOf { AioUiStyle() }

data class FontLoadResult(
    val fontFamily: FontFamily?,
    val errorMessage: String? = null
)

// Bounded 24 MB memory cache for decoded ImageBitmap textures & wallpaper previews
private val textureBitmapCache: LruCache<String, ImageBitmap> =
    object : LruCache<String, ImageBitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            val kb = (value.width.toLong() * value.height.toLong() * 4L) / 1024L
            return kb.toInt().coerceAtLeast(1)
        }
    }

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

fun getCachedTextureImageBitmap(path: String?, version: Long, maxDimensionPx: Int): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return textureBitmapCache.get("$path|$version|$maxDimensionPx")
}

fun decodeSampledTextureImageBitmap(
    path: String?,
    version: Long,
    maxDimensionPx: Int
): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    val key = "$path|$version|$maxDimensionPx"
    textureBitmapCache.get(key)?.let { return it }
    val bitmap = decodeSampledBackgroundBitmap(path, maxDimensionPx = maxDimensionPx) ?: return null
    val imageBitmap = bitmap.asImageBitmap()
    textureBitmapCache.put(key, imageBitmap)
    return imageBitmap
}

@Composable
fun rememberDecodedTextureBitmap(
    path: String?,
    version: Long,
    maxDimensionPx: Int
): ImageBitmap? {
    val initial = remember(path, version, maxDimensionPx) {
        getCachedTextureImageBitmap(path, version, maxDimensionPx)
    }
    val state by produceState<ImageBitmap?>(
        initialValue = initial,
        key1 = path,
        key2 = version,
        key3 = maxDimensionPx
    ) {
        value = if (path.isNullOrBlank()) {
            null
        } else {
            getCachedTextureImageBitmap(path, version, maxDimensionPx)
                ?: withContext(Dispatchers.IO) {
                    decodeSampledTextureImageBitmap(path, version, maxDimensionPx)
                }
        }
    }
    return state
}

fun createTiledTextureBrush(imageBitmap: ImageBitmap): ShaderBrush {
    return ShaderBrush(
        ImageShader(
            image = imageBitmap,
            tileModeX = TileMode.Repeated,
            tileModeY = TileMode.Repeated
        )
    )
}

@Composable
fun TextureOverlayLayer(
    texturePath: String?,
    textureVersion: Long,
    textureAlpha: Float,
    scaleMode: TextureScaleMode,
    maxDimensionPx: Int,
    isLightForeground: Boolean,
    modifier: Modifier = Modifier
) {
    if (texturePath.isNullOrBlank()) return
    val clampedAlpha = textureAlpha.coerceIn(0f, 1f)
    if (clampedAlpha <= 0.01f) return

    val bitmap = rememberDecodedTextureBitmap(
        path = texturePath,
        version = textureVersion,
        maxDimensionPx = maxDimensionPx
    ) ?: return

    Box(modifier = modifier) {
        when (scaleMode) {
            TextureScaleMode.CROP -> {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = clampedAlpha,
                    modifier = Modifier.fillMaxSize()
                )
            }
            TextureScaleMode.FILL -> {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    alpha = clampedAlpha,
                    modifier = Modifier.fillMaxSize()
                )
            }
            TextureScaleMode.TILE -> {
                val tiledBrush = remember(bitmap) { createTiledTextureBrush(bitmap) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(brush = tiledBrush, alpha = clampedAlpha)
                )
            }
        }

        // Smart contrast scrim so text over any texture remains crisp and legible
        val scrimColor = if (isLightForeground) {
            Color.Black.copy(alpha = (0.30f * clampedAlpha).coerceIn(0f, 0.42f))
        } else {
            Color.White.copy(alpha = (0.36f * clampedAlpha).coerceIn(0f, 0.48f))
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor)
        )
    }
}

fun isValidFontFile(file: File): Boolean {
    if (!file.exists() || !file.isFile || file.length() < 16L || file.length() > 20L * 1024L * 1024L) {
        return false
    }
    val header = ByteArray(4)
    val readCount = runCatching {
        FileInputStream(file).use { it.read(header) }
    }.getOrDefault(-1)
    if (readCount < 4) return false

    val isTrueType = header[0] == 0x00.toByte() &&
        header[1] == 0x01.toByte() &&
        header[2] == 0x00.toByte() &&
        header[3] == 0x00.toByte()
    val tag = String(header, Charsets.US_ASCII)
    val isSupportedHeader = isTrueType || tag == "OTTO" || tag == "true" || tag == "ttcf"
    if (!isSupportedHeader) return false

    return runCatching {
        val typeface = Typeface.createFromFile(file)
        typeface != null
    }.getOrDefault(false)
}

fun loadSafeFontFamily(path: String?): FontLoadResult {
    if (path.isNullOrBlank()) return FontLoadResult(fontFamily = null, errorMessage = null)
    val file = File(path)
    if (!isValidFontFile(file)) {
        return FontLoadResult(
            fontFamily = null,
            errorMessage = "Archivo de fuente inválido o corrupto (${file.name}). Se usa la fuente predeterminada."
        )
    }
    return try {
        val typeface = Typeface.createFromFile(file)
        FontLoadResult(fontFamily = FontFamily(typeface), errorMessage = null)
    } catch (t: Throwable) {
        FontLoadResult(
            fontFamily = null,
            errorMessage = "No se pudo aplicar la fuente (${t.message ?: "error de lectura"}). Se usa la fuente predeterminada."
        )
    }
}

fun extractFontDisplayName(path: String?): String? {
    if (path.isNullOrBlank()) return null
    val rawName = File(path).name
    val idx = rawName.indexOf('_')
    return if (idx in 1 until rawName.length - 1) {
        rawName.substring(idx + 1)
    } else {
        rawName
    }
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
