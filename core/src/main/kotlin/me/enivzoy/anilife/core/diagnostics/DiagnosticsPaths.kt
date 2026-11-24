package me.enivzoy.anilife.core.diagnostics

import java.nio.file.Files
import java.nio.file.Path

object DiagnosticsPaths {
    val root: Path
        get() = Path.of(System.getProperty("user.home"), "AppData", "Roaming", "AniLife")
    val logs: Path
        get() = root.resolve("logs")
    val heapDumps: Path
        get() = root.resolve("heapdumps")

    fun ensure() {
        Files.createDirectories(logs)
        Files.createDirectories(heapDumps)
    }
}
