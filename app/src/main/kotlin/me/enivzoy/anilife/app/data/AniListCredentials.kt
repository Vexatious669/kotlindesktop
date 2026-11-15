package me.enivzoy.anilife.app.data

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64

internal data class AniListCredentials(
    val accessToken: String,
    val expiresAt: Long,
    val user: AniListUser? = null,
)

internal fun extractAniListAccessToken(input: String, nowSeconds: Long = System.currentTimeMillis() / 1000): AniListCredentials {
    val value = input.trim()
    require(value.isNotBlank()) { "Paste the AniList access token from the browser redirect." }
    val token = if (value.contains("://") || value.contains('#') || value.startsWith("access_token=")) {
        val fragment = value.substringAfter('#', value.substringAfter('?', value))
        val fields = fragment.split('&').mapNotNull { part ->
            val split = part.split('=', limit = 2)
            if (split.size == 2) {
                URLDecoder.decode(split[0], StandardCharsets.UTF_8) to
                    URLDecoder.decode(split[1], StandardCharsets.UTF_8)
            } else {
                null
            }
        }.toMap()
        fields["access_token"] ?: error("No access_token was found. Paste the browser redirect URL or the token itself.")
    } else {
        value
    }

    val parts = token.split('.')
    require(
        parts.size == 3 &&
            token.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")),
    ) { "AniList did not provide an access token. Paste the token, not an authorization code." }
    val claims = runCatching {
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).jsonObject
    }.getOrElse { error("The pasted AniList token is malformed.") }
    val expiresAt = claims["exp"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: error("The pasted AniList token has no expiration.")
    require(expiresAt > nowSeconds) { "That AniList token has expired. Connect again to get a new token." }
    return AniListCredentials(token, expiresAt)
}

internal class AniListCredentialStore(
    private val directory: File = appDataDirectory(),
) {
    private val file = File(directory, "anilist-session.bin")

    fun read(): AniListCredentials? {
        if (!file.isFile) return null
        val bytes = Files.readAllBytes(file.toPath())
        val plaintext = if (isWindows()) Crypt32Util.cryptUnprotectData(bytes) else bytes
        val root = Json.parseToJsonElement(String(plaintext, StandardCharsets.UTF_8)).jsonObject
        val token = root["accessToken"]?.jsonPrimitive?.content
            ?: error("The saved AniList session is invalid.")
        val expiresAt = root["expiresAt"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: error("The saved AniList session is invalid.")
        val savedUser = (root["user"] as? JsonObject)?.let { user ->
            AniListUser(
                id = user["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@let null,
                name = user["name"]?.jsonPrimitive?.content ?: "AniList user",
                avatar = user["avatar"]?.jsonPrimitive?.content,
                scoreFormat = AniListScoreFormat.parse(user["scoreFormat"]?.jsonPrimitive?.content),
            )
        }
        return AniListCredentials(token, expiresAt, savedUser)
    }

    fun write(credentials: AniListCredentials) {
        if (!directory.exists() && !directory.mkdirs()) {
            error("Couldn't create the AniLife data folder.")
        }
        val payload = buildJsonObject {
            put("accessToken", credentials.accessToken)
            put("expiresAt", credentials.expiresAt)
            credentials.user?.let { user ->
                put("user", buildJsonObject {
                    put("id", user.id)
                    put("name", user.name)
                    user.avatar?.let { put("avatar", it) }
                    put("scoreFormat", user.scoreFormat.name)
                })
            }
        }
        val serialized = Json.encodeToString(JsonObject.serializer(), payload)
            .toByteArray(StandardCharsets.UTF_8)
        val saved = if (isWindows()) Crypt32Util.cryptProtectData(serialized) else serialized
        Files.write(file.toPath(), saved)
        if (!isWindows()) setOwnerOnlyPermissions(file)
    }

    fun delete() {
        if (file.exists() && !file.delete()) error("Couldn't remove the saved AniList session.")
    }

    private fun setOwnerOnlyPermissions(file: File) {
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX platforms rely on their per-user application data directory ACL.
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private companion object {
        fun appDataDirectory(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AniLife")
        }
    }
}
