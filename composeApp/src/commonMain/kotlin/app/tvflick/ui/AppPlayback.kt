package app.tvflick.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.tvflick.plugin.api.ResolvedVideoSource
import app.tvflick.plugin.video.PlayerFailure

data class PlayerWindowOptions(
    val mini: Boolean = false,
    val onMini: () -> Boolean = { false },
    val onFull: () -> Unit = {},
    val onRotation: () -> Unit = {},
    val onFullscreen: () -> Unit = {},
    val onActiveChanged: (Boolean) -> Unit = {},
    val registerClose: ((suspend () -> Unit)?) -> Unit = {},
)

@Composable
expect fun PlatformPlaybackHost(state: FlickState, content: PlayingContent, options: PlayerWindowOptions, modifier: Modifier)

@Composable
expect fun rememberMediaDownloader(): suspend (ResolvedVideoSource, String) -> Unit

class UnsupportedMediaDownload : Exception()

internal fun videoFileStem(title: String): String = title
    .map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
    .joinToString("").take(120).trim().trimEnd('.').ifBlank { "TV Flick video" }

fun FlickState.playerFailure(failure: PlayerFailure, expected: PlayingContent? = null) {
    if (expected != null && playback?.active !== expected) return
    notices.show(if (failure == PlayerFailure.Progress) UserProblem.LibraryUpdateFailed else UserProblem.PlaybackFailed)
    if (failure == PlayerFailure.Open) playback?.closed()
}

@Composable
fun PlaybackDownloads(state: FlickState) {
    val downloader = rememberMediaDownloader()
    val controller = state.playback ?: return
    val request = controller.downloadRequest
    LaunchedEffect(request) {
        request ?: return@LaunchedEffect
        try { downloader(request.source, request.title) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: UnsupportedMediaDownload) { state.notices.show(UserProblem.DownloadUnsupported) }
        catch (_: Exception) { state.notices.show(UserProblem.DownloadFailed) }
        finally { controller.consumeDownload(request) }
    }
}
