package me.enivzoy.anilife.core

import me.enivzoy.anilife.core.error.ErrorEntry
import me.enivzoy.anilife.core.model.GENRES
import me.enivzoy.anilife.core.model.Window
import me.enivzoy.anilife.core.model.countdown
import me.enivzoy.anilife.core.model.formatLabel
import me.enivzoy.anilife.core.model.plainDescription
import me.enivzoy.anilife.core.model.seasonLabel
import me.enivzoy.anilife.core.model.seasonOf
import me.enivzoy.anilife.core.model.seasonYearOf
import me.enivzoy.anilife.core.model.statusLabel
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreModelsAndFormatEdgeCaseTest {
    @Test
    fun errorEntryParsesValidDiagnosticCodesAndFallsBackOnMalformedInput() {
        assertEquals(ErrorEntry.Fallback, ErrorEntry.asEntry(null))
        assertEquals(ErrorEntry.Fallback, ErrorEntry.asEntry(""))
        assertEquals(ErrorEntry.Fallback, ErrorEntry.asEntry("   "))
        assertEquals(ErrorEntry.Fallback, ErrorEntry.asEntry("dapp-01"))
        assertEquals(ErrorEntry.Fallback, ErrorEntry.asEntry("DAPP-01"))
        assertEquals(ErrorEntry.Fallback, ErrorEntry.asEntry("DAB-"))
        assertEquals(ErrorEntry.Fallback, ErrorEntry.asEntry("D12-01"))
        assertEquals(ErrorEntry.Fallback, ErrorEntry.asEntry(" DAB-01 "))

        val valid = ErrorEntry.asEntry("DAB-404")
        assertEquals("DAB-404", valid.code)
        assertEquals("DAB-404", valid.message)
    }

    @Test
    fun formatAndStatusLabelsMapKnownConstantsAndPreserveCustomValues() {
        assertNull(formatLabel(null))
        assertEquals("TV", formatLabel("TV"))
        assertEquals("TV Short", formatLabel("TV_SHORT"))
        assertEquals("Movie", formatLabel("MOVIE"))
        assertEquals("Special", formatLabel("SPECIAL"))
        assertEquals("OVA", formatLabel("OVA"))
        assertEquals("ONA", formatLabel("ONA"))
        assertEquals("Music", formatLabel("MUSIC"))
        assertEquals("UNKNOWN_FORMAT", formatLabel("UNKNOWN_FORMAT"))

        assertNull(statusLabel(null))
        assertEquals("Airing", statusLabel("RELEASING"))
        assertEquals("Finished", statusLabel("FINISHED"))
        assertEquals("Upcoming", statusLabel("NOT_YET_RELEASED"))
        assertEquals("Cancelled", statusLabel("CANCELLED"))
        assertEquals("Hiatus", statusLabel("HIATUS"))
        assertEquals("CUSTOM_STATUS", statusLabel("CUSTOM_STATUS"))
    }

    @Test
    fun seasonLabelHandlesNullsBlanksAndCaseNormalization() {
        assertNull(seasonLabel("FALL", null))
        assertNull(seasonLabel(null, null))
        assertEquals("2026", seasonLabel(null, 2026))
        assertEquals("2026", seasonLabel("", 2026))
        assertEquals("2026", seasonLabel("   ", 2026))
        assertEquals("Winter 2026", seasonLabel("WINTER", 2026))
        assertEquals("Spring 2025", seasonLabel("spring", 2025))
    }

    @Test
    fun seasonOfAndSeasonYearOfHandleAllMonthsAndDecemberRollover() {
        assertEquals("WINTER", seasonOf(LocalDate.of(2026, 1, 1)))
        assertEquals("WINTER", seasonOf(LocalDate.of(2024, 2, 29)))
        assertEquals("WINTER", seasonOf(LocalDate.of(2026, 3, 31)))
        assertEquals("SPRING", seasonOf(LocalDate.of(2026, 4, 1)))
        assertEquals("SPRING", seasonOf(LocalDate.of(2026, 6, 30)))
        assertEquals("SUMMER", seasonOf(LocalDate.of(2026, 7, 1)))
        assertEquals("SUMMER", seasonOf(LocalDate.of(2026, 9, 30)))
        assertEquals("FALL", seasonOf(LocalDate.of(2026, 10, 1)))
        assertEquals("FALL", seasonOf(LocalDate.of(2026, 11, 30)))
        assertEquals("FALL", seasonOf(LocalDate.of(2026, 12, 31)))

        assertEquals(2026, seasonYearOf(LocalDate.of(2026, 1, 15)))
        assertEquals(2026, seasonYearOf(LocalDate.of(2026, 11, 30)))
        assertEquals(2027, seasonYearOf(LocalDate.of(2026, 12, 1)))
    }

    @Test
    fun countdownCoversPastImmediateMinuteHourAndDayBoundaries() {
        val now = 1_000_000L

        assertEquals("now", countdown(now - 500, now))
        assertEquals("now", countdown(now, now))
        assertEquals("now", countdown(now + 59, now))
        assertEquals("in 1m", countdown(now + 60, now))
        assertEquals("in 59m", countdown(now + 3_599, now))
        assertEquals("in 1h 0m", countdown(now + 3_600, now))
        assertEquals("in 2h 30m", countdown(now + 9_000, now))
        assertEquals("in 23h 59m", countdown(now + 86_399, now))
        assertEquals("in 1d 0h", countdown(now + 86_400, now))
        assertEquals("in 3d 5h", countdown(now + 3 * 86_400 + 5 * 3_600 + 120, now))
    }

    @Test
    fun plainDescriptionStripsHtmlUnescapesEntitiesCollapsesNewlinesAndTruncates() {
        assertEquals("", plainDescription(null))
        assertEquals("", plainDescription(""))
        assertEquals("", plainDescription("   \n\t  "))

        val html = "  <b>Line 1</b><br><BR/>Line &quot;2&quot; &amp; &#039;3&#039; &lt;tag&gt;<br><br><br><br>Line 4  "
        assertEquals("Line 1\n\nLine \"2\" & '3' <tag>\n\nLine 4", plainDescription(html))

        val truncated = plainDescription("Hello world from AniLife", limit = 12)
        assertEquals("Hello world…", truncated)
        assertEquals("Short", plainDescription("Short", limit = 10))
    }

    @Test
    fun windowValidationChecksOrderingAndFiniteness() {
        assertTrue(Window(0.0, 90.0).isValid)
        assertTrue(Window(-5.0, 0.0).isValid)
        assertFalse(Window(50.0, 50.0).isValid)
        assertFalse(Window(90.0, 10.0).isValid)
        assertFalse(Window(Double.NaN, 90.0).isValid)
        assertFalse(Window(0.0, Double.NaN).isValid)
        assertFalse(Window(0.0, Double.POSITIVE_INFINITY).isValid)
        assertFalse(Window(Double.NEGATIVE_INFINITY, 10.0).isValid)
    }

    @Test
    fun genresCatalogueContainsStandardCategories() {
        assertTrue(GENRES.containsAll(listOf("Action", "Drama", "Fantasy", "Sci-Fi", "Thriller")))
        assertEquals(GENRES.distinct().size, GENRES.size)
    }
}
