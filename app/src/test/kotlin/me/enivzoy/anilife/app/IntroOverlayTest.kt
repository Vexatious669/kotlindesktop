package me.enivzoy.anilife.app

import me.enivzoy.anilife.mpv.Event
import me.enivzoy.anilife.mpv.Mpv
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntroOverlayTest {
	@Test
	fun introUsesComposeCompatibleSoftwareRendering() {
		val options = introPlaybackOptions().toMap()

		assertEquals("libmpv", options["vo"])
		assertEquals("no", options["hwdec"])
		assertFalse("wid" in options)
		assertEquals("yes", options["idle"])
		assertEquals("no", options["keep-open"])
	}

	@Test
	fun introClipProducesAFrameOnTheSoftwareSurface() {
		val library = Mpv.defaultLibraryPath()
		if (library == null || !File(library).isFile) {
			println("libmpv is unavailable; skipping intro software-render test")
			return
		}
		val intro = File.createTempFile("AniLife-intro-test-", ".mp4")
		val input = checkNotNull(Mpv::class.java.getResourceAsStream("/anilife-intro.mp4"))
		input.use { stream -> intro.outputStream().use(stream::copyTo) }
		val lastEvent = AtomicReference("no mpv events received")
		var player: Mpv? = null
		var surface: Mpv.SoftwareVideoSurface? = null
		try {
			val activePlayer = Mpv(library, introPlaybackOptions())
			player = activePlayer
			activePlayer.setLogLevel("warn")
			activePlayer.startEvents { event ->
				lastEvent.set(
					when (event) {
						Event.StartFile -> "start-file"
						Event.FileLoaded -> "file-loaded"
						is Event.EndFile -> "end-file ${event.reason} ${event.error.orEmpty()}"
						is Event.Log -> "${event.level}: ${event.text}"
						else -> event.toString()
					},
				)
			}
			val videoSurface = activePlayer.createSoftwareVideoSurface(1280, 720)
			surface = videoSurface
			activePlayer.loadfile(intro.absolutePath)

			val deadline = System.nanoTime() + 5_000_000_000L
			var renderedFrame = false
			while (!renderedFrame && System.nanoTime() < deadline) {
				if (videoSurface.needsRender()) renderedFrame = videoSurface.render() != null
				if (!renderedFrame) Thread.sleep(8)
			}

			assertTrue(renderedFrame, "mpv produced no intro frame; last event: ${lastEvent.get()}")
		} finally {
			surface?.close()
			player?.shutdown()
			intro.delete()
		}
	}
}
