package me.enivzoy.anilife.core.net

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AniListResponseCacheTest {

    private class MemoryCache(private val clock: () -> Long) : ResponseCache {
        val entries = mutableMapOf<String, CachedResponse>()

        override fun get(key: String): CachedResponse? = entries[key]

        override fun put(key: String, data: JsonObject) {
            entries[key] = CachedResponse(data, clock())
        }
    }

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    @Volatile private var requests = 0
    @Volatile private var status = HttpURLConnection.HTTP_OK
    @Volatile private var body = SEARCH_BODY

    init {
        server.createContext("/") { exchange ->
            requests += 1
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private var now = 1_000_000L

    private fun client(cache: ResponseCache): AniListClient = AniListClient(
        http = HttpClient.newHttpClient(),
        endpoint = "http://127.0.0.1:${server.address.port}/",
        cache = cache,
        clock = { now },
    )

    @Test
    fun aFreshEntryAvoidsTheNetwork() = runBlocking {
        val cache = MemoryCache { now }
        val client = client(cache)

        client.search("frieren")
        client.search("frieren")

        assertEquals(1, requests)
        assertEquals(1, cache.entries.size)
    }

    @Test
    fun anExpiredEntryFetchesAgain() = runBlocking {
        val cache = MemoryCache { now }
        val client = client(cache)

        client.search("frieren")
        now += 301_000L
        client.search("frieren")

        assertEquals(2, requests)
    }

    @Test
    fun aFailedRequestFallsBackToTheStaleEntry() = runBlocking {
        val cache = MemoryCache { now }
        val client = client(cache)
        val first = client.search("frieren")

        status = HttpURLConnection.HTTP_INTERNAL_ERROR
        body = "upstream is down"
        now += 3_600_000L

        val stale = client.search("frieren")

        assertEquals(first.results.size, stale.results.size)
        assertEquals(2, requests)
    }

    @Test
    fun aColdCacheSurfacesTheFailure() = runBlocking {
        status = HttpURLConnection.HTTP_INTERNAL_ERROR
        body = "upstream is down"

        assertFailsWith<AniListException> {
            client(MemoryCache { now }).search("frieren")
        }
    }

    @Test
    fun aGraphqlRejectionIsNeverCached() = runBlocking {
        val cache = MemoryCache { now }
        body = """{"errors":[{"message":"invalid query"}]}"""

        assertFailsWith<AniListException> {
            client(cache).search("frieren")
        }
        assertTrue(cache.entries.isEmpty())

        body = SEARCH_BODY
        client(cache).search("frieren")

        assertTrue(cache.entries.isNotEmpty())
    }

    @Test
    fun aRateLimitIsNotCached() = runBlocking {
        val cache = MemoryCache { now }
        status = 429
        body = ""

        assertFailsWith<AniListException> {
            client(cache).search("frieren")
        }
        assertTrue(cache.entries.isEmpty())
    }

    private companion object {
        const val SEARCH_BODY = """{"data":{"Page":{"pageInfo":{"hasNextPage":false},"media":[]}}}"""
    }
}