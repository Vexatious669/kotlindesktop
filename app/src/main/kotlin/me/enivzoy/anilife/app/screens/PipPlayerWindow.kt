package me.enivzoy.anilife.app.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import me.enivzoy.anilife.app.ui.AniLifeColors
import me.enivzoy.anilife.app.ui.appTween

private val PipDragHandleHeight = 32.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PipPlayerWindow(
    videoSurface: @Composable (Modifier) -> Unit,
    isPlaying: Boolean,
    onTogglePause: () -> Unit,
    onSeek: (Double) -> Unit,
    onClose: () -> Unit,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
) {
    var showControls by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val state = rememberWindowState(
        size = DpSize(400.dp, 225.dp),
        position = WindowPosition(Alignment.Center),
    )
    Window(
        onCloseRequest = onClose,
        state = state,
        alwaysOnTop = true,
        undecorated = true,
        resizable = true,
        transparent = true,
        onPreviewKeyEvent = onPreviewKeyEvent,
    ) {
        LaunchedEffect(Unit) {
            window.toFront()
            focusRequester.requestFocus()
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AniLifeColors.Background,
            modifier = Modifier.fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPointerEvent(PointerEventType.Enter) { showControls = true }
                .onPointerEvent(PointerEventType.Exit) { showControls = false },
        ) {
            Box(Modifier.fillMaxSize()) {
                videoSurface(Modifier.fillMaxSize())
                // invisible drag handle keeps the video unobstructed and mpv out of the move gesture
                WindowDraggableArea(
                    modifier = Modifier.align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(PipDragHandleHeight),
                ) {
                    Box(Modifier.fillMaxSize())
                }
                AnimatedVisibility(
                    visible = showControls,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    enter = fadeIn(appTween(150)),
                    exit = fadeOut(appTween(200)),
                ) {
                    Row(
                        Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PipIconButton(Icons.Default.Replay10, "Seek back 10 seconds") { onSeek(-10.0) }
                        PipIconButton(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            onTogglePause,
                        )
                        PipIconButton(Icons.Default.Forward10, "Seek forward 10 seconds") { onSeek(10.0) }
                        PipIconButton(Icons.Default.Close, "Close picture in picture", onClose)
                    }
                }
            }
        }
    }
}

@Composable
private fun PipIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}
