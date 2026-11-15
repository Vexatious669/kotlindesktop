package me.enivzoy.anilife.app.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Instant
import xyz.orbitary.perigee.activity.ActivityType
import xyz.orbitary.perigee.activity.StatusDisplayType

class AppSettingsTest {
    @Test
    fun discordPresenceFormatsIdleMovieAndEpisodeActivities() {
        val startedAt = Instant.parse("2026-11-03T22:00:00Z")
        val cover = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/1.jpg"
        val idle = buildDiscordPresenceActivity(null, startedAt)
        val movie = buildDiscordPresenceActivity(
            DiscordPresenceTarget("movie", "My Movie", true, "FALL", 2026, 8.0),
            startedAt,
        )
        val series = buildDiscordPresenceActivity(
            DiscordPresenceTarget("series", "My Series", false, "FALL", 2026, 8.0, cover),
            startedAt,
        )
        val missingSeason = buildDiscordPresenceActivity(
            DiscordPresenceTarget("series-2", "My Series", false, null, null, 8.0),
            startedAt,
        )

        assertEquals(ActivityType.WATCHING, idle.type())
        assertEquals("AniLife", idle.details())
        assertEquals("AniLife", idle.name())
        assertEquals("i love anilife", idle.state())
        assertEquals(StatusDisplayType.DETAILS, idle.statusDisplayType())
        assertEquals("My Movie", movie.details())
        assertEquals("My Series - Fall 2026 Episode 8", series.details())
        assertEquals("My Series - Episode 8", missingSeason.details())
        assertEquals(cover, series.assets().largeImage())
        assertEquals("My Series", series.assets().largeText())
        assertEquals(startedAt, series.timestamps()?.start())
    }

    @Test
    fun discordPresenceDefaultsToTheBundledAniLifeApplication() {
        val settings = AppSettings()

        assertEquals(true, settings.discordPresenceEnabled)
        assertEquals("1567301811604553728", settings.discordApplicationId)
    }

    @Test
    fun settingsPersistAndInvalidValuesAreNormalized() {
        val root = Files.createTempDirectory("AniLife-settings")
        try {
            val file = root.resolve("settings.properties").toFile()
            val store = AppSettingsStore(file)
            store.update {
                it.copy(
                    audio = "dub",
                    subtitleLanguage = "fr",
                    resumePlayback = false,
                    playIntro = false,
                    titleLanguage = "romaji",
                    discordPresenceEnabled = true,
                    discordApplicationId = "123456789012345678x",
                    volume = 140,
                    contentScale = "invalid",
                    subtitleScale = 4f,
                    aniListSubmitPercent = 250,
                    captionFont = "  Custom Anime Font  ",
                    captionFontFile = root.resolve("custom.otf").toString(),
                    captionTextColor = "#AABBCC",
                    captionTextOpacity = 0.7f,
                    captionBackgroundColor = "#112233",
                    captionBackgroundOpacity = 0.45f,
                    captionEdgeStyle = "shadow",
                    captionVerticalPosition = "top",
                    captionVerticalOffset = 12f,
                    captionAlignment = "right",
                    captionPreset = "cinema",
                    downloadDirectory = root.toString(),
                    themeCss = ":root {\n  --background: #101010;\n}",
                )
            }

            val persisted = AppSettingsStore(file).settings.value

            assertEquals("dub", persisted.audio)
            assertEquals("fr", persisted.subtitleLanguage)
            assertEquals(false, persisted.resumePlayback)
            assertEquals(false, persisted.playIntro)
            assertEquals("romaji", persisted.titleLanguage)
            assertEquals(true, persisted.discordPresenceEnabled)
            assertEquals("123456789012345678", persisted.discordApplicationId)
            assertEquals(100, persisted.volume)
            assertEquals("fit", persisted.contentScale)
            assertEquals(2f, persisted.subtitleScale)
            assertEquals(100, persisted.aniListSubmitPercent)
            assertEquals("Custom Anime Font", persisted.captionFont)
            assertEquals(root.resolve("custom.otf").toString(), persisted.captionFontFile)
            assertEquals("#AABBCC", persisted.captionTextColor)
            assertEquals(0.7f, persisted.captionTextOpacity)
            assertEquals("#112233", persisted.captionBackgroundColor)
            assertEquals(0.45f, persisted.captionBackgroundOpacity)
            assertEquals("shadow", persisted.captionEdgeStyle)
            assertEquals("top", persisted.captionVerticalPosition)
            assertEquals(12f, persisted.captionVerticalOffset)
            assertEquals("right", persisted.captionAlignment)
            assertEquals("cinema", persisted.captionPreset)
            assertEquals(root.toAbsolutePath().normalize().toString(), persisted.downloadDirectory)
            assertEquals(":root {\n  --background: #101010;\n}", persisted.themeCss)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unsupportedSubtitleLanguageFallsBackToEnglish() {
        val root = Files.createTempDirectory("AniLife-settings-language")
        try {
            val file = root.resolve("settings.properties").toFile()
            val store = AppSettingsStore(file)
            store.update { it.copy(subtitleLanguage = "unsupported") }
            assertEquals("en", AppSettingsStore(file).settings.value.subtitleLanguage)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun homeSectionOrderAndVisibilityPersistAndMissingSectionsAreRestored() {
        val root = Files.createTempDirectory("AniLife-settings-home")
        try {
            val file = root.resolve("settings.properties").toFile()
            val store = AppSettingsStore(file)
            store.update {
                it.copy(
                    homeSectionOrder = listOf(HomeSection.MOVIES, HomeSection.NEW_EPISODES),
                    hiddenHomeSections = setOf(HomeSection.CONTINUE_WATCHING),
                )
            }

            val persisted = AppSettingsStore(file).settings.value

            assertEquals(
                listOf(HomeSection.MOVIES, HomeSection.NEW_EPISODES) +
                    HomeSection.entries.filterNot { it == HomeSection.MOVIES || it == HomeSection.NEW_EPISODES },
                persisted.homeSectionOrder,
            )
            assertEquals(setOf(HomeSection.CONTINUE_WATCHING), persisted.hiddenHomeSections)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

