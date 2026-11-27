package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.diagnostics.ResourceTracker
import me.enivzoy.anilife.mpv.Mpv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration

object MpvBootstrap {
    suspend fun ensure(onStatus: (String) -> Unit = {}): String? {
        val override = System.getProperty("AniLife.mpv.library")?.takeIf(String::isNotBlank)
        if (override != null) {
            // A user-supplied -DAniLife.mpv.library must survive setup untouched.
            if (File(override).isFile) {
                onStatus("mpv ready")
                return override
            }
            // Stale override from an uninstalled copy: drop it and keep looking.
            System.clearProperty("AniLife.mpv.library")
        }
        Mpv.defaultLibraryPath()?.let { found ->
            System.setProperty("AniLife.mpv.library", found)
            onStatus("mpv ready")
            return found
        }
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            onStatus("libmpv not found")
            return null
        }
        val targetDir = dependencyDirectory().resolve("mpv").apply { mkdirs() }
        val target = File(targetDir, "libmpv-2.dll")
        if (target.exists() && target.length() > 0) {
            System.setProperty("AniLife.mpv.library", target.absolutePath)
            onStatus("mpv ready")
            return target.absolutePath
        }
        onStatus("mpv not found; downloading.")
        return try {
            withContext(Dispatchers.IO) {
                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                // Same ordered mirrors as setup: one code path for libmpv.
                firstWorkingSource(Dependency.MPV, candidateSources(Dependency.MPV, ::fetchText)) { source ->
                    onStatus("fetching libmpv.")
                    val request = HttpRequest.newBuilder(URI.create(source.url))
                        .timeout(Duration.ofMinutes(2))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/octet-stream")
                        .GET()
                        .build()
                    val response = ResourceTracker.networkOpened(request.uri().host).use {
                        client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                    }
                    if (response.statusCode() !in 200..299) {
                        error("HTTP ${response.statusCode()} from ${request.uri().host}")
                    }
                    val bytes = response.body()
                    val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    if (!actual.equals(source.sha256, ignoreCase = true)) {
                        error("libmpv checksum mismatch.")
                    }
                    val archive = Files.createTempFile("anilife-mpv-", ".7z")
                    try {
                        Files.write(archive, bytes)
                        onStatus("extracting.")
                        extractArchive(
                            archive,
                            targetDir.toPath(),
                            name = { it.substringAfterLast('/') },
                            keep = { it.equals("libmpv-2.dll", ignoreCase = true) },
                        )
                    } finally {
                        Files.deleteIfExists(archive)
                    }
                }
                if (target.exists() && target.length() > 0) {
                    System.setProperty("AniLife.mpv.library", target.absolutePath)
                    onStatus("mpv ready")
                    target.absolutePath
                } else {
                    null
                }
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // A resolver failure already carries the compact per-source summary; keep it intact.
            onStatus(if (error is SourceResolutionException) error.message.orEmpty() else shortReason(error))
            null
        }
    }
}