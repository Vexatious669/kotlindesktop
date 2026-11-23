package me.enivzoy.anilife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawPlainBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.colorControls

val LocalGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

@Composable
fun GlassHost(content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AniLifeColors.Background)
                .drawBehind {
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
                .layerBackdrop(backdrop),
        )
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) { content() }
    }
}

@Composable
fun Modifier.liquidGlass(
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    alpha: Float = 0.5f,
): Modifier {
    val backdrop = LocalGlassBackdrop.current
        ?: return this.then(background(AniLifeColors.SurfaceVariant.copy(alpha = alpha), shape))
    return this.drawPlainBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(24.dp.toPx())
            colorControls(saturation = 1.4f)
        },
        onDrawSurface = { drawRect(AniLifeColors.SurfaceVariant.copy(alpha = alpha)) },
    )
}
