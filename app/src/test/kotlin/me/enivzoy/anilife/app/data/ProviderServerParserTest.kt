package me.enivzoy.anilife.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderServerParserTest {

    private fun server(
        language: Any? = null,
        dub: Boolean = true,
        streams: List<Any?> = listOf(
            mapOf("url" to "https://cdn.example/a.m3u8", "format" to "hls", "quality" to "1080p"),
        ),
    ): Map<String, Any?> = buildMap {
        put("name", "Server One")
        put("dub", dub)
        put("headers", listOf(listOf("Referer", "https://provider.example/")))
        language?.let { put("language", it) }
        put("streams", streams)
    }

    @Test
    fun readsTheOptionalLanguageField() {
        val sources = ProviderServerParser.parse(server(language = "es-419"), "Provider", requestedDub = true)

        assertEquals(1, sources.size)
        assertEquals("es-419", sources.single().language)
        assertEquals(AudioLanguage("es", "419"), sources.single().audioLanguage)
        assertEquals("Dub", sources.single().audio)
    }

    @Test
    fun aStreamLanguageOverridesTheServerLanguage() {
        val sources = ProviderServerParser.parse(
            value = server(
                language = "en",
                streams = listOf(
                    mapOf("url" to "https://cdn.example/en.m3u8", "language" to "en"),
                    mapOf("url" to "https://cdn.example/lat.m3u8", "language" to "es-419"),
                ),
            ),
            providerName = "Provider",
            requestedDub = true,
        )

        assertEquals(listOf("en", "es-419"), sources.map { it.language })
        assertEquals(listOf("English", "Spanish (Latin America)"), sources.map { it.resolvedLanguage })
    }

    @Test
    fun acceptsAListOfLanguagesForOlderFlavoursOfTheExtension() {
        val sources = ProviderServerParser.parse(server(language = listOf("pt-BR", "en")), "Provider", requestedDub = true)
        assertEquals("pt-BR", sources.single().language)
    }

    @Test
    fun componentsWithoutLanguageFieldsStillWork() {
        val dub = ProviderServerParser.parse(server(language = null, dub = true), "Provider", requestedDub = true)
        val sub = ProviderServerParser.parse(server(language = null, dub = false), "Provider", requestedDub = false)

        assertEquals(AudioLanguage(), dub.single().audioLanguage)
        assertEquals("Unknown", dub.single().resolvedLanguage)
        assertEquals(AudioLanguage("ja"), sub.single().audioLanguage)
        assertEquals("Japanese", sub.single().resolvedLanguage)
    }

    @Test
    fun theServerDubFlagWinsOverTheRequestedMode() {
        val sources = ProviderServerParser.parse(server(dub = false), "Provider", requestedDub = true)
        assertEquals("Sub", sources.single().audio)
    }

    @Test
    fun copiesHeadersAndKeepsDistinctStreams() {
        val sources = ProviderServerParser.parse(
            value = server(
                streams = listOf(
                    mapOf("url" to "https://cdn.example/a.m3u8", "format" to "hls", "quality" to "1080p"),
                    mapOf("url" to "https://cdn.example/b.mp4", "quality" to "720p"),
                ),
            ),
            providerName = "Provider",
            requestedDub = true,
        )

        assertEquals(2, sources.size)
        assertEquals(mapOf("Referer" to "https://provider.example/"), sources.first().headers)
        assertEquals(listOf("hls", "mp4"), sources.map { it.format })
        assertTrue(sources.all { it.provider == "Provider" && it.server == "Server One" })
    }

    @Test
    fun rejectsRecordsWithoutAUsableStream() {
        val sources = ProviderServerParser.parse(
            value = mapOf("name" to "Broken", "dub" to true, "headers" to emptyList<Any>(), "streams" to listOf(mapOf("quality" to "1080p"))),
            providerName = "Provider",
            requestedDub = true,
        )
        assertTrue(sources.isEmpty())
        assertTrue(ProviderServerParser.parse(null, "Provider", true).isEmpty())
        assertTrue(ProviderServerParser.parse("not a record", "Provider", true).isEmpty())
    }
}