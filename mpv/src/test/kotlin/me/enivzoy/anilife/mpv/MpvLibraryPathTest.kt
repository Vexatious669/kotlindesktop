package me.enivzoy.anilife.mpv

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class MpvLibraryPathTest {

    @Test
    fun propertyOverrideWins() {
        val dir = Files.createTempDirectory("anilife-mpv-property-")
        val library = dir.resolve("libmpv-2.dll")
        Files.writeString(library, "stub")

        val previous = System.getProperty("AniLife.mpv.library")
        System.setProperty("AniLife.mpv.library", library.toString())
        try {
            assertEquals(library.toString(), Mpv.defaultLibraryPath())
            assertEquals(library.toString(), System.getProperty("AniLife.mpv.library"))
        } finally {
            if (previous == null) {
                System.clearProperty("AniLife.mpv.library")
            } else {
                System.setProperty("AniLife.mpv.library", previous)
            }
            dir.toFile().deleteRecursively()
        }
    }
}