package app.tvflick.plugin.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.tvflick.plugin.api.PlaybackOption
import java.io.File

/** Live reference comparison only; never included in application distributions. */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "TV Flick — Player QA", undecorated = true,
        state = rememberWindowState(width = 814.dp, height = 375.dp)) {
        var mini by remember { mutableStateOf(false) }
        var locked by remember { mutableStateOf(false) }
        var playing by remember { mutableStateOf(false) }
        var position by remember { mutableStateOf(1_530_000L) }
        var volume by remember { mutableStateOf(.47f) }
        var autoMark by remember { mutableStateOf(true) }
        var episode by remember { mutableStateOf(3L) }
        val background = remember {
            File("docs/design/player-qa-background.png").takeIf { it.exists() }?.let {
                org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF303030))) {
            background?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            if (mini) MiniControls("Landman S1E2", position, 4_694_000L, { position = it }, { mini = false })
            else FullControls(position, 4_694_000L, playing, locked, volume, 1f,
                listOf("1080P", "720P", "480P", "360P").map { PlaybackOption(it, it) },
                listOf("4erkama", "Lovalyme", "Manik", "Prolab").map { PlaybackOption(it, it) },
                "1080P", "4erkama", "1.0",
                { position = it }, { playing = !playing }, { locked = it }, { volume = it }, {}, {}, {},
                "Landman S1E2", (1L..8L).map { PlayerEpisode(it, "Серия $it", it < 3) }, episode,
                { episode = it.contentId }, autoMark, { autoMark = it }, false, {}, { mini = true }, {}, ::exitApplication, {})
        }
    }
}
