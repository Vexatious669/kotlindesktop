package me.enivzoy.anilife.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.oshai.kotlinlogging.KotlinLogging
import me.enivzoy.anilife.app.ui.appTween
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.enivzoy.anilife.mpv.Event
import me.enivzoy.anilife.mpv.Mpv
import me.enivzoy.anilife.mpv.defaultPlayerOptions
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import java.io.File

private val logger = KotlinLogging.logger("AniLife.intro")

internal fun introPlaybackOptions(): List<Pair<String, String>> =
    defaultPlayerOptions()
        .filterNot { it.first in setOf("vo", "wid", "idle", "keep-open", "volume", "keepaspect", "panscan", "hwdec") } +
        listOf(
            "vo" to "libmpv",
            "hwdec" to "no",
            "idle" to "yes",
            "keep-open" to "no",
            "volume" to "100",
            "keepaspect" to "yes",
            "panscan" to "0",
        )

@Composable
fun IntroOverlay(homeReady: Boolean, onFinished: () -> Unit) {
    val currentHomeReady by rememberUpdatedState(homeReady)
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var blackCover by remember { mutableStateOf(true) }
    val coverAlpha by animateFloatAsState(
        targetValue = if (blackCover) 1f else 0f,
        animationSpec = appTween(500),
        label = "intro-black-fade",
    )

    LaunchedEffect(Unit) {
        var player: Mpv? = null
        var surface: Mpv.SoftwareVideoSurface? = null
        var renderedFrame = false
        try {
            val introFile = withContext(Dispatchers.IO) {
                val destination = File.createTempFile("AniLife-intro-", ".mp4")
                destination.deleteOnExit()
                val input = checkNotNull(Mpv::class.java.getResourceAsStream("/anilife-intro.mp4")) {
                    "The intro video resource is missing."
                }
                input.use { stream -> destination.outputStream().use(stream::copyTo) }
                destination
            }
            val library = Mpv.defaultLibraryPath() ?: error("libmpv wasn't found.")
            val activePlayer = withContext(Dispatchers.IO) { Mpv(library, introPlaybackOptions()) }
            player = activePlayer
            activePlayer.setLogLevel("warn")
            val ended = CompletableDeferred<Unit>()
            activePlayer.startEvents { event ->
                when (event) {
                    Event.StartFile -> logger.info { "intro file playback started" }
                    Event.FileLoaded -> logger.info { "intro file loaded" }
                    is Event.EndFile -> {
                        logger.info { "intro playback ended reason=${event.reason}" }
                        ended.complete(Unit)
                    }
                    is Event.Log -> when (event.level) {
                        "fatal", "error" -> {
                            logger.error { "mpv[${event.level}]: ${event.text}" }
                            if (event.level == "fatal") ended.complete(Unit)
                        }
                        "warn" -> logger.warn { "mpv[warn]: ${event.text}" }
                    }
                    else -> Unit
                }
            }
            val videoSurface = withContext(Dispatchers.IO) {
                activePlayer.createSoftwareVideoSurface(INTRO_RENDER_WIDTH, INTRO_RENDER_HEIGHT)
            }
            surface = videoSurface
            withContext(Dispatchers.IO) { activePlayer.loadfile(introFile.absolutePath) }

            val playbackStartedAt = System.nanoTime()
            while (!ended.isCompleted && elapsedMillis(playbackStartedAt) < INTRO_TIMEOUT_MILLIS) {
                val pixels = withContext(Dispatchers.IO) {
                    if (videoSurface.needsRender()) videoSurface.render() else null
                }
                if (pixels != null) {
                    val bitmap = SkiaImage.makeRaster(
                        ImageInfo(INTRO_RENDER_WIDTH, INTRO_RENDER_HEIGHT, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
                        pixels,
                        INTRO_RENDER_WIDTH * 4,
                    ).toComposeImageBitmap()
                    frame = bitmap
                    renderedFrame = true
                    blackCover = false
                } else if (!renderedFrame && elapsedMillis(playbackStartedAt) >= INTRO_FIRST_FRAME_TIMEOUT_MILLIS) {
                    logger.warn { "intro skipped because mpv produced no video frame within ${INTRO_FIRST_FRAME_TIMEOUT_MILLIS}ms" }
                    break
                }
                delay(8)
            }
            if (renderedFrame) {
                withTimeoutOrNull(HOME_READY_TIMEOUT_MILLIS) {
                    snapshotFlow { currentHomeReady }.first { it }
                }
                blackCover = true
                delay(550)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error(error) { "intro playback failed" }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                surface?.close()
                player?.shutdown()
            }
        }
        frame = null
        blackCover = false
        delay(550)
        onFinished()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        frame?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        if (coverAlpha > 0f) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = coverAlpha }
                    .background(Color.Black),
            )
        }
    }
}

private fun elapsedMillis(startedAt: Long): Long =
    (System.nanoTime() - startedAt) / 1_000_000L

private const val INTRO_RENDER_WIDTH = 1280
private const val INTRO_RENDER_HEIGHT = 720
private const val INTRO_FIRST_FRAME_TIMEOUT_MILLIS = 3_000L
private const val INTRO_TIMEOUT_MILLIS = 20_000L
private const val HOME_READY_TIMEOUT_MILLIS = 15_000L

