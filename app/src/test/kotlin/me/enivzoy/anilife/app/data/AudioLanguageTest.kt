package me.enivzoy.anilife.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioLanguageTest {

    private fun source(
        audio: String = "Dub",
        provider: String = "Provider",
        server: String = "Server",
        language: String = "",
    ) = AnimeVideoSource(
        provider = provider,
        server = server,
        audio = audio,
        format = "hls",
        quality = null,
        url = "https://example.com/$provider/$server/$audio.m3u8",
        headers = emptyMap(),
        language = language,
    )

    @Test
    fun readsIsoCodesAndThreeLetterAliases() {
        assertEquals(AudioLanguage("en"), AudioLanguages.parse("en"))
        assertEquals(AudioLanguage("ja"), AudioLanguages.parse("jpn"))
        assertEquals(AudioLanguage("es"), AudioLanguages.parse("spa"))
        assertEquals(AudioLanguage("pt", "BR"), AudioLanguages.parse("pt-BR"))
        assertEquals(AudioLanguage("es", "419"), AudioLanguages.parse("es-419"))
    }

    @Test
    fun readsLanguageNamesInEnglishSpanishAndPortuguese() {
        assertEquals(AudioLanguage("en"), AudioLanguages.parse("English"))
        assertEquals(AudioLanguage("ja"), AudioLanguages.parse("Japanese"))
        assertEquals(AudioLanguage("es"), AudioLanguages.parse("Español"))
        assertEquals(AudioLanguage("fr"), AudioLanguages.parse("Français"))
        assertEquals(AudioLanguage("de"), AudioLanguages.parse("Deutsch"))
        assertEquals(AudioLanguage("pt"), AudioLanguages.parse("Português"))
    }

    @Test
    fun keepsVerifiedRegionalVariantsApart() {
        val latin = AudioLanguages.parse("Spanish (Latin America)")!!
        val castilian = AudioLanguages.parse("Castellano")!!
        val spain = AudioLanguages.parse("Español (España)")!!
        val brazil = AudioLanguages.parse("Portuguese (Brazil)")!!
        val plain = AudioLanguages.parse("Spanish")!!

        assertEquals("es", latin.code)
        assertEquals("419", latin.region)
        assertEquals("es", castilian.code)
        assertNull(castilian.region)
        assertEquals("ES", spain.region)
        assertEquals("BR", brazil.region)
        assertNull(plain.region)

        assertNotEquals(latin.key, spain.key)
        assertNotEquals(plain.key, latin.key)
        assertEquals("Spanish (Latin America)", AudioLanguages.displayName(latin))
        assertEquals("Spanish", AudioLanguages.displayName(castilian))
        assertEquals("Spanish (Spain)", AudioLanguages.displayName(spain))
        assertEquals("Portuguese (Brazil)", AudioLanguages.displayName(brazil))
        assertEquals("Spanish", AudioLanguages.displayName(plain))
    }

    @Test
    fun neverInventsAVariantForAPlainLabel() {
        val parsed = AudioLanguages.parse("Spanish")!!
        assertNull(parsed.region)
        assertTrue(parsed.known)
    }

    @Test
    fun treatsUndecodableLabelsAsUnknown() {
        assertNull(AudioLanguages.parse(""))
        assertNull(AudioLanguages.parse("und"))
        assertNull(AudioLanguages.parse("multi-audio"))
        assertEquals("Unknown", AudioLanguages.displayName(AudioLanguage()))
        assertEquals(AudioLanguage(), AudioLanguage())
    }

    @Test
    fun subStreamsAreJapaneseAndUnlabelledDubsStayUnknown() {
        assertEquals(AudioLanguage("ja"), source(audio = "Sub").audioLanguage)
        assertEquals(AudioLanguage(), source(audio = "Dub").audioLanguage)
        assertEquals("Japanese", source(audio = "Sub").resolvedLanguage)
        assertEquals("Unknown", source(audio = "Dub").resolvedLanguage)
    }

    @Test
    fun detectsLanguageFromProviderMetadataForTheEpisode() {
        assertEquals(
            AudioLanguage("es", "419"),
            source(audio = "Dub", server = "Servidor Latino").audioLanguage,
        )
        assertEquals(
            AudioLanguage("pt", "BR"),
            source(audio = "Dub", provider = "Anime Brasil").audioLanguage,
        )
        assertEquals(
            AudioLanguage("en", "US"),
            source(audio = "Dub", language = "English (US)").audioLanguage,
        )
    }

    @Test
    fun theProvidedLanguageWinsOverTheProviderName() {
        val parsed = source(audio = "Dub", provider = "English Site", language = "es-419").audioLanguage
        assertEquals(AudioLanguage("es", "419"), parsed)
    }
}