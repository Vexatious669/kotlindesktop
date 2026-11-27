package me.enivzoy.anilife.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.enivzoy.anilife.core.model.AnimeCard
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadQueueRetryTest {
    private val episode = LocalEpisode(
        anime = AnimeCard(id = 1, title = "Test"),
        episode = 1.0,
        audio = "sub",
        path = Path.of("C:/anime/Test - E1.mkv"),
        bytes = 42,
    )

    private fun source(url: String, headers: Map<String, String> = mapOf("Referer" to "https://p/")) =
        AnimeVideoSource(
            provider = "miruro",
            server = "cdn",
            audio = "Sub",
            format = "hls",
            quality = "1080p",
            url = url,
            headers = headers,
        )

    @Test
    fun resolvesAFreshSourceBeforeTheFirstAttempt() = runBlocking {
        val requested = mutableListOf<String?>()
        val used = mutableListOf<String>()
        var resolveCalls = 0
        val queue = DownloadQueueManager(
            repository = null,
            scope = this,
            concurrency = 1,
            runner = { _, url, _, _ ->
                used += url
                episode
            },
        )
        val id = queue.enqueue(
            title = "Test",
            episode = 1.0,
            url = "https://stale.example/master.m3u8?token=old",
            audio = "sub",
            downloadRoot = Path.of("C:/anime"),
            resolve = { avoid ->
                requested += avoid
                resolveCalls++
                source("https://fresh.example/master.m3u8?token=$resolveCalls")
            },
        )
        val job = awaitTerminal(queue, id)
        assertEquals(DownloadStatus.Done, job.status)
        assertEquals(listOf<String?>(null), requested)
        assertEquals(listOf("https://fresh.example/master.m3u8?token=1"), used)
    }

    @Test
    fun reResolvesAndAvoidsTheFailedUrlAfterAnExpiredSource() = runBlocking {
        val avoid = mutableListOf<String?>()
        val used = mutableListOf<String>()
        var resolveCalls = 0
        val queue = DownloadQueueManager(
            repository = null,
            scope = this,
            concurrency = 1,
            runner = { _, url, _, _ ->
                used += url
                if (url.contains("expired")) throw SourceException(SourceFailure.Expired, 410)
                episode
            },
        )
        val id = queue.enqueue(
            title = "Test",
            episode = 1.0,
            url = "https://queue.example/master.m3u8?token=queued",
            audio = "sub",
            downloadRoot = Path.of("C:/anime"),
            resolve = { failed ->
                avoid += failed
                resolveCalls++
                if (resolveCalls == 1) source("https://cdn.a/master.m3u8?token=expired")
                else source("https://cdn.b/master.m3u8?token=fresh")
            },
        )
        val job = awaitTerminal(queue, id)
        assertEquals(DownloadStatus.Done, job.status)
        assertEquals(2, resolveCalls)
        assertEquals(listOf(null, "https://cdn.a/master.m3u8?token=expired"), avoid)
        assertEquals(
            listOf("https://cdn.a/master.m3u8?token=expired", "https://cdn.b/master.m3u8?token=fresh"),
            used,
        )
        assertEquals("https://cdn.b/master.m3u8?token=fresh", job.url)
        assertNull(job.error)
    }

    @Test
    fun failsWithTheProvidersReasonWhenNoFreshSourceIsAvailable() = runBlocking {
        val queue = DownloadQueueManager(
            repository = null,
            scope = this,
            concurrency = 1,
            runner = { _, _, _, _ -> throw SourceException(SourceFailure.Forbidden, 403) },
        )
        val id = queue.enqueue(
            title = "Test",
            episode = 1.0,
            url = "https://cdn.example/master.m3u8?token=x",
            audio = "sub",
            downloadRoot = Path.of("C:/anime"),
            resolve = { null },
        )
        val job = awaitTerminal(queue, id)
        assertEquals(DownloadStatus.Failed, job.status)
        assertNotNull(job.error)
        assertTrue(job.error!!.contains("403"), "expected the http status, got ${job.error}")
    }

    @Test
    fun doesNotRetryNonSourceFailures() = runBlocking {
        var attempts = 0
        var resolveCalls = 0
        val queue = DownloadQueueManager(
            repository = null,
            scope = this,
            concurrency = 1,
            runner = { _, _, _, _ ->
                attempts++
                throw IllegalStateException("disk full")
            },
        )
        val id = queue.enqueue(
            title = "Test",
            episode = 1.0,
            url = "https://cdn.example/master.m3u8?token=x",
            audio = "sub",
            downloadRoot = Path.of("C:/anime"),
            resolve = {
                resolveCalls++
                source("https://cdn.example/master.m3u8?token=y")
            },
        )
        val job = awaitTerminal(queue, id)
        assertEquals(DownloadStatus.Failed, job.status)
        assertEquals(1, attempts)
        assertEquals(1, resolveCalls)
        assertEquals("disk full", job.error)
    }

    @Test
    fun keepsTheQueuedHeadersWhenNoResolverIsSupplied() = runBlocking {
        val seen = mutableListOf<Map<String, String>>()
        val queue = DownloadQueueManager(
            repository = null,
            scope = this,
            concurrency = 1,
            runner = { _, _, headers, _ ->
                seen += headers
                episode
            },
        )
        val headers = mapOf("Referer" to "https://provider.example/", "Cookie" to "session=1")
        val id = queue.enqueue(
            title = "Test",
            episode = 1.0,
            url = "https://cdn.example/video.mp4",
            audio = "sub",
            downloadRoot = Path.of("C:/anime"),
            headers = headers,
        )
        val job = awaitTerminal(queue, id)
        assertEquals(DownloadStatus.Done, job.status)
        assertEquals(listOf(headers), seen)
    }

    private suspend fun awaitTerminal(queue: DownloadQueueManager, id: String): DownloadJob =
        withTimeout(10_000) {
            while (true) {
                val job = queue.jobs.value.firstOrNull { it.id == id } ?: error("job vanished")
                if (job.status == DownloadStatus.Done ||
                    job.status == DownloadStatus.Failed ||
                    job.status == DownloadStatus.Cancelled
                ) {
                    return@withTimeout job
                }
                kotlinx.coroutines.delay(20)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
}
