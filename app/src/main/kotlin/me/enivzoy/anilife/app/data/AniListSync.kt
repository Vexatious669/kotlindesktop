package me.enivzoy.anilife.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AniListUser(val id: Long, val name: String, val avatar: String?)

object AniListSync {
    private const val AUTHORIZE = "https://anilist.co/api/v2/oauth/authorize"
    private const val TOKEN = "https://anilist.co/api/v2/oauth/token"
    private const val ENDPOINT = "https://graphql.anilist.co"
    private const val REDIRECT = "https://anilist.co/api/v2/oauth/pin"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenFile = File(dataDirectory(), "anilist-token.txt")
    private val mutableUser = MutableStateFlow<AniListUser?>(readUser())
    val user: StateFlow<AniListUser?> = mutableUser.asStateFlow()
    private val mutableConfigured = MutableStateFlow(true)
    val configured: StateFlow<Boolean> = mutableConfigured.asStateFlow()

    fun clientId(): String = AniListConfig.clientId()

    private fun dataDirectory(): File {
        val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/share")
        return File(base, "AniLife").also { it.mkdirs() }
    }

    private fun readUser(): AniListUser? {
        val token = runCatching { tokenFile.readText().trim() }.getOrNull().orEmpty()
        if (token.isBlank()) return null
        return runCatching { fetchViewer(token) }.getOrNull()
    }

    fun beginAuth() {
        // Implicit grant: authorize with client_id + response_type=token only, per the docs the
        // redirect_uri is not required; the access token arrives in the fragment of the pin redirect.
        val url = "$AUTHORIZE?client_id=${clientId()}&response_type=token"
        println("[AniList] beginAuth: opening $url")
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url))
        }
    }

    suspend fun submitPasted(value: String) {
        val cleaned = value.trim().removePrefix("access_token=").trim()
        require(cleaned.isNotBlank()) { "Paste the AniList access token or PIN." }
        println("[AniList] submitPasted: received value len=${cleaned.length}, prefix=${cleaned.take(13)}")
        val viewer = runCatching { fetchViewer(cleaned) }
            .onFailure { println("[AniList] bearer token check failed: ${it.message}") }
            .getOrNull()
        if (viewer != null) {
            println("[AniList] bearer token accepted for viewer=${viewer.id} name=${viewer.name}")
            tokenFile.writeText(cleaned)
            mutableUser.value = viewer
            return
        }
        println("[AniList] bearer token rejected; attempting authorization code exchange")
        val secret = AniListConfig.clientSecret()
        if (secret.isBlank()) {
            println("[AniList] no client secret configured (set ANILIST_CLIENT_SECRET in .env); cannot exchange code")
            error("AniList sign-in failed. Make sure you logged in on the AniList pin page and pasted the access token it gave you (with the access_token= prefix if present).")
        }
        val body = "grant_type=authorization_code&client_id=${URLEncoder.encode(clientId(), Charsets.UTF_8)}&client_secret=${URLEncoder.encode(secret, Charsets.UTF_8)}&redirect_uri=${URLEncoder.encode(REDIRECT, Charsets.UTF_8)}&code=${URLEncoder.encode(cleaned, Charsets.UTF_8)}"
        val request = HttpRequest.newBuilder(URI.create(TOKEN))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        println("[AniList] code exchange status=${response.statusCode()} body=${response.body().take(400)}")
        if (response.statusCode() in 200..299) {
            val root = json.parseToJsonElement(response.body()).jsonObject
            val token = root["access_token"]?.jsonPrimitive?.content
            if (!token.isNullOrBlank()) {
                tokenFile.writeText(token)
                mutableUser.value = fetchViewer(token)
                return
            }
        }
        error("AniList sign-in failed. The authorization code exchange did not return a token (status ${response.statusCode()}, body ${response.body().take(200)}).")
    }

    suspend fun submitToken(token: String) = submitPasted(token)
    suspend fun submitCode(code: String) = submitPasted(code)

    fun disconnect() {
        runCatching { tokenFile.delete() }
        mutableUser.value = null
    }

    private fun fetchViewer(token: String): AniListUser? {
        val query = "query { Viewer { id name avatar { large } } }"
        val payload = """{"query":"${query.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
        val request = HttpRequest.newBuilder(URI.create(ENDPOINT))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val root = json.parseToJsonElement(response.body()).jsonObject
        println("[AniList] viewer check status=${response.statusCode()} body=${response.body().take(300)}")
        val viewer = root["data"]?.jsonObject?.get("Viewer")?.jsonObject ?: return null
        val id = viewer["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        val name = viewer["name"]?.jsonPrimitive?.content ?: "AniList user"
        val avatar = viewer["avatar"]?.jsonObject?.get("large")?.jsonPrimitive?.content
        return AniListUser(id, name, avatar)
    }
}