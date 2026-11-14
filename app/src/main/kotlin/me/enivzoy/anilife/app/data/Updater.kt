package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.Update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

private const val DEFAULT_MANIFEST_URL =
    "https://github.com/anilifecommunity/kotlindesktop/releases/latest/download/version.json"

/**
 * Looks an auto-update manifest published with a GitHub release and decides whether the running
 * build is behind it. The manifest is the same JSON the release pipeline drops next to the
 * installers (see the auto-updater plan in `Instructions.md`).
 */
class Updater(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        // GitHub release endpoints answer 302 (latest -> tagged asset -> CDN); without this the
        // manifest body is empty and the check silently reports "up to date".
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
) {
    private val mutableUpdate = MutableStateFlow<Update?>(null)
    val update: StateFlow<Update?> = mutableUpdate.asStateFlow()

    /** Returns the newer [Update] for [currentVersion], or null when the app is up to date. */
    suspend fun check(currentVersion: String): Update? {
        val update = runCatching { fetchUpdate(currentVersion) }
            .onFailure { System.err.println("[Updater] update check failed: ${it.message}") }
            .getOrNull() ?: return null
        mutableUpdate.value = update
        return update
    }

    fun clear() {
        mutableUpdate.value = null
    }

    /** Downloads the release asset and verifies its SHA-256 against the manifest. */
    suspend fun download(update: Update, destination: Path, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Boolean {
        val uri = httpUri(update.url) ?: run {
            System.err.println("[Updater] invalid download URL for version ${update.version}")
            return false
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(600))
            .header("Accept", "*/*")
            .GET()
            .uri(uri)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() >= 400) {
            println("[Updater] asset download failed (HTTP ${response.statusCode()})")
            return false
        }
        val bytes = response.body()
        Files.createDirectories(destination.parent)
        Files.newOutputStream(destination).use { stream -> stream.write(bytes) }
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (update.sha256.isNotBlank() && digest.lowercase() != update.sha256.lowercase()) {
            println("[Updater] sha256 mismatch for ${update.version}")
            return false
        }
        return true
    }

    /**
     * Launches the downloaded installer. Windows: MSI via msiexec, portable executable by
     * running it directly (it self-extracts and hands off to the app). The host app should exit
     * after the installer hands control back.
     */
    fun installWindows(installer: Path): Boolean {
        val name = installer.fileName.toString()
        val process = if (name.lowercase().endsWith(".msi")) {
            ProcessBuilder("msiexec", "/i", installer.toString(), "/qn", "/norestart").start()
        } else {
            ProcessBuilder(installer.toString()).start()
        }
        val exited = process.waitFor(0L, java.util.concurrent.TimeUnit.MILLISECONDS)
        return !exited
    }

    private suspend fun fetchUpdate(currentVersion: String): Update? {
        val uri = URI.create(manifestUrl)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .GET()
            .uri(uri)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 400) {
            println("[Updater] update manifest fetch failed (HTTP ${response.statusCode()})")
            return null
        }
        return parseUpdateManifest(response.body(), currentVersion, currentPlatform())
    }

    companion object {
        const val FALLBACK_VERSION = "1.0.0"
        const val UPDATES_DIRECTORY = "updates"

        fun currentVersion(): String {
            val stream = Updater::class.java.classLoader.getResourceAsStream("version/version.properties")
            val value = stream?.bufferedReader()?.readText()?.trim()?.substringAfter('=')?.takeIf(String::isNotBlank)
            return value ?: FALLBACK_VERSION
        }

        fun updatesDirectory(): Path {
            val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".local", "share")
            return base.resolve("AniLife").resolve("updates")
        }

        fun parseVersion(version: String): List<Int> =
            version.trim().split('.').mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) }

        fun isNewer(candidate: String, current: String): Boolean {
            val left = parseVersion(candidate)
            val right = parseVersion(current)
            val size = maxOf(left.size, right.size)
            for (index in 0 until size) {
                val a = left.getOrElse(index) { 0 }
                val b = right.getOrElse(index) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}

internal fun parseUpdateManifest(body: String, currentVersion: String, platform: String): Update? {
    val json = Json { ignoreUnknownKeys = true }
    val root = json.parseToJsonElement(body) as? JsonObject ?: return null
    val version = root.string("version") ?: return null
    if (!Updater.isNewer(version, currentVersion)) return null
    val asset = (root["assets"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { it.string("platform").equals(platform, ignoreCase = true) }
    val url = sequenceOf(root.string("url"), root.string("assetUrl"), asset?.string("url"))
        .filterNotNull()
        .firstOrNull(String::isNotBlank)
        ?: return null
    val downloadUrl = normalizeAssetUrl(url) ?: return null
    return Update(
        version = version,
        changelog = root.string("releaseNotes") ?: root.string("changelog") ?: "",
        url = downloadUrl,
        sizeBytes = root.long("sizeBytes") ?: root.long("size") ?: asset?.long("size") ?: 0L,
        sha256 = root.string("sha256") ?: root.string("checksum") ?: asset?.string("sha256") ?: "",
        publishedAt = root.long("releasedAt") ?: 0L,
    )
}

private fun currentPlatform(): String = when {
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true) -> "windows"
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true) -> "macos"
    else -> "linux"
}

private fun httpUri(url: String): URI? = runCatching { URI.create(url) }
    .getOrNull()
    ?.takeIf { uri ->
        uri.isAbsolute &&
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }

private fun normalizeAssetUrl(url: String): String? {
    val uri = httpUri(url) ?: return null
    if (!uri.host.equals("github.com", ignoreCase = true) ||
        !uri.path.contains("/releases/download/") ||
        !uri.path.contains("/dist/") ||
        uri.rawFragment.isNullOrBlank()
    ) {
        return uri.toASCIIString()
    }

    val path = "${uri.path.substringBeforeLast("/dist/")}/${uri.path.substringAfterLast('/')}.${uri.rawFragment}"
    return runCatching { URI(uri.scheme, uri.authority, path, uri.query, null).toASCIIString() }.getOrNull()
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
