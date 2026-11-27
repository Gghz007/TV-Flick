package app.tvflick.plugin.video

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import app.tvflick.plugin.api.PlaybackOption
import app.tvflick.plugin.api.PlaybackProgressSink
import app.tvflick.plugin.api.ResolvedVideoSource
import app.tvflick.plugin.api.reachesWatchedThreshold
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.features.AudioLevelController

enum class PlayerPresentation { Full, Mini }
enum class PlayerFailure { Open, Progress }

/**
 * Visual episode state for the Full Player's fixed-width, horizontally
 * scrollable episode strip. The parent switches the actual source/session
 * when [onEpisodeSelected] is called.
 */
data class PlayerEpisode(
    val contentId: Long,
    val label: String,
    val isWatched: Boolean,
)

/**
 * A presentation-independent playback session. Keep this object at the
 * Full/Mini host level so a Desktop window move never creates a second player.
 */
class FlickPlayerSession internal constructor(
    internal val player: org.openani.mediamp.MediampPlayer,
    internal val engine: MediaMpVideoEngine,
) {
    internal var openedSource: ResolvedVideoSource? = null
    internal var activeContentId: Long? = null
    internal var activeProgressSink: PlaybackProgressSink? = null
    internal var bindingRevision by mutableStateOf(0L)
    internal var nativeErrorReported = false
    internal val transition = Mutex()
    internal val visibility = PlaybackVisibility(transition,
        isPlaying = { player.state.value.playWhenReady }, pause = engine::pause, play = engine::play,
        persist = { persistActiveProgress(this) })
    internal var locked by mutableStateOf(false)
    internal var reportError: (PlayerFailure) -> Unit = {}
    internal var selectedAudioId by mutableStateOf<String?>(null)
    internal var selectedQualityId by mutableStateOf<String?>(null)
    internal var speed by mutableStateOf(1f)
    internal var isEpisode = false
    // This visual state is session-local. It always updates the player strip,
    // even when the user declines to sync automatic watched marks to TV Flick.
    internal val playerWatchedEpisodeIds = mutableStateListOf<Long>()
    internal var autoWatchListEnabled by mutableStateOf(true)
}

@Composable
fun rememberFlickPlayerSession(): FlickPlayerSession {
    val player = rememberMediampPlayer()
    return remember(player) { FlickPlayerSession(player, MediaMpVideoEngine(player)) }
}

/** Convenience overload for a host that never moves the player into another window. */
@Composable
fun FlickVideoPlayer(
    source: ResolvedVideoSource,
    contentId: Long,
    startPositionMs: Long = 0,
    progressSink: PlaybackProgressSink? = null,
    presentation: PlayerPresentation = PlayerPresentation.Full,
    showRotationControl: Boolean = false,
    onRequestMini: () -> Unit = {},
    onRequestFull: () -> Unit = {},
    onRequestRotation: () -> Unit = {},
    onRequestFullscreen: () -> Unit = {},
    onClose: () -> Unit = {},
    onError: (PlayerFailure) -> Unit = {},
    onDownload: (String?, String?) -> Unit = { _, _ -> },
    onQualitySelected: (PlaybackOption) -> Unit = {},
    onAudioTrackSelected: (PlaybackOption) -> Unit = {},
    title: String = "",
    episodes: List<PlayerEpisode> = emptyList(),
    onEpisodeSelected: (PlayerEpisode) -> Unit = {},
    modifier: Modifier = Modifier,
    showVolumeControl: Boolean = true,
) {
    val session = rememberFlickPlayerSession()
    FlickVideoPlayerSessionSurface(
        session = session,
        source = source,
        contentId = contentId,
        startPositionMs = startPositionMs,
        progressSink = progressSink,
        presentation = presentation,
        showRotationControl = showRotationControl,
        onRequestMini = onRequestMini,
        onRequestFull = onRequestFull,
        onRequestRotation = onRequestRotation,
        onRequestFullscreen = onRequestFullscreen,
        onClose = onClose,
        onError = onError,
        onDownload = onDownload,
        onQualitySelected = onQualitySelected,
        onAudioTrackSelected = onAudioTrackSelected,
        title = title,
        episodes = episodes,
        onEpisodeSelected = onEpisodeSelected,
        modifier = modifier,
        showVolumeControl = showVolumeControl,
    )
}

