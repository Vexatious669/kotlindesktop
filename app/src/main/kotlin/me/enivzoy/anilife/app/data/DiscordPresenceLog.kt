package me.enivzoy.anilife.app.data

import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Small self-contained logger for Rich Presence diagnostics.
 *
 * Perigee has its own logger, but without a timestamped trace of what the app submitted and what
 * the worker decided, "nothing shows up on Discord" is impossible to diagnose. Every line goes to
 * stdout (visible when launched with `gradlew :app:run`) and to a bounded file so it can be read
 * after the app has been closed.
 */
object DiscordPresenceLog {
    private const val MAX_FILE_BYTES = 256 * 1024

    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val file: File by lazy {
        val base = System.getProperty("localappdata")
            ?.takeIf(String::isNotBlank)
            ?.let { File(it, "AniLife") }
            ?: File(System.getProperty("user.home"), ".anilife")
        runCatching {
            base.mkdirs()
            File(base, "discord-presence.log")
        }.getOrElse { File(System.getProperty("java.io.tmpdir"), "anilife-discord-presence.log") }
    }

    @Synchronized
    fun d(message: String) = write("INFO", message, null)

    @Synchronized
    fun w(message: String) = write("WARN", message, null)

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
