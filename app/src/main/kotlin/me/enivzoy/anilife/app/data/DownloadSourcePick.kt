package me.enivzoy.anilife.app.data

import java.net.URI

// a queued download keeps only what identifies the episode, never the signed url it resolved to
internal data class DownloadTarget(
    val resolution: Int,
    val server: String,
)

internal fun pickDownloadSource(
    sources: List<AnimeVideoSource>,
    target: DownloadTarget,
    avoidUrl: String? = null,
): AnimeVideoSource? {
    val downloadable = sources.filter { downloadableSource(it) }
    val candidates = downloadable.filter { avoidUrl == null || it.url != avoidUrl }
    val pool = candidates.ifEmpty { downloadable }
    if (pool.isEmpty()) return null
    val preferred = if (target.server.equals("Auto", ignoreCase = true)) {
        pool
    } else {
        pool.filter {
            it.server.contains(target.server, ignoreCase = true) || it.provider.contains(target.server, ignoreCase = true)
        }
    }
    val eligible = preferred.filter { sourceHeight(it.quality) <= target.resolution }
    return eligible.maxByOrNull { sourceHeight(it.quality) }
        ?: preferred.minByOrNull { sourceHeight(it.quality) }
        ?: pool.minByOrNull { sourceHeight(it.quality) }
}

internal fun downloadableSource(source: AnimeVideoSource): Boolean =
    directVideoFile(source.url) ||
        source.format.equals("hls", ignoreCase = true) ||
        source.format.equals("dash", ignoreCase = true) ||
        listOf(".m3u8", ".mpd").any { source.url.contains(it, ignoreCase = true) }

internal fun sourceHeight(quality: String?): Int =
    Regex("(\\d{3,4})").find(quality.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

private fun directVideoFile(url: String): Boolean {
    val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
    return listOf(".mp4", ".mkv", ".m4v", ".webm", ".avi", ".mov", ".ts").any { path.endsWith(it) }
}