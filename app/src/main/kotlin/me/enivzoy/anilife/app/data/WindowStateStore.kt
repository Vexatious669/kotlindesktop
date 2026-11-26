package me.enivzoy.anilife.app.data

import java.awt.Rectangle
import java.io.File
import java.util.Properties

data class SavedWindow(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximized: Boolean,
)

class WindowStateStore(private val file: File = defaultFile()) {

    fun read(): SavedWindow? = runCatching { parse(file) }.getOrNull()

    fun write(state: SavedWindow) {
        runCatching {
            file.parentFile?.mkdirs()
            val properties = Properties().apply {
                setProperty("x", state.x.toString())
                setProperty("y", state.y.toString())
                setProperty("width", state.width.toString())
                setProperty("height", state.height.toString())
                setProperty("maximized", state.maximized.toString())
            }
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.outputStream().use { output -> properties.store(output, null) }
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        }
    }

    private fun parse(source: File): SavedWindow? {
        if (!source.isFile) return null
        val properties = Properties().apply { source.inputStream().use(::load) }
        val x = properties.getProperty("x")?.toIntOrNull() ?: return null
        val y = properties.getProperty("y")?.toIntOrNull() ?: return null
        val width = properties.getProperty("width")?.toIntOrNull() ?: return null
        val height = properties.getProperty("height")?.toIntOrNull() ?: return null
        val maximized = properties.getProperty("maximized")?.toBooleanStrictOrNull() ?: false
        return SavedWindow(x = x, y = y, width = width, height = height, maximized = maximized)
    }

    companion object {
        fun defaultFile(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AniLife/window.properties")
        }
    }
}

internal const val MIN_VISIBLE_WINDOW_PIXELS = 200

internal fun restoreWindowBounds(
    saved: SavedWindow?,
    screens: List<Rectangle>,
    minWidth: Int = 800,
    minHeight: Int = 480,
): Rectangle? {
    if (saved == null) return null
    if (saved.width < minWidth || saved.height < minHeight) return null
    val bounds = Rectangle(saved.x, saved.y, saved.width, saved.height)
    val visible = screens.any { screen ->
        val overlap = screen.intersection(bounds)
        overlap.width >= MIN_VISIBLE_WINDOW_PIXELS && overlap.height >= MIN_VISIBLE_WINDOW_PIXELS
    }
    return bounds.takeIf { visible }
}