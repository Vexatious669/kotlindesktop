package me.enivzoy.anilife.app.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsSourceTest {
    private lateinit var server: HttpServer
    private var base: String = ""
    private val hits = mutableListOf<String>()
    private val requiredHeader = "Referer"

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        server.createContext("/") { exchange -> route(exchange) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        synchronized(hits) { hits += path }
        val body: ByteArray
        val status: Int
        val contentType: String
        when {
            query.contains("expired=1") -> {
                status = 410
                contentType = "text/html"
                body = "<html>link expired</html>".toByteArray()
            }

            query.contains("forbidden=1") -> {
                status = 403
                contentType = "text/html"
                body = "<html>denied</html>".toByteArray()
            }

            path.endsWith("/html.m3u8") -> {
                status = 200
                contentType = "text/html"
                body = "<!doctype html><html>captcha</html>".toByteArray()
            }

            path.endsWith("/empty.m3u8") -> {
                status = 200
                contentType = "application/vnd.apple.mpegurl"
                body = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-ENDLIST\n".toByteArray()
            }

            path.endsWith("/redirect.m3u8") -> {
                exchange.responseHeaders.add("Location", "$base/media.m3u8")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return
            }

            path.endsWith("/master.m3u8") -> {
                status = 200
                contentType = "application/vnd.apple.mpegurl"
                body = """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
                    low.m3u8
                    #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
                    high.m3u8
                """.trimIndent().toByteArray()
            }

            path.endsWith("/media.m3u8") || path.endsWith("/low.m3u8") || path.endsWith("/high.m3u8") -> {
                if (path.endsWith("/media.m3u8") && !authorised(exchange)) {
                    status = 403
                    contentType = "text/html"
                    body = "<html>no referer</html>".toByteArray()
                } else {
                    status = 200
                    contentType = "application/vnd.apple.mpegurl"
                    body = """
                        #EXTM3U
                        #EXT-X-TARGETDURATION:4
                        #EXT-X-MEDIA-SEQUENCE:7
                        #EXTINF:4.0,
                        seg-7.ts
                        #EXTINF:4.0,
                        seg-8.ts
                        #EXT-X-ENDLIST
                    """.trimIndent().toByteArray()
                }
            }

            path.endsWith("/encrypted.m3u8") -> {
                status = 200
                contentType = "application/vnd.apple.mpegurl"
                body = """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:4
                    #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                    #EXTINF:4.0,
                    enc-0.ts
                    #EXT-X-ENDLIST
                """.trimIndent().toByteArray()
            }

            path.endsWith("/media-seg-7.ts") || path.endsWith("/seg-7.ts") -> {
                status = 200
                contentType = "video/mp2t"
                body = "SEGMENT-7".toByteArray()
            }

            path.endsWith("/media-seg-8.ts") || path.endsWith("/seg-8.ts") -> {
                status = 200
                contentType = "video/mp2t"
                body = "SEGMENT-8".toByteArray()
            }

            path.endsWith("/key.bin") -> {
                status = 200
                contentType = "application/octet-stream"
                body = KEY
            }

            path.endsWith("/enc-0.ts") -> {
                status = 200
                contentType = "video/mp2t"
                body = encryptedSegment()
            }

            path.endsWith("/missing-seg.ts") -> {
                status = 404
                contentType = "text/plain"
                body = "gone".toByteArray()
            }

            path.endsWith("/broken.m3u8") -> {
                status = 200
                contentType = "application/vnd.apple.mpegurl"
                body = """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:4
                    #EXTINF:4.0,
                    missing-seg.ts
                    #EXT-X-ENDLIST
                """.trimIndent().toByteArray()
            }

            path.endsWith("/video.mp4") -> {
                status = 200
                contentType = "video/mp4"
                body = ByteArray(4096) { 7 }
            }

            path.endsWith("/opaque") -> {
                status = 200
                contentType = "application/octet-stream"
                body = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nmedia-seg-7.ts\n#EXT-X-ENDLIST\n".toByteArray()
            }

            else -> {
                status = 404
                contentType = "text/plain"
                body = "not found".toByteArray()
            }
        }
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun authorised(exchange: HttpExchange): Boolean =
        exchange.requestHeaders.getFirst(requiredHeader)?.isNotBlank() == true

    private fun encryptedSegment(): ByteArray {
        // re-encrypt known plaintext so the downloader must decrypt it back
        val encrypt = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        encrypt.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(KEY, "AES"),
            javax.crypto.spec.IvParameterSpec(ivFor(0)),
        )
        return encrypt.doFinal(PLAINTEXT)
    }

    private val headers = mapOf("Referer" to "https://provider.example/")

    @Test
    fun resolvesAMediaPlaylistAndDownloadsSegmentsInOrder() = runBlocking {
        val source = HlsSource.validate("$base/media.m3u8", headers)
        assertEquals(SourceKind.HlsMedia, source.kind)
        assertEquals(2, source.playlist?.segments?.size)
        assertFalse(source.playlist!!.isFragmented)

        val output = Files.createTempFile("hls-media", ".ts")
        Files.delete(output)
        val written = HlsSource.downloadMediaPlaylist(source.playlist!!, headers, output) {}
        assertEquals("SEGMENT-7SEGMENT-8".length.toLong(), written)
        assertEquals("SEGMENT-7SEGMENT-8", Files.readString(output))
        Files.deleteIfExists(output)
    }

    @Test
    fun picksTheVariantThatFitsTheRequestedHeight() = runBlocking {
        val capped = HlsSource.validate("$base/master.m3u8", headers, maxHeight = 480)
        assertEquals(SourceKind.HlsMedia, capped.kind)
        assertTrue(capped.url.endsWith("low.m3u8"), "expected the 360p variant, got ${capped.url}")

        val best = HlsSource.validate("$base/master.m3u8", headers)
        assertTrue(best.url.endsWith("high.m3u8"), "expected the 720p variant, got ${best.url}")
    }

    @Test
    fun followsAPlaylistRedirect() = runBlocking {
        val source = HlsSource.validate("$base/redirect.m3u8", headers)
        assertEquals(SourceKind.HlsMedia, source.kind)
        assertTrue(source.url.endsWith("media.m3u8"))
        assertEquals(2, source.playlist?.segments?.size)
    }

    @Test
    fun reportsAnExpiredLinkInsteadOfInvalidMedia() = runBlocking {
        val failure = assertFailsWith<SourceException> {
            HlsSource.validate("$base/media.m3u8?expired=1", headers)
        }
        assertEquals(SourceFailure.Expired, failure.reason)
        assertTrue(failure.reason.retryWithFreshSource)
    }

    @Test
    fun reportsAForbiddenSourceAndKeepsTheStatus() = runBlocking {
        val failure = assertFailsWith<SourceException> {
            HlsSource.validate("$base/media.m3u8?forbidden=1", headers)
        }
        assertEquals(SourceFailure.Forbidden, failure.reason)
        assertEquals(403, failure.status)
    }

    @Test
    fun rejectsAnHtmlPageServedAsAPlaylist() = runBlocking {
        val failure = assertFailsWith<SourceException> {
            HlsSource.validate("$base/html.m3u8", headers)
        }
        assertEquals(SourceFailure.NotPlaylist, failure.reason)
        assertContains(failure.message.orEmpty(), "html")
    }

    @Test
    fun rejectsAPlaylistWithoutSegments() = runBlocking {
        val failure = assertFailsWith<SourceException> {
            HlsSource.validate("$base/empty.m3u8", headers)
        }
        assertEquals(SourceFailure.EmptyPlaylist, failure.reason)
        assertTrue(failure.reason.retryWithFreshSource)
    }

    @Test
    fun sendsProviderHeadersToPlaylistsAndSegments() = runBlocking {
        val failure = assertFailsWith<SourceException> {
            HlsSource.validate("$base/media.m3u8", emptyMap())
        }
        assertEquals(SourceFailure.Forbidden, failure.reason)

        val source = HlsSource.validate("$base/media.m3u8", headers)
        val output = Files.createTempFile("hls-headers", ".ts")
        Files.delete(output)
        HlsSource.downloadMediaPlaylist(source.playlist!!, headers, output) {}
        assertContains(Files.readString(output), "SEGMENT-8")
        Files.deleteIfExists(output)
    }

    @Test
    fun decryptsAes128SegmentsWithTheSequenceIv() = runBlocking {
        val source = HlsSource.validate("$base/encrypted.m3u8", headers)
        val playlist = assertNotNull(source.playlist)
        val output = Files.createTempFile("hls-aes", ".ts")
        Files.delete(output)
        HlsSource.downloadMediaPlaylist(playlist, headers, output) {}
        assertEquals(PLAINTEXT.toString(Charsets.UTF_8), Files.readString(output))
        Files.deleteIfExists(output)
    }

    @Test
    fun deletesThePartialFileWhenASegmentFails() = runBlocking {
        val source = HlsSource.validate("$base/broken.m3u8", headers)
        val playlist = assertNotNull(source.playlist)
        val directory = Files.createTempDirectory("hls-fail")
        val output = directory.resolve("episode.ts")

        val failure = assertFailsWith<SourceException> {
            HlsSource.downloadMediaPlaylist(playlist, headers, output) {}
        }
        assertEquals(SourceFailure.NotFound, failure.reason)
        assertFalse(Files.exists(output))
        assertFalse(Files.exists(directory.resolve("episode.ts.part")))
    }

    @Test
    fun treatsAnExtensionlessManifestAsHls() = runBlocking {
        val source = HlsSource.validate("$base/opaque?token=abc", headers)
        assertEquals(SourceKind.HlsMedia, source.kind)
        assertEquals(1, source.playlist?.segments?.size)
    }

    @Test
    fun keepsAPlainVideoFileAsDirectMedia() = runBlocking {
        val source = HlsSource.validate("$base/video.mp4", headers)
        assertEquals(SourceKind.DirectMedia, source.kind)
        assertNull(source.playlist)
    }

    @Test
    fun redactsSignedQueryStringsFromLogs() {
        val redacted = redactUrl("https://cdn.example/hls/abc123.m3u8?token=supersecret&exp=99")
        assertFalse(redacted.contains("token"))
        assertFalse(redacted.contains("supersecret"))
        assertContains(redacted, "cdn.example")
    }

    private fun ivFor(sequence: Int): ByteArray {
        val iv = ByteArray(16)
        for (index in 0 until 8) iv[15 - index] = ((sequence.toLong() ushr (8 * index)) and 0xFF).toByte()
        return iv
    }

    private companion object {
        val KEY = ByteArray(16) { (it + 1).toByte() }
        val PLAINTEXT = "decrypted-segment-payload".toByteArray()
    }
}