package me.enivzoy.anilife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.colorControls
import com.kashif_e.backdrop.effects.lens
import com.kashif_e.backdrop.effects.reflectiveGlass
import com.kashif_e.backdrop.highlight.Highlight
import com.kashif_e.backdrop.highlight.HighlightStyle
import com.kashif_e.backdrop.platform.PlatformCapabilities
import me.enivzoy.anilife.app.ui.theme.GlassStyle
import me.enivzoy.anilife.app.ui.theme.LocalAppearance
import me.enivzoy.anilife.app.ui.theme.glassStyle

/**
 * The backdrop sampled by every glass surface in the current subtree.
 *
 * Glass surfaces do not paint a translucent rectangle: they read the pixels of the layer
 * registered here, refract them, blur them and light the result. That is why the sampled
 * layer must be drawn *before* the glass surface, and must not contain the glass surface
 * itself (otherwise the surface would sample its own previous frame).
 */
val LocalGlassBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * Root host that registers the ambient wallpaper plane as the glass source.
 *
 * The wallpaper is a real drawn layer, not a colour constant, so moving a glass control
 * across the window changes what it refracts. Screens that contain artwork can register a
 * tighter, content-rich source with [GlassBackdrop] so their floating controls refract the
 * actual artwork instead.
 */
@Composable
fun GlassHost(content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    val style = LocalAppearance.current.themeMode.glassStyle
    Box(Modifier.fillMaxSize().background(AniLifeColors.Background)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            AmbientWallpaper(style)
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) { content() }
    }
}

/**
 * A layered, dark ambient plane. It gives glass controls something with depth and colour to
 * sample, while staying inside AniLife's palette.
 */
@Composable
private fun AmbientWallpaper(style: GlassStyle) {
    val primaryStrength = when (style) {
        GlassStyle.LEGACY -> 0.10f
        GlassStyle.FROSTED -> 0.08f
        GlassStyle.LIQUID -> 0.16f
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(AniLifeColors.Background)
                // Wide aurora fields: slow colour transitions give the glass something to bend.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(AniLifeColors.Primary.copy(alpha = primaryStrength), Color.Transparent),
                        center = Offset(size.width * 0.10f, size.height * 0.04f),
                        radius = size.minDimension * 0.85f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(AniLifeColors.Success.copy(alpha = primaryStrength * 0.7f), Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.90f),
                        radius = size.minDimension * 0.75f,
                    ),
                )
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1B3A2E).copy(alpha = primaryStrength * 0.8f),
                            Color.Transparent,
                            Color(0xFF10222E).copy(alpha = primaryStrength * 0.6f),
                        ),
                        start = Offset(size.width * 0.4f, 0f),
                        end = Offset(size.width * 0.8f, size.height),
                    ),
                )
                // A faint horizon band keeps large glass panels from reading as a flat tint.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.018f), Color.Transparent),
                        startY = size.height * 0.42f,
                        endY = size.height * 0.62f,
                    ),
                )
            },
    )
}

