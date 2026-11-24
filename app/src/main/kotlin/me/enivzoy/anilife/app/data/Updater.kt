package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.Update
import me.enivzoy.anilife.core.diagnostics.ResourceTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

private const val DEFAULT_MANIFEST_URL =
    "https://github.com/anilifecommunity/kotlindesktop/releases/latest/download/latest.json"

private const val FALLBACK_MANIFEST_URL =
    "https://api.github.com/repos/anilifecommunity/kotlindesktop/releases/latest"

/**
 * Checks the latest GitHub release without following its installer asset redirect. The parser
 * also accepts the version manifest format used by earlier releases.
 */
class Updater(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
) {
    private val mutableUpdate = MutableStateFlow<Update?>(null)
    val update: StateFlow<Update?> = mutableUpdate.asStateFlow()

    /** Returns the newer [Update] for [currentVersion], or null when the app is up to date. */
    suspend fun check(currentVersion: String): Update? = withContext(Dispatchers.IO) {
        val update = fetchUpdate(currentVersion)
        mutableUpdate.value = update
        update
    }

    fun clear() {
        mutableUpdate.value = null
    }

    /** Streams the release asset to disk, verifies it, then publishes it at [destination]. */
    suspend fun download(
        update: Update,
        destination: Path,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val uri = httpUri(update.url) ?: run {
            System.err.println("[Updater] invalid download URL for version ${update.version}")
            return@withContext false
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(600))
            .header("User-Agent", "AniLife-Updater")
            .header("Accept", "*/*")
            .GET()
            .build()
        val lease = ResourceTracker.networkOpened(request.uri().host)
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
        val streamBody = lease.track(response.body())
        if (response.statusCode() >= 400) {
            streamBody.close()
            System.err.println("[Updater] asset download failed (HTTP ${response.statusCode()})")
            return@withContext false
        }
        val parent = destination.toAbsolutePath().parent
        val temporary = try {
            Files.createDirectories(parent)
            Files.createTempFile(parent, destination.fileName.toString(), ".part")
        } catch (failure: Throwable) {
            streamBody.close()
            throw failure
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            streamBody.use { input ->
                Files.newOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, update.sizeBytes.takeIf { it > 0L })
                    }
                }
            }
            if (update.sizeBytes > 0L && copied != update.sizeBytes) {
                System.err.println("[Updater] asset size mismatch for ${update.version}")
                return@withContext false
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (update.sha256.isNotBlank() && !actual.equals(update.sha256, ignoreCase = true)) {
                System.err.println("[Updater] sha256 mismatch for ${update.version}")
                return@withContext false
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Starts the downloaded Windows installer silently; the caller should exit afterwards. */
    fun installWindows(installer: Path) {
        val name = installer.fileName.toString().lowercase()
        if (name.endsWith(".msi")) {
            ProcessBuilder("msiexec", "/i", installer.toString(), "/qn", "/norestart").start()
        } else {
            ProcessBuilder(installer.toString(), "/quiet", "/norestart").start()
        }
    }

    private suspend fun fetchUpdate(currentVersion: String): Update? {
        val failures = mutableListOf<String>()
        for (url in listOf(manifestUrl, FALLBACK_MANIFEST_URL)) {
            val uri = URI.create(url)
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "AniLife-Updater")
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build()
            val response = ResourceTracker.networkOpened(request.uri().host).use {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            }
            if (response.statusCode() >= 400) {
                failures += "$url (HTTP ${response.statusCode()})"
                continue
            }
            return parseUpdateManifest(response.body(), currentVersion, currentPlatform())
        }
        throw IOException("Update manifest fetch failed: ${failures.joinToString(", ")}")
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
    val version = root.string("version")
        ?: root.string("tag_name")?.removePrefix("v")
        ?: return null
    if (!Updater.isNewer(version, currentVersion)) return null
    val assets = (root["assets"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
    val platformAsset = assets.firstOrNull {
        it.string("platform").equals(platform, ignoreCase = true)
    }
    val releaseAsset = if (root.string("tag_name") != null) {
        assets.firstOrNull {
            val name = it.string("name").orEmpty()
            name.endsWith(".base.msi", ignoreCase = true) ||
                name.endsWith("#base.msi", ignoreCase = true)
        } ?: assets.firstOrNull {
            val name = it.string("name").orEmpty()
            name.endsWith(".base.exe", ignoreCase = true) ||
                name.endsWith("#base.exe", ignoreCase = true)
        }
    } else {
        null
    }
    val asset = platformAsset ?: releaseAsset
    val url = sequenceOf(
        root.string("url"),
        root.string("assetUrl"),
        asset?.string("browser_download_url"),
        asset?.string("url"),
    )
        .filterNotNull()
        .firstOrNull(String::isNotBlank)
        ?: return null
    val downloadUrl = normalizeAssetUrl(url) ?: return null
    return Update(
        version = version,
        changelog = root.string("releaseNotes") ?: root.string("changelog") ?: root.string("body") ?: "",
        url = downloadUrl,
        sizeBytes = root.long("sizeBytes") ?: root.long("size") ?: asset?.long("sizeBytes")
            ?: asset?.long("size") ?: 0L,
        sha256 = root.string("sha256") ?: root.string("checksum")
            ?: asset?.string("sha256")
            ?: asset?.string("digest")?.removePrefix("sha256:")
            ?: "",
        publishedAt = root.long("releasedAt")
            ?: root.string("published_at")?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
            ?: 0L,
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
