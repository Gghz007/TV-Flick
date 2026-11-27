package app.tvflick.plugin.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.graphics.painter.Painter
import java.awt.Dimension
import java.awt.Frame
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import java.beans.PropertyChangeListener

/**
 * A platform presentation shell only. Its caller supplies the same player
 * session used by Full Player, so moving to this window does not reopen media.
 */
@Composable
fun DesktopMiniPlayerWindow(
    visible: Boolean,
    onClose: () -> Unit,
    state: WindowState = rememberWindowState(width = 420.dp, height = 236.dp),
    icon: Painter? = null,
    content: @Composable () -> Unit,
) {
    if (!visible) return

    Window(
        onCloseRequest = onClose,
        title = "TV Flick — Mini Player",
        state = state,
        icon = icon,
        alwaysOnTop = true,
        resizable = true,
    ) {
        val nativeWindow = window
        DisposableEffect(nativeWindow) {
            fun enforceSize() {
                val configuration = nativeWindow.graphicsConfiguration ?: return
                val screen = configuration.bounds
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
                val maxWidth = minOf(960, (screen.width - insets.left - insets.right).coerceAtLeast(1))
                val maxHeight = minOf(540, (screen.height - insets.top - insets.bottom).coerceAtLeast(1))
                val minWidth = minOf(240, maxWidth)
                val minHeight = minOf(135, maxHeight)
                nativeWindow.minimumSize = Dimension(minWidth, minHeight)
                nativeWindow.maximumSize = Dimension(maxWidth, maxHeight)
                val bounded = Dimension(nativeWindow.width.coerceIn(minWidth, maxWidth), nativeWindow.height.coerceIn(minHeight, maxHeight))
                if (bounded != nativeWindow.size) nativeWindow.size = bounded
            }
            // AWT maximumSize is only a hint on Windows; enforce it after native resize events.
            val resize = object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) = enforceSize()
            }
            val monitor = PropertyChangeListener { enforceSize() }
            val placement = WindowStateListener { event ->
                if (event.newState and Frame.MAXIMIZED_BOTH != 0)
                    nativeWindow.extendedState = event.newState and Frame.MAXIMIZED_BOTH.inv()
                enforceSize()
            }
            nativeWindow.addComponentListener(resize)
            nativeWindow.addPropertyChangeListener("graphicsConfiguration", monitor)
            nativeWindow.addWindowStateListener(placement)
            enforceSize()
            onDispose {
                nativeWindow.removeComponentListener(resize)
                nativeWindow.removePropertyChangeListener("graphicsConfiguration", monitor)
                nativeWindow.removeWindowStateListener(placement)
            }
        }
        content()
    }
}
