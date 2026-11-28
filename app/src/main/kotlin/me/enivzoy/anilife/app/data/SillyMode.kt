package me.enivzoy.anilife.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.enivzoy.anilife.mpv.Mpv
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

object SillyMode {
    private const val TRACK = "https://www.youtube.com/watch?v=mvkcyB_lve0"
    private const val YT_DLP_RELEASE = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var active = false

    @Volatile
    private var process: Process? = null

    @Volatile
    private var player: Mpv? = null

    @Volatile
    private var videoPlaying = false

    private var resumeJob: Job? = null

    fun start() {
        if (job?.isActive == true) return
        active = true
        job = scope.launch {
            runCatching { play() }.onFailure {
                println("silly mode: no soundtrack today (${it.message}). the disco dances on.")
            }
        }
    }

    fun stop() {
        active = false
        resumeJob?.cancel()
        resumeJob = null
        job?.cancel()
        job = null
        process?.destroy()
        process = null
        val current = player
        player = null
        if (current != null) scope.launch { runCatching { current.shutdown() } }
    }

    fun pause() {
        videoPlaying = true
        resumeJob?.cancel()
        resumeJob = null
        if (player != null) logger.info { "silly audio paused for video" }
        runCatching { player?.setPaused(true) }
    }

    fun resume() {
        videoPlaying = false
        resumeJob?.cancel()
        resumeJob = null
        if (player != null) logger.info { "silly audio resumed" }
        if (job?.isActive == true) runCatching { player?.play() }
    }

    fun paused(paused: Boolean) {
        if (!videoPlaying) return
        resumeJob?.cancel()
        resumeJob = null
        if (!paused) {
            runCatching { player?.setPaused(true) }
            return
        }
        resumeJob = scope.launch {
            delay(5_000)
            if (videoPlaying) {
                logger.info { "silly audio back after pause" }
                runCatching { player?.play() }
            }
        }
    }

    private fun play() {
        val appData = System.getenv("APPDATA")?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"))
        val root = File(appData, "AniLife")
        val track = File(root, "buckshot_ost.mp3")
        if (!track.isFile || track.length() == 0L) {
            val ytDlp = ensureYtDlp(File(root, "bin"))
            if (!downloadTrack(ytDlp, track)) return
        }
        if (!active) return
        val mpv = Mpv(
            initialOptions = listOf(
                "vo" to "null",
                "video" to "no",
                "loop-file" to "inf",
                "volume" to "100",
                "audio-exclusive" to "no",
                "terminal" to "no",
            ),
        )
        player = mpv
        mpv.loadfile(track.absolutePath)
        if (!videoPlaying) mpv.play()
        if (!active) {
            player = null
            runCatching { mpv.shutdown() }
        }
    }

    private fun downloadTrack(ytDlp: File, track: File): Boolean {
        val ffmpeg = File(dependencyDirectory(), "ffmpeg/ffmpeg.exe")
        val command = buildList {
            add(ytDlp.absolutePath)
            add("-x")
            add("--audio-format")
            add("mp3")
            add("--no-playlist")
            add("--no-warnings")
            if (ffmpeg.isFile) {
                add("--ffmpeg-location")
                add(ffmpeg.absolutePath)
            }
            add("-o")
            add(track.absolutePath)
            add(TRACK)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        this.process = process
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        this.process = null
        if (code != 0 || !track.isFile || track.length() == 0L) {
            println("silly mode: youtube said no (exit $code). ${output.takeLast(200)}")
            return false
        }
        return true
    }

    private fun ensureYtDlp(directory: File): File {
        val exe = File(directory, "yt-dlp.exe")
        if (exe.isFile && exe.length() > 0L) return exe
        directory.mkdirs()

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val release = client.send(
            HttpRequest.newBuilder(URI.create(YT_DLP_RELEASE))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "AniLife")
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()

        val assets = Json.parseToJsonElement(release).jsonObject["assets"]?.jsonArray
            ?: error("yt-dlp release lookup failed")
        fun asset(name: String) = assets.map { it.jsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.content == name }

        val binary = asset("yt-dlp.exe") ?: error("yt-dlp.exe missing from release")
        val url = binary["browser_download_url"]?.jsonPrimitive?.content ?: error("yt-dlp url missing")
        val expected = binary["digest"]?.jsonPrimitive?.content
            ?.takeIf(String::isNotBlank)
            ?.removePrefix("sha256:")
            ?: sha256FromSums(client, asset("SHA2-256SUMS"))

        val bytes = client.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", "AniLife")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        ).body()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) error("yt-dlp checksum mismatch")
        exe.writeBytes(bytes)
        return exe
    }

    private fun sha256FromSums(client: HttpClient, sums: JsonObject?): String {
        val url = sums?.get("browser_download_url")?.jsonPrimitive?.content
            ?: error("yt-dlp checksum unavailable")
        val text = client.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "AniLife")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        return text.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 2 && it.last() == "yt-dlp.exe" }
            ?.first()
            ?: error("yt-dlp checksum missing from SHA2-256SUMS")
    }
}