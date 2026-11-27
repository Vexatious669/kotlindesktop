package me.enivzoy.anilife.app.data

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal typealias FetchText = (String) -> String

private val logger = KotlinLogging.logger("AniLife.setup")

private const val MAX_ERROR_LENGTH = 240

internal const val AZUL_PACKAGES =
    "https://api.azul.com/metadata/v1/zulu/packages/?java_version=25&os=windows&arch=x64" +
        "&archive_type=zip&java_package_type=jre&latest=true&release_status=ga&page_size=100"
internal const val AZUL_PACKAGE_DETAILS = "https://api.azul.com/metadata/v1/zulu/packages/"

// Pinned 2026-11-27 from github.com/adoptium/temurin25-binaries (asset + published .sha256.txt).
internal const val TEMURIN_PINNED_URL =
    "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5.1%2B2/" +
        "OpenJDK25U-jre_x64_windows_hotspot_25.0.5.1_2.zip"
internal const val TEMURIN_PINNED_SHA256 =
    "3260a1b9899f30ac80d737daafc053042c525249f52d066101fbf81d78d73f25"
internal const val TEMURIN_PINNED_VERSION = "25.0.5.1+2"

// Pinned 2026-11-27 from github.com/shinchiro/mpv-winbuild-cmake (dev asset digest via GitHub API).
internal const val MPV_PINNED_URL =
    "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261111/" +
        "mpv-dev-x86_64-20261111-git-bcde6b93cc.7z"
internal const val MPV_PINNED_SHA256 =
    "273c079b11ea5575822f972756f4c9a71149a5c10130353abeb8586738938452"
internal const val MPV_PINNED_VERSION = "20261111"

internal const val SHINCHIRO_LATEST = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/latest"
internal const val SHINCHIRO_EXPANDED_ASSETS =
    "https://github.com/shinchiro/mpv-winbuild-cmake/releases/expanded_assets/"
internal const val SHINCHIRO_DOWNLOAD_BASE =
    "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/"

internal const val BTBN_CHECKSUMS =
    "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/checksums.sha256"
internal const val BTBN_DOWNLOAD_BASE = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/"
internal const val BTBN_ASSET = "ffmpeg-master-latest-win64-gpl.zip"
internal const val GYAN_FFMPEG_URL = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
internal const val GYAN_FFMPEG_SHA256_URL = "$GYAN_FFMPEG_URL.sha256"

private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

/**
 * Ordered mirrors per dependency. The first candidate that both resolves and installs wins;
 * every failure (metadata, HTTP, timeout, hash mismatch) moves on to the next one.
 */
internal fun candidateSources(dependency: Dependency, fetch: FetchText): List<Pair<String, () -> DependencySource>> =
    when (dependency) {
        Dependency.JRE -> jreCandidates(fetch)
        Dependency.MPV -> mpvCandidates(fetch)
        Dependency.FFMPEG -> ffmpegCandidates(fetch)
        Dependency.UPSCALER -> listOf("Bundled upscaler" to { upscalerSource() })
    }

private fun jreCandidates(fetch: FetchText): List<Pair<String, () -> DependencySource>> = listOf(
    "Adoptium API" to { parseJreSource(fetch(ADOPTIUM_API)) },
    "Azul API" to {
        val id = parseAzulPackageList(fetch(AZUL_PACKAGES))
        parseAzulPackageDetails(fetch(AZUL_PACKAGE_DETAILS + id))
    },
    "pinned Temurin" to {
        DependencySource(TEMURIN_PINNED_URL, TEMURIN_PINNED_SHA256, TEMURIN_PINNED_VERSION)
    },
)

