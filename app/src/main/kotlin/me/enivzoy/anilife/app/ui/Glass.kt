package me.enivzoy.anilife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.drawPlainBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.colorControls
import com.kashif_e.backdrop.highlight.Highlight
import com.kashif_e.backdrop.highlight.HighlightStyle
import com.kashif_e.backdrop.platform.PlatformCapabilities
import me.enivzoy.anilife.app.ui.theme.GlassStyle
import me.enivzoy.anilife.app.ui.theme.LocalAppearance
import me.enivzoy.anilife.app.ui.theme.glassStyle

val LocalGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

@Composable
fun GlassHost(content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    val style = LocalAppearance.current.themeMode.glassStyle
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AniLifeColors.Background)
                .drawBehind {
                    when (style) {
                        GlassStyle.LEGACY -> {
                            drawCircle(
                                color = AniLifeColors.Primary.copy(alpha = 0.10f),
                                radius = size.minDimension * 0.45f,
                                center = Offset(size.width * 0.15f, size.height * 0.10f),
                            )
                            drawCircle(
                                color = AniLifeColors.Success.copy(alpha = 0.08f),
                                radius = size.minDimension * 0.38f,
                                center = Offset(size.width * 0.88f, size.height * 0.85f),
                            )
                        }
                        GlassStyle.FROSTED -> {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        AniLifeColors.Primary.copy(alpha = 0.05f),
                                        Color.Transparent,
                                    ),
                                ),
                                size = size,
                            )
                            drawCircle(
                                color = Color(0xFFBFD8D2).copy(alpha = 0.05f),
                                radius = size.minDimension * 0.55f,
                                center = Offset(size.width * 0.12f, size.height * 0.05f),
                            )
                        }
                        GlassStyle.LIQUID -> {
                            drawCircle(
                                color = AniLifeColors.Primary.copy(alpha = 0.13f),
                                radius = size.minDimension * 0.50f,
                                center = Offset(size.width * 0.12f, size.height * 0.06f),
                            )
                            drawCircle(
                                color = AniLifeColors.Success.copy(alpha = 0.10f),
                                radius = size.minDimension * 0.42f,
                                center = Offset(size.width * 0.90f, size.height * 0.88f),
                            )
                        }
                    }
                }
                .layerBackdrop(backdrop),
        )
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) { content() }
    }
}

@Composable
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(16.dp),
    alpha: Float = 0.5f,
): Modifier = glassSurface(shape = shape, alpha = alpha)

@Composable
fun Modifier.adaptiveGlass(
    shape: Shape = RoundedCornerShape(16.dp),
    alpha: Float = 0.5f,
    elevation: Dp = 0.dp,
): Modifier {
    val style = LocalAppearance.current.themeMode.glassStyle
    return if (style == GlassStyle.LEGACY) {
        this
    } else {
        glassSurface(shape = shape, alpha = alpha, elevation = elevation)
    }
}

@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(16.dp),
    alpha: Float = 0.5f,
    elevation: Dp = 0.dp,
): Modifier {
    val appearance = LocalAppearance.current
    val style = appearance.themeMode.glassStyle
    val backdrop = LocalGlassBackdrop.current

    // The default theme intentionally keeps the original AniLife surface exactly as it was:
    // no extra border or highlight, the same blur, saturation and tint.
    if (style == GlassStyle.LEGACY) {
        val tint = AniLifeColors.SurfaceVariant.copy(alpha = alpha)
        if (backdrop == null) return background(tint, shape)
        val blurPx = with(LocalDensity.current) { 24.dp.toPx() }
        return drawPlainBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                blur(blurPx)
                colorControls(saturation = 1.4f)
            },
            onDrawSurface = { drawRect(tint) },
        )
    }

    val surfaceAlpha = (alpha * appearance.glassTransparency / 0.5f).coerceIn(0.18f, 0.86f)
    val borderAlpha = when (style) {
        GlassStyle.LEGACY -> 0.10f
        GlassStyle.FROSTED -> 0.18f
        GlassStyle.LIQUID -> 0.24f
    }
    val borderColor = when (style) {
        GlassStyle.LEGACY -> AniLifeColors.Border
        GlassStyle.FROSTED -> Color.White
        GlassStyle.LIQUID -> AniLifeColors.Primary
    }.copy(alpha = borderAlpha)
    val fallbackColor = when (style) {
        GlassStyle.LEGACY -> AniLifeColors.SurfaceVariant.copy(alpha = surfaceAlpha)
        GlassStyle.FROSTED -> Color(0xFFDCE7E2).copy(alpha = surfaceAlpha * 0.45f)
        GlassStyle.LIQUID -> AniLifeColors.SurfaceVariant.copy(alpha = surfaceAlpha * 0.8f)
    }
    val base = if (elevation > 0.dp) {
        this.shadow(elevation, shape, ambientColor = AniLifeColors.Shadow, spotColor = Color.Transparent)
    } else {
        this
    }

    if (backdrop == null || !PlatformCapabilities.supportsBlur) {
        return base
            .background(fallbackColor, shape)
            .border(1.dp, borderColor, shape)
    }

    val blurRadius = when (style) {
        GlassStyle.LEGACY -> 24.dp
        GlassStyle.FROSTED -> 16.dp
        GlassStyle.LIQUID -> 30.dp
    } * appearance.glassBlurIntensity
    val blurPx = with(LocalDensity.current) { blurRadius.toPx() }
    val saturation = when (style) {
        GlassStyle.LEGACY -> 1.4f
        GlassStyle.FROSTED -> 1.08f
        GlassStyle.LIQUID -> 1.55f
    }
    val tint = when (style) {
        GlassStyle.LEGACY -> AniLifeColors.SurfaceVariant.copy(alpha = surfaceAlpha)
        GlassStyle.FROSTED -> Color(0xFFDCE7E2).copy(alpha = surfaceAlpha * 0.45f)
        GlassStyle.LIQUID -> AniLifeColors.SurfaceVariant.copy(alpha = surfaceAlpha * 0.8f)
    }
    val highlightAlpha = (0.35f * appearance.glassHighlightIntensity).coerceIn(0f, 0.7f)
    return base
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                blur(blurPx)
                colorControls(saturation = saturation, brightness = 1.03f, contrast = 1.02f)
            },
            highlight = {
                Highlight(
                    width = 1.dp,
                    blurRadius = 3.dp,
                    alpha = if (style == GlassStyle.LIQUID) highlightAlpha else highlightAlpha * 0.4f,
                    style = if (style == GlassStyle.LIQUID) HighlightStyle.Ambient else HighlightStyle.Plain,
                )
            },
            onDrawSurface = { drawRect(tint) },
        )
        .border(1.dp, borderColor, shape)
}
