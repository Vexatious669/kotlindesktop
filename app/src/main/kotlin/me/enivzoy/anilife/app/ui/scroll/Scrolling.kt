package me.enivzoy.anilife.app.ui.scroll

import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import me.enivzoy.anilife.app.ui.AniLifeColors
import me.enivzoy.anilife.app.ui.rememberAppFloat
import me.enivzoy.anilife.app.ui.theme.LocalAppearance
import me.enivzoy.anilife.app.ui.theme.ScrollbarVisibility
import kotlinx.coroutines.delay

class ScrollController internal constructor(
    private val smoothScrolling: Boolean,
    private val reducedMotion: Boolean,
    private val durationMillis: Int,
) {
    suspend fun scrollTo(state: ScrollState, value: Int) {
        if (smoothScrolling && !reducedMotion) {
            state.animateScrollTo(value, animationSpec = tween(durationMillis))
        } else {
            state.scrollTo(value)
        }
    }

    suspend fun scrollBy(state: ScrollState, value: Int) {
        val target = (state.value + value).coerceAtLeast(0)
        if (smoothScrolling && !reducedMotion) {
            state.animateScrollTo(target, animationSpec = tween(durationMillis))
        } else {
            state.scrollTo(target)
        }
    }

    suspend fun scrollToTop(state: ScrollState) = scrollTo(state, 0)

    suspend fun scrollToItem(state: LazyListState, index: Int, offset: Int = 0) {
        if (smoothScrolling && !reducedMotion) {
            state.animateScrollToItem(index, offset)
        } else {
            state.scrollToItem(index, offset)
        }
    }

    suspend fun scrollBy(state: LazyListState, value: Float) {
        if (smoothScrolling && !reducedMotion) {
            state.animateScrollBy(value)
        } else {
            state.scroll { scrollBy(value) }
        }
    }
}

@Composable
fun rememberScrollController(): ScrollController {
    val appearance = LocalAppearance.current
    return remember(
        appearance.smoothScrolling,
        appearance.reducedMotion,
        appearance.scrollDuration.millis,
    ) {
        ScrollController(
            smoothScrolling = appearance.smoothScrolling,
            reducedMotion = appearance.reducedMotion,
            durationMillis = appearance.scrollDuration.millis,
        )
    }
}

@Composable
fun AniLifeScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) = AniLifeScrollbar(rememberScrollbarAdapter(state), modifier, active)

@Composable
fun AniLifeScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) = AniLifeScrollbar(rememberScrollbarAdapter(state), modifier, active)

@Composable
fun AniLifeScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) = AniLifeScrollbar(rememberScrollbarAdapter(state), modifier, active)

@Composable
fun AniLifeScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val appearance = LocalAppearance.current
    if (appearance.scrollbarVisibility == ScrollbarVisibility.HIDDEN) return

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scrollOffset by remember(adapter) { derivedStateOf { adapter.scrollOffset } }
    var lastOffset by remember { mutableStateOf(scrollOffset) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(scrollOffset) {
        if (scrollOffset == lastOffset) return@LaunchedEffect
        lastOffset = scrollOffset
        recentlyScrolled = true
        delay(1_400)
        recentlyScrolled = false
    }
    val targetAlpha = when {
        appearance.scrollbarVisibility == ScrollbarVisibility.ALWAYS -> 1f
        hovered || active || recentlyScrolled -> 1f
        else -> 0f
    }
    val alpha by rememberAppFloat(targetAlpha, useSpring = false, durationMillis = 220)

    val style = LocalScrollbarStyle.current.copy(
        minimalHeight = 28.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        unhoverColor = AniLifeColors.Foreground.copy(alpha = 0.22f),
        hoverColor = AniLifeColors.Foreground.copy(alpha = 0.55f),
    )
    CompositionLocalProvider(LocalScrollbarStyle provides style) {
        Box(
            modifier
                .hoverable(interaction)
                .graphicsLayer { this.alpha = alpha },
        ) {
            VerticalScrollbar(
                adapter = adapter,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }
    }
}
