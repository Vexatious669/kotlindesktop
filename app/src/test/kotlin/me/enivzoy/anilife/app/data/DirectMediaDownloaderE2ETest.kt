package me.enivzoy.anilife.app.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectMediaDownloaderE2ETest {
    private lateinit var server: HttpServer
    private lateinit var root: Path
    private lateinit var downloadRoot: Path
    private var base: String = ""
    private val referer = "https://provider.example/watch"

    @BeforeTest
    fun start() {
        val ffmpeg = locateFfmpeg() ?: return
        assumeTrue(ffmpeg != null, "ffmpeg is required for the end-to-end download test")
        System.setProperty("AniLife.ffmpeg.dir", ffmpeg!!.parentFile.absolutePath)

        root = Files.createTempDirectory("hls-fixture")
        downloadRoot = Files.createTempDirectory("hls-downloads")
        val generated = ProcessBuilder(
            ffmpeg.absolutePath, "-nostdin", "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", "3", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-shortest",
            "-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod",
            "-hls_segment_filename", root.resolve("seg%d.ts").toString(),
            root.resolve("index.m3u8").toString(),
        ).redirectErrorStream(true).start()
        val log = generated.inputStream.bufferedReader().readText()
        assertEquals(0, generated.waitFor(), "fixture generation failed: $log")
        assertTrue(Files.exists(root.resolve("index.m3u8")))

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { exchange -> serve(exchange) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stop() {
        if (::server.isInitialized) server.stop(0)
        if (::root.isInitialized) root.toFile().deleteRecursively()
        if (::downloadRoot.isInitialized) downloadRoot.toFile().deleteRecursively()
        System.clearProperty("AniLife.ffmpeg.dir")
    }

    private fun serve(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        when {
            query.contains("expired=1") -> respond(exchange, 410, "text/html", "<html>expired</html>".toByteArray())
            query.contains("html=1") -> respond(exchange, 200, "text/html", "<!doctype html><html>nope</html>".toByteArray())
            exchange.requestHeaders.getFirst("Referer") != referer ->
                respond(exchange, 403, "text/html", "<html>missing referer</html>".toByteArray())
            else -> {
                val file = root.resolve(path.trimStart('/'))
                if (!file.normalize().startsWith(root) || !Files.isRegularFile(file)) {
                    respond(exchange, 404, "text/plain", "not found".toByteArray())
                } else {
                    respond(exchange, 200, contentType(file), Files.readAllBytes(file))
                }
            }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, type: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun contentType(file: Path): String = when (file.fileName.toString().substringAfterLast('.')) {
        "m3u8" -> "application/vnd.apple.mpegurl"
        "ts" -> "video/mp2t"
        else -> "application/octet-stream"
    }

    @Test
    fun downloadsARealHlsEpisodeThroughFfmpeg() = runBlocking {
        val url = "$base/index.m3u8?token=signedsecret"
        val result = DirectMediaDownloader.download(
            url = url,
            animeTitle = "Hls Fixture",
            episode = 1.0,
            audio = "sub",
            downloadRoot = downloadRoot,
            headers = mapOf("Referer" to referer),
            maxHeight = 720,
        )
        assertTrue(Files.exists(result.path), "no output file at ${result.path}")
        assertTrue(result.bytes > 0L)
        assertEquals("mkv", result.path.fileName.toString().substringAfterLast('.'))
        assertEquals(0, probe(result.path), "ffprobe rejected the downloaded file")

        val leftovers = Files.walk(downloadRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().contains(".part") }.count()
        }
        assertEquals(0L, leftovers, "partial files were left behind")
    }

    @Test
    fun refusesAnExpiredSourceWithTheRealStatusAndLeavesNoOutput() = runBlocking {
        val failure = assertFailsWith<SourceException> {
            DirectMediaDownloader.download(
                url = "$base/index.m3u8?token=x&expired=1",
                animeTitle = "Hls Fixture",
                episode = 2.0,
                audio = "sub",
                downloadRoot = downloadRoot,
                headers = mapOf("Referer" to referer),
            )
        }
        assertEquals(SourceFailure.Expired, failure.reason)
        assertTrue(failure.reason.retryWithFreshSource)
        assertNoEpisodeFiles()
    }

    @Test
    fun refusesAnHtmlPageInsteadOfAPlaylist() = runBlocking {
        val failure = assertFailsWith<SourceException> {
            DirectMediaDownloader.download(
                url = "$base/index.m3u8?token=x&html=1",
                animeTitle = "Hls Fixture",
                episode = 3.0,
                audio = "sub",
                downloadRoot = downloadRoot,
                headers = mapOf("Referer" to referer),
            )
        }
        assertEquals(SourceFailure.NotPlaylist, failure.reason)
        assertNoEpisodeFiles()
    }

    @Test
    fun requiresTheProviderRequestContext() = runBlocking {
        val failure = assertFailsWith<SourceException> {
            DirectMediaDownloader.download(
                url = "$base/index.m3u8?token=x",
                animeTitle = "Hls Fixture",
                episode = 4.0,
                audio = "sub",
                downloadRoot = downloadRoot,
            )
        }
        assertEquals(SourceFailure.Forbidden, failure.reason)
        assertNoEpisodeFiles()
    }

    private fun assertNoEpisodeFiles() {
        val files = Files.walk(downloadRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().contains("Hls Fixture") }.count()
        }
        assertEquals(0L, files, "a failed download left episode files behind")
        assertFalse(Files.exists(downloadRoot.resolve("Hls Fixture/Resources/info.json")) && files > 0)
    }

    private fun probe(file: Path): Int {
        val ffprobe = File(locateFfmpeg()!!.parentFile, "ffprobe.exe")
        if (!ffprobe.isFile) return 0
        val process = ProcessBuilder(
            ffprobe.absolutePath, "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=codec_type", "-of", "csv=p=0", file.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        return if (exit == 0 && output.contains("video")) 0 else exit.coerceAtLeast(1)
    }

    private fun locateFfmpeg(): File? {
        System.getenv("ANILIFE_TEST_FFMPEG")?.let { candidate ->
            File(candidate).takeIf { it.isFile }?.let { return it }
        }
        val names = listOf("ffmpeg.exe", "ffmpeg")
        val directories = mutableListOf<String>()
        System.getProperty("AniLife.ffmpeg.dir")?.let(directories::add)
        System.getenv("PATH")?.split(File.pathSeparator)?.let(directories::addAll)
        directories.forEach { directory ->
            names.forEach { name ->
                File(directory, name).takeIf { it.isFile && it.canExecute() }?.let { return it }
            }
        }
        return null
    }
}