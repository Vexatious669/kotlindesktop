package me.enivzoy.anilife.app.data

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.Desktop
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DiscordOAuthUser(
    val id: String,
    val username: String,
    val globalName: String?,
    val avatarHash: String?,
) {
    val displayName: String get() = globalName?.takeIf(String::isNotBlank) ?: username
    val avatarUrl: String? get() = avatarHash?.takeIf(String::isNotBlank)?.let {
        "https://cdn.discordapp.com/avatars/$id/$it.png?size=256"
    }
}

data class DiscordOAuthState(
    val status: DiscordOAuthStatus = DiscordOAuthStatus.DISCONNECTED,
    val user: DiscordOAuthUser? = null,
    val message: String? = null,
)

enum class DiscordOAuthStatus { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

object DiscordOAuth {
    private const val CLIENT_ID = "1567301811604553728"
    private const val REDIRECT_URI = "http://localhost:2339/authenticate"
    private const val CALLBACK_PORT = 2339
    private const val AUTHORIZE_URL = "https://discord.com/api/oauth2/authorize"
    private const val TOKEN_URL = "https://discord.com/api/oauth2/token"
    private const val USER_URL = "https://discord.com/api/v10/users/@me"
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(DiscordOAuthState())
    val state: kotlinx.coroutines.flow.StateFlow<DiscordOAuthState> = _state

    private var accessToken: String? = null
    private var tokenExpiry: Long = 0

    fun restore() {
        val creds = readCredentials() ?: return
        if (creds.expiresAt <= System.currentTimeMillis() / 1000) {
            _state.value = DiscordOAuthState(DiscordOAuthStatus.DISCONNECTED, message = "Discord session expired. Connect again.")
            return
        }
        accessToken = creds.accessToken
        _state.value = DiscordOAuthState(DiscordOAuthStatus.CONNECTED, user = creds.user)
    }

    suspend fun signIn(): DiscordOAuthUser {
        _state.value = DiscordOAuthState(DiscordOAuthStatus.CONNECTING)
        return try {
            val codeVerifier = generateCodeVerifier()
            val codeChallenge = generateCodeChallenge(codeVerifier)
            val stateToken = generateState()
            val url = buildAuthorizeUrl(codeChallenge, stateToken)
            val code = awaitCallback(url, stateToken)
            val token = exchangeCode(code, codeVerifier)
            val user = fetchUser(token)
            storeCredentials(token, user)
            accessToken = token
            _state.value = DiscordOAuthState(DiscordOAuthStatus.CONNECTED, user = user)
            user
        } catch (e: Throwable) {
            _state.value = DiscordOAuthState(DiscordOAuthStatus.FAILED, message = e.message ?: "Discord sign-in failed.")
            throw e
        }
    }

    fun disconnect() {
        deleteCredentials()
        accessToken = null
        tokenExpiry = 0
        _state.value = DiscordOAuthState(DiscordOAuthStatus.DISCONNECTED)
    }

    private fun buildAuthorizeUrl(challenge: String, state: String): String {
        val params = listOf(
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "scope" to "identify",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
        )
        return AUTHORIZE_URL + "?" + params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }
    }

