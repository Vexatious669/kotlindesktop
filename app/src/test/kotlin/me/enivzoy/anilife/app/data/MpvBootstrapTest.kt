package me.enivzoy.anilife.app.data

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpvBootstrapTest {

    @Test
    fun ensureKeepsAnExistingUserSuppliedLibrary() = runBlocking {
        val dir = Files.createTempDirectory("anilife-mpv-override-")
        val library = dir.resolve("libmpv-2.dll")
        Files.writeString(library, "not a real library")

        val previous = System.getProperty("AniLife.mpv.library")
        System.setProperty("AniLife.mpv.library", library.toString())
        try {
            val resolved = MpvBootstrap.ensure()
            assertEquals(library.toString(), resolved)
            assertEquals(library.toString(), System.getProperty("AniLife.mpv.library"))
        } finally {
            restoreProperty("AniLife.mpv.library", previous)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun staleOverrideIsDroppedOffWindows() = runBlocking {
        // On Windows this would start a real download, so only the non-Windows path is exercised.
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return@runBlocking

        val dir = Files.createTempDirectory("anilife-mpv-stale-")
        val home = Files.createDirectories(dir.resolve("home"))
        val cwd = Files.createDirectories(dir.resolve("cwd"))
        val stale = dir.resolve("missing/libmpv-2.dll").toString()

        val previousLibrary = System.getProperty("AniLife.mpv.library")
        val previousHome = System.getProperty("user.home")
        val previousDir = System.getProperty("user.dir")
        System.setProperty("AniLife.mpv.library", stale)
        System.setProperty("user.home", home.toString())
        System.setProperty("user.dir", cwd.toString())
        try {
            assertNull(MpvBootstrap.ensure())
            assertNull(System.getProperty("AniLife.mpv.library"))
        } finally {
            restoreProperty("AniLife.mpv.library", previousLibrary)
            restoreProperty("user.home", previousHome)
            restoreProperty("user.dir", previousDir)
            dir.toFile().deleteRecursively()
        }
    }

    private fun restoreProperty(name: String, previous: String?) {
        if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
    }
}