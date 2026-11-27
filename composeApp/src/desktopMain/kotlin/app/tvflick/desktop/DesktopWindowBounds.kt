package app.tvflick.desktop

import java.awt.Frame
import java.awt.Insets
import java.awt.Rectangle
import kotlin.math.abs

/** Windows Snap leaves AWT placement as Floating. Match half/quarter work-area tiles too. */
internal fun desktopTileHasSquareCorners(bounds: Rectangle, area: Rectangle, tolerance: Int = 3): Boolean {
    fun near(a: Int, b: Int) = abs(a - b) <= tolerance
    if (bounds.width <= 0 || bounds.height <= 0) return false
    val horizontalTile = (near(bounds.x, area.x) || near(bounds.x + bounds.width, area.x + area.width)) &&
        (near(bounds.width, area.width / 2) || near(bounds.width, area.width / 3) || near(bounds.width, area.width * 2 / 3))
    val verticalTile = (near(bounds.y, area.y) || near(bounds.y + bounds.height, area.y + area.height)) &&
        (near(bounds.height, area.height) || near(bounds.height, area.height / 2))
    return (horizontalTile && verticalTile) || bounds == area
}

/** AWT screen bounds and insets share logical coordinates, including on HiDPI monitors. */
internal fun desktopWorkArea(screen: Rectangle, insets: Insets): Rectangle = Rectangle(
    screen.x + insets.left,
    screen.y + insets.top,
    (screen.width - insets.left - insets.right).coerceAtLeast(1),
    (screen.height - insets.top - insets.bottom).coerceAtLeast(1),
)

internal fun Frame.updateMaximizedWorkArea() {
    val configuration = graphicsConfiguration ?: return
    val area = desktopWorkArea(configuration.bounds, toolkit.getScreenInsets(configuration))
    if (maximizedBounds != area) maximizedBounds = area
}
