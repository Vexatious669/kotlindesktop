package me.enivzoy.anilife.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Locale

private val perfLogger = KotlinLogging.logger("AniLife.perf")

internal const val SLOW_FRAME_MILLIS = 50.0
private const val MAX_TRUSTED_DELTA_MILLIS = 1_000.0
private const val REPORT_INTERVAL_NANOS = 5_000_000_000L

internal class FrameStats {
    private var frames = 0
    private var slowFrames = 0
    private var worstMillis = 0.0

    @Synchronized
    fun add(deltaMillis: Double) {
        frames += 1
        if (deltaMillis > SLOW_FRAME_MILLIS) slowFrames += 1
        if (deltaMillis > worstMillis) worstMillis = deltaMillis
    }

    @Synchronized
    fun snapshot(): Triple<Int, Int, Double> = Triple(frames, slowFrames, worstMillis)

    @Synchronized
    fun reset() {
        frames = 0
        slowFrames = 0
        worstMillis = 0.0
    }
}

// opt-in frame budget probe; inert unless ANILIFE_PERF=1
@Composable
fun FrameProbe() {
    if (System.getenv("ANILIFE_PERF") != "1") return
    LaunchedEffect(Unit) {
        val stats = FrameStats()
        var previous = 0L
        var reportedAt = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                if (previous != 0L) {
                    val delta = (now - previous) / 1_000_000.0
                    if (delta <= MAX_TRUSTED_DELTA_MILLIS) stats.add(delta)
                }
                previous = now
                if (now - reportedAt >= REPORT_INTERVAL_NANOS) {
                    val (frames, slow, worst) = stats.snapshot()
                    perfLogger.info {
                        "frames=$frames slow=$slow worst=${String.format(Locale.ROOT, "%.1f", worst)}ms"
                    }
                    stats.reset()
                    reportedAt = now
                }
            }
        }
    }
}