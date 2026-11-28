package me.enivzoy.anilife.app

import me.enivzoy.anilife.app.data.DownloadJob
import me.enivzoy.anilife.app.data.DownloadStatus
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadQueueProgressTest {
	private fun job(status: DownloadStatus, downloaded: Long = 0, total: Long? = null, fraction: Float? = null) = DownloadJob(
		title = "Test",
		episode = 1.0,
		url = "https://example.com/video.mp4",
		audio = "sub",
		downloadRoot = Path.of("C:/anime"),
		status = status,
		downloaded = downloaded,
		total = total,
		progressFraction = fraction,
	)

	@Test
	fun aggregatesActiveDownloadBytes() {
		assertEquals(0.5f, downloadQueueProgress(listOf(
			job(DownloadStatus.Running, downloaded = 100, total = 200),
			job(DownloadStatus.Running, downloaded = 150, total = 300),
		)))
	}

	@Test
	fun aggregatesHlsProgressByCompletedSegmentFractionWhenByteSizeIsUnknown() {
		assertEquals(0.5f, downloadQueueProgress(listOf(
			job(DownloadStatus.Running, downloaded = 100, fraction = 0.25f),
			job(DownloadStatus.Running, downloaded = 300, fraction = 0.75f),
		)))
	}

	@Test
	fun indicatesActivityWhenAnyActiveJobHasUnknownSize() {
		assertTrue(downloadQueueProgress(listOf(
			job(DownloadStatus.Running, downloaded = 100, total = 200),
			job(DownloadStatus.Running),
		))!!.isNaN())
	}

	@Test
	fun hidesProgressWhenNoDownloadsAreActive() {
		assertNull(downloadQueueProgress(listOf(job(DownloadStatus.Done, downloaded = 100, total = 100))))
		assertNull(downloadQueueProgress(emptyList()))
	}
}
