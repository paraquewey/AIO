package com.example.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.domain.models.TunnelProfile
import com.example.ui.theme.LocalAioUiStyle
import com.example.ui.theme.SpaceBgDeep
import com.example.ui.theme.SpaceBgNavy
import com.example.ui.theme.SpaceEmerald
import com.example.ui.theme.SpaceGlassHighlight
import com.example.ui.theme.SpaceViolet
import com.example.ui.theme.TextureOverlayLayer
import com.example.ui.theme.TextureSlot
import com.example.ui.theme.rememberDecodedTextureBitmap
import com.example.vpn.tun.DnsManager

@Composable
fun SpaceGlassBackground(
    isConnected: Boolean,
    useLightTheme: Boolean = false,
    customBackgroundPath: String? = null,
    customBackgroundVersion: Long = 0L,
    backgroundDimmerAlpha: Float = 0.35f,
    primaryAccent: Color = SpaceViolet,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val customBitmap = rememberDecodedTextureBitmap(
        path = customBackgroundPath,
        version = customBackgroundVersion,
        maxDimensionPx = 1440
    )

    val topGlowColor by animateColorAsState(
        targetValue = if (isConnected) {
            SpaceEmerald.copy(alpha = if (customBitmap != null) 0.16f else if (useLightTheme) 0.14f else 0.12f)
        } else {
            primaryAccent.copy(alpha = if (customBitmap != null) 0.14f else if (useLightTheme) 0.10f else 0.16f)
        },
        animationSpec = tween(500),
        label = "spaceGlowTop"
    )
    val secondaryGlowColor by animateColorAsState(
        targetValue = if (isConnected) {
            primaryAccent.copy(alpha = if (customBitmap != null) 0.10f else if (useLightTheme) 0.08f else 0.12f)
        } else {
            SpaceEmerald.copy(alpha = if (useLightTheme) 0.04f else 0.05f)
        },
        animationSpec = tween(500),
        label = "spaceGlowSecondary"
    )

    val baseBrush = if (useLightTheme) {
        Brush.verticalGradient(listOf(Color(0xFFF3F5FB), Color(0xFFE9EDF8)))
    } else {
        Brush.verticalGradient(listOf(SpaceBgDeep, SpaceBgNavy))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBrush)
    ) {
        if (customBitmap != null) {
            Image(
                bitmap = customBitmap,
                contentDescription = "Fondo de pantalla personalizado",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Adjustable dimmer overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = backgroundDimmerAlpha.coerceIn(0f, 0.85f)))
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Subtle diffused radial nebula glows behind connection hero area
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(topGlowColor, Color.Transparent),
                    center = Offset(w * 0.5f, h * 0.27f),
                    radius = w * 0.85f
                ),
                radius = w * 0.85f,
                center = Offset(w * 0.5f, h * 0.27f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(secondaryGlowColor, Color.Transparent),
                    center = Offset(w * 0.82f, h * 0.72f),
                    radius = w * 0.65f
                ),
                radius = w * 0.65f,
                center = Offset(w * 0.82f, h * 0.72f)
            )

            // Faint space star-dot grid only when default space wallpaper is active
            if (customBitmap == null) {
                val stepPx = 38.dp.toPx()
                val dotColor = if (useLightTheme) {
                    Color(0xFF394168).copy(alpha = 0.06f)
                } else {
                    Color.White.copy(alpha = 0.045f)
                }
                var y = stepPx * 0.5f
                var row = 0
                while (y < h) {
                    var x = stepPx * 0.5f
                    var col = 0
                    while (x < w) {
                        val radius = if ((row + col) % 7 == 0) 1.35.dp.toPx() else 0.75.dp.toPx()
                        drawCircle(color = dotColor, radius = radius, center = Offset(x, y))
                        x += stepPx
                        col++
                    }
                    y += stepPx
                    row++
                }
            }
        }
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    cornerRadius: Dp = 20.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val uiStyle = LocalAioUiStyle.current
    val borderBrush = if (borderColor != null) {
        Brush.verticalGradient(
            listOf(
                borderColor,
                borderColor.copy(alpha = 0.28f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                uiStyle.cardBorderColor,
                uiStyle.cardBorderSubtleColor
            )
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (uiStyle.isLightForeground) 10.dp else 4.dp,
                shape = shape,
                ambientColor = uiStyle.primaryAccent.copy(alpha = 0.14f),
                spotColor = Color.Black.copy(alpha = 0.35f)
            )
            .border(1.dp, borderBrush, shape),
        shape = shape,
        color = uiStyle.cardBackgroundColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            TextureOverlayLayer(
                texturePath = uiStyle.cardTexturePath,
                textureVersion = uiStyle.cardTextureVersion,
                textureAlpha = uiStyle.cardTextureAlpha,
                scaleMode = uiStyle.cardTextureMode,
                maxDimensionPx = TextureSlot.CARD.maxDimensionPx,
                isLightForeground = uiStyle.isLightForeground,
                modifier = Modifier.matchParentSize()
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                if (uiStyle.isLightForeground) SpaceGlassHighlight else Color.White.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(contentPadding),
                content = content
            )
        }
    }
}

private val COUNTRY_CUE_MAP = listOf(
    listOf("argentina", "[ar]", " ar ", "(ar)") to "AR",
    listOf("brasil", "brazil", "[br]", " br ", "(br)") to "BR",
    listOf("chile", "[cl]", " cl ", "(cl)") to "CL",
    listOf("colombia", "[co]", " co ", "(co)") to "CO",
    listOf("mexico", "méxico", "[mx]", " mx ", "(mx)") to "MX",
    listOf("peru", "perú", "[pe]", " pe ", "(pe)") to "PE",
    listOf("ecuador", "[ec]", " ec ", "(ec)") to "EC",
    listOf("usa", "estados unidos", "united states", "miami", "new york", "dallas", "[us]", " us ") to "US",
    listOf("canada", "canadá", "toronto", "montreal", "[ca]", " ca ") to "CA",
    listOf("españa", "spain", "madrid", "[es]", " es ") to "ES",
    listOf("alemania", "germany", "frankfurt", "[de]", " de ") to "DE",
    listOf("francia", "france", "paris", "[fr]", " fr ") to "FR",
    listOf("holanda", "netherlands", "amsterdam", "[nl]", " nl ") to "NL",
    listOf("reino unido", "uk", "london", "londres", "[gb]", " gb ") to "GB",
    listOf("singapur", "singapore", "[sg]", " sg ") to "SG",
    listOf("japon", "japón", "japan", "tokyo", "[jp]", " jp ") to "JP"
)

fun inferCountryFlagForProfile(profile: TunnelProfile): String? {
    val name = profile.name
    val cps = name.codePoints().toArray()
    for (i in 0 until cps.size - 1) {
        if (cps[i] in 0x1F1E6..0x1F1FF && cps[i + 1] in 0x1F1E6..0x1F1FF) {
            return String(intArrayOf(cps[i], cps[i + 1]), 0, 2)
        }
    }

    val haystack = " ${profile.name} ${profile.category} ${profile.tags.joinToString(" ")} ".lowercase()
    for ((cues, iso) in COUNTRY_CUE_MAP) {
        if (cues.any { haystack.contains(it) }) {
            return DnsManager.countryCodeToFlagEmoji(iso)
        }
    }
    return null
}
