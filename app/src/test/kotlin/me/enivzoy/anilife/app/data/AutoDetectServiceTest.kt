package me.enivzoy.anilife.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.enivzoy.anilife.core.model.AnimeCard
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoDetectServiceTest {
    private class FakeLibrary(private val results: List<AnimeCard>) : AutoDetectLibrary {
        val queries = mutableListOf<String>()
        val lookups = mutableListOf<List<Long>>()
        val registered = mutableListOf<LocalEpisode>()

        override suspend fun searchAniList(query: String): List<AnimeCard> {
            queries += query
            return results
        }

        override suspend fun lookupAniList(ids: List<Long>): List<AnimeCard> {
            lookups += ids
            return results.filter { it.id in ids }
        }

        override fun register(episodes: List<LocalEpisode>, anime: List<AnimeCard>) {
            registered += episodes
        }
    }

    private fun card(id: Long, title: String, year: Int? = null, aliases: List<String> = emptyList()) =
        AnimeCard(id = id, title = title, year = year, aliases = aliases)

    private fun folder(
        root: Path,
        name: String,
        episodes: List<String> = listOf("$name - 01.mkv"),
        modifiedAt: Long = NOW - 120_000L,
    ): Path {
        val folder = Files.createDirectories(root.resolve(name))
        episodes.forEach { file ->
            val path = Files.write(folder.resolve(file), ByteArray(64) { 1 })
            Files.setLastModifiedTime(path, FileTime.fromMillis(modifiedAt))
        }
        return folder
    }

    private fun service(library: AutoDetectLibrary, store: AutoDetectStore, root: Path) = AutoDetectService(
        library = library,
        store = store,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        clock = { NOW },
        pollIntervalMillis = 50L,
        searchSpacingMillis = 0L,
    ).apply {
        configure(
            directories = listOf(root.toString()),
            settleSeconds = 10,
            audio = "sub",
            autoImport = true,
        )
    }

    private fun withTempRoot(block: (Path, AutoDetectStore) -> Unit) {
        val root = Files.createTempDirectory("AniLife-autodetect-service")
        try {
            val store = AutoDetectStore(root.resolve("autodetect.properties").toFile())
            block(root.resolve("downloads").also(Files::createDirectories), store)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun completeFolderIsImportedOnceAndNotResearched() = withTempRoot { root, store ->
        val library = FakeLibrary(listOf(card(1, "Frieren")))
        val service = service(library, store, root)
        folder(root, "Frieren", listOf("Frieren - 01.mkv", "Frieren - 02.mkv"))
        runBlocking {
            val first = service.scanOnce()
            assertEquals(1, first.ready.size)
            assertEquals(1, library.queries.size)
            assertEquals(2, library.registered.size)
            assertTrue(store.imported.containsKey(keyOf(root.resolve("Frieren"))))

            val second = service.scanOnce()
            assertTrue(second.folders.isEmpty())
            assertEquals(1, library.queries.size)
            assertEquals(2, library.registered.size)
        }
    }

    @Test
    fun newEpisodeAfterImportUsesTheKnownAnimeInsteadOfSearchingAgain() = withTempRoot { root, store ->
        val library = FakeLibrary(listOf(card(7, "Frieren")))
        val service = service(library, store, root)
        val folder = folder(root, "Frieren", listOf("Frieren - 01.mkv"))
        runBlocking {
            service.scanOnce()
            assertEquals(1, library.queries.size)

            Files.write(folder.resolve("Frieren - 02.mkv"), ByteArray(64) { 2 })
            Files.setLastModifiedTime(folder.resolve("Frieren - 02.mkv"), FileTime.fromMillis(NOW - 120_000L))
            service.scanOnce()

            assertEquals(1, library.queries.size)
            assertEquals(1, library.lookups.size)
            assertEquals(2, store.imported.getValue(keyOf(folder)).files)
            assertEquals(setOf(1.0, 2.0), library.registered.map { it.episode }.toSet())
        }
    }

    @Test
    fun folderWithTemporaryFilesIsNotImported() = withTempRoot { root, store ->
        val library = FakeLibrary(listOf(card(1, "Frieren")))
        val service = service(library, store, root)
        val folder = folder(root, "Frieren", listOf("Frieren - 01.mkv"))
        Files.write(folder.resolve("Frieren - 02.mkv.part"), ByteArray(8))
        runBlocking {
            val result = service.scanOnce()
            assertTrue(result.folders.single().settled.not())
            assertEquals(0, library.registered.size)
            assertTrue(store.imported.isEmpty())
        }
    }

    @Test
    fun recentlyWrittenFolderWaitsForTheSettleWindow() = withTempRoot { root, store ->
        val library = FakeLibrary(listOf(card(1, "Frieren")))
        val service = service(library, store, root)
        folder(root, "Frieren", modifiedAt = NOW - 1_000L)
        runBlocking {
            val result = service.scanOnce()
            assertEquals(1, result.pending.size)
            assertEquals(0, library.registered.size)
        }
    }

    @Test
    fun ambiguousMatchIsHeldForTheUserAndImportsOnDemand() = withTempRoot { root, store ->
        // two AniList entries share the title, so an unattended import would have to guess
        val library = FakeLibrary(listOf(card(1, "Frieren"), card(2, "Frieren")))
        val service = service(library, store, root)
        folder(root, "Frieren")
        runBlocking {
            val result = service.scanOnce()
            val candidate = result.folders.single()
            assertTrue(candidate.settled)
            assertTrue(candidate.needsConfirmation)
            assertEquals(0, library.registered.size)

            val outcome = service.importChosen(candidate, 1)
            assertEquals(1, outcome.importedEpisodes)
            assertEquals(2L, library.registered.single().anime.id)
        }
    }

    @Test
    fun folderWaitingForAChoiceIsNotResearchedOnEveryPoll() = withTempRoot { root, store ->
        val library = FakeLibrary(listOf(card(1, "Frieren"), card(2, "Frieren")))
        val service = service(library, store, root)
        folder(root, "Frieren")
        runBlocking {
            service.scanOnce()
            val second = service.scanOnce()
            assertEquals(1, library.queries.size)
            assertEquals(2, second.folders.single().matches.size)
        }
    }

    @Test
    fun ignoredFolderIsSkippedAndNotSearched() = withTempRoot { root, store ->
        val library = FakeLibrary(listOf(card(1, "Frieren")))
        val service = service(library, store, root)
        val path = folder(root, "Frieren")
        service.ignore(path)
        runBlocking {
            val result = service.scanOnce()
            assertTrue(result.folders.isEmpty())
            assertEquals(0, library.queries.size)
            assertEquals(listOf(keyOf(path)), result.ignored)

            service.unignore(keyOf(path))
            val again = service.scanOnce()
            assertEquals(1, again.folders.size)
            assertTrue(again.ignored.isEmpty())
        }
    }

    @Test
    fun importingAStillDownloadingFolderIsRefused() = withTempRoot { root, store ->
        val library = FakeLibrary(listOf(card(1, "Frieren")))
        val service = service(library, store, root)
        folder(root, "Frieren", modifiedAt = NOW - 1_000L)
        runBlocking {
            val candidate = service.scanOnce().folders.single()
            assertFalse(candidate.settled)
            assertEquals(0, service.importChosen(candidate, 0).importedEpisodes)
            assertEquals(0, library.registered.size)
            assertTrue(store.imported.isEmpty())
        }
    }

    @Test
    fun savedLibHintResolvesTheAnimeWithoutASearch() = withTempRoot { root, store ->
        val library = FakeLibrary(listOf(card(42, "Frieren")))
        val service = service(library, store, root)
        Files.writeString(
            root.resolve("saved.lib"),
            """[{"id":42,"title":"Frieren","cover":"","banner":"","episodes":1.0}]""",
        )
        val path = folder(root, "Frieren [SubsPlease]")
        runBlocking {
            val result = service.scanOnce()
            assertEquals(0, library.queries.size)
            assertEquals(1, library.lookups.size)
            assertEquals(42L, result.folders.single().matches.single().anime.id)
            assertTrue(store.imported.containsKey(keyOf(path)))
        }
    }

    @Test
    fun renamedFolderIsDetectedAgainAndTheStaleRecordIsDropped() = withTempRoot { root, store ->
        val library = FakeLibrary(listOf(card(1, "Frieren")))
        val service = service(library, store, root)
        val path = folder(root, "Frieren")
        runBlocking {
            service.scanOnce()
            val staleKey = keyOf(path)
            assertTrue(store.imported.containsKey(staleKey))

            Files.move(path, root.resolve("Frieren Season 2"))
            service.scanOnce()

            assertFalse(store.imported.containsKey(staleKey))
            assertEquals(1, store.imported.size)
            assertTrue(store.imported.containsKey(keyOf(root.resolve("Frieren Season 2"))))
        }
    }

    @Test
    fun nestedSeasonFoldersResolveToTheSeriesName() = withTempRoot { root, store ->
        val library = FakeLibrary(listOf(card(1, "Frieren")))
        val service = service(library, store, root)
        val series = Files.createDirectories(root.resolve("Frieren"))
        folder(series, "Season 1")
        runBlocking {
            val result = service.scanOnce()
            assertEquals("Frieren", result.folders.single().folderName)
        }
    }

    @Test
    fun containerFoldersAreDescendedOnce() = withTempRoot { root, store ->
        val library = FakeLibrary(listOf(card(1, "Frieren")))
        val service = service(library, store, root)
        val nested = Files.createDirectories(root.resolve("Anime").resolve("Winter"))
        folder(nested, "Frieren")
        runBlocking {
            val result = service.scanOnce()
            assertEquals(1, result.folders.size)
        }
    }

    @Test
    fun disablingStopsWatchingAndReenablingResumes() = withTempRoot { root, store ->
        val library = FakeLibrary(listOf(card(1, "Frieren")))
        val service = service(library, store, root)
        service.start()
        assertTrue(service.running.value)
        service.stop()
        assertFalse(service.running.value)
        service.start()
        assertTrue(service.running.value)
        service.stop()
    }

    @Test
    fun startingWithoutFoldersReportsItInsteadOfRunning() {
        val root = Files.createTempDirectory("AniLife-autodetect-empty")
        try {
            val library = FakeLibrary(emptyList())
            val store = AutoDetectStore(root.resolve("autodetect.properties").toFile())
            val service = AutoDetectService(library, store, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            service.configure(emptyList(), 10, "sub", true)
            service.start()
            assertFalse(service.running.value)
            assertTrue(service.status.value?.contains("folder") == true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun anilistFailureLeavesTheFolderDetectedButUnmatched() = withTempRoot { root, store ->
        val library = object : AutoDetectLibrary {
            override suspend fun searchAniList(query: String): List<AnimeCard> =
                throw IllegalStateException("rate limit reached")

            override fun register(episodes: List<LocalEpisode>, anime: List<AnimeCard>) = Unit
        }
        val service = service(library, store, root)
        folder(root, "Frieren")
        runBlocking {
            val result = service.scanOnce()
            assertTrue(result.folders.single().matches.isEmpty())
            assertTrue(result.skipped.any { it.contains("AniList unavailable") })
        }
    }

    @Test
    fun existingAnimeFolderScanKeepsWorking() = runBlocking {
        val root = Files.createTempDirectory("AniLife-legacy-scan")
        try {
            val folder = Files.createDirectories(root.resolve("Frieren"))
            Files.write(folder.resolve("Frieren - 01.mkv"), ByteArray(16))
            val result = MediaAutoDetector.scan(root, "sub", { listOf(card(1, "Frieren")) })
            assertEquals(1, result.folders.size)
            assertEquals("Frieren", result.folders.single().name)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
