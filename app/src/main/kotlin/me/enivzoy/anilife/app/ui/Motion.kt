package me.enivzoy.anilife.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val LocalHighRefresh = compositionLocalOf { false }

class RefreshRateClock(private val framesPerSecond: Int) : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        delay(1_000_000_000L / framesPerSecond)
        return onFrame(System.nanoTime())
    }
}

@Composable
fun ProvideHighRefresh(enabled: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHighRefresh provides enabled) { content() }
}

@Composable
fun Modifier.hoverScale(target: Float = 1.02f): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highRefresh = LocalHighRefresh.current
    val clock = remember(highRefresh) { if (highRefresh) RefreshRateClock(120) else null }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(hovered, clock) {
        val destination = if (hovered) target else 1f
        if (clock != null) withContext(clock) { scale.animateTo(destination, spring()) }
        else scale.animateTo(destination, spring())
    }
    return this
        .hoverable(interaction)
        .pointerHoverIcon(PointerIcon.Hand)
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
