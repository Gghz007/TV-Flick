package app.tvflick.ui

import androidx.compose.runtime.*
import app.tvflick.core.library.*
import app.tvflick.core.tmdb.*
import app.tvflick.plugin.engine.PluginEngine
import app.tvflick.plugin.engine.PluginRegistry
import app.tvflick.plugin.source.AdaptiveSourcePlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

enum class Destination(val label: String) { Shows("Shows"), Movies("Movies"), Search("Search"), Profile("Profile") }
enum class LibraryTab(val label: String) { WatchList("WATCH LIST"), Upcoming("UPCOMING") }
enum class SettingsTab { PROFILE, BACKUP, DATABASE, ADBLOCK }

@Stable
class FlickState(
    val repository: LibraryRepository?, val tmdb: TmdbClient?, private val scope: CoroutineScope,
    val playbackCoordinator: PlaybackCoordinator? = null,
) {
    val notices = UserNotices()
    private val profileStore = repository as? ProfilePreferencesRepository
    private val profileMutex = Mutex()
    var profilePreferences by mutableStateOf(ProfilePreferences())
        private set
    val hasProfileSettings get() = profileStore != null
    var resettingLibrary by mutableStateOf(false)
        private set

    fun toggleLightTheme() = changeProfilePreferences { it.copy(lightTheme = !it.lightTheme) }
    fun chooseAppLanguage(language: AppLanguage) = changeProfilePreferences { it.copy(language = language) }
    fun toggleReleaseAlerts() = changeProfilePreferences { it.copy(releaseAlerts = !it.releaseAlerts) }

    private fun changeProfilePreferences(update: (ProfilePreferences) -> ProfilePreferences) = libraryAction {
        val store = profileStore ?: return@libraryAction
        profileMutex.withLock {
            val next = update(store.profilePreferences())
            store.saveProfilePreferences(next)
            profilePreferences = next
        }
    }

    fun resetLibrary(scopes: Set<LibraryResetScope>) {
        val store = profileStore ?: return
        if (scopes.isEmpty() || libraryRestoring || resettingLibrary) return
        resettingLibrary = true
        scope.launch {
            try {
                withLibraryRestore { store.resetLibrary(scopes) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notices.show(UserProblem.LibraryUpdateFailed) }
            finally { resettingLibrary = false }
        }
    }

    internal suspend fun checkReleaseAlerts() {
        if (!profilePreferences.releaseAlerts || !libraryLoaded || libraryRestoring) return
        val store = profileStore ?: return
        val today = calendarEpochDay(calendarDate) ?: return
        fun recent(date: String?): Boolean =
            date?.let(::calendarEpochDay)?.let { it in (today - 6)..today } == true
        val candidates = buildMap {
            for (item in library) {
                if (!item.isInWatchList) continue
                if (item.content.mediaType == MediaType.Movie) {
                    val date = item.content.releaseDate
                    if (item.movieWatchedAtEpochMs == null && date != null && recent(date)) {
                        put("movie:${item.content.tmdbId}", date)
                    }
                } else {
                    for (episode in episodes[item.localId].orEmpty()) {
                        val date = episode.airDate
                        if (episode.watchedAtEpochMs == null && date != null && recent(date)) {
                            put("tv:${item.content.tmdbId}:${episode.seasonNumber}:${episode.episodeNumber}", date)
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) return
        val prior = store.releaseAlertHistory().filterValues(::recent)
        val new = candidates.filterKeys { it !in prior }
        if (new.isEmpty() || !profilePreferences.releaseAlerts || libraryRestoring) return
        // Leave pending releases for the next scan if another notice is visible.
        if (notices.showReleases(new.size)) store.saveReleaseAlertHistory(prior + new)
    }
    val playback = if (playbackCoordinator != null && repository != null)
        PlaybackController(playbackCoordinator, repository, ::launchLibraryAction, notices::show, ::refresh) else null
    var playInsteadOfMark by mutableStateOf(false)
        private set
    val hasVideoSources get() = sourceSites.any { it.isEnabled } && playback != null
    fun togglePlayInsteadOfMark() = libraryAction {
        val settings = repository as? PlaybackSettingsRepository ?: return@libraryAction
        settings.setPlayInsteadOfMark(!playInsteadOfMark)
        playInsteadOfMark = settings.playInsteadOfMark()
    }
    private val libraryJobs = mutableSetOf<Job>()
    var libraryRestoring by mutableStateOf(false)
        private set
    private var libraryRevision = 0L

    private fun libraryAction(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(start = CoroutineStart.LAZY) {
            if (libraryRestoring) return@launch
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                libraryError = "Could not save library changes"
                notices.show(UserProblem.LibraryUpdateFailed)
            }
        }.also { job ->
            libraryJobs += job
            job.invokeOnCompletion { libraryJobs -= job }
            job.start()
        }

    internal fun launchLibraryAction(block: suspend () -> Unit): Job = libraryAction { block() }

    suspend fun withLibraryRestore(block: suspend () -> Unit) {
        check(!libraryRestoring) { "Restore already in progress" }
        libraryRestoring = true
        libraryRevision++
        try {
            playback?.resetForRestore()
            // Stop old playback sinks before any restored rows can reuse their local IDs.
            playbackCoordinator?.invalidate()
            libraryJobs.toList().forEach { it.cancelAndJoin() }
            selectedTitle = null
            profileCollection = null
            catalogCollection = null
            block()
        } finally {
            // A database transaction may have committed even when its caller was cancelled.
            withContext(NonCancellable) {
                libraryRestoring = false
                reloadLibrary()
            }
        }
    }
    var destination by mutableStateOf(Destination.Shows)
    var libraryTab by mutableStateOf(LibraryTab.WatchList)
    var profileOpen by mutableStateOf(false)
    var catalogCollection by mutableStateOf<CatalogFeed?>(null)
    var settingsOpen by mutableStateOf(false)
    var settingsTab by mutableStateOf(SettingsTab.PROFILE)
    var profileCollection by mutableStateOf<String?>(null)
    var selectedTitle by mutableStateOf<LibraryContentDraft?>(null)
    var library by mutableStateOf(emptyList<LocalLibraryItem>())
    var libraryLoaded by mutableStateOf(false)
    var calendarDate by mutableStateOf(localToday())
        private set
    fun updateCalendar(date: String = localToday()) { calendarDate = date }
    var upcoming by mutableStateOf(emptyList<UpcomingEpisode>())
    var query by mutableStateOf("")
    var catalogFeeds by mutableStateOf(CatalogCategory.entries.filterNot { it.needsSeed }.map { newCatalogFeed(it) })
        private set
    var results by mutableStateOf(emptyList<TmdbSearchResult>())
    var searchBusy by mutableStateOf(false)
    var completedSearchQuery by mutableStateOf<String?>(null)
    var searchError by mutableStateOf<String?>(null)
    var sourceSites by mutableStateOf(emptyList<VideoSourceSite>())
    var sourceInput by mutableStateOf("")
    var sourceBusy by mutableStateOf(false)
        private set
    var sourceError by mutableStateOf<String?>(null)
    var sourceAdblockSettings by mutableStateOf(emptyMap<String, Boolean>())
        private set

    fun addSource() = changeSource {
        try { normalizeSourceAddress(sourceInput) }
        catch (_: Exception) { sourceError = "Enter a valid site URL"; notices.show(UserProblem.InvalidSourceAddress); return@changeSource }
        try { VideoSourceSettings(it).add(sourceInput) }
        catch (e: IllegalArgumentException) { sourceError = "Source already added"; notices.show(UserProblem.DuplicateSource); return@changeSource }
        sourceInput = ""
    }

    fun toggleSource(site: VideoSourceSite) = changeSource {
        VideoSourceSettings(it).setEnabled(site, !site.isEnabled)
    }

    fun removeSource(site: VideoSourceSite) = changeSource { it.removeVideoSourceSite(site.id) }

    fun toggleSourceAdblock(site: VideoSourceSite) = changeSource {
        it.setSourceAdblockEnabled(site.id, !(sourceAdblockSettings[site.id] ?: true))
    }

    private fun changeSource(action: suspend (VideoSourceSiteRepository) -> Unit) {
        if (sourceBusy || libraryRestoring) return
        val repo = repository as? VideoSourceSiteRepository ?: run {
            sourceError = "Local database is unavailable"; notices.show(UserProblem.LibraryUnavailable); return
        }
        sourceBusy = true
        sourceError = null
        libraryAction {
            try {
                action(repo)
                playback?.sourcesChanged()
                sourceSites = repo.listVideoSourceSites()
                sourceAdblockSettings = repo.listSourceAdblockSettings()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { sourceError = "Could not save source settings"; notices.show(UserProblem.SourceSettingsFailed) }
            finally { sourceBusy = false }
        }
    }
    var episodes by mutableStateOf<Map<Long, List<EpisodePlaybackState>>>(emptyMap())
    var libraryError by mutableStateOf<String?>(null)

    private val catalogRequests = Semaphore(3)

    private fun newCatalogFeed(category: CatalogCategory, seedId: Long? = null) = CatalogFeed(category, seedId,
        cache = repository as? CatalogPreviewCache) { type, page, seed ->
        catalogRequests.withPermit {
        checkNotNull(tmdb) { "TMDB is not configured" }.catalogPage(type, page, seed)
        }
    }

    // Requests belong to the app, so lazy-row disposal cannot cancel a cache refresh.
    fun loadCatalogPreview(feed: CatalogFeed) = scope.launch {
        feed.loadInitial()
        if (feed.error != null) notices.show(UserProblem.CatalogUnavailable)
    }
    fun loadCatalogNext(feed: CatalogFeed) = scope.launch {
        feed.loadNext()
        if (feed.error != null) notices.show(UserProblem.CatalogUnavailable)
    }

    fun preloadSearch() {
        if (tmdb == null) return
        catalogFeeds.take(3).forEach { feed -> if (!feed.loaded && !feed.busy) loadCatalogPreview(feed) }
    }

    suspend fun pruneCatalogCache() {
        try { (repository as? CatalogPreviewCache)?.prunePreviews() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Catalog loading still works when the optional cache is unavailable. */ }
    }

    private fun updateRecommendationFeeds() {
        val personalized = listOf(CatalogCategory.RecommendedShows, CatalogCategory.RecommendedMovies).mapNotNull { category ->
            val seed = library.filter { item ->
                item.content.mediaType.databaseValue == category.mediaType &&
                    (item.isFavorite || item.movieWatchedAtEpochMs != null ||
                        episodes[item.localId].orEmpty().any { it.watchedAtEpochMs != null })
            }.maxByOrNull { item ->
                maxOf(item.addedAtEpochMs, item.movieWatchedAtEpochMs ?: 0,
                    episodes[item.localId].orEmpty().maxOfOrNull { it.watchedAtEpochMs ?: 0 } ?: 0)
            } ?: return@mapNotNull null
            catalogFeeds.firstOrNull { it.category == category && it.seedId == seed.content.tmdbId }
                ?: newCatalogFeed(category, seed.content.tmdbId)
        }
        catalogFeeds = personalized + catalogFeeds.filterNot { it.category.needsSeed }
    }

    val visibleLibraryItems: List<LocalLibraryItem>
        get() {
            val media = if (destination == Destination.Movies) MediaType.Movie else MediaType.Tv
            return library.filter { it.content.mediaType == media && it.isInWatchList }
        }

    val visibleUpcoming: List<UpcomingEpisode>
        get() {
            return visibleLibraryItems.filter { it.content.mediaType == MediaType.Tv }.flatMap { item ->
                episodes[item.localId].orEmpty().filter {
                    it.watchedAtEpochMs == null && (it.airDate?.let { date -> daysUntil(date, calendarDate) } ?: 0) > 0
                }.map { UpcomingEpisode(item.localId, item.content.title, item.content.posterPath,
                    it.seasonNumber, it.episodeId, it.episodeNumber, it.title, it.airDate!!) }
            }
        }

    val visibleUpcomingMovies: List<LocalLibraryItem>
        get() = visibleLibraryItems.filter {
            it.content.mediaType == MediaType.Movie && it.movieWatchedAtEpochMs == null &&
                (it.content.releaseDate?.let { date -> daysUntil(date, calendarDate) } ?: 0) > 0
        }.sortedBy { it.content.releaseDate }

    val watchListEntries: List<Pair<LocalLibraryItem, EpisodePlaybackState?>>
        get() = visibleLibraryItems.flatMap { item ->
            when {
                item.content.mediaType == MediaType.Movie ->
                    if (item.movieWatchedAtEpochMs == null &&
                        (item.content.releaseDate?.let { daysUntil(it, calendarDate) } ?: 0) == 0L) listOf(item to null) else emptyList()
                episodes[item.localId].isNullOrEmpty() ->
                    if ((item.content.releaseDate?.let { daysUntil(it, calendarDate) } ?: 0) == 0L) listOf(item to null) else emptyList()
                else -> listOfNotNull(nextEpisode(item)?.let { item to it })
            }
        }

    val showEmptyReference: Boolean
        get() = when (destination) {
            Destination.Shows, Destination.Movies -> libraryLoaded && libraryError == null &&
                if (libraryTab == LibraryTab.WatchList) watchListEntries.isEmpty() else visibleUpcoming.isEmpty() && visibleUpcomingMovies.isEmpty()
            Destination.Search -> query.trim().length >= 2 && completedSearchQuery == query.trim() &&
                !searchBusy && searchError == null && results.isEmpty()
            Destination.Profile -> false
        }

    suspend fun refresh() {
        if (libraryRestoring) return
        val revision = libraryRevision
        val nextLibrary = repository?.listItems().orEmpty()
        val nextUpcoming = repository?.listUpcomingEpisodes().orEmpty()
        val nextSources = (repository as? VideoSourceSiteRepository)?.listVideoSourceSites().orEmpty()
        val nextAdblock = (repository as? VideoSourceSiteRepository)?.listSourceAdblockSettings().orEmpty()
        val nextPlaybackAction = (repository as? PlaybackSettingsRepository)?.playInsteadOfMark() ?: false
        val nextEpisodes = nextLibrary.filter { it.content.mediaType == MediaType.Tv }.associate {
            it.localId to repository?.listEpisodesForShow(it.localId).orEmpty()
        }
        // Publish only a complete read; a failed query must not replace part of the visible library.
        if (revision != libraryRevision || libraryRestoring) return
        library = nextLibrary
        upcoming = nextUpcoming
        sourceSites = nextSources
        sourceAdblockSettings = nextAdblock
        playInsteadOfMark = nextPlaybackAction
        episodes = nextEpisodes
        libraryLoaded = true
        updateRecommendationFeeds()
    }

    suspend fun reloadLibrary(): Boolean = try {
        checkNotNull(repository)
        profileStore?.let { store -> profileMutex.withLock { profilePreferences = store.profilePreferences() } }
        refresh()
        libraryError = null
        true
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) {
        libraryError = "Could not load library"
        notices.show(UserProblem.LibraryUnavailable)
        false
    }

    fun retryLibrary() = libraryAction { if (reloadLibrary()) startMetadataRefresh() }

    fun addToLibrary(title: LibraryContentDraft) = libraryAction {
        val repo = repository ?: return@libraryAction
        repo.save(title, true)
        libraryTab = LibraryTab.WatchList
        libraryError = null
        refresh()
        updateTitleMetadata(title)
    }

    /** An implicit addition (favorite or manual mark) is queued, while an existing stop is preserved. */
    internal suspend fun ensureLibraryItem(title: LibraryContentDraft): LocalLibraryItem {
        val repo = requireNotNull(repository)
        repo.listItems().firstOrNull { it.content.tmdbId == title.tmdbId && it.content.mediaType == title.mediaType }?.let { return it }
        val item = repo.save(title, true)
        refresh()
        libraryAction { updateTitleMetadata(title) }
        return item
    }

    internal suspend fun applyTitleAction(title: LibraryContentDraft, action: TitleAction) {
        val repo = requireNotNull(repository)
        val existing = repo.listItems().firstOrNull { it.content.tmdbId == title.tmdbId && it.content.mediaType == title.mediaType }
        when (action) {
            TitleAction.Favorite -> {
                val item = existing ?: ensureLibraryItem(title)
                repo.setFavorite(item.localId, !item.isFavorite)
            }
            TitleAction.WatchLater -> {
                val hasProgress = existing?.let { item ->
                    item.movieWatchedAtEpochMs != null || (item.moviePositionMs ?: 0) > 0 ||
                        repo.listEpisodesForShow(item.localId).any { it.watchedAtEpochMs != null || (it.positionMs ?: 0) > 0 }
                } ?: false
                if (existing?.isInWatchList == true && !hasProgress) repo.setWatchList(existing.localId, false)
                else addToLibrary(title).join()
            }
            TitleAction.StopWatching -> if (existing != null) {
                if (existing.isInWatchList) repo.setWatchList(existing.localId, false) else addToLibrary(title).join()
            }
            TitleAction.Remove -> existing?.let { repo.remove(it.localId) }
            TitleAction.ResetView -> existing?.let { item ->
                if (title.mediaType == MediaType.Movie) repo.setMovieWatched(item.localId, null)
                else repo.listEpisodesForShow(item.localId).forEach { repo.setEpisodeWatched(it.episodeId, null) }
            }
        }
        refresh()
    }

    private val refreshingMetadata = mutableSetOf<Pair<MediaType, Long>>()
    private var metadataRefreshJob: Job? = null
    fun startMetadataRefresh() {
        if (libraryRestoring) return
        if (metadataRefreshJob?.isActive != true) metadataRefreshJob = libraryAction { refreshStaleMetadata() }
    }

    @OptIn(ExperimentalTime::class)
    suspend fun refreshStaleMetadata() {
        if (tmdb == null) return
        libraryError = null
        val now = Clock.System.now().toEpochMilliseconds()
        for (item in repository?.listItems().orEmpty()) {
            val updated = item.content.metadataUpdatedAtEpochMs
            if (updated == null || now < updated || now - updated >= 86_400_000L) {
                if (!updateTitleMetadata(item.content)) break // Don't repeatedly hit an unavailable API for every title.
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun updateTitleMetadata(title: LibraryContentDraft): Boolean {
        val repo = repository ?: return false
        val key = title.mediaType to title.tmdbId
        if (!refreshingMetadata.add(key)) return true
        try {
            // Only an addition fetches metadata. Opening the library reads SQLite.
            val details = tmdb?.titleDetails(title.mediaType.databaseValue, title.tmdbId) ?: return false
            // A slow metadata response must not recreate a removed title or undo a user's Stop watching.
            val item = repo.listItems().find { it.content.tmdbId == title.tmdbId && it.content.mediaType == title.mediaType } ?: return true
            val updatedContent = title.copy(title = details.title, originalTitle = details.originalTitle,
                overview = details.overview, runtimeMinutes = details.runtimeMinutes,
                releaseYear = details.releaseDate?.take(4)?.toIntOrNull() ?: title.releaseYear,
                releaseDate = details.releaseDate, alternativeTitles = details.alternativeTitles, genres = details.genres,
                posterPath = details.posterPath, backdropPath = details.backdropPath, metadataUpdatedAtEpochMs = null)
            if (!repo.updateMetadataIfPresent(item.localId, updatedContent)) return true
            var allSeasonsUpdated = true
            details.seasons.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }.forEach { summary ->
                try {
                    repo.syncSeason(item.localId, summary, tmdb.seasonDetails(title.tmdbId, summary.seasonNumber))
                    refresh() // Publish ready episodes without waiting for every season of a long show.
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { allSeasonsUpdated = false; libraryError = "Could not update some episodes"; notices.show(UserProblem.MetadataUnavailable) }
            }
            if (allSeasonsUpdated) repo.updateMetadataIfPresent(item.localId,
                updatedContent.copy(metadataUpdatedAtEpochMs = Clock.System.now().toEpochMilliseconds()))
            refresh()
            return allSeasonsUpdated
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { libraryError = "Could not update title"; notices.show(UserProblem.MetadataUnavailable); refresh(); return false }
        finally { refreshingMetadata.remove(key) }
    }

    @OptIn(ExperimentalTime::class)
    fun markNext(item: LocalLibraryItem) = libraryAction {
        if (item.content.mediaType == MediaType.Movie) {
            repository?.setMovieWatched(item.localId, if (item.movieWatchedAtEpochMs != null) null else Clock.System.now().toEpochMilliseconds())
        } else nextEpisode(item)?.let { repository?.setEpisodeWatched(it.episodeId, Clock.System.now().toEpochMilliseconds()) }
        refresh()
    }

    @OptIn(ExperimentalTime::class)
    fun markEpisode(episode: EpisodePlaybackState) = libraryAction {
        repository?.setEpisodeWatched(episode.episodeId, Clock.System.now().toEpochMilliseconds())
        refresh()
    }

    fun releasedEpisodes(item: LocalLibraryItem) = episodes[item.localId].orEmpty().filter {
        it.watchedAtEpochMs == null && it.airDate?.let { date -> daysUntil(date, calendarDate) == 0L } == true
    }.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))

    fun remainingAfterNext(item: LocalLibraryItem) = (releasedEpisodes(item).size - 1).coerceAtLeast(0)

    fun nextEpisode(item: LocalLibraryItem) = releasedEpisodes(item).firstOrNull()

    fun select(destination: Destination) {
        if (destination == Destination.Profile) {
            profileOpen = true
            settingsOpen = false
            profileCollection = null
        } else this.destination = destination
    }

    internal fun selectAndroidPage(destination: Destination) {
        selectedTitle = null
        catalogCollection = null
        profileOpen = false
        select(destination)
    }

    fun openRecommendations() {
        query = ""
        catalogCollection = null
        selectedTitle = null
        profileOpen = false
        select(Destination.Search)
    }
}

fun TmdbSearchResult.draft() = LibraryContentDraft(
    mediaType = if (mediaType == "movie") MediaType.Movie else MediaType.Tv,
    tmdbId = id.toLong(), title = title, releaseYear = subtitle.toIntOrNull(),
    posterPath = posterPath, backdropPath = backdropPath, overview = overview,
    releaseDate = releaseDate,
)

@Composable
fun rememberFlickState(key: String, repository: LibraryRepository?): FlickState {
    val scope = rememberCoroutineScope()
    val adBlock = remember { app.tvflick.plugin.adblock.RuleBasedAdBlockPlugin() }
    val source = remember(repository) { if (repository is VideoSourceSiteRepository) AdaptiveSourcePlugin() else null }
    DisposableEffect(source) { onDispose { source?.close() } }
    val state = remember(key, repository, source) {
        val coordinator = if (source != null && repository is VideoSourceSiteRepository) {
            PlaybackCoordinator(repository, repository, PluginEngine(PluginRegistry(
                videoSources = listOf(source), adBlockPlugins = listOf(adBlock))))
        } else null
        FlickState(repository, key.takeIf(String::isNotBlank)?.let(::TmdbClient), scope, coordinator)
    }
    LoadAdBlockRules(adBlock) { state.notices.show(UserProblem.SourceSettingsFailed) }
    LaunchedEffect(state) {
        state.preloadSearch()
        val libraryReady = state.reloadLibrary()
        // Library data may add personalized feeds after the general categories start loading.
        state.preloadSearch()
        state.pruneCatalogCache()
        if (libraryReady) state.startMetadataRefresh()
    }
    LaunchedEffect(state) {
        var minutes = 0
        while (isActive) {
            delay(60_000)
            val dateChanged = state.calendarDate != localToday()
            state.updateCalendar()
            if (dateChanged || ++minutes >= 60) { minutes = 0; state.startMetadataRefresh() }
        }
    }
    LaunchedEffect(state, state.profilePreferences.releaseAlerts) {
        if (!state.profilePreferences.releaseAlerts) return@LaunchedEffect
        while (isActive) {
            try { state.checkReleaseAlerts() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.notices.show(UserProblem.LibraryUpdateFailed) }
            delay(15_000)
        }
    }
    return state
}

