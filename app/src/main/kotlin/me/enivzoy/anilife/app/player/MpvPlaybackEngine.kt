package me.enivzoy.anilife.app.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import me.enivzoy.anilife.mpv.EndReason
import me.enivzoy.anilife.mpv.Event
import me.enivzoy.anilife.mpv.Mpv
import me.enivzoy.anilife.mpv.PropertyValue
import me.enivzoy.anilife.mpv.defaultPlayerOptions
import me.enivzoy.anilife.app.data.AnimeSubtitleSource
import me.enivzoy.anilife.app.data.DiscordPlaybackInfo
import me.enivzoy.anilife.app.data.Presence
import me.enivzoy.anilife.app.data.SubtitleAppearance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class PlayerSubtitleTrack(
    val id: Int,
    val label: String,
    val language: String?,
    val external: Boolean,
    val selected: Boolean,
)

/**
 * Picks the caption track to start with. mpv only auto-selects a track when its own `slang`
 * list matches, so a stream that only carries, say, Japanese captions would otherwise start
 * with the captions switched off even though the viewer asked for them.
 */
internal fun chooseSubtitleTrack(
    tracks: List<PlayerSubtitleTrack>,
    language: String,
): PlayerSubtitleTrack? {
    if (tracks.isEmpty()) return null
    val wanted = language.trim().lowercase().takeIf { it.isNotEmpty() && it != "none" }
        ?: return tracks.first()
    return tracks.firstOrNull { track -> subtitleLanguageMatches(track.language, wanted) }
        ?: tracks.first()
}

internal fun subtitleLanguageMatches(code: String?, wanted: String): Boolean {
    val actual = code?.trim()?.lowercase()?.substringBefore('-')?.takeIf(String::isNotBlank) ?: return false
    val target = wanted.trim().lowercase().substringBefore('-')
    return actual == target || actual.take(2) == target.take(2)
}

/**
 * Drives libmpv for the player screen.
 *
 * mpv decodes into a software render target (see [Mpv.SoftwareVideoSurface]); every decoded
 * frame is turned into an [ImageBitmap] so Compose can draw it under the controls. Transport
 * state comes back through mpv property observation, so the seek bar, play/pause icon and end
 * time follow the real playback instead of a simulated clock.
 */
