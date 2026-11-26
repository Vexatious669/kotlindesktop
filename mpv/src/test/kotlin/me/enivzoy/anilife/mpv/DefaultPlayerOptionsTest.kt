package me.enivzoy.anilife.mpv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultPlayerOptionsTest {

    private val options = defaultPlayerOptions().toMap()

    @Test
    fun keepsTheForwardBufferBoundedToProtectCdnLimits() {
        assertEquals("yes", options["cache"])
        assertEquals("120", options["cache-secs"])
        assertEquals("150MiB", options["demuxer-max-bytes"])
        assertEquals("50MiB", options["demuxer-max-back-bytes"])
    }

    @Test
    fun asksLavfToRetryFailedHlsSegments() {
        val demuxer = options.getValue("demuxer-lavf-o")
        assertTrue("seg_max_retry=8" in demuxer)
        assertTrue("reconnect=1" in demuxer)
        assertTrue("reconnect_streamed=1" in demuxer)
        assertTrue("reconnect_delay_max=6" in demuxer)

        val stream = options.getValue("stream-lavf-o")
        assertTrue("reconnect=1" in stream)
        assertTrue("reconnect_streamed=1" in stream)
        assertTrue("reconnect_delay_max=6" in stream)
    }

    @Test
    fun declaresNoDuplicateOptionNames() {
        val names = defaultPlayerOptions().map { it.first }
        assertEquals(names.size, names.distinct().size)
    }
}