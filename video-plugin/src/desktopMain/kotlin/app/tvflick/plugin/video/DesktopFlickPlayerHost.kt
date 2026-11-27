package app.tvflick.plugin.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import app.tvflick.plugin.api.PlaybackOption
import app.tvflick.plugin.api.PlaybackProgressSink
import app.tvflick.plugin.api.ResolvedVideoSource
import kotlinx.coroutines.launch

/**
 * Windows presentation host for one playback session. Moving to the Mini
 * Player swaps only the Compose surface into an always-on-top window: the
 * MediaMP player, selected stream, position and progress sink stay intact.
 */
@Composable
fun DesktopFlickPlayerHost(
    source: ResolvedVideoSource,
    contentId: Long,
    startPositionMs: Long = 0,
    progressSink: PlaybackProgressSink? = null,
    title: String = "",
    episodes: List<PlayerEpisode> = emptyList(),
    onEpisodeSelected: (PlayerEpisode) -> Unit = {},
    onQualitySelected: (PlaybackOption) -> Unit = {},
    onAudioTrackSelected: (PlaybackOption) -> Unit = {},
    onClose: () -> Unit = {},
    onError: (PlayerFailure) -> Unit = {},
    onDownload: (String?, String?) -> Unit = { _, _ -> },
    onFullscreen: () -> Unit = {},
    onMini: () -> Unit = {},
    onFull: () -> Unit = {},
    windowIcon: Painter? = null,
    registerClose: ((suspend () -> Unit)?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val session = rememberFlickPlayerSession()
    DisposableEffect(session, registerClose) {
        registerClose { session.closeAndPersistPlayback() }
        onDispose { registerClose(null) }
    }
    val scope = rememberCoroutineScope()
    var miniVisible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val miniWindowState = rememberWindowState(width = 420.dp, height = 236.dp)

    if (!miniVisible) {
        FlickVideoPlayerSessionSurface(
            session = session,
            source = source,
            contentId = contentId,
            startPositionMs = startPositionMs,
            progressSink = progressSink,
            presentation = PlayerPresentation.Full,
            onRequestMini = { if (!closing) { onMini(); miniVisible = true } },
            onRequestFullscreen = onFullscreen,
            onClose = onClose,
            onError = onError,
            onDownload = onDownload,
            onQualitySelected = onQualitySelected,
            onAudioTrackSelected = onAudioTrackSelected,
            title = title,
            episodes = episodes,
            onEpisodeSelected = onEpisodeSelected,
            modifier = modifier,
        )
    }

    DesktopMiniPlayerWindow(
        visible = miniVisible,
        state = miniWindowState,
        icon = windowIcon,
        onClose = {
            if (!closing) {
                closing = true
                scope.launch {
                    try { session.closeAndPersistPlayback() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { onError(PlayerFailure.Progress) }
                    finally { miniVisible = false; onClose() }
                }
            }
        },
    ) {
        FlickVideoPlayerSessionSurface(
            session = session,
            source = source,
            contentId = contentId,
            startPositionMs = startPositionMs,
            progressSink = progressSink,
            presentation = PlayerPresentation.Mini,
            miniControlsAutoHide = true,
            onRequestFull = { if (!closing) { miniVisible = false; onFull() } },
            onError = onError,
            title = title,
            episodes = episodes,
            onEpisodeSelected = onEpisodeSelected,
            modifier = Modifier,
        )
    }
}
