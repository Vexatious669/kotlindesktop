package me.enivzoy.anilife.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.enivzoy.anilife.core.model.AnimeCard
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AniListIntegrationTest {
    @Test
    fun extractsTokenFromImplicitRedirectAndRejectsExpiredOrNonTokenValues() {
        val now = 1_700_000_000L
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"exp":${now + 3600}}""".toByteArray())
        val token = "header.$payload.signature"

        assertEquals(
            token,
            extractAniListAccessToken("https://anilist.co/api/v2/oauth/pin#access_token=$token&token_type=Bearer", now)
                .accessToken,
        )
        assertFailsWith<IllegalArgumentException> {
            extractAniListAccessToken(token, now + 3600)
        }
        assertFailsWith<IllegalArgumentException> {
            extractAniListAccessToken("authorization-code", now)
        }
    }

    @Test
    fun fetchesAllListPagesAndMapsAniListStatusesAndMedia() = runBlocking {
        val requestedPages = mutableListOf<Int>()
        val api = AniListAccountApi { _, _, variables ->
            val page = variables["page"]?.jsonPrimitive?.content?.toInt()
            requestedPages += page!!
            val hasNext = page == 1
            val mediaList = if (page == 1) {
                """[${entryJson(id = 123, status = "CURRENT", progress = 4)}]"""
            } else {
                """[${entryJson(id = 456, status = "COMPLETED", progress = 12)}]"""
            }
            Json.parseToJsonElement(
                """{"Page":{"pageInfo":{"hasNextPage":$hasNext},"mediaList":$mediaList}}""",
            ).jsonObject
        }

        val entries = api.list("token", 99)

        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(listOf(123L, 456L), entries.map { it.anime.id })
        assertEquals(AniListListStatus.CURRENT, entries[0].status)
        assertEquals(4, entries[0].progress)
        assertEquals("Fixture anime", entries[0].anime.title)
        assertEquals(AniListListStatus.COMPLETED, entries[1].status)
    }

    @Test
    fun persistsViewerListAndKeepsTheHighestPendingEpisode() {
        val root = Files.createTempDirectory("AniLife-anilist")
        try {
            val store = AniListListStore(root.toFile())
            val anime = AnimeCard(id = 123, title = "Fixture anime", episodes = 12)
            val entry = AniListListEntry(
                entryId = 789,
                anime = anime,
                status = AniListListStatus.CURRENT,
                progress = 4,
                score = 8.5,
                updatedAt = 12345,
            )
            store.write(99, listOf(entry))
            store.enqueueProgress(99, anime, 7)
            store.enqueueProgress(99, anime, 5)

            assertEquals(listOf(entry), store.read(99))
            assertEquals(7, store.pendingProgress(99)[123]?.episode)
            assertNull(store.pendingProgress(100)[123])

            store.removePendingProgress(99, 123)
            assertNull(store.pendingProgress(99)[123])
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun entryJson(id: Long, status: String, progress: Int) = """
        {
          "id": $id,
          "status": "$status",
          "progress": $progress,
          "score": 8.5,
          "updatedAt": 12345,
          "media": {
            "id": $id,
            "idMal": 321,
            "isAdult": false,
            "format": "TV",
            "status": "FINISHED",
            "episodes": 12,
            "averageScore": 82,
            "season": "FALL",
            "seasonYear": 2024,
            "genres": ["Drama"],
            "title": {
              "romaji": "Fixture anime",
              "userPreferred": "Fixture anime"
            },
            "coverImage": {
              "large": "https://example.com/cover.jpg"
            }
          }
        }
    """.trimIndent()
}
