package app.tvflick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tvflick.core.tmdb.YoutubeTrailer

@Composable
internal expect fun PlatformTrailerWindow(trailer: YoutubeTrailer, notices: UserNotices, onDismiss: () -> Unit)

@Composable
internal fun TrailerWindow(
    trailer: YoutubeTrailer,
    notices: UserNotices,
    onDismiss: () -> Unit,
    unavailable: Boolean,
    player: (@Composable (Modifier) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val videoHeight = (minOf(maxWidth, 720.dp) * (9f / 16f)).coerceAtLeast(200.dp)
            val canShowPlayer = !unavailable && player != null && maxWidth >= 200.dp && maxHeight >= 248.dp
            val availablePlayerHeight = minOf(videoHeight, maxHeight - 48.dp)
            Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth(), shape = RoundedCornerShape(6.dp), color = FlickDesign.panel) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Label("Trailer", Modifier.weight(1f), 14, FontWeight.SemiBold)
                        TextButton(onClick = onDismiss) { Label("Close", size = 13) }
                    }
                    if (canShowPlayer) {
                        player?.invoke(Modifier.fillMaxWidth().height(availablePlayerHeight).background(Color.Black))
                    } else {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                            Label("Trailer playback is unavailable here.", size = 14, color = FlickDesign.muted)
                            TextButton(onClick = {
                                runCatching { uriHandler.openUri(trailer.watchUrl) }
                                    .onSuccess { onDismiss() }
                                    .onFailure { notices.show(UserProblem.LinkUnavailable) }
                            }) { Label("Open on YouTube", size = 14) }
                        }
                    }
                }
            }
        }
    }
}
