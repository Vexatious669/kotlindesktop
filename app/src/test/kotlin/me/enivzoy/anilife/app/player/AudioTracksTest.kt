package me.enivzoy.anilife.app.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.enivzoy.anilife.app.data.AnimeVideoSource
import me.enivzoy.anilife.app.data.AudioLanguage
import me.enivzoy.anilife.app.data.AudioLanguages

class AudioTracksTest {

    private val trackList = """
        [
          {"type":"video","id":1},
          {"type":"audio","id":2,"lang":"jpn","title":"Japanese","codec":"aac","demux-channel-count":2,"selected":true},
          {"type":"audio","id":3,"lang":"spa","selected":false},
          {"type":"audio","id":4,"title":"Latin Spanish","selected":false},
          {"type":"sub","id":5,"lang":"eng","title":"English","external":"true","selected":true},
          {"type":"sub","id":6,"lang":"por","external":"true","selected":false}
        ]
    """.trimIndent()

    private fun source(url: String, language: String, audio: String = "Dub", quality: String = "1080p") =
        AnimeVideoSource(
            provider = "Provider",
            server = "Server",
            audio = audio,
            format = "hls",
            quality = quality,
            url = url,
            headers = emptyMap(),
            language = language,
        )

    @Test
    fun enumeratesEveryEmbeddedAudioTrackAndIgnoresVideoAndCaptions() {
        val tracks = parseAudioTracks(trackList)

        assertEquals(listOf(2, 3, 4), tracks.map(PlayerAudioTrack::id))
        assertTrue(tracks[0].selected)
        assertEquals(AudioLanguage("ja"), tracks[0].language)
        assertEquals("aac", tracks[0].codec)
        assertEquals(2, tracks[0].channels)
        assertEquals(AudioLanguage("es"), tracks[1].language)
        assertEquals(AudioLanguage("es", "419"), tracks[2].language)
        assertEquals("Latin Spanish", tracks[2].label)
        assertTrue(tracks.none { it.id == 5 || it.id == 6 })
    }

    @Test
    fun stillReadsSubtitleTracksFromTheSameList() {
        val subtitles = parseSubtitleTracks(trackList)

        assertEquals(listOf(5, 6), subtitles.map(PlayerSubtitleTrack::id))
        assertEquals("English", subtitles[0].label)
        assertEquals("Portuguese", subtitles[1].label)
        assertTrue(subtitles.all(PlayerSubtitleTrack::external))
    }

    @Test
    fun missingOrBrokenTrackListIsEmptyRatherThanThrowing() {
        assertTrue(parseAudioTracks("").isEmpty())
        assertTrue(parseAudioTracks("not json").isEmpty())
        assertTrue(parseAudioTracks("{}").isEmpty())
    }

    @Test
    fun choosesTheSelectedTrackForTheWantedLanguage() {
        val tracks = listOf(
            PlayerAudioTrack(1, "Spanish (Spain)", AudioLanguage("es", "ES"), null, null, true),
            PlayerAudioTrack(2, "Spanish (Latin America)", AudioLanguage("es", "419"), null, null, false),
        )

        assertEquals(1, chooseAudioTrack(tracks, AudioLanguage("es", "ES"))?.id)
        assertEquals(2, chooseAudioTrack(tracks, AudioLanguage("es", "419"))?.id)
        // A plain "es" may match either region, but a region mismatch may never match.
        assertEquals(1, chooseAudioTrack(tracks, AudioLanguage("es"))?.id)
        assertNull(chooseAudioTrack(tracks, AudioLanguage("pt")))
    }

    @Test
    fun anExactRegionBeatsAPlainTrackOfTheSameLanguage() {
        val plain = PlayerAudioTrack(1, "Spanish", AudioLanguage("es"), null, null, true)
        val latin = PlayerAudioTrack(2, "Spanish (Latin America)", AudioLanguage("es", "419"), null, null, false)
        val spain = PlayerAudioTrack(3, "Spanish (Spain)", AudioLanguage("es", "ES"), null, null, false)

        assertEquals(2, chooseAudioTrack(listOf(plain, latin, spain), AudioLanguage("es", "419"))?.id)
        assertEquals(3, chooseAudioTrack(listOf(plain, spain, latin), AudioLanguage("es", "ES"))?.id)
        assertEquals(1, chooseAudioTrack(listOf(latin, plain, spain), AudioLanguage("es"))?.id)
    }

