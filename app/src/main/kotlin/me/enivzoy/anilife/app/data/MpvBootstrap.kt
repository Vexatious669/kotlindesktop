package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.diagnostics.ResourceTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

object MpvBootstrap {
    suspend fun ensure(onStatus: (String) -> Unit = {}): String? {
        System.clearProperty("AniLife.mpv.library")
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
        onStatus("mpv not found; downloading…")
        return try {
            withContext(Dispatchers.IO) {
                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                val releaseRequest = request(MPV_RELEASES, "application/vnd.github+json")
                val releaseResponse = ResourceTracker.networkOpened(releaseRequest.uri().host).use {
                    client.send(releaseRequest, HttpResponse.BodyHandlers.ofString())
                }
                requireSuccess(releaseResponse.statusCode(), releaseRequest.uri(), releaseResponse.body())
                val source = parseReleaseSource(releaseResponse.body(), releaseAssetPattern(Dependency.MPV))
                onStatus("fetching libmpv…")
                val archive = Files.createTempFile("anilife-mpv-", ".7z")
                try {
                    val assetRequest = request(source.url, "application/octet-stream")
                    val assetResponse = ResourceTracker.networkOpened(assetRequest.uri().host).use {
                        client.send(assetRequest, HttpResponse.BodyHandlers.ofByteArray())
                    }
                    requireSuccess(assetResponse.statusCode(), assetRequest.uri(), "")
                    Files.write(archive, assetResponse.body())
                    onStatus("extracting…")
                    extractArchive(
                        archive,
                        targetDir.toPath(),
                        name = { it.substringAfterLast('/') },
                        keep = { it.equals("libmpv-2.dll", ignoreCase = true) },
                    )
                } finally {
                    Files.deleteIfExists(archive)
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
            onStatus("mpv download failed: ${error.message}")
            null
        }
    }

    private fun request(url: String, accept: String): HttpRequest =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(2))
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .build()

    private fun requireSuccess(status: Int, uri: URI, body: String) {
        if (status !in 200..299) {
            error("HTTP $status from ${uri.host}: ${body.take(240).ifBlank { "No response details" }}")
        }
    }
}
