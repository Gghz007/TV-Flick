package app.tvflick.ui

import androidx.compose.runtime.*
import app.tvflick.core.library.*
import app.tvflick.plugin.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class PlaybackKey(val contentId: Long, val episodeId: Long? = null)

data class PlayingContent(
    val key: PlaybackKey,
    val title: String,
    val episodes: List<EpisodePlaybackState>,
    val prepared: PreparedPlayback,
    val progressSink: PlaybackProgressSink,
    val sourceId: String? = null,
)

data class PlaybackSelection(val key: PlaybackKey, val discovery: PlaybackDiscovery, val download: Boolean)
data class DownloadSelection(val source: ResolvedVideoSource, val title: String, val preferredAudioId: String? = null)

/** UI orchestration only. Core owns matching, stream preparation and typed progress writes. */
@Stable
class PlaybackController(
    private val coordinator: PlaybackCoordinator,
    private val library: LibraryRepository,
    private val launch: (suspend () -> Unit) -> Job,
    private val report: (UserProblem) -> Unit,
    private val onProgressChanged: suspend () -> Unit,
) {
    private var request: Job? = null
    private var requestId = 0L
    var revision by mutableStateOf(0L)
        private set
    var busy by mutableStateOf(false)
        private set
    var active by mutableStateOf<PlayingContent?>(null)
        private set
    var selection by mutableStateOf<PlaybackSelection?>(null)
        private set
    var downloadSelection by mutableStateOf<DownloadSelection?>(null)
        private set
    var downloadRequest by mutableStateOf<DownloadSelection?>(null)
        private set

    fun start(key: PlaybackKey, download: Boolean = false) {
        selection = null
        downloadSelection = null
        begin {
            val found = coordinator.discover(key.contentId, key.episodeId)
            val choice = found.automaticChoice
            when {
                choice != null -> open(key, found, choice, download)
                found.choices.isNotEmpty() -> selection = PlaybackSelection(key, found, download)
                else -> report(sourceProblem(found.failures.map { it.reason }))
            }
        }
    }

    fun switchEpisode(episodeId: Long) {
        val playing = active ?: return
        val sourceId = playing.sourceId ?: return
        if (playing.key.episodeId == episodeId || playing.episodes.none { it.episodeId == episodeId }) return
        selection = null
        downloadSelection = null
        val key = PlaybackKey(playing.key.contentId, episodeId)
        begin {
            val found = coordinator.discover(key.contentId, episodeId, sourceId)
            val choice = found.automaticChoice
            if (choice != null) open(key, found, choice, download = false)
            else report(if (found.choices.isNotEmpty()) UserProblem.PlaybackSelectionChanged
                else sourceProblem(found.failures.map { it.reason }))
        }
    }

    fun choose(choice: PlaybackChoice) {
        val selected = selection ?: return
        if (selected.discovery.choices.none { it === choice }) return
        selection = null
        begin { open(selected.key, selected.discovery, choice, selected.download) }
    }

    private fun begin(block: suspend () -> Unit) {
        request?.cancel()
        val id = ++requestId
        busy = true
        request = launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: PlaybackSelectionExpired) { report(UserProblem.PlaybackSelectionChanged) }
            catch (e: SourceFailure) { report(sourceProblem(listOf(e.reason))) }
            catch (_: Exception) { report(UserProblem.PlaybackFailed) }
            finally { if (requestId == id) busy = false }
        }
    }

    private suspend fun open(key: PlaybackKey, found: PlaybackDiscovery, choice: PlaybackChoice, download: Boolean) {
        val prepared = coordinator.prepare(found, choice)
        val item = library.listItems().singleOrNull { it.localId == key.contentId } ?: throw PlaybackSelectionExpired()
        val episodes = if (item.content.mediaType == MediaType.Tv) library.listEpisodesForShow(key.contentId)
            .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber })) else emptyList()
        currentCoroutineContext().ensureActive()
        if (download) {
            val episode = episodes.find { it.episodeId == key.episodeId }
            downloadSelection = DownloadSelection(prepared.source, item.content.title +
                (episode?.let { " S${it.seasonNumber}E${it.episodeNumber}" } ?: ""))
            return
        }
        var markPublished = false
        val sink = PlaybackProgressSink { snapshot ->
            prepared.progressSink.save(snapshot)
            if (!markPublished && snapshot.allowLibraryWatchedMark && snapshot.reachesWatchedThreshold()) {
                onProgressChanged()
                markPublished = true
            }
        }
        active = PlayingContent(key, item.content.title, episodes, prepared, sink, sourceId = choice.sourceId)
    }

    private fun sourceProblem(reasons: List<SourceFailureReason>): UserProblem = when {
        reasons.any { it !in setOf(SourceFailureReason.NotFound, SourceFailureReason.UnsupportedFormat) } -> UserProblem.SourceUnavailable
        SourceFailureReason.UnsupportedFormat in reasons -> UserProblem.SourceUnsupported
        else -> UserProblem.VideoNotFound
    }

    fun dismissSelection() { selection = null }
    fun dismissDownload() { downloadSelection = null }
    fun chooseDownload(source: ResolvedVideoSource) {
        val selected = downloadSelection ?: return
        // The UI passes only a resolver-provided variant. Never accept an arbitrary download URL.
        val original = source.url == selected.source.url && source.kind == selected.source.kind && source.headers == selected.source.headers
        if (!original && selected.source.variants.none {
            it.url == source.url && it.kind == source.kind && it.headers == source.headers
        }) return
        downloadSelection = null
        downloadRequest = DownloadSelection(source, selected.title)
    }
    fun consumeDownload(expected: DownloadSelection) { if (downloadRequest === expected) downloadRequest = null }
    /** Player selections are already confirmed. Only card downloads need the audio/quality menus. */
    fun downloadCurrent(key: PlaybackKey, audioId: String?, qualityId: String?) {
        val playing = active?.takeIf { it.key == key } ?: return
        val source = playing.prepared.source
        val selected = if (source.variants.isEmpty()) source else {
            val variant = source.variants.find { it.audioId == audioId && it.qualityId == qualityId }
                ?: return report(UserProblem.PlaybackSelectionChanged)
            source.copy(url = variant.url, kind = variant.kind, headers = variant.headers)
        }
        val episode = playing.episodes.find { it.episodeId == key.episodeId }
        downloadSelection = null
        downloadRequest = DownloadSelection(selected, playing.title +
            (episode?.let { " S${it.seasonNumber}E${it.episodeNumber}" } ?: ""))
    }

    /** Explicit player close happens after the player has saved its final snapshot. */
    fun closed() {
        cancelRequest()
        active = null
        launch { onProgressChanged() }
    }

    /** Source edits invalidate discoveries without discarding progress of already playing media. */
    fun sourcesChanged() {
        cancelRequest()
        revision++
        downloadSelection = null
        downloadRequest = null
    }

    fun resetForRestore() { sourcesChanged(); active = null }

    private fun cancelRequest() {
        requestId++
        request?.cancel()
        request = null
        busy = false
        selection = null
    }
}