/**
 * Registers an artwork-rich layer as a local glass source and renders [content] above it.
 *
 * Use this around a hero image or video area so floating controls can genuinely refract the
 * artwork behind them:
 *
 *     GlassBackdrop(
 *         modifier = Modifier.fillMaxWidth().height(288.dp),
 *         source = { HeroImage(...) },
 *     ) {
 *         GlassIconButton(...)
 *     }
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    source: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    Box(modifier) {
        // Keep artwork/content in a dedicated source layer. Overlay controls are deliberately
        // composed after it so they sample the artwork, never their own previous frame.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop), content = source)
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            content()
        }
    }
}

@Composable
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(16.dp),
    alpha: Float = 0.5f,
    accent: Color? = null,
    interactionSource: MutableInteractionSource? = null,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
): Modifier = glassSurface(
    shape = shape,
    alpha = alpha,
    accent = accent,
    interactionSource = interactionSource,
    backdrop = backdrop,
)

@Composable
fun Modifier.adaptiveGlass(
    shape: Shape = RoundedCornerShape(16.dp),
    alpha: Float = 0.5f,
    elevation: Dp = 0.dp,
    accent: Color? = null,
    interactionSource: MutableInteractionSource? = null,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
): Modifier {
    val style = LocalAppearance.current.themeMode.glassStyle
    return if (style == GlassStyle.LEGACY) {
        this
    } else {
        glassSurface(shape, alpha, elevation, accent, interactionSource, backdrop)
    }
}

/**
 * The Liquid Glass renderer.
 *
 * Rendering pipeline for one surface:
 *  1. sample the registered backdrop layer,
 *  2. blur it (depth of field) and lift saturation/contrast,
 *  3. refract it: a Skia runtime shader displaces samples along the rounded-rect edge (lens)
 *     and adds a specular sweep, edge glow, chromatic dispersion and vignette,
 *  4. composite a tint, a top specular sheen and a pointer-localised light on the surface,
 *  5. draw a rim light that follows the shape.
 *
 * Interaction comes from [interactionSource]: hover/focus raise the ambient light, press
 * adds a small optical scale-down and a stronger specular response, and the pointer position
 * is tracked so the highlight is localised rather than uniform.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(16.dp),
    alpha: Float = 0.5f,
    elevation: Dp = 0.dp,
    accent: Color? = null,
    interactionSource: MutableInteractionSource? = null,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
): Modifier {
    val appearance = LocalAppearance.current
    val style = appearance.themeMode.glassStyle
    val density = LocalDensity.current

    val hovered by (interactionSource?.collectIsHoveredAsState() ?: remember { mutableStateOf(false) })
    val pressed by (interactionSource?.collectIsPressedAsState() ?: remember { mutableStateOf(false) })
    val focused by (interactionSource?.collectIsFocusedAsState() ?: remember { mutableStateOf(false) })
    val interactionTarget = when {
        pressed -> 1f
        hovered -> 0.55f
        focused -> 0.7f
        else -> 0f
    }
    val glowState = rememberAppFloat(interactionTarget)
    val pointer = remember { mutableStateOf(Offset.Unspecified) }

    val tintAlpha = when (style) {
        GlassStyle.LEGACY -> (alpha * appearance.glassTransparency / 0.5f * 0.42f)
        GlassStyle.FROSTED -> (alpha * appearance.glassTransparency / 0.5f * 0.30f)
        GlassStyle.LIQUID -> (alpha * appearance.glassTransparency / 0.5f * 0.22f)
    }.coerceIn(0.06f, 0.72f)

    val surfaceTint = when {
        accent != null -> accent.copy(alpha = (tintAlpha * 0.85f).coerceIn(0.08f, 0.6f))
        style == GlassStyle.FROSTED -> Color(0xFFDCE7E2).copy(alpha = tintAlpha * 0.55f)
        else -> AniLifeColors.SurfaceVariant.copy(alpha = tintAlpha)
    }
    val borderColor = when {
        accent != null -> accent.copy(alpha = 0.42f + 0.2f * interactionTarget)
        style == GlassStyle.LEGACY -> Color.White.copy(alpha = 0.09f + 0.08f * interactionTarget)
        style == GlassStyle.FROSTED -> Color.White.copy(alpha = 0.16f + 0.1f * interactionTarget)
        else -> AniLifeColors.Primary.copy(alpha = 0.16f + 0.14f * interactionTarget)
    }

    val base = if (elevation > 0.dp) {
        this.shadow(elevation, shape, ambientColor = AniLifeColors.Shadow, spotColor = Color.Transparent)
    } else {
        this
    }

    val tracked = base.onPointerEvent(PointerEventType.Move) { event ->
        val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
        pointer.value = position
    }.onPointerEvent(PointerEventType.Exit) { pointer.value = Offset.Unspecified }

    if (backdrop == null || !PlatformCapabilities.supportsBlur) {
        // Graceful fallback: keep the shape, depth and rim, drop sampling.
        return tracked
            .background(
                Brush.verticalGradient(
                    listOf(
                        surfaceTint,
                        (accent ?: AniLifeColors.SurfaceVariant).copy(alpha = (tintAlpha * 0.7f).coerceIn(0.05f, 0.6f)),
                    ),
                ),
                shape,
            )
            .border(1.dp, borderColor, shape)
    }

    val blurRadius = when (style) {
        GlassStyle.LEGACY -> 8.dp
        GlassStyle.FROSTED -> 6.dp
        GlassStyle.LIQUID -> 10.dp
    } * appearance.glassBlurIntensity
    val blurPx = with(density) { blurRadius.toPx() }
    val saturation = when (style) {
        GlassStyle.LEGACY -> 1.05f
        GlassStyle.FROSTED -> 1.01f
        GlassStyle.LIQUID -> 1.08f
    }
    val brightness = when (style) {
        GlassStyle.LEGACY -> 0.96f
        GlassStyle.FROSTED -> 0.94f
        GlassStyle.LIQUID -> 0.82f
    }
    val contrast = when (style) {
        GlassStyle.LEGACY -> 1.01f
        GlassStyle.FROSTED -> 1.005f
        GlassStyle.LIQUID -> 1.02f
    }
    val highlightAlpha = ((0.30f + 0.40f * interactionTarget) * appearance.glassHighlightIntensity)
        .coerceIn(0f, 0.85f)
    val reflectionStrength = when (style) {
        GlassStyle.LEGACY -> 0.12f
        GlassStyle.FROSTED -> 0.10f
        GlassStyle.LIQUID -> 0.18f
    } * (0.75f + 0.45f * interactionTarget)
    val distortion = when (style) {
        GlassStyle.LEGACY -> 0.30f
        GlassStyle.FROSTED -> 0.22f
        GlassStyle.LIQUID -> 0.46f
    }
    val vignette = when (style) {
        GlassStyle.LEGACY -> 0.10f
        GlassStyle.FROSTED -> 0.06f
        GlassStyle.LIQUID -> 0.15f
    }
    val useShader = PlatformCapabilities.supportsRuntimeShader
    val refractionHeight = with(density) { 16.dp.toPx() }
    val refractionAmount = with(density) { 9.dp.toPx() }

    return tracked
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                blur(blurPx)
                colorControls(saturation = saturation, brightness = brightness, contrast = contrast)
                if (useShader) {
                    reflectiveGlass(
                        reflectionStrength,
                        distortion,
                        if (style == GlassStyle.LIQUID) 0.03f else 0f,
                        vignette,
                    )
                    // Edge-only lens refraction on surfaces with enough room for it to read.
                    if (size.minDimension > with(density) { 96.dp.toPx() }) {
                        lens(refractionHeight, refractionAmount, true)
                    }
                }
            },
            highlight = {
                Highlight(
                    width = 1.dp,
                    blurRadius = 4.dp,
                    alpha = highlightAlpha,
                    style = HighlightStyle.Ambient(intensity = 0.9f),
                )
            },
            onDrawSurface = {
                drawRect(surfaceTint)
                // Top-edge specular sheen: brighter while hovered/pressed.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.025f + 0.035f * interactionTarget),
                            Color.White.copy(alpha = 0.005f),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = size.height * 0.62f,
                    ),
                )
                // Bottom shade gives the material thickness.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.08f)),
                        startY = size.height * 0.55f,
                        endY = size.height,
                    ),
                )
                // Pointer-localised light, the part that makes the glass feel alive.
                val p = pointer.value
                if (p != Offset.Unspecified && p.x.isFinite() && p.y.isFinite()) {
                    val radius = size.minDimension * (0.55f + 0.15f * interactionTarget)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                (accent ?: Color.White).copy(alpha = 0.12f + 0.20f * interactionTarget),
                                Color.Transparent,
                            ),
                            center = p,
                            radius = radius,
                        ),
                        radius = radius,
                        center = p,
                    )
                }
            },
        )
        .border(1.dp, borderColor, shape)
}

/** Convenience for call sites that only have a hover/selection boolean. */
@Composable
fun Modifier.glassTint(color: Color, shape: Shape = RoundedCornerShape(12.dp)): Modifier =
    background(color, shape).border(1.dp, color.copy(alpha = 0.5f), shape)
