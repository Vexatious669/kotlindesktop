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
    minWidth: Int = 480,
    minHeight: Int = 240,
): Rectangle? {
    if (saved == null) return null
    if (saved.width < minWidth || saved.height < minHeight) return null
    val bounds = Rectangle(saved.x, saved.y, saved.width, saved.height)
    val targetScreen = screens.map { screen -> screen to screen.intersection(bounds) }
        .filter { (_, overlap) ->
            overlap.width >= MIN_VISIBLE_WINDOW_PIXELS && overlap.height >= MIN_VISIBLE_WINDOW_PIXELS
        }
        .maxByOrNull { (_, overlap) -> overlap.width.toLong() * overlap.height }
        ?.first ?: return null
    val width = bounds.width.coerceAtMost(targetScreen.width)
    val height = bounds.height.coerceAtMost(targetScreen.height)
    return Rectangle(
        bounds.x.coerceIn(targetScreen.x, targetScreen.x + targetScreen.width - width),
        bounds.y.coerceIn(targetScreen.y, targetScreen.y + targetScreen.height - height),
        width,
        height,
    )
}