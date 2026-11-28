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
    fun reportsDirectFile403AsARefreshableSourceFailure() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mp4") { exchange ->
            val body = "forbidden".toByteArray()
            exchange.sendResponseHeaders(403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val error = assertFailsWith<SourceException> {
                runBlocking {
                    DirectMediaDownloader.download(
                        url = "http://127.0.0.1:${server.address.port}/video.mp4",
                        animeTitle = "Test",
                        episode = 1.0,
                        audio = "sub",
                        downloadRoot = tempDirectory,
                    )
                }
            }
            assertEquals(SourceFailure.Forbidden, error.reason)
            assertTrue(error.reason.retryWithFreshSource)
        } finally {
            server.stop(0)
        }
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