    private suspend fun awaitCallback(url: String, expectedState: String): String =
        suspendCancellableCoroutine { cont ->
            val server = try {
                HttpServer.create(InetSocketAddress("127.0.0.1", CALLBACK_PORT), 0)
            } catch (e: Throwable) {
                cont.resumeWithException(IllegalStateException("Could not open Discord callback port $CALLBACK_PORT: ${e.message}"))
                return@suspendCancellableCoroutine
            }
            server.createContext("/authenticate") { exchange: HttpExchange ->
                val params = parseQuery(exchange.requestURI.rawQuery.orEmpty())
                val code = params["code"]
                val state = params["state"]
                val error = params["error"]
                val body = if (code != null) "Signed in to AniLife. You can close this tab."
                else "Sign-in failed: ${error ?: "no code returned"}."
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                if (code != null && state == expectedState && cont.isActive) {
                    cont.resume(code)
                    server.stop(0)
                } else if (error != null && cont.isActive) {
                    cont.resumeWithException(IllegalStateException(error))
                    server.stop(0)
                }
            }
            // Also handle root path
            server.createContext("/") { exchange: HttpExchange ->
                val path = exchange.requestURI.path
                if (path == "/authenticate") return@createContext
                val bytes = "Waiting for Discord sign-in...".toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            cont.invokeOnCancellation { server.stop(0) }
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url))
                } else {
                    throw IllegalStateException("Desktop browsing is not supported.")
                }
            } catch (e: Throwable) {
                server.stop(0)
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    private fun exchangeCode(code: String, verifier: String): String {
        val body = listOf(
            "client_id" to CLIENT_ID,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to verifier,
        ).joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val conn = URI(TOKEN_URL).toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val response = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = response.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (conn.responseCode !in 200..299) error("Discord token exchange failed: $text")
        val root = json.parseToJsonElement(text).jsonObject
        return root["access_token"]?.jsonPrimitive?.content ?: error("Discord returned no access token.")
    }

    private fun fetchUser(token: String): DiscordOAuthUser {
        val conn = URI(USER_URL).toURL().openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val root = json.parseToJsonElement(text).jsonObject
        return DiscordOAuthUser(
            id = root["id"]?.jsonPrimitive?.content ?: error("Discord returned no user ID."),
            username = root["username"]?.jsonPrimitive?.content ?: "discord-user",
            globalName = root["global_name"]?.jsonPrimitive?.content,
            avatarHash = root["avatar"]?.jsonPrimitive?.content,
        )
    }

    // --- PKCE ---
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun generateState(): String = ByteArray(16).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    // --- Credential storage (DPAPI on Windows) ---
    private fun credentialFile(): File {
        val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/share")
        return File(base, "AniLife/discord-oauth.bin")
    }

    private data class StoredCreds(val accessToken: String, val expiresAt: Long, val user: DiscordOAuthUser)

    private fun readCredentials(): StoredCreds? = runCatching {
        val file = credentialFile()
        if (!file.isFile) return null
        val bytes = Files.readAllBytes(file.toPath())
        val plaintext = if (isWindows()) Crypt32Util.cryptUnprotectData(bytes) else bytes
        val root = json.parseToJsonElement(String(plaintext, StandardCharsets.UTF_8)).jsonObject
        val token = root["accessToken"]?.jsonPrimitive?.content ?: return null
        val expiresAt = root["expiresAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        val userObj = root["user"]?.jsonObject ?: return null
        val user = DiscordOAuthUser(
            id = userObj["id"]?.jsonPrimitive?.content ?: return null,
            username = userObj["username"]?.jsonPrimitive?.content ?: "discord-user",
            globalName = userObj["global_name"]?.jsonPrimitive?.content,
            avatarHash = userObj["avatar"]?.jsonPrimitive?.content,
        )
        StoredCreds(token, expiresAt, user)
    }.getOrNull()

    private fun storeCredentials(token: String, user: DiscordOAuthUser) {
        val file = credentialFile()
        file.parentFile?.mkdirs()
        val payload = buildJsonObject {
            put("accessToken", token)
            put("expiresAt", System.currentTimeMillis() / 1000 + 604800) // 7 days
            put("user", buildJsonObject {
                put("id", user.id)
                put("username", user.username)
                user.globalName?.let { put("global_name", it) }
                user.avatarHash?.let { put("avatar", it) }
            })
        }
        val serialized = json.encodeToString(JsonObject.serializer(), payload).toByteArray(StandardCharsets.UTF_8)
        val saved = if (isWindows()) Crypt32Util.cryptProtectData(serialized) else serialized
        Files.write(file.toPath(), saved)
        if (!isWindows()) setOwnerOnly(file)
    }

    private fun deleteCredentials() {
        val file = credentialFile()
        if (file.exists()) file.delete()
    }

    private fun setOwnerOnly(file: File) {
        try {
            Files.setPosixFilePermissions(file.toPath(), setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ))
        } catch (_: Exception) {}
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private fun parseQuery(raw: String): Map<String, String> = raw.split('&').mapNotNull { part ->
        val pair = part.split('=', limit = 2)
        if (pair.size == 2) URLDecoder.decode(pair[0], StandardCharsets.UTF_8) to URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
        else null
    }.toMap()

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}