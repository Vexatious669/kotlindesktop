package me.enivzoy.anilife.app.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.enivzoy.anilife.core.model.AnimeCard
import me.enivzoy.anilife.core.model.Update
import me.enivzoy.anilife.core.net.AniListClient
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataLayerEdgeCaseTest {
    companion object {
        private val CORE_WASM_HEADER = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)
        private val COMPONENT_WASM_HEADER = byteArrayOf(0, 0x61, 0x73, 0x6d, 0x0d, 0, 1, 0)
    }

    @Test
    fun animeCardTitlePreferenceSelectsConfiguredLanguageAndFallsBackCleanly() {
        val card = AnimeCard(
            id = 1,
            title = "Default",
            titleRomaji = "Romaji Title",
            titleEnglish = "English Title",
            titleNative = "ネイティブ",
        )

        assertEquals("English Title", card.withTitlePreference("english").title)
        assertEquals("Romaji Title", card.withTitlePreference("romaji").title)
        assertEquals("ネイティブ", card.withTitlePreference("native").title)

        val sparse = AnimeCard(id = 2, title = "Fallback Only")
        assertEquals("Fallback Only", sparse.withTitlePreference("native").title)
        assertEquals("Fallback Only", sparse.withTitlePreference("romaji").title)
    }

    @Test
    fun homeSectionOrderAndHiddenParsersDeduplicateAndRestoreMissingSections() {
        val parsed = HomeSection.parseOrder(" movies , TRENDING_NOW , movies , invalid_section ")
        assertEquals(HomeSection.MOVIES, parsed[0])
        assertEquals(HomeSection.TRENDING_NOW, parsed[1])
        assertEquals(HomeSection.entries.size, parsed.size)

        val hidden = HomeSection.parseHidden("CONTINUE_WATCHING, unknown, my_library")
        assertEquals(setOf(HomeSection.CONTINUE_WATCHING, HomeSection.MY_LIBRARY), hidden)
    }

    @Test
    fun wasmProviderFileStoreValidatesHeadersDeduplicatesHashesAndRoundTripsJson() = runBlocking {
        val root = Files.createTempDirectory("AniLife-wasm-store")
        try {
            val storeDir = root.resolve("providers")
            val store = WasmProviderFileStore(storeDir)

            val validA = Files.write(root.resolve("alpha.wasm"), COMPONENT_WASM_HEADER + byteArrayOf(1, 2, 3))
            val validDuplicate = Files.write(root.resolve("alpha-copy.wasm"), COMPONENT_WASM_HEADER + byteArrayOf(1, 2, 3))
            val validSameNameDifferentBytes = Files.write(
                Files.createDirectory(root.resolve("sub")).resolve("alpha.wasm"),
                CORE_WASM_HEADER + byteArrayOf(9, 9, 9),
            )

            val first = store.import(validA)
            val duplicate = store.import(validDuplicate)
            val second = store.import(validSameNameDifferentBytes)

            assertEquals(first.sha256, duplicate.sha256)
            assertEquals("alpha.wasm", first.name)
            assertEquals("alpha (2).wasm", second.name)
            assertEquals(2, store.list().size)

            val exportedJson = store.exportToJson(first)
            store.remove(first)
            assertEquals(1, store.list().size)

            val jsonFile = Files.writeString(root.resolve("exported.json"), exportedJson)
            val reimported = store.import(jsonFile)
            assertEquals(first.sha256, reimported.sha256)
            assertEquals(2, store.list().size)

            val tooShort = Files.write(root.resolve("short.wasm"), byteArrayOf(0, 0x61, 0x73))
            assertFailsWith<IllegalArgumentException> { store.import(tooShort) }

            val badHeader = Files.write(root.resolve("bad.wasm"), ByteArray(12) { 0x42 })
            assertFailsWith<IllegalArgumentException> { store.import(badHeader) }

            val wrongExt = Files.write(root.resolve("plugin.txt"), COMPONENT_WASM_HEADER)
            assertFailsWith<IllegalArgumentException> { store.import(wrongExt) }

            val outsideModule = ImportedWasmProvider("outside.wasm", validA, 11, first.sha256)
            assertFailsWith<IllegalArgumentException> { store.remove(outsideModule) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun updaterVersionComparisonAndAssetDownloadVerificationCoverEdgeCases() = runBlocking {
        assertTrue(Updater.isNewer("1.0.10", "1.0.9"))
        assertTrue(Updater.isNewer("2.0", "1.9.9"))
        assertFalse(Updater.isNewer("1.2.0", "1.2"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.0"))
        assertEquals(listOf(0), Updater.parseVersion("invalid"))

        val payload = "AniLife installer binary payload".toByteArray(Charsets.UTF_8)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/installer.exe") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.createContext("/missing.exe") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        val root = Files.createTempDirectory("AniLife-updater-test")
        try {
            val updater = Updater(HttpClient.newHttpClient())
            val validUrl = "http://127.0.0.1:${server.address.port}/installer.exe"
            val dest = root.resolve("anilife.exe")

            assertFailsWith<UpdateException> {
                updater.download(Update("1.1.0", "", "ftp://example.com/a.exe", 0, ""), dest)
            }
            assertFailsWith<UpdateHttpException> {
                updater.download(
                    Update("1.1.0", "", "http://127.0.0.1:${server.address.port}/missing.exe", 0, ""),
                    dest,
                )
            }
            assertFailsWith<SizeMismatchException> {
                updater.download(Update("1.1.0", "", validUrl, payload.size + 5L, sha256), dest)
            }
            assertFailsWith<ChecksumException> {
                updater.download(Update("1.1.0", "", validUrl, payload.size.toLong(), "0000"), dest)
            }

            assertTrue(updater.download(Update("1.1.0", "", validUrl, payload.size.toLong(), sha256), dest))
            assertEquals(payload.toList(), Files.readAllBytes(dest).toList())
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun directMediaDownloaderRejectsInvalidArgumentsAndEmptyResponses() = runBlocking {
        val root = Files.createTempDirectory("AniLife-downloader-edge")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/empty.mp4") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"

            assertFailsWith<IllegalArgumentException> {
                DirectMediaDownloader.download("$baseUrl/empty.mp4", "   ", 1.0, "sub", root)
            }
            assertFailsWith<IllegalArgumentException> {
                DirectMediaDownloader.download("$baseUrl/empty.mp4", "Show", 0.0, "sub", root)
            }
            assertFailsWith<IllegalArgumentException> {
                DirectMediaDownloader.download("$baseUrl/empty.mp4", "Show", Double.NaN, "sub", root)
            }
            assertFailsWith<IllegalArgumentException> {
                DirectMediaDownloader.download("ftp://example.com/video.mp4", "Show", 1.0, "sub", root)
            }
            assertFailsWith<IllegalArgumentException> {
                DirectMediaDownloader.download("https://example.com/playlist.m3u8", "Show", 1.0, "sub", root)
            }
            assertFailsWith<IllegalArgumentException> {
                DirectMediaDownloader.download("$baseUrl/empty.mp4", "Show", 1.0, "sub", root)
            }
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun releaseNotifierDeduplicatesAiringAlertsAndFormatsDownloadNotices() = runBlocking {
        val root = Files.createTempDirectory("AniLife-notifier")
        val nowSeconds = System.currentTimeMillis() / 1000
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = """
                {
                  "data": {
                    "Page": {
                      "pageInfo": {"hasNextPage": false},
                      "airingSchedules": [
                        {"airingAt": ${nowSeconds - 600}, "episode": 4, "media": {"id": 10, "title": {"english": "Followed Show"}}},
                        {"airingAt": ${nowSeconds + 3600}, "episode": 5, "media": {"id": 10, "title": {"english": "Followed Show"}}},
                        {"airingAt": ${nowSeconds - 600}, "episode": 1, "media": {"id": 99, "title": {"english": "Unfollowed Show"}}}
                      ]
                    }
                  }
                }
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val library = LocalLibraryStore(root.resolve("library.properties").toFile())
            val notices = NoticeStore(root.resolve("notices.properties").toFile())
            val repo = AniListRepository(
                client = AniListClient(HttpClient.newHttpClient(), "http://127.0.0.1:${server.address.port}/"),
                library = library,
            )
            val notifier = ReleaseNotifier(repo, notices)

            assertEquals(0, notifier.check())
            assertTrue(notifier.lastCheck.value > 0L)

            val show = AnimeCard(id = 10, title = "Followed Show")
            library.add(show)

            assertEquals(1, notifier.check())
            assertEquals(0, notifier.check())
            assertEquals("Episode 4 is out", notices.notices.value.single().body)

            notifier.announceDownload(show, 1)
            notifier.announceDownload(show, 3)
            assertEquals("Downloaded 3 episodes", notices.notices.value[0].body)
            assertEquals("Downloaded 1 episode", notices.notices.value[1].body)
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun downloadQueueManagerHandlesFailureRetryAndClearingFinishedJobs() = runBlocking {
        val root = Files.createTempDirectory("AniLife-queue")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val library = LocalLibraryStore(root.resolve("library.properties").toFile())
            val repo = AniListRepository(library = library)
            val queue = DownloadQueueManager(repo, scope, concurrency = 2)

            val id = queue.enqueue(
                title = "Test Anime",
                episode = 1.0,
                url = "invalid://bad-url",
                audio = "sub",
                downloadRoot = root,
            )

            val deadline = System.currentTimeMillis() + 5_000L
            while (queue.jobs.value.first().status != DownloadStatus.Failed && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(20)
            }

            assertEquals(DownloadStatus.Failed, queue.jobs.value.single().status)
            queue.clearFinished()
            assertTrue(queue.jobs.value.isEmpty())
            queue.remove(id)
        } finally {
            scope.cancel()
            root.toFile().deleteRecursively()
        }
    }
}
