package app.tvflick.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tvflick.plugin.video.DesktopFlickPlayerHost
import app.tvflick.plugin.video.PlayerEpisode
import org.jetbrains.compose.resources.painterResource
import tv_flick.composeapp.generated.resources.Res
import tv_flick.composeapp.generated.resources.tv_track_icon

@Composable
actual fun PlatformPlaybackHost(state: FlickState, content: PlayingContent, options: PlayerWindowOptions, modifier: Modifier) {
    DesktopFlickPlayerHost(
        source = content.prepared.source, contentId = content.prepared.target.id,
        startPositionMs = content.prepared.resumePositionMs, progressSink = content.progressSink,
        title = content.title, episodes = content.episodes.map {
            PlayerEpisode(it.episodeId, "S${it.seasonNumber}E${it.episodeNumber}", it.watchedAtEpochMs != null)
        },
        onEpisodeSelected = { state.playback?.switchEpisode(it.contentId) },
        onClose = { if (state.playback?.active === content) state.playback?.closed() }, onError = { state.playerFailure(it, content) },
        onDownload = { audio, quality -> state.playback?.downloadCurrent(content.key, audio, quality) }, onFullscreen = options.onFullscreen,
        registerClose = options.registerClose, modifier = modifier,
        onMini = { options.onMini() }, onFull = options.onFull, windowIcon = painterResource(Res.drawable.tv_track_icon),
    )
}
