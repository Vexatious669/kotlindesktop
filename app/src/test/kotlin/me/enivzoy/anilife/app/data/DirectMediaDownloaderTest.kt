package me.enivzoy.anilife.app.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectMediaDownloaderTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun createsTheRequestedDownloadFolderAndParents() {
        val downloadDirectory = tempDirectory.resolve("AniLife/Downloads")

        assertEquals(downloadDirectory, DirectMediaDownloader.ensureDownloadDirectory(downloadDirectory))
        assertTrue(Files.isDirectory(downloadDirectory))
    }

    @Test
    fun defaultsDownloadsToTheUsersVideosFolder() {
        assertEquals(
            Path.of(System.getProperty("user.home"), "Videos", "AniLife"),
            DirectMediaDownloader.defaultDownloadDirectory(),
        )
    }

    @Test
    fun detectsHlsAndDashPlaylists() {
        assertTrue(DirectMediaDownloader.isStreamUrl("https://example.com/master.m3u8?token=abc"))
        assertTrue(DirectMediaDownloader.isStreamUrl("https://example.com/manifest.mpd"))
        assertFalse(DirectMediaDownloader.isStreamUrl("https://example.com/video.mp4"))
    }

    @Test
    fun rejectsUnsupportedUrlBeforeNetwork() {
        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                DirectMediaDownloader.download(
                    url = "https://example.com/file.txt",
                    animeTitle = "Test",
                    episode = 1.0,
                    audio = "sub",
                )
            }
        }
        assertTrue(error.message.orEmpty().contains("HLS/DASH"))
    }
}
