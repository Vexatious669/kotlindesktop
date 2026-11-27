package me.enivzoy.anilife.app.player

import me.enivzoy.anilife.app.data.SubtitleAppearance
import me.enivzoy.anilife.mpv.Mpv
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvPlaybackEngineTest {

    @Test
    fun extendedBufferingCanBeTurnedOffWithoutLosingTheCacheOption() {
        val extended = bufferedPlayerOptions(experimental = true).toMap()
        assertEquals("120", extended["cache-secs"])
        assertEquals("yes", extended["cache"])

        val lightweight = bufferedPlayerOptions(experimental = false).toMap()
        assertEquals("no", lightweight["cache"])
        assertTrue(lightweight.keys.none { it in lightweightBufferKeys })
    }

    @Test
    fun subtitleAppearanceMapsToMpvOptions() {
        val fontFile = File("custom-fonts/Anime Font.ttf").absoluteFile
        val options = subtitleAppearanceOptions(
            SubtitleAppearance(
                fontFamily = "Anime Font",
                fontFile = fontFile.path,
                scale = 1.25f,
                textColor = "#AABBCC",
                textOpacity = 0.5f,
                backgroundColor = "#112233",
                backgroundOpacity = 0.75f,
                edgeStyle = "shadow",
                verticalPosition = "top",
                verticalOffset = 12f,
                alignment = "right",
            ),
        ).toMap()

        assertEquals("Anime Font", options["sub-font"])
        assertEquals("1.25", options["sub-scale"])
        assertEquals("#7FAABBCC", options["sub-color"])
        assertEquals("#BF112233", options["sub-back-color"])
        assertEquals("0", options["sub-border-size"])
        assertEquals("2", options["sub-shadow-offset"])
        assertEquals("12", options["sub-pos"])
        assertEquals("right", options["sub-align-x"])
        assertEquals(fontFile.parentFile.absolutePath, options["sub-fonts-dir"])
    }

    @Test
    fun picksThePreferredCaptionLanguageAndFallsBackToAnyTrack() {
        val english = PlayerSubtitleTrack(1, "English", "en", false, true)
        val japanese = PlayerSubtitleTrack(2, "Japanese", "ja", false, false)

        assertEquals(english, chooseSubtitleTrack(listOf(japanese, english), "en"))
        assertEquals(japanese, chooseSubtitleTrack(listOf(japanese, english), "ja"))
        // 3-letter codes like "eng" should still match the two-letter preference.
        val shortCodeEnglish = PlayerSubtitleTrack(3, "English", "eng", false, false)
        assertEquals(3, chooseSubtitleTrack(listOf(japanese, shortCodeEnglish), "en")?.id)
        // No preference ("none") or an unknown language falls back to the first track.
        assertEquals(japanese, chooseSubtitleTrack(listOf(japanese, english), "none"))
        assertEquals(japanese, chooseSubtitleTrack(listOf(japanese, english), "zz"))
        assertNull(chooseSubtitleTrack(emptyList(), "en"))
    }

    @Test
    fun reportsRealPlaybackStateAndRespondsToTransportControls() {
        val library = Mpv.defaultLibraryPath()
        val clip = File("src/main/resources/anilife-intro.mp4")
        if (library == null || !File(library).isFile || !clip.isFile) {
            println("libmpv or the intro clip is unavailable; skipping playback engine test")
            return
        }

        val engine = MpvPlaybackEngine(
            url = clip.absolutePath,
            fallbackDurationSeconds = 60.0,
            renderWidth = 320,
            renderHeight = 180,
        )
        engine.start()
        try {
            assertTrue(await(15_000L) { engine.videoFrame != null }, "expected a decoded video frame")
            assertTrue(await(15_000L) { engine.positionSeconds > 0.2 }, "expected the mpv clock to advance")
            assertTrue(engine.durationSeconds in 1.0..30.0, "unexpected duration ${engine.durationSeconds}")
            assertTrue(engine.isPlaying, "mpv should be playing after load")

            engine.seekTo(1.0)
            assertTrue(await(5_000L) { engine.positionSeconds in 0.9..4.0 }, "seek did not move the clock")

            engine.togglePause()
            assertTrue(await(3_000L) { !engine.isPlaying }, "pause was not reflected in the engine")

            engine.togglePause()
            assertTrue(await(3_000L) { engine.isPlaying }, "play was not reflected in the engine")

            val before = engine.positionSeconds
            engine.seekRelative(1.5)
            assertTrue(await(3_000L) { engine.positionSeconds >= before }, "relative seek moved backwards")
        } finally {
            engine.close()
        }
    }

    @Test
    fun reportsAFailureInsteadOfStayingOnTheLoadingState() {
        val engine = MpvPlaybackEngine(
            url = "file:///definitely-not-a-video.mp4",
            renderWidth = 160,
            renderHeight = 90,
        )
        engine.start()
        try {
            // Either libmpv is absent (the engine reports a load error) or it is present and
            // rejects the file; both must stop the chrome from claiming to buffer forever.
            assertTrue(
                await(15_000L) { engine.error != null || !engine.isLoading },
                "the engine stayed in the loading state for a missing file",
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun listsAndSelectsTheEmbeddedAudioTrack() {
        val clip = File("src/main/resources/anilife-intro.mp4")
        if (!playable(clip)) return
        val engine = MpvPlaybackEngine(url = clip.absolutePath, renderWidth = 320, renderHeight = 180)
        engine.start()
        try {
            assertTrue(await(15_000L) { engine.videoFrame != null }, "expected the clip to play")
            if (engine.audioTracks.isEmpty()) {
                println("the sample clip has no audio track; audio switching is untested here")
                return
            }
            val track = engine.audioTracks.first()
            engine.selectAudioTrack(track.id)
            assertTrue(
                await(5_000L) { engine.audioTracks.any { it.selected && it.id == track.id } },
                "audio track ${track.id} was not selected",
            )
            assertNull(engine.switchError)
        } finally {
            engine.close()
        }
    }

    @Test
    fun switchingSourcePreservesPositionAndPlaybackState() {
        val clip = File("src/main/resources/anilife-intro.mp4")
        if (!playable(clip)) return
        val copy = File.createTempFile("anilife-audio-switch", ".mp4")
        clip.copyTo(copy, overwrite = true)
        val engine = MpvPlaybackEngine(url = clip.absolutePath, renderWidth = 320, renderHeight = 180)
        engine.start()
        try {
            assertTrue(await(15_000L) { engine.videoFrame != null }, "expected the first stream to play")
            engine.seekTo(2.0)
            assertTrue(await(5_000L) { engine.positionSeconds in 1.9..8.0 }, "seek did not take")
            engine.togglePause()
            assertTrue(await(3_000L) { !engine.isPlaying }, "pause did not take")

            engine.switchSource(copy.absolutePath, emptyMap(), emptyList(), null, "Test source")

            assertTrue(
                await(20_000L) { engine.currentLoadedUrl == copy.absolutePath },
                "the switched stream never opened",
            )
            assertTrue(await(5_000L) { engine.positionSeconds >= 1.9 }, "position was not restored: ${engine.positionSeconds}")
            assertTrue(await(3_000L) { !engine.isPlaying }, "paused state was not restored")
            assertNull(engine.switchError)
            assertNull(engine.error)
        } finally {
            engine.close()
            copy.delete()
        }
    }

    @Test
    fun aFailedSwitchKeepsTheWorkingStream() {
        val clip = File("src/main/resources/anilife-intro.mp4")
        if (!playable(clip)) return
        val engine = MpvPlaybackEngine(url = clip.absolutePath, renderWidth = 320, renderHeight = 180)
        engine.start()
        try {
            assertTrue(await(15_000L) { engine.videoFrame != null }, "expected the first stream to play")
            engine.seekTo(2.0)
            assertTrue(await(5_000L) { engine.positionSeconds in 1.9..8.0 }, "seek did not take")
            engine.togglePause()
            assertTrue(await(3_000L) { !engine.isPlaying }, "pause did not take")

            engine.switchSource("file:///definitely-not-a-video.mp4", emptyMap(), emptyList(), null, "Broken source")

            assertTrue(await(20_000L) { engine.switchError != null }, "the failed switch was not reported")
            assertNull(engine.error)
            assertEquals(clip.absolutePath, engine.currentUrl)
            assertTrue(
                await(10_000L) { engine.currentLoadedUrl == clip.absolutePath && engine.videoFrame != null },
                "the previous stream was not restored",
            )
            assertTrue(await(5_000L) { engine.positionSeconds >= 1.9 }, "position was not restored: ${engine.positionSeconds}")
            assertTrue(await(3_000L) { !engine.isPlaying }, "paused state was not restored")
        } finally {
            engine.close()
        }
    }

    private fun playable(clip: File): Boolean {
        val library = Mpv.defaultLibraryPath() ?: return false
        return File(library).isFile && clip.isFile
    }

    private fun await(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25L)
        }
        return condition()
    }

    @Test
    fun hwdecForAutoReturnsAutoCopySafe() {
        assertEquals("auto-copy-safe", hwdecFor("auto"))
    }

    @Test
    fun hwdecForOffReturnsNo() {
        assertEquals("no", hwdecFor("off"))
    }

    @Test
    fun hwdecForEmptyReturnsNo() {
        assertEquals("no", hwdecFor(""))
    }

    @Test
    fun hwdecForUnknownReturnsNo() {
        assertEquals("no", hwdecFor("anything"))
    }

    @Test
    fun withHwdecReplacesExistingHwdecEntry() {
        val options = listOf("hwdec" to "no", "vo" to "gpu")
        val result = withHwdec(options, "auto-copy-safe")
        assertEquals(2, result.size)
        assertEquals("auto-copy-safe", result.first { it.first == "hwdec" }.second)
    }

    @Test
    fun withHwdecAddsHwdecWhenMissing() {
        val options = listOf("vo" to "gpu")
        val result = withHwdec(options, "auto-copy-safe")
        assertEquals(2, result.size)
        assertEquals("auto-copy-safe", result.first { it.first == "hwdec" }.second)
    }

    @Test
    fun withHwdecPutsHwdecAtEnd() {
        val options = listOf("vo" to "gpu", "hwdec" to "no", "profile" to "low-latency")
        val result = withHwdec(options, "auto-copy-safe")
        assertEquals(3, result.size)
        assertEquals("gpu", result[0].second)
        assertEquals("low-latency", result[1].second)
        assertEquals("auto-copy-safe", result[2].second)
    }
}

