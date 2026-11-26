package me.enivzoy.anilife.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameStatsTest {

    @Test
    fun countsFramesAndTreatsExactlyFiftyMillisAsFast() {
        val stats = FrameStats()
        stats.add(16.6)
        stats.add(50.0)
        stats.add(50.1)

        assertEquals(Triple(3, 1, 50.1), stats.snapshot())
    }

    @Test
    fun tracksTheWorstFrame() {
        val stats = FrameStats()
        stats.add(12.0)
        stats.add(220.5)
        stats.add(33.0)

        assertEquals(220.5, stats.snapshot().third)
        assertEquals(1, stats.snapshot().second)
    }

    @Test
    fun resetClearsEveryCounter() {
        val stats = FrameStats()
        stats.add(16.0)
        stats.add(500.0)

        stats.reset()

        assertEquals(Triple(0, 0, 0.0), stats.snapshot())
    }

    @Test
    fun emptyStatsReportNothing() {
        assertEquals(Triple(0, 0, 0.0), FrameStats().snapshot())
    }
}