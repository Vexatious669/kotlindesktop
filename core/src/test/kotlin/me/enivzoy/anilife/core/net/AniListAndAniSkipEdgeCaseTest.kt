package me.enivzoy.anilife.core.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AniListAndAniSkipEdgeCaseTest {
    private fun startServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        return server
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test
    fun parseCardRespectsTitleLanguageFallbacksAndDeduplicatesAliases() {
        val raw = Json.parseToJsonElement(
            """
            {
              "id": 42,
              "idMal": 420,
              "isAdult": true,
              "format": "TV",
              "status": "RELEASING",
              "episodes": 24,
              "averageScore": 88,
              "season": "FALL",
              "seasonYear": 2026,
              "genres": ["Action", "Fantasy"],
              "title": {
                "romaji": "Sousou no Frieren",
                "english": "Frieren: Beyond Journey's End",
                "native": "葬送のフリーレン",
                "userPreferred": "Frieren"
              },
              "synonyms": ["葬送のフリーレン", "Frieren 2"],
              "coverImage": {
                "large": "https://img/large.jpg",
                "extraLarge": "https://img/xl.jpg",
                "color": "#87D66A"
              },
              "nextAiringEpisode": {
                "episode": 5,
                "airingAt": 1800000000
              }
            }
            """.trimIndent(),
        ).jsonObject

        val english = parseCard(raw, "english")!!
        val romaji = parseCard(raw, "romaji")!!
        val native = parseCard(raw, "native")!!

        assertEquals("Frieren: Beyond Journey's End", english.title)
        assertEquals("Sousou no Frieren", romaji.title)
        assertEquals("葬送のフリーレン", native.title)
        assertEquals("https://img/xl.jpg", english.cover)
        assertEquals(5, english.nextAiring?.episode)
        assertEquals(listOf("葬送のフリーレン", "Frieren 2"), english.aliases)
    }

    @Test
    fun parseCardHandlesMissingIdUntitledFallbackAndPartialNextAiring() {
        val noId = Json.parseToJsonElement("""{"title":{"english":"No ID"}}""").jsonObject
        assertNull(parseCard(noId))

        val untitled = Json.parseToJsonElement(
            """
            {
              "id": 99,
              "title": {},
              "coverImage": {"large": "https://img/large.jpg"},
              "nextAiringEpisode": {"episode": 2}
            }
            """.trimIndent(),
        ).jsonObject
        val card = parseCard(untitled, "english")!!
        assertEquals("Untitled", card.title)
        assertEquals("https://img/large.jpg", card.cover)
        assertNull(card.nextAiring)
    }

    @Test
    fun aniListClientValidatesBatchLimitsAndDeduplicatesIds() = runBlocking {
        var calls = 0
        val server = startServer { exchange ->
            calls++
            respond(exchange, 200, """{"data":{"Page":{"media":[{"id":1,"title":{"english":"One"}}]}}}""")
        }
        try {
            val client = AniListClient(HttpClient.newHttpClient(), "http://127.0.0.1:${server.address.port}/")

            assertEquals(emptyList(), client.cardsByIds(emptyList()))
            assertEquals(0, calls)

            val duplicateBatch = List(80) { 1L }
            assertEquals(1, client.cardsByIds(duplicateBatch).size)
            assertEquals(1, calls)

            val oversizedBatch = (1L..51L).toList()
            assertFailsWith<IllegalArgumentException> {
                client.cardsByIds(oversizedBatch)
            }
            assertFailsWith<IllegalArgumentException> {
                client.cardsByMalIds(oversizedBatch)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aniListClientParsesEpisodeTitlesAndIgnoresMalformedStreamingEntries() = runBlocking {
        val server = startServer { exchange ->
            respond(
                exchange,
                200,
                """
                {
                  "data": {
                    "Media": {
                      "streamingEpisodes": [
                        {"title": "Episode 1 - The Journey Begins"},
                        {"title": "Ep. #02:   Second Step  "},
                        {"title": "Episode 3:"},
                        {"title": "Trailer"}
                      ]
                    }
                  }
                }
                """.trimIndent(),
            )
        }
        try {
            val client = AniListClient(HttpClient.newHttpClient(), "http://127.0.0.1:${server.address.port}/")
            val titles = client.episodeTitles(10)

            assertEquals(mapOf(1 to "The Journey Begins", 2 to "Second Step"), titles)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aniListClientParsesDetailDatesRelationsAndFiltersSelfRecommendations() = runBlocking {
        val server = startServer { exchange ->
            respond(
                exchange,
                200,
                """
                {
                  "data": {
                    "Media": {
                      "id": 10,
                      "idMal": 100,
                      "title": {"english": "Main Show"},
                      "description": "Line 1<br>Line 2",
                      "startDate": {"year": 2026, "month": 4, "day": 5},
                      "endDate": {"year": 2026, "month": 9},
                      "studios": {"nodes": [{"name": "Madhouse"}]},
                      "trailer": {"site": "youtube", "id": "abc"},
                      "relations": {
                        "edges": [
                          {"relationType": "SEQUEL", "node": {"id": 11, "type": "ANIME", "title": {"english": "Sequel"}}},
                          {"relationType": "ADAPTATION", "node": {"id": 12, "type": "MANGA", "title": {"english": "Manga"}}}
                        ]
                      },
                      "recommendations": {
                        "nodes": [
                          {"mediaRecommendation": {"id": 10, "type": "ANIME", "title": {"english": "Main Show"}}},
                          {"mediaRecommendation": {"id": 13, "type": "ANIME", "title": {"english": "Recommended"}}}
                        ]
                      },
                      "characters": {
                        "edges": [
                          {
                            "role": "MAIN",
                            "node": {"name": {"userPreferred": "Hero"}, "image": {"large": "https://img/hero.jpg"}},
                            "voiceActors": [{"name": {"userPreferred": "VA Name"}}]
                          }
                        ]
                      }
                    }
                  }
                }
                """.trimIndent(),
            )
        }
        try {
            val client = AniListClient(HttpClient.newHttpClient(), "http://127.0.0.1:${server.address.port}/")
            val detail = client.detail(10)

            assertEquals("2026-04-05", detail.startDate)
            assertEquals("2026-09", detail.endDate)
            assertEquals("Line 1\nLine 2", detail.description)
            assertEquals(listOf("Madhouse"), detail.studios)
            assertEquals(listOf(11L), detail.relations.map { it.anime.id })
            assertEquals(listOf(13L), detail.recommendations.map { it.id })
            assertEquals("Hero", detail.characters.single().name)
            assertEquals("VA Name", detail.characters.single().voiceActor)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aniListClientSurfacesRateLimitGraphQlAndCorruptPayloadErrors() = runBlocking {
        var mode = "429"
        val server = startServer { exchange ->
            when (mode) {
                "429" -> respond(exchange, 429, "{}")
                "gql" -> respond(exchange, 400, """{"errors":[{"message":"Custom GraphQL error"}]}""")
                "corrupt" -> respond(exchange, 502, "not-json")
            }
        }
        try {
            val client = AniListClient(HttpClient.newHttpClient(), "http://127.0.0.1:${server.address.port}/")

            val rateLimit = assertFailsWith<AniListException> { client.random() }
            assertTrue(rateLimit.message.contains("rate limit", ignoreCase = true))

            mode = "gql"
            val gqlError = assertFailsWith<AniListException> { client.random() }
            assertEquals("Custom GraphQL error", gqlError.message)

            mode = "corrupt"
            val corruptError = assertFailsWith<AniListException> { client.random() }
            assertTrue(corruptError.message.contains("unreadable response"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aniSkipRelationRulesRedirectAndIgnoreOutOfBoundsOrSelfMappings() {
        val rules = listOf(
            AniSkipRelationRule(fromStart = 1, fromEnd = 12, toMalId = 100, toStart = 1, toEnd = 12),
            AniSkipRelationRule(fromStart = 13, fromEnd = 24, toMalId = 200, toStart = 1, toEnd = 12),
            AniSkipRelationRule(fromStart = 25, fromEnd = null, toMalId = 300, toStart = 1, toEnd = 5),
        )

        assertNull(rules.redirect(100, 1))
        assertEquals(100L to 5, rules.redirect(999, 5))
        assertEquals(200L to 1, rules.redirect(100, 13))
        assertEquals(200L to 12, rules.redirect(100, 24))
        assertEquals(300L to 5, rules.redirect(100, 29))
        assertNull(rules.redirect(100, 30))
        assertNull(rules.redirect(100, 0))
    }

    @Test
    fun aniSkipValidatesInputsAndRetriesWithCoreTypesWhenFullQueryFails() = runBlocking {
        var attempts = 0
        val server = startServer { exchange ->
            attempts++
            val query = exchange.requestURI.rawQuery.orEmpty()
            if ("mixed-op" in query) {
                respond(exchange, 500, """{"found":false}""")
            } else {
                respond(
                    exchange,
                    200,
                    """{"found":true,"results":[{"skipType":"op","episodeLength":1440.0,"interval":{"startTime":15.0,"endTime":105.0}}]}""",
                )
            }
        }
        try {
            val client = AniSkipClient(HttpClient.newHttpClient(), "http://127.0.0.1:${server.address.port}")

            assertNull(client.skipTimes(0, 1, 1440.0))
            assertNull(client.skipTimes(10, 0, 1440.0))
            assertEquals(emptyList(), client.relationRules(0))
            assertFailsWith<IllegalArgumentException> { client.skipTimes(10, 1, -1.0) }
            assertFailsWith<IllegalArgumentException> { client.skipTimes(10, 1, Double.NaN) }
            assertFailsWith<IllegalArgumentException> { client.skipTimes(10, 1, Double.POSITIVE_INFINITY) }

            val skip = client.skipTimes(10, 1, 1440.0)
            assertEquals(15.0, skip?.intro?.start)
            assertEquals(105.0, skip?.intro?.end)
            assertEquals(2, attempts)
        } finally {
            server.stop(0)
        }
    }
}
