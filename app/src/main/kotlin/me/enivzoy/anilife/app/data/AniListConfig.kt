package me.enivzoy.anilife.app.data

import java.io.File

/** Public AniList OAuth client id, overridable via env/.env. */
object AniListConfig {
    const val DEFAULT_CLIENT_ID = "56997"

    fun clientId(): String {
        val fromEnvironment = System.getenv("ANILIST_CLIENT_ID")?.trim().orEmpty()
        val fromFile = runCatching {
            File(System.getProperty("user.dir"), ".env").readLines()
                .firstOrNull { it.trimStart().startsWith("ANILIST_CLIENT_ID=") }
                ?.substringAfter('=')?.trim()?.trim('"', '\'')
        }.getOrNull().orEmpty()
        return (fromEnvironment.ifBlank { fromFile }.ifBlank { DEFAULT_CLIENT_ID }).take(128)
    }

    fun clientSecret(): String {
        val fromEnvironment = System.getenv("ANILIST_CLIENT_SECRET")?.trim().orEmpty()
        val fromFile = runCatching {
            File(System.getProperty("user.dir"), ".env").readLines()
                .firstOrNull { it.trimStart().startsWith("ANILIST_CLIENT_SECRET=") }
                ?.substringAfter('=')?.trim()?.trim('"', '\'')
        }.getOrNull().orEmpty()
        return (fromEnvironment.ifBlank { fromFile }).take(256)
    }
}