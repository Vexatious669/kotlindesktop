package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.Update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
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
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableUpdate = MutableStateFlow<Update?>(null)
    val update: StateFlow<Update?> = mutableUpdate.asStateFlow()

    /** Returns the newer [Update] for [currentVersion], or null when the app is up to date. */
    suspend fun check(currentVersion: String): Update? {
        val update = runCatching { fetchUpdate(currentVersion) }.getOrNull() ?: return null
        mutableUpdate.value = update
        return update
    }

    fun clear() {
        mutableUpdate.value = null
    }

    /** Downloads the release asset and verifies its SHA-256 against the manifest. */
    suspend fun download(update: Update, destination: Path, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Boolean {
        val uri = URI.create(update.url)
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
        val root = json.parseToJsonElement(response.body()) as? JsonObject ?: return null
        val version = root.string("version") ?: return null
        if (!isNewer(version, currentVersion)) return null
        return Update(
            version = version,
            changelog = root.string("releaseNotes") ?: root.string("changelog") ?: "",
            url = root.string("url") ?: root.string("assetUrl") ?: "",
            sizeBytes = root.long("sizeBytes")?.toLong() ?: root.long("size")?.toLong() ?: 0L,
            sha256 = root.string("sha256") ?: root.string("checksum") ?: "",
            publishedAt = root.long("releasedAt")?.toLong() ?: 0L,
        )
    }

    companion object {
        const val FALLBACK_VERSION = "1.0.0"
        const val UPDATES_DIRECTORY = "updates"

        fun currentVersion(): String {
            val stream = Updater::class.java.classLoader.getResourceAsStream("version/version.properties")
            val value = stream?.bufferedReader()?.readText()?.trim()?.substringAfter('=')?.takeIf(String::isNotBlank)
            return value ?: FALLBACK_VERSION
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

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
