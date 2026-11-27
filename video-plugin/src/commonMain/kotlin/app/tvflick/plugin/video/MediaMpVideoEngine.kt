package app.tvflick.plugin.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.tvflick.plugin.api.PlaybackSnapshot
import app.tvflick.plugin.api.ResolvedVideoSource
import app.tvflick.plugin.api.VideoEngine
import app.tvflick.plugin.api.VideoEnginePlugin
import app.tvflick.plugin.api.reachesWatchedThreshold
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.source.UriMediaData
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * TV Flick's shipping candidate. MediaMP keeps a single observable player
 * instance independent from the composable surface, which allows Full/Mini
 * hosts to switch without reopening the video URL.
 */
class MediaMpVideoEngine internal constructor(
    internal val player: MediampPlayer,
) : VideoEngine {
    private var recentSeek: RequestedSeek? = null

    override suspend fun open(source: ResolvedVideoSource, startPositionMs: Long) {
        recentSeek = null
        player.setMediaData(UriMediaData(source.url, source.headers), true, startPositionMs.coerceAtLeast(0))
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) {
        val status = player.state.value.mediaStatus
        if (status !is MediaStatus.Ready && status !is MediaStatus.Ended) return
        val duration = player.mediaProperties.value?.durationMillis?.takeIf { it > 0 } ?: return
        val position = positionMs.coerceIn(0, duration)
        player.seekTo(position)
        recentSeek = if (player.currentPositionMillis.value == position) null
            else RequestedSeek(position, duration, TimeSource.Monotonic.markNow())
    }

    override fun setSpeed(speed: Float) {
        player.features[PlaybackSpeed]?.set(speed.coerceIn(.25f, 2f))
    }

    override fun setLocked(locked: Boolean) = Unit

    override fun close() = player.close()

    internal fun snapshot(contentId: Long, allowLibraryWatchedMark: Boolean = true) = PlaybackSnapshot(
        contentId = contentId,
        positionMs = player.currentPositionMillis.value.coerceAtLeast(0),
        durationMs = player.mediaProperties.value?.durationMillis,
        completed = player.state.value.mediaStatus is MediaStatus.Ended,
        allowLibraryWatchedMark = allowLibraryWatchedMark,
    )

    /** Opening/released/error states can expose temporary zeroes or the previous media's properties. */
    internal fun snapshotIfReady(contentId: Long, allowLibraryWatchedMark: Boolean): PlaybackSnapshot? {
        val status = player.state.value.mediaStatus
        val observed = if (status is MediaStatus.Ready || status is MediaStatus.Ended)
            snapshot(contentId, allowLibraryWatchedMark) else null
        val requested = recentSeek ?: return observed
        val checkpoint = requested.snapshot(contentId, allowLibraryWatchedMark)
        if (requested.time.elapsedNow() > 3.seconds ||
            (observed != null && observed.durationMs != null && abs(observed.positionMs - requested.positionMs) <= 250 &&
                observed.reachesWatchedThreshold() == checkpoint.reachesWatchedThreshold())
        ) {
            recentSeek = null
            return observed
        }
        return checkpoint
    }

    private data class RequestedSeek(val positionMs: Long, val durationMs: Long, val time: TimeMark) {
        fun snapshot(contentId: Long, allowLibraryWatchedMark: Boolean) =
            PlaybackSnapshot(contentId, positionMs, durationMs, completed = positionMs == durationMs,
                allowLibraryWatchedMark = allowLibraryWatchedMark)
    }
}

class MediaMpVideoEnginePlugin : VideoEnginePlugin {
    override val id = "tvflick.video.mediamp"
    override val version = "0.5.1"

    @Composable
    override fun rememberEngine(): VideoEngine {
        val player = rememberMediampPlayer()
        return remember(player) { MediaMpVideoEngine(player) }
    }

    @Composable
    override fun Render(engine: VideoEngine, modifier: Modifier) {
        require(engine is MediaMpVideoEngine) {
            "MediaMpVideoEnginePlugin can render only its own engine instance."
        }
        MediampPlayerSurface(engine.player, modifier)
    }
}
