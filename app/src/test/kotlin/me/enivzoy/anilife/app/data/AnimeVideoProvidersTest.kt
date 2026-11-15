package me.enivzoy.anilife.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class AnimeVideoProvidersTest {
    @Test
    fun parsesMiruroEpisodeThumbnailsAndAirDates() {
        val root = Json.parseToJsonElement(
            """
                {
                  "data": [
                    {
                      "kind": "regular",
                      "episode_number": "1",
                      "title": "The First Episode",
                      "thumbnail_url": "https://example.com/episode-1.jpg",
                      "aired_on": "1999-10-20"
                    },
                    {
                      "kind": "special",
                      "episode_number": "1",
                      "title": "Special"
                    }
                  ]
                }
            """.trimIndent(),
        ).jsonObject

        assertEquals(
            listOf(
                AnimeEpisode(
                    number = 1.0,
                    title = "The First Episode",
                    thumbnail = "https://example.com/episode-1.jpg",
                    airedOn = "1999-10-20",
                ),
            ),
            AnimeVideoProviders().parseAnimeEpisodes(root),
        )
    }
}