    @Test
    fun embeddedTracksWinAndSourceLanguagesFillTheRest() {
        val tracks = parseAudioTracks(trackList)
        val choices = buildAudioChoices(
            tracks = tracks,
            sources = listOf(
                source("https://cdn.example/lat.m3u8", "es-419"),
                source("https://cdn.example/ja.m3u8", "ja", audio = "Sub"),
            ),
            currentSourceUrl = "https://cdn.example/ja.m3u8",
        )

        assertEquals(listOf("ja", "es", "es-419"), choices.map { it.language.key })
        assertTrue(choices.first().selected)
        assertTrue(choices.first().embedded)
        assertEquals(2, choices.first().embeddedTrackId)
        // The duplicate Japanese source is hidden because the embedded track already covers it.
        assertTrue(choices.none { it.source?.url == "https://cdn.example/ja.m3u8" })
    }

    @Test
    fun separateSourceLanguagesArePreservedIncludingRegions() {
        val choices = buildAudioChoices(
            tracks = emptyList(),
            sources = listOf(
                source("https://cdn.example/es.m3u8", "es"),
                source("https://cdn.example/lat.m3u8", "es-419"),
                source("https://cdn.example/br.m3u8", "pt-BR"),
            ),
            currentSourceUrl = "https://cdn.example/lat.m3u8",
        )

        assertEquals(listOf("es-419", "es", "pt-br"), choices.map { it.language.key })
        assertTrue(choices.first().selected)
        assertEquals(listOf("Spanish (Latin America)", "Spanish", "Portuguese (Brazil)"), choices.map { AudioLanguages.displayName(it.language) })
    }

    @Test
    fun duplicateSourcesForOneLanguageCollapseToTheCurrentServer() {
        val choices = buildAudioChoices(
            tracks = emptyList(),
            sources = listOf(
                source("https://cdn.example/a.m3u8", "en"),
                source("https://cdn.example/b.m3u8", "en"),
            ),
            currentSourceUrl = "https://cdn.example/b.m3u8",
        )

        assertEquals(1, choices.size)
        assertEquals("https://cdn.example/b.m3u8", choices.single().source?.url)
        assertTrue(choices.single().selected)
    }

    @Test
    fun unknownDubsCollapseIntoOneHonestRow() {
        val choices = buildAudioChoices(
            tracks = emptyList(),
            sources = listOf(
                source("https://cdn.example/a.m3u8", ""),
                source("https://cdn.example/b.m3u8", ""),
            ),
            currentSourceUrl = "https://cdn.example/a.m3u8",
        )

        assertEquals(1, choices.size)
        assertEquals("unknown", choices.single().language.key)
        assertEquals("Unknown", AudioLanguages.displayName(choices.single().language))
        assertTrue(choices.single().selected)
    }

    @Test
    fun aSingleRealLanguageDoesNotImplyAlternatives() {
        val choices = buildAudioChoices(
            tracks = emptyList(),
            sources = listOf(source("https://cdn.example/only.m3u8", "ja", audio = "Sub")),
            currentSourceUrl = "https://cdn.example/only.m3u8",
        )

        assertEquals(1, choices.size)
        assertEquals("Japanese", AudioLanguages.displayName(choices.single().language))
    }

    @Test
    fun anUnknownEmbeddedTrackLeavesTheSourceRowMarked() {
        val track = PlayerAudioTrack(1, "Audio 1", AudioLanguage(), "aac", 2, true)
        val choices = buildAudioChoices(
            tracks = listOf(track),
            sources = listOf(source("https://cdn.example/pt.m3u8", "pt-BR")),
            currentSourceUrl = "https://cdn.example/pt.m3u8",
        )

        assertEquals(listOf("pt-br", "unknown"), choices.map { it.language.key })
        assertTrue(choices.first().selected)
        assertTrue(choices.first().source != null)
        assertTrue(choices.none { it.embedded && it.selected })
    }

    @Test
    fun noTracksAndNoSourcesProducesNoFakeOptions() {
        assertTrue(buildAudioChoices(emptyList(), emptyList(), null).isEmpty())
    }

    @Test
    fun restoringAPositionClampsToTheRealDuration() {
        assertEquals(100.0, restorePosition(130.0, 100.0))
        assertEquals(0.0, restorePosition(-4.0, 100.0))
        assertEquals(42.0, restorePosition(42.0, 0.0))
        assertEquals(0.0, restorePosition(-4.0, 0.0))
    }

    @Test
    fun headersKeepProviderContextIncludingSeparators() {
        assertEquals(
            "Referer: https://provider.example/a b,Origin: https://provider.example",
            httpHeaderFields(
                mapOf(
                    "Referer" to "https://provider.example/a,b",
                    "Origin" to "https://provider.example",
                    " " to "ignored",
                ),
            ),
        )
        assertEquals("", httpHeaderFields(emptyMap()))
    }
}