package me.enivzoy.anilife.app.data

import java.awt.Rectangle
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WindowStateStoreTest {

    private val directory = Files.createTempDirectory("anilife-window").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun roundTripsTheSavedBounds() {
        val store = WindowStateStore(File(directory, "window.properties"))
        val saved = SavedWindow(x = 120, y = 240, width = 1180, height = 720, maximized = true)

        store.write(saved)

        assertEquals(saved, store.read())
    }

    @Test
    fun aMissingFileReadsAsNull() {
        assertNull(WindowStateStore(File(directory, "missing.properties")).read())
    }

    @Test
    fun aCorruptFileReadsAsNull() {
        val file = File(directory, "corrupt.properties")
        file.writeText("x=not-a-number")

        assertNull(WindowStateStore(file).read())
    }

    @Test
    fun aMissingFileIsWrittenAgainWithoutError() {
        val store = WindowStateStore(File(directory, "nested/window.properties"))
        store.write(SavedWindow(0, 0, 900, 600, false))

        assertNotNull(store.read())
    }

    @Test
    fun offScreenBoundsAreRejected() {
        val saved = SavedWindow(x = 5_000, y = 5_000, width = 1_000, height = 700, maximized = false)

        assertNull(restoreWindowBounds(saved, listOf(Rectangle(0, 0, 1920, 1080))))
    }

    @Test
    fun barelyVisibleBoundsAreRejected() {
        val saved = SavedWindow(x = 1900, y = 100, width = 1_000, height = 700, maximized = false)

        assertNull(restoreWindowBounds(saved, listOf(Rectangle(0, 0, 1920, 1080))))
    }

    @Test
    fun fullyVisibleBoundsAreRestored() {
        val saved = SavedWindow(x = 100, y = 100, width = 1_200, height = 800, maximized = false)

        assertEquals(
            Rectangle(100, 100, 1_200, 800),
            restoreWindowBounds(saved, listOf(Rectangle(0, 0, 1_920, 1_080))),
        )
    }

    @Test
    fun aSecondScreenCountsAsVisible() {
        val saved = SavedWindow(x = 2_100, y = 300, width = 1_200, height = 800, maximized = false)

        assertNotNull(
            restoreWindowBounds(
                saved,
                listOf(Rectangle(0, 0, 1_920, 1_080), Rectangle(1_920, 0, 1_920, 1_080)),
            ),
        )
    }

    @Test
    fun restoredWindowIsResizedAndPositionedWithinItsScreen() {
        val saved = SavedWindow(x = -500, y = -200, width = 1_600, height = 900, maximized = false)

        assertEquals(
            Rectangle(0, 0, 1_024, 768),
            restoreWindowBounds(saved, listOf(Rectangle(0, 0, 1_024, 768))),
        )
    }

    @Test
    fun compactSavedBoundsCanBeRestored() {
        val saved = SavedWindow(x = 20, y = 30, width = 640, height = 360, maximized = false)

        assertEquals(
            Rectangle(20, 30, 640, 360),
            restoreWindowBounds(saved, listOf(Rectangle(0, 0, 1_024, 768))),
        )
    }

    @Test
    fun undersizedBoundsAreRejected() {
        val saved = SavedWindow(x = 100, y = 100, width = 400, height = 200, maximized = false)

        assertNull(restoreWindowBounds(saved, listOf(Rectangle(0, 0, 1_920, 1_080))))
    }

    @Test
    fun noSavedWindowMeansNoRestore() {
        assertNull(restoreWindowBounds(null, listOf(Rectangle(0, 0, 1_920, 1_080))))
    }
}
