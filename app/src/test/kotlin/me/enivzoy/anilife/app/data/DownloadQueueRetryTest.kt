package me.enivzoy.anilife.app.data

import kotlinx.coroutines.CompletableDeferred
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
        val requested = mutableListOf<DownloadSourceExclusions>()
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
            resolve = { exclusions ->
                requested += exclusions
                resolveCalls++
                source("https://fresh.example/master.m3u8?token=$resolveCalls")
            },
        )
        val job = awaitTerminal(queue, id)
        assertEquals(DownloadStatus.Done, job.status)
        assertEquals(listOf(DownloadSourceExclusions()), requested)
        assertEquals(listOf("https://fresh.example/master.m3u8?token=1"), used)
    }

    @Test
    fun startsSourceResolutionAsSoonAsTheJobIsAddedToTheQueue() = runBlocking {
        val resolving = CompletableDeferred<Unit>()
        val finishResolution = CompletableDeferred<AnimeVideoSource?>()
        val queue = DownloadQueueManager(
            repository = null,
            scope = this,
            concurrency = 1,
            runner = { _, _, _, _ -> episode },
        )

        val id = queue.enqueue(
            title = "Test",
            episode = 1.0,
            url = "",
            audio = "sub",
            downloadRoot = Path.of("C:/anime"),
            resolve = {
                resolving.complete(Unit)
                finishResolution.await()
            },
        )
        resolving.await()

        assertEquals(DownloadStatus.Running, queue.jobs.value.single { it.id == id }.status)
        finishResolution.complete(source("https://cdn.example/video.mp4"))
        assertEquals(DownloadStatus.Done, awaitTerminal(queue, id).status)
    }

    @Test
    fun recordsSourceResolutionFailuresInsteadOfSilentlyDroppingThem() = runBlocking {
        val queue = DownloadQueueManager(
            repository = null,
            scope = this,
            concurrency = 1,
            runner = { _, _, _, _ -> episode },
        )

        val id = queue.enqueue(
            title = "Test",
            episode = 1.0,
            url = "",
            audio = "sub",
            downloadRoot = Path.of("C:/anime"),
            resolve = { throw IllegalStateException("provider unavailable") },
        )

        val job = awaitTerminal(queue, id)
        assertEquals(DownloadStatus.Failed, job.status)
        assertEquals("provider unavailable", job.error)
    }

    @Test
    fun progressFractionUsesFractionalByteDivisionAndHandlesUnknownTotals() {
        assertEquals(0.5f, downloadProgressFraction(50, 100))
        assertEquals(0f, downloadProgressFraction(50, null))
        assertEquals(1f, downloadProgressFraction(200, 100))
    }

    @Test
    fun reResolvesAndAvoidsTheFailedUrlAfterAnExpiredSource() = runBlocking {
        val avoided = mutableListOf<DownloadSourceExclusions>()
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
            resolve = { exclusions ->
                avoided += exclusions
                resolveCalls++
                if (resolveCalls == 1) source("https://cdn.a/master.m3u8?token=expired").copy(server = "A")
                else source("https://cdn.b/master.m3u8?token=fresh").copy(server = "B")
            },
        )
        val job = awaitTerminal(queue, id)
        assertEquals(DownloadStatus.Done, job.status)
        assertEquals(2, resolveCalls)
        assertEquals(
            listOf(
                DownloadSourceExclusions(),
                DownloadSourceExclusions(
                    urls = setOf("https://cdn.a/master.m3u8?token=expired"),
                    servers = setOf(downloadServerIdentity(source("https://cdn.a/master.m3u8?token=expired").copy(server = "A"))),
                ),
            ),
            avoided,
        )
        assertEquals(
            listOf("https://cdn.a/master.m3u8?token=expired", "https://cdn.b/master.m3u8?token=fresh"),
            used,
        )
        assertEquals("https://cdn.b/master.m3u8?token=fresh", job.url)
        assertNull(job.error)
    }

    @Test
    fun refreshesA403SourceAndUsesAReplacementUrl() = runBlocking {
        val attempted = mutableListOf<String>()
        val resolveAvoided = mutableListOf<DownloadSourceExclusions>()
        val queue = DownloadQueueManager(
            repository = null,
            scope = this,
            concurrency = 1,
            runner = { _, url, _, _ ->
                attempted += url
                if (url.endsWith("stale.m3u8")) throw SourceException(SourceFailure.Forbidden, 403)
                episode
            },
        )

        val id = queue.enqueue(
            title = "Test",
            episode = 1.0,
            url = "",
            audio = "sub",
            downloadRoot = Path.of("C:/anime"),
            resolve = { exclusions ->
                resolveAvoided += exclusions
                val available = listOf(
                    source("https://cdn.example/stale.m3u8").copy(server = "stale"),
                    source("https://cdn.example/fresh.m3u8").copy(server = "fresh"),
                )
                pickDownloadSource(available, DownloadTarget(1080, "Auto"), exclusions)
            },
        )

        assertEquals(DownloadStatus.Done, awaitTerminal(queue, id).status)
        assertEquals(listOf("https://cdn.example/stale.m3u8", "https://cdn.example/fresh.m3u8"), attempted)
        assertEquals(
            listOf(
                DownloadSourceExclusions(),
                DownloadSourceExclusions(
                    urls = setOf("https://cdn.example/stale.m3u8"),
                    servers = setOf(downloadServerIdentity(source("https://cdn.example/stale.m3u8").copy(server = "stale"))),
                ),
            ),
            resolveAvoided,
        )
    }

    @Test
    fun triesSeveralDistinctSourcesWithoutCyclingBackToFailedUrls() = runBlocking {
        val attempted = mutableListOf<String>()
        val avoidSets = mutableListOf<DownloadSourceExclusions>()
        val available = listOf("a", "b", "c", "good").map {
            source("https://cdn.example/$it.m3u8").copy(server = it)
        }
        val queue = DownloadQueueManager(
            repository = null,
            scope = this,
            concurrency = 1,
            runner = { _, url, _, _ ->
                attempted += url
                if (!url.endsWith("good.m3u8")) throw SourceException(SourceFailure.Forbidden, 403)
                episode
            },
        )
        val id = queue.enqueue(
            title = "Test",
            episode = 1.0,
            url = "",
            audio = "sub",
            downloadRoot = Path.of("C:/anime"),
            resolve = { exclusions ->
                avoidSets += exclusions
                pickDownloadSource(available, DownloadTarget(1080, "Auto"), exclusions)
            },
        )

        assertEquals(DownloadStatus.Done, awaitTerminal(queue, id).status)
        assertEquals(available.map { it.url }, attempted)
        assertEquals(
            listOf(
                DownloadSourceExclusions(),
                DownloadSourceExclusions(setOf(available[0].url), setOf(downloadServerIdentity(available[0]))),
                DownloadSourceExclusions(available.take(2).map { it.url }.toSet(), available.take(2).map(::downloadServerIdentity).toSet()),
                DownloadSourceExclusions(available.take(3).map { it.url }.toSet(), available.take(3).map(::downloadServerIdentity).toSet()),
            ),
            avoidSets,
        )
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
