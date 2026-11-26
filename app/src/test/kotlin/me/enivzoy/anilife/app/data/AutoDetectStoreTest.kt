package me.enivzoy.anilife.app.data

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoDetectStoreTest {
    private fun entry(
        path: String,
        animeId: Long? = 1L,
        title: String? = "Frieren",
        episodes: Int = 12,
        bytes: Long = 1_000L,
        files: Int = 12,
        updatedAt: Long = 5_000L,
    ) = AutoDetectEntry(path, animeId, title, episodes, bytes, files, updatedAt)

    @Test
    fun entriesAndIgnoresSurviveAReopen() {
        val root = Files.createTempDirectory("AniLife-autodetect")
        try {
            val file = root.resolve("autodetect.properties").toFile()
            val store = AutoDetectStore(file)
            store.remember(entry("C:\\Anime\\Frieren", animeId = 1, episodes = 12, bytes = 2_000L, files = 12))
            store.remember(entry("C:\\Anime\\Dandadan", animeId = 2, title = "Dandadan", episodes = 6, bytes = 900L, files = 6))
            store.ignore("C:\\Anime\\Skip")

            val reopened = AutoDetectStore(file)
            assertEquals(2, reopened.imported.size)
            val frieren = reopened.imported.getValue("C:\\Anime\\Frieren")
            assertEquals(1L, frieren.animeId)
            assertEquals("Frieren", frieren.title)
            assertEquals(12, frieren.episodes)
            assertEquals(2_000L, frieren.bytes)
            assertEquals(12, frieren.files)
            assertEquals(5_000L, frieren.updatedAt)
            assertEquals(2L, reopened.imported.getValue("C:\\Anime\\Dandadan").animeId)
            assertEquals(setOf("C:\\Anime\\Skip"), reopened.ignored)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rememberingTheSamePathReplacesTheEntry() {
        val root = Files.createTempDirectory("AniLife-autodetect-replace")
        try {
            val file = root.resolve("autodetect.properties").toFile()
            val store = AutoDetectStore(file)
            store.remember(entry("C:\\Anime\\Frieren", episodes = 12, bytes = 1_000L))
            store.remember(entry("C:\\Anime\\Frieren", episodes = 13, bytes = 1_500L))

            val reopened = AutoDetectStore(file)
            assertEquals(1, reopened.imported.size)
            val only = reopened.imported.getValue("C:\\Anime\\Frieren")
            assertEquals(13, only.episodes)
            assertEquals(1_500L, only.bytes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun forgetUnignoreAndClearPersist() {
        val root = Files.createTempDirectory("AniLife-autodetect-clear")
        try {
            val file = root.resolve("autodetect.properties").toFile()
            val store = AutoDetectStore(file)
            store.remember(entry("C:\\Anime\\Frieren"))
            store.remember(entry("C:\\Anime\\Dandadan"))
            store.ignore("C:\\Anime\\Skip")

            store.forget("C:\\Anime\\Dandadan")
            store.unignore("C:\\Anime\\Skip")
            store.unignore("C:\\Anime\\Never")

            val partial = AutoDetectStore(file)
            assertEquals(setOf("C:\\Anime\\Frieren"), partial.imported.keys)
            assertTrue(partial.ignored.isEmpty())

            partial.clear()

            val cleared = AutoDetectStore(file)
            assertTrue(cleared.imported.isEmpty())
            assertTrue(cleared.ignored.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun aMissingFileLoadsAsEmpty() {
        val root = Files.createTempDirectory("AniLife-autodetect-missing")
        try {
            val store = AutoDetectStore(root.resolve("nowhere/autodetect.properties").toFile())
            assertTrue(store.imported.isEmpty())
            assertTrue(store.ignored.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun aCorruptFileLoadsAsEmpty() {
        val root = Files.createTempDirectory("AniLife-autodetect-corrupt")
        try {
            val file = root.resolve("autodetect.properties").toFile()
            file.writeText("this is not a properties file\u0000")

            val store = AutoDetectStore(file)
            assertTrue(store.imported.isEmpty())
            assertTrue(store.ignored.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun aCountLargerThanTheEntriesKeepsWhatIsValid() {
        val root = Files.createTempDirectory("AniLife-autodetect-count")
        try {
            val file = root.resolve("autodetect.properties").toFile()
            file.writeText(
                """
                version=1
                imported.count=3
                imported.0.path=C:/Anime/Frieren
                imported.0.animeId=1
                imported.0.episodes=12
                imported.1.animeId=2
                imported.1.episodes=6
                imported.2.title=Dandadan
                ignored.count=0
                """.trimIndent(),
            )

            val store = AutoDetectStore(file)
            assertEquals(setOf("C:/Anime/Frieren"), store.imported.keys)
            assertEquals(12, store.imported.getValue("C:/Anime/Frieren").episodes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun anEntryWithoutAPathIsSkipped() {
        val root = Files.createTempDirectory("AniLife-autodetect-nopath")
        try {
            val file = root.resolve("autodetect.properties").toFile()
            file.writeText(
                """
                version=1
                imported.count=2
                imported.0.animeId=1
                imported.0.title=Frieren
                imported.1.path=C:/Anime/Dandadan
                imported.1.animeId=2
                ignored.count=0
                """.trimIndent(),
            )

            val store = AutoDetectStore(file)
            assertEquals(setOf("C:/Anime/Dandadan"), store.imported.keys)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun aLegacyFileWithoutAVersionStillLoads() {
        val root = Files.createTempDirectory("AniLife-autodetect-legacy")
        try {
            val file = root.resolve("autodetect.properties").toFile()
            file.writeText(
                """
                imported.count=1
                imported.0.path=C:/Anime/Frieren
                imported.0.episodes=12
                ignored.count=1
                ignored.0.path=C:/Anime/Skip
                """.trimIndent(),
            )

            val store = AutoDetectStore(file)
            assertEquals(setOf("C:/Anime/Frieren"), store.imported.keys)
            assertEquals(setOf("C:/Anime/Skip"), store.ignored)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun anIdenticalRememberDoesNotRewriteTheFile() {
        val root = Files.createTempDirectory("AniLife-autodetect-noop")
        try {
            val file = root.resolve("autodetect.properties").toFile()
            val store = AutoDetectStore(file)
            val same = entry("C:\\Anime\\Frieren")
            store.remember(same)

            val before: FileTime = Files.getLastModifiedTime(file.toPath())
            val content = file.readText()
            Thread.sleep(20)
            store.remember(same)

            assertEquals(before, Files.getLastModifiedTime(file.toPath()))
            assertEquals(content, file.readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun pathsWithSpacesAndNonAsciiRoundTrip() {
        val root = Files.createTempDirectory("AniLife-autodetect-unicode")
        try {
            val file = root.resolve("autodetect.properties").toFile()
            val path = "C:\\Meus Animes\\Kimi no Na wa — 君の名は"
            val store = AutoDetectStore(file)
            store.remember(entry(path, title = "君の名は"))
            store.ignore("$path (dublado)")

            val reopened = AutoDetectStore(file)
            assertEquals(path, reopened.imported.getValue(path).path)
            assertEquals("君の名は", reopened.imported.getValue(path).title)
            assertTrue(reopened.ignored.contains("$path (dublado)"))
            assertFalse(reopened.ignored.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}