class MpvPlaybackEngine(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    fallbackDurationSeconds: Double = 0.0,
    private val serverSubtitles: List<AnimeSubtitleSource> = emptyList(),
    private val startPositionSeconds: Double = 0.0,
    private val subtitlesByDefault: Boolean = true,
    private val subtitleLanguage: String = "en",
    subtitleAppearance: SubtitleAppearance = SubtitleAppearance(),
    private val renderWidth: Int = 1280,
    private val renderHeight: Int = 720,
    private val renderFormat: String = "bgra",
    private val playbackBufferSize: String = "auto",
    /** Series metadata needed for Rich Presence; playback state is supplied by this engine. */
    private var presenceInfo: DiscordPlaybackInfo? = null,
    private val onPresence: (Presence?) -> Unit = {},
) : AutoCloseable {

    var positionSeconds by mutableStateOf(0.0)
        private set
    var durationSeconds by mutableStateOf(fallbackDurationSeconds)
        private set
    var isPlaying by mutableStateOf(true)
        private set
    var videoFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var hasEnded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var subtitleTracks by mutableStateOf<List<PlayerSubtitleTrack>>(emptyList())
        private set
    var isMuted by mutableStateOf(false)
        private set
    var subtitleDelaySeconds by mutableStateOf(0.0)
        private set

    private val closed = AtomicBoolean(false)
    @Volatile private var player: Mpv? = null
    @Volatile private var surface: Mpv.SoftwareVideoSurface? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var activeSubtitleAppearance = subtitleAppearance
    @Volatile private var lastPresenceReportAt = 0L
    @Volatile private var lastMpvPosition = 0.0
    @Volatile private var lastMpvPositionAt = 0L
    @Volatile private var lastFrameAt = 0L

    fun start() {
        if (worker != null || closed.get()) return
        worker = thread(name = "AniLife-playback", isDaemon = true) { runPlayback() }
    }

    private fun runPlayback() {
        try {
            val mpv = Mpv(initialOptions = playbackOptions(headers))
            player = mpv
            applySubtitleAppearanceTo(mpv, activeSubtitleAppearance)
            mpv.observeDouble("time-pos")
            mpv.observeDouble("duration")
            mpv.observeFlag("pause")
            mpv.startEvents(::onEvent)
            val target = mpv.createSoftwareVideoSurface(renderWidth, renderHeight, renderFormat)
            surface = target
            mpv.loadfile(url)

            while (!closed.get()) {
                // Smooth position tracking: advance positionSeconds between mpv's time-pos reports.
                if (isPlaying && lastMpvPositionAt > 0L) {
                    val elapsed = (System.currentTimeMillis() - lastMpvPositionAt) / 1000.0
                    val interpolated = (lastMpvPosition + elapsed).coerceAtMost(durationSeconds.takeIf { it > 0.0 } ?: Double.MAX_VALUE)
                    if (interpolated > positionSeconds) {
                        positionSeconds = interpolated
                    }
                }
                if (target.needsRender()) {
                    // Limit frame rate to ~30fps to reduce memory pressure from ImageBitmap allocation.
                    val now = System.currentTimeMillis()
                    if (now - lastFrameAt < 33L) {
                        Thread.sleep(33L - (now - lastFrameAt))
                        continue
                    }
                    lastFrameAt = now
                    val bytes = target.render() ?: continue
                    videoFrame = SkiaImage.makeRaster(
                        ImageInfo(renderWidth, renderHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
                        bytes,
                        renderWidth * 4,
                    ).toComposeImageBitmap()
                } else {
                    Thread.sleep(8L)
                }
            }
        } catch (failure: Throwable) {
            error = failure.message?.takeIf(String::isNotBlank)
                ?: failure::class.simpleName
                ?: "Playback failed"
            isLoading = false
        } finally {
            release()
        }
    }

    private fun onEvent(event: Event) {
        when (event) {
            is Event.PropertyChange -> when (event.name) {
                "time-pos" -> (event.value as? PropertyValue.DoubleValue)?.value?.let {
                    lastMpvPosition = it.coerceAtLeast(0.0)
                    lastMpvPositionAt = System.currentTimeMillis()
                    positionSeconds = lastMpvPosition
                    // Position-only updates are throttled; transport changes report immediately.
                    reportPresence()
                }
                "duration" -> (event.value as? PropertyValue.DoubleValue)?.value?.let {
                    if (it.isFinite() && it > 0.0) {
                        durationSeconds = it
                        reportPresence(force = true)
                    }
                }
                "pause" -> (event.value as? PropertyValue.Flag)?.value?.let {
                    isPlaying = !it
                    if (it) {
                        // Paused: sync position to last mpv report.
                        lastMpvPosition = positionSeconds
                        lastMpvPositionAt = System.currentTimeMillis()
                    } else {
                        // Resumed: reset tracking.
                        lastMpvPosition = positionSeconds
                        lastMpvPositionAt = System.currentTimeMillis()
                    }
                    reportPresence(force = true)
                }
            }
            Event.FileLoaded -> {
                isLoading = false
                player?.getFlagProperty("pause")?.let { isPlaying = !it }
                player?.getDoubleProperty("duration")
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?.let { durationSeconds = it }
                serverSubtitles.distinctBy(AnimeSubtitleSource::url).forEach { subtitle ->
                    runCatching { player?.addSubtitle(subtitle.url, subtitle.label, subtitle.language) }
                }
                refreshSubtitleTracks()
                applyDefaultSubtitles()
                // Resume where the episode was left off, a few seconds back so the viewer hears the
                // line before the one they missed.
                if (startPositionSeconds > 0.0) {
                    val target = startPositionSeconds
                    positionSeconds = target
                    runCatching { player?.seekTo(target) }
                }
                reportPresence(force = true)
            }
            is Event.EndFile -> {
                if (event.reason == EndReason.EOF) {
                    isPlaying = false
                    positionSeconds = durationSeconds
                    hasEnded = true
                } else if (event.reason == EndReason.ERROR) {
                    error = event.error ?: "mpv could not play this stream"
                }
                isLoading = false
                onPresence(null)
            }
            is Event.Log -> {
                val level = event.level
                val msg = event.text
                if (level == "error" || level == "fatal" || level == "warn") {
                    System.err.println("mpv[$level]: $msg")
                }
            }
            else -> Unit
        }
    }

    /** Keeps presence metadata current when the episode changes without swapping the stream. */
    fun setPresenceInfo(info: DiscordPlaybackInfo?) {
        presenceInfo = info
        reportPresence(force = true)
    }

    /** Feeds the Discord worker a playback-state snapshot; caller throttles position-only drift. */
    private fun reportPresence(force: Boolean = false) {
        val info = presenceInfo ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPresenceReportAt < 1_000L) return
        lastPresenceReportAt = now
        onPresence(
            Presence(
                animeId = info.animeId,
                title = info.title,
                episode = info.episode,
                episodeTitle = info.episodeTitle,
                total = info.total,
                cover = info.cover,
                position = positionSeconds,
                duration = durationSeconds,
                paused = !isPlaying,
                embedded = info.embedded,
            ),
        )
    }

    fun togglePause() {
        val playing = !isPlaying
        isPlaying = playing
        player?.setPaused(!playing)
        reportPresence(force = true)
    }

    fun seekTo(seconds: Double) {
        val target = if (durationSeconds > 0.0) {
            seconds.coerceIn(0.0, durationSeconds)
        } else {
            seconds.coerceAtLeast(0.0)
        }
        positionSeconds = target
        player?.seekTo(target)
        reportPresence(force = true)
    }

    fun seekRelative(deltaSeconds: Double) {
        player?.seekRelative(deltaSeconds)
        positionSeconds = (positionSeconds + deltaSeconds).coerceAtLeast(0.0)
        reportPresence(force = true)
    }

    fun selectSubtitleTrack(id: Int?) {
        player?.setPropertyValue("sid", id?.toString() ?: "no")
        if (id != null) player?.setPropertyValue("sub-visibility", "yes")
        refreshSubtitleTracks()
    }

    /**
     * Applies the "show captions by default" and "preferred caption language" settings to the
     * current file. Called when the file finishes loading and whenever those settings change
     * during playback.
     */
    fun applyDefaultSubtitles() {
        val mpv = player ?: return
        if (!subtitlesByDefault) {
            runCatching { mpv.setPropertyValue("sid", "no") }
            refreshSubtitleTracks()
            return
        }
        refreshSubtitleTracks()
        if (subtitleTracks.any(PlayerSubtitleTrack::selected)) return
        val track = chooseSubtitleTrack(subtitleTracks, subtitleLanguage) ?: return
        runCatching { mpv.setPropertyValue("sid", track.id.toString()) }
        refreshSubtitleTracks()
    }

    fun addSubtitle(source: AnimeSubtitleSource) {
        player?.addSubtitle(source.url, source.label, source.language, select = true)
        refreshSubtitleTracks()
    }

    fun setVolume(volume: Int) {
        player?.setVolume(volume)
    }

    fun setSpeed(speed: Double) {
        player?.setSpeed(speed)
    }

    fun setMute(muted: Boolean) {
        isMuted = muted
        player?.setPropertyValue("mute", if (muted) "yes" else "no")
    }

    /** Shifts the subtitles earlier (negative) or later (positive), in seconds. */
    fun shiftSubtitles(seconds: Double) {
        subtitleDelaySeconds = seconds
        player?.setPropertyValue("sub-delay", seconds.toString())
    }

    fun setContentScale(scale: String) {
        player?.setPropertyValue("keepaspect", if (scale == "fill") "no" else "yes")
        player?.setPropertyValue("panscan", if (scale == "crop") "1" else "0")
    }

    fun applySubtitleAppearance(appearance: SubtitleAppearance) {
        activeSubtitleAppearance = appearance
        player?.let { applySubtitleAppearanceTo(it, appearance) }
    }

    private fun applySubtitleAppearanceTo(mpv: Mpv, appearance: SubtitleAppearance) {
        subtitleAppearanceOptions(appearance)
            .filterNot { it.first == "sub-fonts-dir" }
            .forEach { (name, value) -> mpv.setPropertyValue(name, value) }
    }

    private fun refreshSubtitleTracks() {
        val text = runCatching { player?.getStringProperty("track-list") }.getOrNull() ?: return
        subtitleTracks = runCatching {
            (Json.parseToJsonElement(text) as? JsonArray ?: JsonArray(emptyList())).mapNotNull { item ->
                val track = item as? JsonObject ?: return@mapNotNull null
                if (track.string("type") != "sub") return@mapNotNull null
                val id = track.string("id")?.toIntOrNull() ?: return@mapNotNull null
                val language = track.string("lang")?.takeIf(String::isNotBlank)
                val title = track.string("title")?.takeIf(String::isNotBlank)
                val external = track.string("external") == "true"
                val label = title ?: language?.let(::subtitleLanguageName)
                    ?: if (external) "External subtitle" else "Subtitle $id"
                PlayerSubtitleTrack(id, label, language, external, track.string("selected") == "true")
            }
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun subtitleLanguageName(code: String): String = when (code.lowercase()) {
        "en" -> "English"
        "ja" -> "Japanese"
        "es" -> "Spanish"
        "fr" -> "French"
        "de" -> "German"
        "it" -> "Italian"
        "pt" -> "Portuguese"
        "ru" -> "Russian"
        "zh" -> "Chinese"
        else -> code.uppercase()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onPresence(null)
        worker?.let { runCatching { it.join(3_000L) } }
        release()
    }

    private fun release() {
        surface?.let { runCatching { it.close() } }
        surface = null
        player?.let { runCatching { it.shutdown() } }
        player = null
    }

    private fun playbackOptions(headers: Map<String, String>): List<Pair<String, String>> {
        val options = defaultPlayerOptions()
            .filterNot { it.first == "hwdec" }
            .toMutableList()
        // The software renderer needs decoded frames in system memory.
        options += "hwdec" to "no"
        // Captions are drawn into the rendered frame, so they follow these options too.
        options += "sub-visibility" to "yes"
        options += subtitleAppearanceOptions(activeSubtitleAppearance)
        options += "sub-ass-override" to "no"
        subtitleLanguage.trim().lowercase()
            .takeIf { it.isNotEmpty() && it != "none" }
            ?.let { options += "slang" to it }
        val fields = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .map { (name, value) -> "$name: ${value.replace(',', ' ').replace('\n', ' ')}" }
        if (fields.isNotEmpty()) options += "http-header-fields" to fields.joinToString(",")
        return options
    }
}

internal fun subtitleAppearanceOptions(appearance: SubtitleAppearance): List<Pair<String, String>> {
    val position = appearance.verticalOffset.coerceIn(0f, 30f)
    return buildList {
        add("sub-font" to appearance.fontFamily)
        add("sub-scale" to appearance.scale.coerceIn(0.5f, 2f).toString())
        add("sub-color" to mpvSubtitleColor(appearance.textColor, appearance.textOpacity))
        add("sub-back-color" to mpvSubtitleColor(appearance.backgroundColor, appearance.backgroundOpacity))
        add("sub-border-color" to "#FF000000")
        add("sub-border-size" to if (appearance.edgeStyle == "outline") "2.5" else "0")
        add("sub-shadow-color" to "#CC000000")
        add("sub-shadow-offset" to if (appearance.edgeStyle == "shadow") "2" else "0")
        add("sub-pos" to if (appearance.verticalPosition == "top") position.toInt().toString() else (100f - position).toInt().toString())
        add("sub-align-x" to appearance.alignment)
        File(appearance.fontFile).takeIf { appearance.fontFile.isNotBlank() }?.parentFile
            ?.let { add("sub-fonts-dir" to it.absolutePath) }
    }
}

private fun mpvSubtitleColor(color: String, opacity: Float): String {
    val rgb = color.removePrefix("#").takeLast(6).uppercase()
    val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt().toString(16).uppercase().padStart(2, '0')
    return "#$alpha$rgb"
}

