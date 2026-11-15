package me.enivzoy.anilife.app.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import me.enivzoy.anilife.app.data.AnimeEpisode

class EpisodeSectionTest {
    @Test
    fun splitsLongSeriesIntoHundredEpisodeRanges() {
        val episodes = (1..201).map { AnimeEpisode(it.toDouble(), null) }

        val ranges = episodeRanges(episodes)

        assertEquals(listOf(100, 100, 1), ranges.map { it.size })
        assertEquals("1–100", episodeRangeLabel(ranges[0]))
        assertEquals("201–201", episodeRangeLabel(ranges[2]))
    }

    @Test
    fun labelsFractionalEpisodeRanges() {
        val episodes = listOf(AnimeEpisode(1.0, null), AnimeEpisode(1.5, null))

        assertEquals("1–1.5", episodeRangeLabel(episodes))
    }

    @Test
    fun rejectsInvalidRangeSizesAndEmptyLabels() {
        assertFailsWith<IllegalArgumentException> { episodeRanges(emptyList(), 0) }
        assertFailsWith<IllegalArgumentException> { episodeRangeLabel(emptyList()) }
    }
}
