package app.tvflick.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.tvflick.App
import app.tvflick.core.update.ReleaseConfig
import app.tvflick.ui.referenceClick
import app.tvflick.ui.PlayerWindowOptions
import app.tvflick.ui.LocalTrailerOwner
import app.tvflick.ui.FlickDesign
import app.tvflick.ui.appText
import app.tvflick.ui.IconAsset
import java.io.File
import java.nio.file.Path
import org.jetbrains.compose.resources.painterResource
import tv_flick.composeapp.generated.resources.Res
import tv_flick.composeapp.generated.resources.tv_track_icon
import tv_flick.composeapp.generated.resources.window_close
import tv_flick.composeapp.generated.resources.window_maximize
import tv_flick.composeapp.generated.resources.window_minimize

fun main() {
    val key = ReleaseConfig.desktopModulePublicKey
    val moduleRoot = if (key.isNotEmpty()) packagedWindowsModuleRoot() else null
    if (moduleRoot != null) withDesktopModuleLock(moduleRoot, shared = true) { runDesktopApp() }
    else runDesktopApp()
}

private fun packagedWindowsModuleRoot(): Path? {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return null
    return runCatching { DesktopLocalDatabase.applicationDirectory() }.getOrNull()
        ?.takeIf { File(it, "TV Flick.exe").isFile && File(it, "app").isDirectory }
        ?.toPath()
}

private fun runDesktopApp() {
    installDesktopCrashLog()
    application {
    // The PC/Tablet Lunacy reference is a 1137×747 application window.
    // Components keep their own reference dimensions when users resize it.
    val windowState = rememberWindowState(width = 1137.dp, height = 747.dp)
    var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
    var returnFromMiniToFullscreen by remember { mutableStateOf(false) }
    var playerClose by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    var closing by remember { mutableStateOf(false) }
    val closeScope = rememberCoroutineScope()
    val requestExit: () -> Unit = {
        if (!closing) {
            closing = true
            closeScope.launch {
                try { playerClose?.invoke() }
                catch (_: Exception) { System.err.println("Could not save final playback position.") }
                finally { exitApplication() }
            }
        }
    }
    val libraryDatabase = remember {
        runCatching { createLibraryDatabase() }
            .onFailure { System.err.println("Unable to open TV Flick library: ${it.javaClass.simpleName}") }
            .getOrNull()
    }
    val libraryRepository = libraryDatabase?.repository
    DisposableEffect(libraryDatabase) { onDispose { libraryDatabase?.close() } }
    Window(
        state = windowState,
        onCloseRequest = requestExit,
        title = "TV Flick",
        icon = painterResource(Res.drawable.tv_track_icon),
        undecorated = true,
        transparent = true,
    ) {
        var snapped by remember { mutableStateOf(false) }
        // Two 359 dp cards + their gap/insets; quarter-screen height must remain usable.
        SideEffect { window.minimumSize = Dimension(768, 400) }
        DisposableEffect(window) {
            fun updateBounds() {
                window.updateMaximizedWorkArea()
                val config = window.graphicsConfiguration
                snapped = config != null && desktopTileHasSquareCorners(window.bounds,
                    desktopWorkArea(config.bounds, window.toolkit.getScreenInsets(config)))
            }
            updateBounds()
            val monitorListener = PropertyChangeListener { updateBounds() }
            val boundsListener = object : ComponentAdapter() {
                override fun componentMoved(event: ComponentEvent) = updateBounds()
                override fun componentResized(event: ComponentEvent) = updateBounds()
            }
            window.addPropertyChangeListener("graphicsConfiguration", monitorListener)
            window.addComponentListener(boundsListener)
            onDispose {
                window.removePropertyChangeListener("graphicsConfiguration", monitorListener)
                window.removeComponentListener(boundsListener)
            }
        }
        CompositionLocalProvider(LocalTrailerOwner provides window) {
        App(
            playerWindowOptions = PlayerWindowOptions(
                registerClose = { playerClose = it },
                onFullscreen = {
                    if (windowState.placement == WindowPlacement.Fullscreen) windowState.placement = previousPlacement
                    else { previousPlacement = windowState.placement; windowState.placement = WindowPlacement.Fullscreen }
                },
                onMini = {
                    returnFromMiniToFullscreen = windowState.placement == WindowPlacement.Fullscreen
                    if (returnFromMiniToFullscreen) windowState.placement = previousPlacement
                    true
                },
                onFull = {
                    windowState.isMinimized = false
                    if (returnFromMiniToFullscreen) windowState.placement = WindowPlacement.Fullscreen
                    returnFromMiniToFullscreen = false
                    window.toFront()
                    window.requestFocus()
                },
                onActiveChanged = { active -> if (!active) {
                    returnFromMiniToFullscreen = false
                    if (windowState.placement == WindowPlacement.Fullscreen) windowState.placement = previousPlacement
                } },
            ),
            squareCorners = snapped || windowState.placement != WindowPlacement.Floating,
            tmdbApiKey = System.getenv("TVFLICK_TMDB_API_KEY")
                ?: System.getProperty("tmdb.apiKey").orEmpty(),
            libraryRepository = libraryRepository,
            topPanel = {
                NativeWindowDraggableArea {
                DesktopTopPanel(
                    onMinimize = { windowState.isMinimized = true },
                    onToggleMaximize = {
                        window.updateMaximizedWorkArea()
                        windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Maximized
                        }
                    },
                    onClose = requestExit,
                )
                }
            },
        )
        }
    }
}

}

private fun createLibraryDatabase(): DesktopLibraryDatabase {
    return DesktopLibraryDatabase.open(DesktopLocalDatabase.prepare())
}

@Composable
private fun DesktopTopPanel(
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = Color.Transparent, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 12.dp)) {
            Image(
                painter = painterResource(Res.drawable.tv_track_icon),
                contentDescription = "TV Flick",
                modifier = Modifier.offset(y = 5.dp).size(26.dp),
            )
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("TV") }
                    append(" ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append("F") }
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("lick") }
                },
                color = FlickDesign.foreground,
                fontSize = 13.sp,
                lineHeight = 15.sp,
                modifier = Modifier.offset(y = 5.dp).padding(start = 9.dp),
            )
            Spacer(Modifier.weight(1f))
            WindowControl(WindowControlType.Minimize, "Minimize", onMinimize)
            WindowControl(WindowControlType.Maximize, "Maximize", onToggleMaximize)
            WindowControl(WindowControlType.Close, "Close", onClose)
        }
    }
}

@Composable
private fun WindowControl(type: WindowControlType, description: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(60.dp)
            .height(40.dp)
            .referenceClick(androidx.compose.ui.graphics.RectangleShape, action = onClick)
            .background(Color.Transparent),
    ) {
        val icon = when (type) {
            WindowControlType.Minimize -> Res.drawable.window_minimize
            WindowControlType.Maximize -> Res.drawable.window_maximize
            WindowControlType.Close -> Res.drawable.window_close
        }
        IconAsset(icon, appText(description), Modifier.size(11.77.dp), tint = FlickDesign.foreground)
    }
}

private enum class WindowControlType { Minimize, Maximize, Close }
