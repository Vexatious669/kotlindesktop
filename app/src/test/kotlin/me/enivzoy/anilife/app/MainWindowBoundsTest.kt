package me.enivzoy.anilife.app

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class MainWindowBoundsTest {
    @Test
    fun keepsRestoredWindowInsideWorkArea() {
        val workArea = Rectangle(0, 0, 1920, 1040)

        assertEquals(
            Rectangle(400, 280, 1200, 700),
            fitWindowBoundsToWorkArea(Rectangle(400, 280, 1200, 700), workArea),
        )
        assertEquals(
            Rectangle(920, 340, 1000, 700),
            fitWindowBoundsToWorkArea(Rectangle(1200, 340, 1000, 700), workArea),
        )
        assertEquals(
            Rectangle(0, 0, 1920, 1040),
            fitWindowBoundsToWorkArea(Rectangle(-200, -100, 2200, 1300), workArea),
        )
    }
}