private fun mpvCandidates(fetch: FetchText): List<Pair<String, () -> DependencySource>> = listOf(
    "GitHub API" to {
        parseReleaseSource(fetch(MPV_RELEASES), releaseAssetPattern(Dependency.MPV))
    },
    "GitHub releases page" to {
        val tag = fetchLatestTag(SHINCHIRO_LATEST)
        parseExpandedAssets(fetch(SHINCHIRO_EXPANDED_ASSETS + tag), tag, releaseAssetPattern(Dependency.MPV))
    },
    "pinned GitHub asset" to {
        DependencySource(MPV_PINNED_URL, MPV_PINNED_SHA256, MPV_PINNED_VERSION)
    },
)

private fun ffmpegCandidates(fetch: FetchText): List<Pair<String, () -> DependencySource>> = listOf(
    "BtbN checksums" to { parseBtbNChecksums(fetch(BTBN_CHECKSUMS)) },
    "gyan.dev" to { parseGyanChecksum(fetch(GYAN_FFMPEG_SHA256_URL)) },
    "GitHub API" to {
        parseReleaseSource(fetch(FFMPEG_RELEASES), releaseAssetPattern(Dependency.FFMPEG))
    },
)

/**
 * Raised when every candidate source for a dependency failed. The message is already short,
 * user-facing text such as "libmpv: GitHub API - rate limited; ...".
 */
internal class SourceResolutionException(message: String) : IOException(message)

/**
 * Resolves and installs one dependency from a candidate list, in order.
 * All candidates failing throws one short, human-readable error.
 */
internal suspend fun <T> firstWorkingSource(
    dependency: Dependency,
    candidates: List<Pair<String, () -> DependencySource>>,
    use: suspend (DependencySource) -> T,
): T {
    require(candidates.isNotEmpty()) { "No sources configured for ${dependency.name}." }
    val failures = mutableListOf<String>()
    for ((label, attempt) in candidates) {
        val source = try {
            attempt()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            failures += "$label - ${shortReason(error)}"
            continue
        }
        if (!SHA256.matches(source.sha256)) {
            failures += "$label - no SHA-256 checksum"
            continue
        }
        try {
            val result = use(source)
            logger.info { "Dependency ${dependency.name} resolved via $label" }
            return result
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            failures += "$label - ${shortReason(error)}"
        }
    }
    throw SourceResolutionException(shortFailure(dependency, failures))
}

private fun Dependency.friendlyName(): String = when (this) {
    Dependency.JRE -> "Java runtime"
    Dependency.MPV -> "libmpv"
    Dependency.FFMPEG -> "ffmpeg"
    Dependency.UPSCALER -> "upscaler"
}

internal fun shortFailure(dependency: Dependency, failures: List<String>): String =
    "${dependency.friendlyName()}: ${failures.joinToString("; ")}".take(MAX_ERROR_LENGTH)

/** Turns arbitrary exceptions into one short phrase; never leaks JSON or proxy internals. */
internal fun shortReason(error: Throwable): String {
    val message = error.message.orEmpty().replace(Regex("\\s+"), " ").trim()
    val code = Regex("""HTTP (\d{3})""").find(message)?.groupValues?.get(1)
    if ((code == "403" || code == "429") && message.contains("rate limit", ignoreCase = true)) {
        return "rate limited"
    }
    if (message.contains("checksum mismatch", ignoreCase = true)) return "checksum mismatch"
    code?.let { return "HTTP $it" }
    if (message.contains("tunnel through proxy", ignoreCase = true) ||
        message.contains("proxy returns", ignoreCase = true)
    ) {
        return "blocked by proxy"
    }
    return when (error) {
        is java.net.UnknownHostException -> "unreachable"
        is java.net.ConnectException -> "unreachable"
        is java.net.SocketTimeoutException -> "timed out"
        is java.net.http.HttpTimeoutException -> "timed out"
        else -> message.substringBefore(". ").take(40).ifBlank { error::class.simpleName ?: "failed" }
    }
}

