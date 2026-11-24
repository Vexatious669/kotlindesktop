package me.enivzoy.anilife.app.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val LocalHighRefresh = compositionLocalOf { false }
val LocalReducedMotion = compositionLocalOf { false }
val LocalMotionClock = compositionLocalOf<MonotonicFrameClock?> { null }

/**
 * Caps animation frames at [framesPerSecond] without inventing extra frames on slower displays.
 * When a platform clock is available we only emit on real frames, accumulating their elapsed time
 * so a 60 Hz screen still receives one frame per refresh instead of a forced 120 FPS loop.
 */
class RefreshRateClock(
    private val framesPerSecond: Int,
    private val baseClock: MonotonicFrameClock? = null,
) : MonotonicFrameClock {
    private val frameIntervalNanos = 1_000_000_000L / framesPerSecond.coerceAtLeast(1)

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        val base = baseClock
        if (base == null) {
            delay(frameIntervalNanos / 1_000_000L)
            return onFrame(System.nanoTime())
        }
        // Each animation awaits frames independently; keep its accumulator local so concurrent
        // animations cannot steal frames from one another through shared mutable state.
        var carriedNanos = 0L
        var previousFrameNanos = 0L
        while (true) {
            val frameNanos = base.withFrameNanos { it }
            if (previousFrameNanos == 0L) {
                previousFrameNanos = frameNanos
                return onFrame(frameNanos)
            }
            carriedNanos += (frameNanos - previousFrameNanos).coerceAtLeast(0L)
            previousFrameNanos = frameNanos
            if (carriedNanos >= frameIntervalNanos) {
                carriedNanos -= frameIntervalNanos
                return onFrame(frameNanos)
            }
        }
    }
}

@Composable
fun ProvideHighRefresh(
    enabled: Boolean,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clock = remember(enabled, scope) {
        if (enabled) RefreshRateClock(120, scope.coroutineContext[MonotonicFrameClock]) else null
    }
    CompositionLocalProvider(
        LocalHighRefresh provides enabled,
        LocalReducedMotion provides reducedMotion,
        LocalMotionClock provides clock,
        content = content,
    )
}

@Composable
fun appDuration(durationMillis: Int): Int =
    if (LocalReducedMotion.current) 0 else durationMillis.coerceAtLeast(0)

@Composable
fun <T> appTween(
    durationMillis: Int = 300,
    easing: Easing = FastOutSlowInEasing,
): TweenSpec<T> = tween(durationMillis = appDuration(durationMillis), easing = easing)

@Composable
fun appSpring(
    dampingRatio: Float = 0.7f,
    stiffness: Float = 0.6f,
): SpringSpec<Float> = spring(dampingRatio = dampingRatio, stiffness = stiffness)

@Composable
fun rememberAppFloat(
    target: Float,
    useSpring: Boolean = true,
    durationMillis: Int = 240,
): State<Float> {
    val spec: AnimationSpec<Float> = if (useSpring) appSpring() else appTween(durationMillis)
    val reducedMotion = LocalReducedMotion.current
    val clock = LocalMotionClock.current
    val value = remember { Animatable(target) }
    LaunchedEffect(target, spec, clock, reducedMotion) {
        if (reducedMotion) {
            value.snapTo(target)
        } else if (clock != null) {
            withContext(clock) { value.animateTo(target, spec) }
        } else {
            value.animateTo(target, spec)
        }
    }
    return value.asState()
}

@Composable
fun rememberAppEnterAlpha(durationMillis: Int = 220): State<Float> {
    val reducedMotion = LocalReducedMotion.current
    val clock = LocalMotionClock.current
    val value = remember { Animatable(0f) }
    LaunchedEffect(reducedMotion, clock, durationMillis) {
        val spec = tween<Float>(if (reducedMotion) 0 else durationMillis)
        if (clock != null && !reducedMotion) {
            withContext(clock) { value.animateTo(1f, spec) }
        } else {
            value.animateTo(1f, spec)
        }
    }
    return value.asState()
}

@Composable
fun Modifier.appEnter(durationMillis: Int = 220): Modifier {
    val alpha by rememberAppEnterAlpha(durationMillis)
    return graphicsLayer { this.alpha = alpha }
}

@Composable
fun rememberAppLoop(durationMillis: Int = 1350): State<Float> {
    val reducedMotion = LocalReducedMotion.current
    val clock = LocalMotionClock.current
    val value = remember { Animatable(0f) }
    LaunchedEffect(reducedMotion, clock, durationMillis) {
        if (reducedMotion) {
            value.snapTo(0f)
            return@LaunchedEffect
        }
        val spec = tween<Float>(durationMillis, easing = LinearEasing)
        while (true) {
            if (clock != null) {
                withContext(clock) { value.animateTo(1f, spec) }
            } else {
                value.animateTo(1f, spec)
            }
            value.snapTo(0f)
        }
    }
    return value.asState()
}

@Composable
fun rememberAppScreenTransition(): ContentTransform {
    val enter = fadeIn(appTween(240))
    val exit = fadeOut(appTween(160))
    return enter togetherWith exit
}

@Composable
fun rememberAppEnterTransition(): EnterTransition = fadeIn(appTween(220))

@Composable
fun rememberAppExitTransition(): ExitTransition = fadeOut(appTween(150))

@Composable
fun Modifier.hoverScale(target: Float = 1.02f): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by rememberAppFloat(if (hovered) target else 1f)
    return this
        .hoverable(interaction)
        .pointerHoverIcon(PointerIcon.Hand)
        .graphicsLayer { scaleX = scale; scaleY = scale }
}
