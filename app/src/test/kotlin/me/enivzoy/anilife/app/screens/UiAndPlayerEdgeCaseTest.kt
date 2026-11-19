package me.enivzoy.anilife.app.screens

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.enivzoy.anilife.app.data.DiscordOAuthUser
import me.enivzoy.anilife.app.data.DiscordPresenceState
import me.enivzoy.anilife.app.data.DiscordPresenceStatus
import me.enivzoy.anilife.app.data.DiscordPresenceTarget
import me.enivzoy.anilife.app.data.buildDiscordPresenceActivity
import me.enivzoy.anilife.app.fitWindowBoundsToWorkArea
import me.enivzoy.anilife.app.ui.LoadState
import me.enivzoy.anilife.app.ui.ThemeCss
import me.enivzoy.anilife.app.ui.cardMeta
import me.enivzoy.anilife.app.ui.loadState
import me.enivzoy.anilife.app.ui.parseAnimeColor
import me.enivzoy.anilife.core.model.AnimeCard
import me.enivzoy.anilife.core.model.SkipTimes
import me.enivzoy.anilife.core.model.Window
import java.awt.Rectangle
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiAndPlayerEdgeCaseTest {
    @Test
    fun windowBoundsClampsNegativeMultiMonitorOriginsAndOversizedDimensions() {
        val secondaryMonitor = Rectangle(-1920, -1080, 1920, 1040)
        val clamped = fitWindowBoundsToWorkArea(Rectangle(-2500, -1500, 2400, 1600), secondaryMonitor)

        assertEquals(secondaryMonitor, clamped)
    }

    @Test
    fun playerLayoutMetricsAdaptsAcrossAllWidthAndHeightBreakpoints() {
        val ultraWide = playerLayoutMetrics(1600, 900)
        val desktop = playerLayoutMetrics(1024, 720)
        val tabletCompact = playerLayoutMetrics(640, 250)
        val miniCompact = playerLayoutMetrics(360, 220)

        assertTrue(ultraWide.showPillLabels)
        assertFalse(ultraWide.compactHeight)

        assertTrue(desktop.showPillLabels)
        assertFalse(desktop.compactHeight)

        assertFalse(tabletCompact.showPillLabels)
        assertTrue(tabletCompact.compactHeight)

        assertFalse(miniCompact.showPillLabels)
        assertTrue(miniCompact.compactHeight)
    }

    @Test
    fun formatPlayerTimeHandlesHoursRoundingNegativeAndNonFiniteValues() {
        assertEquals("00:00", formatPlayerTime(0.0))
        assertEquals("00:00", formatPlayerTime(-45.0))
        assertEquals("00:00", formatPlayerTime(Double.NaN))
        assertEquals("00:00", formatPlayerTime(Double.POSITIVE_INFINITY))
        assertEquals("00:00", formatPlayerTime(Double.NEGATIVE_INFINITY))
        assertEquals("00:59", formatPlayerTime(59.4))
        assertEquals("01:00", formatPlayerTime(59.6))
        assertEquals("59:59", formatPlayerTime(3599.0))
        assertEquals("1:00:00", formatPlayerTime(3600.0))
        assertEquals("2:05:09", formatPlayerTime(7509.0))
    }

    @Test
    fun playerEpisodeNavigationAndSkipBoundariesHandleNonFiniteAndEdgeInputs() {
        val list = listOf(1.0, Double.NaN, 2.0, Double.POSITIVE_INFINITY)
        assertNull(previousEpisodeNumber(list, Double.NaN))
        assertNull(nextEpisodeNumber(list, Double.POSITIVE_INFINITY))
        assertEquals(1.0, previousEpisodeNumber(list, 2.0))
        assertNull(nextEpisodeNumber(list, 2.0))

        val invalidSkip = SkipTimes(
            intro = Window(100.0, 50.0),
            recap = Window(Double.NaN, 30.0),
            outro = Window(1350.0, 1350.0),
        )
        assertNull(activeSkipWindow(invalidSkip, 60.0, 1440.0))

        assertFalse(nextEpisodeDue(-1.0, 1440.0, null))
        assertFalse(nextEpisodeDue(100.0, 0.0, null))
        assertFalse(nextEpisodeDue(1450.0, 1440.0, null))
        assertTrue(nextEpisodeDue(1410.0, 1440.0, null))
        assertTrue(nextEpisodeDue(1435.0, 1440.0, Window(1350.0, 1438.0)))
    }

    @Test
    fun discordModelsAndPresenceTruncateOverlongUnicodeStringsCleanly() {
        val oauthUser = DiscordOAuthUser("123", "fallback_user", "   ", "hash123")
        assertEquals("fallback_user", oauthUser.displayName)
        assertEquals("https://cdn.discordapp.com/avatars/123/hash123.png?size=256", oauthUser.avatarUrl)
        assertNull(oauthUser.copy(avatarHash = " ").avatarUrl)

        val state = DiscordPresenceState(DiscordPresenceStatus.Connected, userId = "123", avatarHash = "abc")
        assertEquals("https://cdn.discordapp.com/avatars/123/abc.png?size=256", state.avatarUrl)
        assertNull(state.copy(userId = null).avatarUrl)

        val longTitle = "あ".repeat(140)
        val activity = buildDiscordPresenceActivity(
            DiscordPresenceTarget("1", longTitle, false, "FALL", 2026, 3.0, seasonNumber = 2),
            Instant.EPOCH,
        )
        val details = activity.details().orEmpty()
        assertEquals(128, details.codePointCount(0, details.length))
        assertTrue(details.endsWith("…"))
        assertEquals("On S02E03", activity.state())

        val blankTitleActivity = buildDiscordPresenceActivity(
            DiscordPresenceTarget("2", "   ", false, null, null, null),
            Instant.EPOCH,
        )
        assertEquals("Anime", blankTitleActivity.details())
        assertEquals("App developed by witherbl_de", blankTitleActivity.state())
    }

    @Test
    fun commonUiHelpersFormatMetaParseColorsAndPreserveCancellation() = runBlocking {
        val fallback = Color(0xFF123456)
        assertEquals(fallback, parseAnimeColor(null, fallback))
        assertEquals(fallback, parseAnimeColor("", fallback))
        assertEquals(fallback, parseAnimeColor("#12345", fallback))
        assertEquals(fallback, parseAnimeColor("#GGGGGG", fallback))
        assertEquals(Color(0xFF87D66A), parseAnimeColor(" #87D66A ", fallback))
        assertEquals(Color(0x80112233), parseAnimeColor("#80112233", fallback))

        val card = AnimeCard(id = 1, title = "Show", format = "TV_SHORT", year = 2026, episodes = 12)
        assertEquals("TV Short · 2026 · 12 eps", cardMeta(card))
        assertEquals("", cardMeta(AnimeCard(id = 2, title = "Empty")))

        assertEquals(LoadState.Ready(42), loadState { 42 })
        assertEquals(LoadState.Failed("Something went wrong."), loadState<Int> { throw RuntimeException(" ") })
        assertFailsWith<CancellationException> {
            loadState<Int> { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun themeCssPresetJsonRoundTripAndNumericArgbImport() {
        val preset = ThemeCss.colorPresets.first()
        val exported = ThemeCss.exportPreset(preset)
        val reimported = ThemeCss.importPreset(exported)

        assertEquals(preset, reimported)

        val numericJson = """{"name":"Numeric","description":"Test","colors":{"--primary":8902250,"background":"#00000000"}}"""
        val parsedNumeric = ThemeCss.importPreset(numericJson)!!
        assertEquals("Numeric", parsedNumeric.name)
        assertEquals(0xFF87D66AL, parsedNumeric.colors["primary"])
        assertEquals(0x00000000L, parsedNumeric.colors["background"])
        assertNull(ThemeCss.importPreset("{broken json"))
    }
}
