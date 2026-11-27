package app.tvflick.desktop

import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DesktopWindowBoundsTest {
    @Test fun `half and quarter snaps on either monitor remove rounding but free windows keep it`() {
        val area = Rectangle(-1920, 0, 1920, 1040)
        assertTrue(desktopTileHasSquareCorners(Rectangle(-1920, 0, 960, 1040), area))
        assertTrue(desktopTileHasSquareCorners(Rectangle(-960, 520, 960, 520), area))
        assertTrue(desktopTileHasSquareCorners(Rectangle(-1920, 0, 640, 1040), area))
        assertFalse(desktopTileHasSquareCorners(Rectangle(-1800, 100, 960, 520), area))
        assertFalse(desktopTileHasSquareCorners(Rectangle(-1920, 0, 1137, 747), area))
    }
    @Test
    fun `maximization excludes bottom taskbar`() {
        assertEquals(Rectangle(0, 0, 1920, 1032),
            desktopWorkArea(Rectangle(0, 0, 1920, 1080), Insets(0, 0, 48, 0)))
    }

    @Test
    fun `work area preserves secondary monitor origin and top or side taskbar insets`() {
        assertEquals(Rectangle(-1860, 40, 1860, 1040),
            desktopWorkArea(Rectangle(-1920, 0, 1920, 1080), Insets(40, 60, 0, 0)))
        assertEquals(Rectangle(1920, -900, 1550, 900),
            desktopWorkArea(Rectangle(1920, -900, 1600, 900), Insets(0, 0, 0, 50)))
    }
}
