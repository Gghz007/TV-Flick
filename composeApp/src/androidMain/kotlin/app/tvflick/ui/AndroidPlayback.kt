package app.tvflick.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.tvflick.plugin.video.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun PlatformPlaybackHost(state: FlickState, content: PlayingContent, options: PlayerWindowOptions, modifier: Modifier) {
    val session = rememberFlickPlayerSession()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentContent by rememberUpdatedState(content)
    var closing by remember(session) { mutableStateOf(false) }
    fun finish(expected: PlayingContent) {
        val controller = state.playback
        if (controller?.active === expected) controller.closed()
    }
    DisposableEffect(session, lifecycle) {
        fun boundary(action: suspend () -> Unit) {
            val expected = currentContent
            // Start before disposal; persistence must survive cancellation of the UI
            // scope when Android removes a PiP window or destroys the activity.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
                try { action() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { state.playerFailure(PlayerFailure.Progress, expected) }
            } }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> boundary { session.setHostVisible(true) }
                Lifecycle.Event.ON_PAUSE -> boundary { session.persistPlayback() }
                Lifecycle.Event.ON_STOP -> boundary { session.setHostVisible(false) }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        boundary { session.setHostVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = !options.mini) {
        if (!closing) {
            closing = true
            scope.launch {
                try { session.closeAndPersistPlayback() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { state.playerFailure(PlayerFailure.Progress, content) }
                finally { finish(content) }
            }
        }
    }
    FlickVideoPlayerSessionSurface(session,
        source = content.prepared.source, contentId = content.prepared.target.id,
        startPositionMs = content.prepared.resumePositionMs, progressSink = content.progressSink,
        presentation = if (options.mini) PlayerPresentation.Mini else PlayerPresentation.Full,
        systemPictureInPicture = options.mini,
        showRotationControl = true,
        showVolumeControl = false,
        onRequestMini = { if (!options.onMini()) state.notices.show(UserProblem.PictureInPictureUnavailable) },
        onRequestFull = options.onFull,
        onRequestRotation = options.onRotation, onClose = { finish(content) }, onError = { state.playerFailure(it, content) },
        onDownload = { audio, quality -> state.playback?.downloadCurrent(content.key, audio, quality) }, title = content.title,
        episodes = content.episodes.map { PlayerEpisode(it.episodeId, "S${it.seasonNumber}E${it.episodeNumber}", it.watchedAtEpochMs != null) },
        onEpisodeSelected = { state.playback?.switchEpisode(it.contentId) },
        modifier = modifier)
}
