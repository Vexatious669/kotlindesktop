package me.enivzoy.anilife.app.screens

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import me.enivzoy.anilife.core.model.AiringEntry
import me.enivzoy.anilife.core.model.AnimeCard

class ScheduleScreenTest {
    @Test
    fun dateWindowShowsTwoPastDaysAndTheNextSixDays() {
        val today = LocalDate.of(2026, 11, 15)

        val dates = scheduleDateWindow(today)

        assertEquals(9, dates.size)
        assertEquals(today.minusDays(2), dates.first())
        assertEquals(today, dates[2])
        assertEquals(today.plusDays(6), dates.last())
    }

    @Test
    fun scheduleFiltersMatchFormatAndLocalLibrary() {
        val tv = AiringEntry(1_800_000_000, 3, AnimeCard(id = 1, title = "Series", format = "TV"))
        val movie = AiringEntry(1_800_000_100, 1, AnimeCard(id = 2, title = "Film", format = "MOVIE"))

        val filtered = filterScheduleEntries(
            entries = listOf(tv, movie),
            formatFilters = setOf("TV"),
            libraryIds = setOf(1),
            onlyMyList = true,
        )

        assertEquals(listOf(tv), filtered)
        assertEquals(
            listOf(tv, movie),
            filterScheduleEntries(listOf(tv, movie), emptySet(), emptySet(), onlyMyList = false),
        )
    }
}
