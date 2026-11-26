package me.enivzoy.anilife.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.enivzoy.anilife.core.net.CachedResponse
import me.enivzoy.anilife.core.net.ResponseCache
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileResponseCache(
    private val directory: File = defaultDirectory(),
    private val maxEntries: Int = MAX_ENTRIES,
) : ResponseCache {

    override fun get(key: String): CachedResponse? {
        if (!isSafeKey(key)) return null
        return runCatching { read(fileFor(key)) }.getOrNull()
    }

    override fun put(key: String, data: JsonObject) {
        if (!isSafeKey(key)) return
        runCatching {
            directory.mkdirs()
            val payload = buildJsonObject {
                put("savedAt", System.currentTimeMillis())
                put("data", data)
            }
            val temporary = File(directory, "$key.tmp")
            temporary.writeText(payload.toString())
            moveIntoPlace(temporary, fileFor(key))
            evict()
        }
    }

    private fun read(source: File): CachedResponse? {
        if (!source.isFile) return null
        val root = Json.parseToJsonElement(source.readText()) as? JsonObject ?: return null
        val savedAt = (root["savedAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return null
        val data = root["data"] as? JsonObject ?: return null
        return CachedResponse(data = data, savedAtMillis = savedAt)
    }

    private fun moveIntoPlace(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (unsupported: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun evict() {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxEntries)
            .forEach { it.delete() }
    }

    private fun fileFor(key: String): File = File(directory, "$key.json")

    companion object {
        private const val MAX_ENTRIES = 300
        private val KEY_PATTERN = Regex("[0-9a-f]{64}")

        private fun isSafeKey(key: String): Boolean = KEY_PATTERN.matches(key)

        fun defaultDirectory(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AniLife/response-cache")
        }
    }
}