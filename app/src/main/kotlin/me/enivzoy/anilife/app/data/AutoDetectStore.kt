package me.enivzoy.anilife.app.data

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

internal data class AutoDetectEntry(
    val path: String,
    val animeId: Long?,
    val title: String?,
    val episodes: Int,
    val bytes: Long,
    val files: Int,
    val updatedAt: Long,
)

internal class AutoDetectStore(private val file: File = autoDetectFile()) {
    private val lock = Any()
    private var state = readState()

    val imported: Map<String, AutoDetectEntry>
        get() = synchronized(lock) { state.imported }

    val ignored: Set<String>
        get() = synchronized(lock) { state.ignored }

    // the service builds the same key when it matches a folder against this store
    fun key(path: String): String = path

    fun remember(entry: AutoDetectEntry) = synchronized(lock) {
        commit(state.copy(imported = state.imported + (key(entry.path) to entry)))
    }

    fun forget(path: String) = synchronized(lock) {
        val id = key(path)
        if (id in state.imported) commit(state.copy(imported = state.imported - id))
    }

    fun ignore(path: String) = synchronized(lock) {
        commit(state.copy(ignored = state.ignored + key(path)))
    }

    fun unignore(path: String) = synchronized(lock) {
        val id = key(path)
        if (id in state.ignored) commit(state.copy(ignored = state.ignored - id))
    }

    fun clear() = synchronized(lock) {
        commit(State(emptyMap(), emptySet()))
    }

    private fun commit(updated: State) {
        if (updated == state) return
        writeState(updated)
        state = updated
    }

    private fun readState(): State = try {
        if (!file.isFile) {
            State(emptyMap(), emptySet())
        } else {
            val properties = Properties()
            file.inputStream().use(properties::load)
            val imported = LinkedHashMap<String, AutoDetectEntry>()
            val importedCount = properties.getProperty("imported.count")?.toIntOrNull() ?: 0
            for (index in 0 until importedCount.coerceIn(0, MAX_ENTRIES)) {
                val prefix = "imported.$index."
                val path = properties.getProperty(prefix + "path")?.takeIf(String::isNotBlank) ?: continue
                imported[key(path)] = AutoDetectEntry(
                    path = path,
                    animeId = properties.getProperty(prefix + "animeId")?.toLongOrNull(),
                    title = properties.getProperty(prefix + "title")?.takeIf(String::isNotBlank),
                    episodes = properties.getProperty(prefix + "episodes")?.toIntOrNull() ?: 0,
                    bytes = properties.getProperty(prefix + "bytes")?.toLongOrNull() ?: 0L,
                    files = properties.getProperty(prefix + "files")?.toIntOrNull() ?: 0,
                    updatedAt = properties.getProperty(prefix + "updatedAt")?.toLongOrNull() ?: 0L,
                )
            }
            val ignored = LinkedHashSet<String>()
            val ignoredCount = properties.getProperty("ignored.count")?.toIntOrNull() ?: 0
            for (index in 0 until ignoredCount.coerceIn(0, MAX_ENTRIES)) {
                val path = properties.getProperty("ignored.$index.path")?.takeIf(String::isNotBlank) ?: continue
                ignored += key(path)
            }
            State(imported, ignored)
        }
    } catch (_: Throwable) {
        // unreadable state must not take the whole auto-detect feature down
        State(emptyMap(), emptySet())
    }

    private fun writeState(updated: State) {
        val properties = Properties()
        properties.setProperty("version", SCHEMA_VERSION.toString())
        val entries = updated.imported.values.toList()
        properties.setProperty("imported.count", entries.size.toString())
        entries.forEachIndexed { index, entry ->
            val prefix = "imported.$index."
            properties.setProperty(prefix + "path", entry.path)
            entry.animeId?.let { properties.setProperty(prefix + "animeId", it.toString()) }
            entry.title?.let { properties.setProperty(prefix + "title", it) }
            properties.setProperty(prefix + "episodes", entry.episodes.toString())
            properties.setProperty(prefix + "bytes", entry.bytes.toString())
            properties.setProperty(prefix + "files", entry.files.toString())
            properties.setProperty(prefix + "updatedAt", entry.updatedAt.toString())
        }
        val ignored = updated.ignored.toList()
        properties.setProperty("ignored.count", ignored.size.toString())
        ignored.forEachIndexed { index, path ->
            properties.setProperty("ignored.$index.path", path)
        }
        val target = file.absoluteFile
        val parent = target.parentFile ?: return
        Files.createDirectories(parent.toPath())
        val temporary = File(parent, "${file.name}.tmp")
        temporary.outputStream().use { properties.store(it, "AniLife auto-detect downloads") }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: IOException) {
            temporary.delete()
            throw error
        }
    }

    private data class State(
        val imported: Map<String, AutoDetectEntry>,
        val ignored: Set<String>,
    )

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_ENTRIES = 10_000

        private fun autoDetectFile(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AniLife/autodetect.properties")
        }
    }
}