/** Full and Mini surfaces consume the supplied [session] without recreating it. */
@Composable
fun FlickVideoPlayerSessionSurface(
    session: FlickPlayerSession,
    source: ResolvedVideoSource,
    contentId: Long,
    startPositionMs: Long = 0,
    progressSink: PlaybackProgressSink? = null,
    presentation: PlayerPresentation = PlayerPresentation.Full,
    showRotationControl: Boolean = false,
    onRequestMini: () -> Unit = {},
    onRequestFull: () -> Unit = {},
    onRequestRotation: () -> Unit = {},
    onRequestFullscreen: () -> Unit = {},
    onClose: () -> Unit = {},
    onError: (PlayerFailure) -> Unit = {},
    onDownload: (String?, String?) -> Unit = { _, _ -> },
    onQualitySelected: (PlaybackOption) -> Unit = {},
    onAudioTrackSelected: (PlaybackOption) -> Unit = {},
    title: String = "",
    episodes: List<PlayerEpisode> = emptyList(),
    onEpisodeSelected: (PlayerEpisode) -> Unit = {},
    modifier: Modifier = Modifier,
    miniControlsAutoHide: Boolean = false,
    // Android system PiP owns interaction; drawing desktop controls there would be inert.
    systemPictureInPicture: Boolean = false,
    showVolumeControl: Boolean = true,
) {
    val player = session.player
    val engine = session.engine
    val state by player.state.collectAsState()
    val positionMs by player.currentPositionMillis.collectAsState()
    val properties by player.mediaProperties.collectAsState()
    val durationMs = properties?.durationMillis ?: 0L
    val currentPosition by rememberUpdatedState(positionMs)
    val currentlyPlaying by rememberUpdatedState(state.playWhenReady)
    val audioLevel = remember(player) { player.features[AudioLevelController] }
    val volume = audioLevel?.volume?.collectAsState()?.value ?: 1f
    val scope = rememberCoroutineScope()
    var locked by session::locked
    val reportError by rememberUpdatedState(onError)
    SideEffect { session.reportError = onError }
    var skipLabel by remember { mutableStateOf<String?>(null) }
    var unlockVisible by remember { mutableStateOf(true) }
    var lockTap by remember { mutableLongStateOf(0L) }
    LaunchedEffect(locked, presentation, lockTap) {
        unlockVisible = true
        if (locked && presentation == PlayerPresentation.Full) {
            delay(2_500)
            unlockVisible = false
        }
    }
    var miniControlsVisible by remember { mutableStateOf(true) }
    var miniPointerPressed by remember { mutableStateOf(false) }
    var miniInteraction by remember { mutableStateOf(0L) }
    LaunchedEffect(presentation, miniControlsAutoHide, miniInteraction, miniPointerPressed) {
        if (presentation == PlayerPresentation.Mini && miniControlsAutoHide && !miniPointerPressed) {
            delay(2_500)
            miniControlsVisible = false
        }
    }

    LaunchedEffect(session, source, contentId, startPositionMs) {
        try {
            session.openPlayback(source, contentId, startPositionMs, progressSink, episodes.any { it.contentId == contentId })
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { session.nativeErrorReported = true; runCatching { engine.pause() }; onError(PlayerFailure.Open) }
    }
    LaunchedEffect(session, source, contentId, session.bindingRevision, state.mediaStatus) {
        val error = state.mediaStatus as? org.openani.mediamp.MediaStatus.Error ?: return@LaunchedEffect
        if (session.claimNativeFailure(source, contentId, error)) onError(PlayerFailure.Open)
    }
    LaunchedEffect(session, engine) {
        while (isActive) {
            delay(5_000)
            session.activeContentId?.let {
                try { saveActiveProgress(session) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { reportError(PlayerFailure.Progress) }
            }
        }
    }

    // A threshold/Ended transition is a persistence boundary, not just the 5 s timer.
    // This also updates player-local marks when Auto MARK is disabled.
    val reachedThreshold = state.mediaStatus is org.openani.mediamp.MediaStatus.Ended ||
        (durationMs > 0 && positionMs.toDouble() / durationMs >= app.tvflick.plugin.api.WATCHED_COMPLETION_THRESHOLD)
    LaunchedEffect(session, contentId, reachedThreshold, session.autoWatchListEnabled) {
        if (reachedThreshold) saveActiveProgress(session)
    }

    fun selectVariant(audioId: String? = null, qualityId: String? = null, onApplied: () -> Unit = {}) {
        scope.launch {
            try { if (session.switchVariant(source, contentId, audioId, qualityId)) onApplied() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { session.nativeErrorReported = true; runCatching { engine.pause() }; onError(PlayerFailure.Open) }
        }
    }

    Box(modifier.background(Color.Black).pointerInput(presentation, miniControlsAutoHide) {
        if (presentation == PlayerPresentation.Mini && miniControlsAutoHide) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    miniPointerPressed = event.changes.any { it.pressed }
                    miniControlsVisible = event.type != PointerEventType.Exit || miniPointerPressed
                    miniInteraction++
                    // Observe only: the timeline still owns its drag/click events.
                }
            }
        }
    }) {
        Box(
            Modifier.fillMaxSize().pointerInput(locked, durationMs, presentation) {
                if (presentation != PlayerPresentation.Full) return@pointerInput
                if (locked) {
                    detectTapGestures(onTap = {
                        unlockVisible = true
                        lockTap++
                    })
                    return@pointerInput
                }
                detectTapGestures(
                    onTap = { if (!locked) if (currentlyPlaying) { engine.pause(); scope.launch { saveActiveProgress(session) } } else engine.play() },
                    onDoubleTap = { point ->
                        if (!locked) {
                            val delta = if (point.x < size.width / 2f) -PLAYER_SEEK_STEP_MS else PLAYER_SEEK_STEP_MS
                            engine.seekTo((currentPosition + delta).coerceIn(0, durationMs))
                            skipLabel = if (delta < 0) "−10sec" else "+10sec"
                        }
                    },
                )
            },
        ) { MediampPlayerSurface(player, Modifier.fillMaxSize()) }

        skipLabel?.let { label ->
            SeekFeedback(label, Modifier.align(if (label.startsWith("−")) Alignment.CenterStart else Alignment.CenterEnd))
            LaunchedEffect(label) { delay(650); skipLabel = null }
        }
        if (presentation == PlayerPresentation.Full) {
            FullControls(
                position = positionMs,
                duration = durationMs,
                isPlaying = state.playWhenReady,
                locked = locked,
                unlockVisible = unlockVisible,
                showVolumeControl = showVolumeControl,
                volume = volume,
                maxVolume = audioLevel?.maxVolume ?: 1f,
                qualityOptions = source.qualityOptions.filter { option -> source.variants.isEmpty() || source.variants.any {
                    it.audioId == session.selectedAudioId && it.qualityId == option.id
                } },
                audioTrackOptions = source.audioTrackOptions,
                selectedQualityId = session.selectedQualityId,
                selectedAudioId = session.selectedAudioId,
                selectedSpeedId = session.speed.toString(),
                onSeek = engine::seekTo,
                onPlayPause = {
                    if (currentlyPlaying) {
                        engine.pause()
                        scope.launch { saveActiveProgress(session) }
                    } else engine.play()
                },
                onLock = { unlockVisible = true; locked = it; engine.setLocked(it) },
                onVolumeChange = { audioLevel?.setVolume(it) },
                onSpeedChange = { session.speed = it; engine.setSpeed(it) },
                onQualitySelected = { selectVariant(qualityId = it.id, onApplied = { onQualitySelected(it) }) },
                onAudioTrackSelected = { selectVariant(audioId = it.id, onApplied = { onAudioTrackSelected(it) }) },
                title = title,
                episodes = episodes.map { episode ->
                    episode.copy(isWatched = episode.isWatched || episode.contentId in session.playerWatchedEpisodeIds)
                },
                currentEpisodeId = contentId,
                onEpisodeSelected = onEpisodeSelected,
                autoWatchListEnabled = session.autoWatchListEnabled,
                onAutoWatchListChanged = { session.autoWatchListEnabled = it },
                showRotation = showRotationControl,
                onRotation = onRequestRotation,
                onMini = { scope.launch { saveActiveProgress(session); onRequestMini() } },
                onFullscreen = onRequestFullscreen,
                onClose = {
                    scope.launch {
                        try { session.closeAndPersistPlayback() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { reportError(PlayerFailure.Progress) }
                        finally { onClose() }
                    }
                },
                onDownload = {
                    scope.launch { session.transition.withLock {
                        if (session.activeContentId == contentId && session.openedSource == source)
                            onDownload(session.selectedAudioId, session.selectedQualityId)
                    } }
                },
            )
        } else if (!systemPictureInPicture) {
            AnimatedVisibility(!miniControlsAutoHide || miniControlsVisible, enter = fadeIn(), exit = fadeOut()) {
                MiniControls(title, positionMs, durationMs, engine::seekTo,
                    onFull = { scope.launch { saveActiveProgress(session); onRequestFull() } })
            }
        }
    }
}

/** Shared source binding for Full/Mini: persist the outgoing identity before opening another. */
internal suspend fun FlickPlayerSession.openPlayback(
    source: ResolvedVideoSource, contentId: Long, startPositionMs: Long,
    progressSink: PlaybackProgressSink?, episode: Boolean,
) {
    transition.withLock {
        val changed = openedSource != source || activeContentId != contentId || isEpisode != episode
        if (changed) {
            persistActiveProgress(this)
            activeContentId = null
            activeProgressSink = null
            openedSource = null
            nativeErrorReported = false
            engine.open(source, startPositionMs)
            engine.setSpeed(speed)
            visibility.sourceOpened()
            val initial = source.variants.find { it.url == source.url }
            selectedAudioId = initial?.audioId ?: source.audioTrackOptions.firstOrNull()?.id
            selectedQualityId = initial?.qualityId ?: source.qualityOptions.firstOrNull()?.id
            openedSource = source
        }
        activeContentId = contentId
        isEpisode = episode
        activeProgressSink = progressSink
        if (changed) bindingRevision++
    }
}

/** Preserves user playback intent even while the native decoder is buffering. */
internal suspend fun FlickPlayerSession.switchVariant(
    source: ResolvedVideoSource, contentId: Long, audioId: String? = null, qualityId: String? = null,
): Boolean = transition.withLock {
    if (activeContentId != contentId || openedSource != source) return@withLock false
    val audio = audioId ?: selectedAudioId
    val quality = qualityId ?: selectedQualityId
    val exact = source.variants.find { it.audioId == audio && it.qualityId == quality }
    val variant = exact ?: if (audioId != null && audio != selectedAudioId && qualityId == null) {
        val oldLabel = source.qualityOptions.find { it.id == selectedQualityId }?.label
        val options = source.variants.filter { it.audioId == audio }
        options.find { candidate -> oldLabel != null && source.qualityOptions.any { it.id == candidate.qualityId && it.label == oldLabel } }
            ?: options.firstOrNull()
    } else null
    variant ?: return@withLock false
    if (variant.audioId == selectedAudioId && variant.qualityId == selectedQualityId) return@withLock false
    val position = engine.snapshotIfReady(contentId, autoWatchListEnabled)?.positionMs ?: return@withLock false
    val playWhenReady = player.state.value.playWhenReady
    val audioLevel = player.features[AudioLevelController]
    val volume = audioLevel?.volume?.value
    val mute = audioLevel?.isMute?.value
    persistActiveProgress(this)
    nativeErrorReported = false
    engine.open(source.copy(url = variant.url, kind = variant.kind, headers = variant.headers), position)
    engine.setSpeed(speed)
    if (volume != null) audioLevel.setVolume(volume)
    if (mute != null) audioLevel.setMute(mute)
    if (!playWhenReady) engine.pause()
    visibility.sourceOpened(autoplay = playWhenReady)
    selectedAudioId = variant.audioId
    selectedQualityId = variant.qualityId
    bindingRevision++
    // Keep the original catalog so a subsequent Full/Mini bind cannot reopen the default variant.
    true
}

internal suspend fun FlickPlayerSession.claimNativeFailure(
    source: ResolvedVideoSource, contentId: Long, error: org.openani.mediamp.MediaStatus.Error,
): Boolean = transition.withLock {
    if (activeContentId != contentId || openedSource != source ||
        player.state.value.mediaStatus !== error || nativeErrorReported) return@withLock false
    nativeErrorReported = true
    runCatching { engine.pause() }
    true
}

private suspend fun saveActiveProgress(
    session: FlickPlayerSession,
) {
    try { session.transition.withLock { persistActiveProgress(session) } }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { session.reportError(PlayerFailure.Progress) }
}

private suspend fun persistActiveProgress(session: FlickPlayerSession) {
    val contentId = session.activeContentId ?: return
    val snapshot = session.engine.snapshotIfReady(contentId, session.autoWatchListEnabled) ?: return
    if (session.isEpisode && snapshot.reachesWatchedThreshold() && contentId !in session.playerWatchedEpisodeIds) {
        session.playerWatchedEpisodeIds += contentId
    }
    session.activeProgressSink?.save(snapshot)
}

/** onStop pauses/saves; onStart resumes only playback that the host paused. */
public suspend fun FlickPlayerSession.setHostVisible(visible: Boolean) {
    visibility.setVisible(visible)
}

/** A PiP transition saves position without pausing or reopening the stream. */
public suspend fun FlickPlayerSession.persistPlayback() {
    transition.withLock { persistActiveProgress(this) }
}

/**
 * Used by a platform Mini Player host when its window is explicitly closed.
 * Full and Mini share this exact session, so closing either form has the same
 * persistence boundary and never silently drops the final seek position.
 */
public suspend fun FlickPlayerSession.closeAndPersistPlayback() {
    transition.withLock {
        try { persistActiveProgress(this) }
        finally {
            activeContentId = null
            activeProgressSink = null
            openedSource = null
            engine.close()
        }
    }
}

internal const val PLAYER_SEEK_STEP_MS = 10_000L
