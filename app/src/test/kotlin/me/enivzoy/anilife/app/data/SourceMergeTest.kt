package me.enivzoy.anilife.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceMergeTest {

    private fun source(
        url: String,
        quality: String? = "1080p",
        language: String = "",
        audio: String = "Dub",
        subtitles: Int = 0,
    ) = AnimeVideoSource(
        provider = "Provider",
        server = "Server",
        audio = audio,
        format = "hls",
        quality = quality,
        url = url,
        headers = emptyMap(),
        subtitles = (1..subtitles).map { AnimeSubtitleSource("https://cdn.example/s$it.vtt", "Sub $it", "en") },
        language = language,
    )

    @Test
    fun keepsOneSourcePerUrl() {
        val merged = mergeVideoSources(
            listOf(
                source("https://cdn.example/a.m3u8"),
                source("https://cdn.example/a.m3u8", quality = "720p"),
                source("https://cdn.example/b.m3u8"),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals(listOf("https://cdn.example/a.m3u8", "https://cdn.example/b.m3u8"), merged.map { it.url })
    }

    @Test
    fun aDuplicateThatNamesItsLanguageWins() {
        val merged = mergeVideoSources(
            listOf(
                source("https://cdn.example/a.m3u8", language = ""),
                source("https://cdn.example/a.m3u8", language = "es-419"),
            ),
        )

        assertEquals(1, merged.size)
        assertEquals(AudioLanguage("es", "419"), merged.single().audioLanguage)
        assertEquals("Spanish (Latin America)", merged.single().resolvedLanguage)
    }

    @Test
    fun bestQualityComesFirst() {
        val merged = mergeVideoSources(
            listOf(
                source("https://cdn.example/sd.m3u8", quality = "480p"),
                source("https://cdn.example/hd.m3u8", quality = "1080p"),
                source("https://cdn.example/md.m3u8", quality = "720p"),
                source("https://cdn.example/unknown.m3u8", quality = null),
            ),
        )

        assertEquals(
            listOf("https://cdn.example/hd.m3u8", "https://cdn.example/md.m3u8", "https://cdn.example/sd.m3u8", "https://cdn.example/unknown.m3u8"),
            merged.map { it.url },
        )
    }

    @Test
    fun aLowerQualityDuplicateStillContributesItsLanguage() {
        val merged = mergeVideoSources(
            listOf(
                source("https://cdn.example/a.m3u8", quality = "1080p", language = ""),
                source("https://cdn.example/a.m3u8", quality = "720p", language = "pt-BR"),
            ),
        )

        assertEquals(AudioLanguage("pt", "BR"), merged.single().audioLanguage)
        assertEquals("1080p", merged.single().quality)
    }

    @Test
    fun equalQualityPrefersTheEntryWithMoreMetadata() {
        val merged = mergeVideoSources(
            listOf(
                source("https://cdn.example/a.m3u8", language = "", subtitles = 0),
                source("https://cdn.example/a.m3u8", language = "en", subtitles = 2),
            ),
        )

        assertEquals(AudioLanguage("en"), merged.single().audioLanguage)
        assertEquals(2, merged.single().subtitles.size)
    }

    @Test
    fun downloadSourceSelectionDoesNotRetryTheRejectedUrlWhenNoAlternativeExists() {
        val rejected = source("https://cdn.example/rejected.m3u8")

        assertEquals(
            null,
            pickDownloadSource(
                sources = listOf(rejected),
                target = DownloadTarget(resolution = 1080, server = "Auto"),
                exclusions = DownloadSourceExclusions(urls = setOf(rejected.url)),
            ),
        )
    }

    @Test
    fun downloadSourceSelectionCanChooseAnotherAvailableServer() {
        val rejected = source("https://cdn.example/rejected.m3u8")
        val alternative = source("https://other.example/available.m3u8")

        assertEquals(
            alternative,
            pickDownloadSource(
                sources = listOf(rejected, alternative),
                target = DownloadTarget(resolution = 1080, server = "Auto"),
                exclusions = DownloadSourceExclusions(urls = setOf(rejected.url)),
            ),
        )
    }

    @Test
    fun sourceSelectionExcludesFreshUrlsFromTheSameProviderServer() {
        val rejected = source("https://cdn.example/old.m3u8?token=old").copy(server = "Mirror A")
        val refreshed = rejected.copy(url = "https://new-cdn.example/new.m3u8?token=fresh")
        val alternative = source("https://other.example/available.m3u8").copy(server = "Mirror B")

        assertEquals(
            alternative,
            pickDownloadSource(
                sources = listOf(refreshed, alternative),
                target = DownloadTarget(resolution = 1080, server = "Auto"),
                exclusions = DownloadSourceExclusions(
                    urls = setOf(rejected.url),
                    servers = setOf(downloadServerIdentity(rejected)),
                ),
            ),
        )
    }

    @Test
    fun anEmptyInputStaysEmpty() {
        assertTrue(mergeVideoSources(emptyList()).isEmpty())
    }
}