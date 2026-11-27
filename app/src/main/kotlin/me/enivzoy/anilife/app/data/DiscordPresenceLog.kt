package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.diagnostics.DiagnosticsPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal fun migrateLegacyDiscordPresenceLog(legacyFile: File, roamingLog: File) {
    if (!legacyFile.isFile || roamingLog.exists() || legacyFile.absoluteFile == roamingLog.absoluteFile) return
    roamingLog.parentFile?.let { Files.createDirectories(it.toPath()) }
    Files.move(legacyFile.toPath(), roamingLog.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

/**
 * Small self-contained logger for Rich Presence diagnostics.
 *
 * Without a timestamped trace of what the app submitted and what the worker decided, "nothing shows
 * up on Discord" is impossible to diagnose. Every line goes to stdout (visible when launched with
 * `gradlew :app:run`) and to a bounded file so it can be read after the app has been closed.
 */
object DiscordPresenceLog {
    private const val MAX_FILE_BYTES = 256 * 1024

    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val file: File by lazy {
        val roamingLog = DiagnosticsPaths.logs.resolve("discord-presence.log").toFile()
        val localAppData = System.getProperty("localappdata")?.takeIf(String::isNotBlank)
            ?: System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
        val legacyLog = localAppData?.let { File(it, "AniLife/discord-presence.log") }
        if (legacyLog != null) {
            runCatching { migrateLegacyDiscordPresenceLog(legacyLog, roamingLog) }
                .onFailure { System.err.println("Couldn't migrate Discord presence log: ${it.message}") }
        }
        roamingLog
    }

    @Synchronized
    fun d(message: String) = write("INFO", message, null)

    @Synchronized
    fun w(message: String) = write("WARN", message, null)

    @Synchronized
    fun w(message: String, failure: Throwable?) = write("WARN", message, failure)

    @Synchronized
    fun e(message: String, failure: Throwable? = null) = write("ERROR", message, failure)

    @Synchronized
    fun logFile(): String = file.absolutePath

    private fun write(level: String, message: String, failure: Throwable?) {
        val line = "[Discord][${LocalTime.now().format(formatter)}][${Thread.currentThread().name}] $level: $message"
        println(line)
        if (failure != null) {
            val trace = failure.stackTraceToString().prependIndent("    ")
            println(trace)
        }
        runCatching {
            file.appendText(line + "\n")
            if (failure != null) file.appendText(failure.stackTraceToString() + "\n")
            if (file.length() > MAX_FILE_BYTES) {
                val keep = file.readText().takeLast(MAX_FILE_BYTES / 2)
                file.writeText(keep)
            }
        }
    }
}
