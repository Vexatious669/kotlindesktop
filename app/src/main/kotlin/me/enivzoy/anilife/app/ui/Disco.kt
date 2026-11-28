package me.enivzoy.anilife.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color

private val discoColors = listOf(
    Color.Red,
    Color.Magenta,
    Color(0xFF00E5FF),
    Color.Green,
    Color.Yellow,
    Color(0xFF7C4DFF),
)

@Composable
fun Modifier.discoMode(enabled: Boolean): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "disco")
    val step by transition.animateFloat(
        initialValue = 0f,
        targetValue = discoColors.size.toFloat(),
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "discoStep",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(tween(200), RepeatMode.Reverse),
        label = "discoPulse",
    )
    val color = discoColors[step.toInt() % discoColors.size]
    return drawWithContent {
        drawContent()
        drawRect(color.copy(alpha = pulse))
    }
}