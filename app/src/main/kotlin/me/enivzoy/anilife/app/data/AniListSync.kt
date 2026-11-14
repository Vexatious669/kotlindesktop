package me.enivzoy.anilife.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Desktop
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AniListUser(val id: Long, val name: String, val avatar: String?)

object AniListSync {
    private const val AUTHORIZE = "https://anilist.co/api/v2/oauth/authorize"
    private const val ENDPOINT = "https://graphql.anilist.co"
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

    private fun writeToken(token: String) {
        runCatching { tokenFile.writeText(token) }
    }

    private fun clearToken() {
        runCatching { tokenFile.delete() }
    }

    suspend fun connect() {
        val port = withContext(Dispatchers.IO) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val deferred = CompletableDeferred<String>()
            server.createContext("/callback") { exchange ->
                val body = """
                    <!doctype html><html><body>
                    <script>
                    const hash = window.location.hash.substring(1);
                    const params = new URLSearchParams(hash);
                    const token = params.get('access_token');
                    fetch('/token', {method:'POST', body: hash}).then(()=>{document.body.innerText='Connected. You can close this tab.';});
                    </script>
                    </body></html>
                """.trimIndent()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            server.createContext("/token") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val params = java.net.URLDecoder.decode(body, Charsets.UTF_8)
                val token = params.split("&").firstOrNull { it.startsWith("access_token=") }?.substringAfter("=")
                if (token != null) deferred.complete(token)
                val resp = "ok"
                exchange.sendResponseHeaders(200, resp.length.toLong())
                exchange.responseBody.use { it.write(resp.toByteArray()) }
            }
            server.start()
            val actualPort = server.address.port
            val redirect = "http://127.0.0.1:$actualPort/callback"
            val url = "$AUTHORIZE?client_id=${clientId()}&response_type=token&redirect_uri=${java.net.URLEncoder.encode(redirect, Charsets.UTF_8)}"
            Desktop.getDesktop().browse(URI.create(url))
            val token = withTimeoutOrNull(120_000) { deferred.await() }
            server.stop(0)
            token
        }
        if (port != null) {
            writeToken(port)
            mutableUser.value = fetchViewer(port)
        }
    }

    fun disconnect() {
        clearToken()
        mutableUser.value = null
    }

    private fun fetchViewer(token: String): AniListUser? {
        val query = """query { Viewer { id name avatar { large } } }"""
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
        val viewer = root["data"]?.jsonObject?.get("Viewer")?.jsonObject ?: return null
        val id = viewer["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        val name = viewer["name"]?.jsonPrimitive?.content ?: "AniList user"
        val avatar = viewer["avatar"]?.jsonObject?.get("large")?.jsonPrimitive?.content
        return AniListUser(id, name, avatar)
    }
}