/** Follows neither redirects nor the API: the tag is the last segment of the Location header. */
internal fun fetchLatestTag(url: String): String {
    val connection = openConnection(url, api = true, followRedirects = false, connectMs = 30_000, readMs = 30_000)
    return try {
        if (connection.responseCode !in 300..399) {
            error("Latest release lookup failed (${connection.responseCode})")
        }
        parseTagFromLocation(connection.getHeaderField("Location") ?: error("Latest release lookup has no redirect"))
    } finally {
        connection.disconnect()
    }
}

internal fun parseTagFromLocation(location: String): String {
    val tag = location.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
    if (!tag.matches(Regex("""\d{8}"""))) error("Unexpected release redirect")
    return tag
}

/**
 * Reads the digest github.com renders inside each asset's copy button on
 * github.com/<owner>/<repo>/releases/expanded_assets/<tag>.
 */
internal fun parseExpandedAssets(html: String, tag: String, pattern: Regex): DependencySource {
    for (button in Regex("""<clipboard-copy\b[^>]*>""").findAll(html)) {
        val attributes = button.value
        val name = Regex("""aria-label="Copy to clipboard digest for ([^"]+)"""")
            .find(attributes)?.groupValues?.get(1) ?: continue
        if (!pattern.matches(name)) continue
        val sha256 = Regex("""value="sha256:([0-9a-fA-F]{64})""")
            .find(attributes)?.groupValues?.get(1)
            ?: error("The release page shows no SHA-256 for $name")
        return DependencySource(SHINCHIRO_DOWNLOAD_BASE + tag + "/" + name, sha256.lowercase(), tag)
    }
    error("The release page has no asset matching ${pattern.pattern}")
}

/** Azul's list endpoint names the package; the hash lives on the per-package endpoint. */
internal fun parseAzulPackageList(body: String): String {
    val packages = Json.parseToJsonElement(body) as? JsonArray ?: error("Azul package list is invalid")
    val packageInfo = packages.mapNotNull { it as? JsonObject }
        .firstOrNull {
            val name = it.jsonText("name").orEmpty()
            name.endsWith(".zip", ignoreCase = true) &&
                name.contains("-jre", ignoreCase = true) &&
                !name.contains("-fx-", ignoreCase = true)
        }
        ?: error("Azul has no Windows x64 JRE zip")
    return packageInfo.jsonText("package_uuid") ?: error("Azul package id is missing")
}

internal fun parseAzulPackageDetails(body: String): DependencySource {
    val details = Json.parseToJsonElement(body) as? JsonObject ?: error("Azul package details are invalid")
    val version = (details["java_version"] as? JsonArray)
        ?.joinToString(".") { it.jsonPrimitive.contentOrNull.orEmpty() }
        ?.trimEnd('.')
    return DependencySource(
        url = details.jsonText("download_url") ?: error("Azul download URL is missing"),
        sha256 = details.jsonText("sha256_hash") ?: error("Azul checksum is missing"),
        version = version?.ifBlank { "25" } ?: "25",
    )
}

/** checksums.sha256 lines are "<sha256>  <filename>"; only the exact win64-gpl.zip is accepted. */
internal fun parseBtbNChecksums(body: String): DependencySource {
    val match = body.lineSequence()
        .map { it.trim() }
        .firstOrNull { line ->
            val parts = line.split(Regex("\\s+"))
            parts.size >= 2 &&
                parts.last().removePrefix("*") == BTBN_ASSET &&
                SHA256.matches(parts.first())
        }
        ?: error("BtbN checksums do not list $BTBN_ASSET")
    return DependencySource(BTBN_DOWNLOAD_BASE + BTBN_ASSET, match.substringBefore(' ').trim().lowercase(), "latest")
}

internal fun parseGyanChecksum(body: String): DependencySource {
    val sha256 = Regex("[0-9a-fA-F]{64}").find(body)?.value
        ?: error("gyan.dev did not publish a SHA-256")
    return DependencySource(GYAN_FFMPEG_URL, sha256.lowercase(), "latest")
}

private fun JsonObject.jsonText(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull