package app.tvflick.core.library

import app.tvflick.core.tmdb.TmdbSeasonDetails
import app.tvflick.core.tmdb.TmdbSeasonSummary
import app.tvflick.plugin.api.PlaybackProgressSink
import app.tvflick.plugin.api.PlaybackSnapshot

/**
 * Local, platform-independent representation of a TMDB title.  This is the
 * boundary between network models and the SQLite database, so UI code never
 * depends on raw TMDB JSON.
 */
data class LibraryContentDraft(
    val mediaType: MediaType,
    val tmdbId: Long,
    val title: String,
    val originalTitle: String? = null,
    val releaseYear: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val runtimeMinutes: Long? = null,
    val metadataUpdatedAtEpochMs: Long? = null,
    val releaseDate: String? = null,
    val alternativeTitles: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
)

enum class MediaType(val databaseValue: String) {
    Movie("movie"),
    Tv("tv"),
    ;

    companion object {
        fun fromDatabase(value: String): MediaType = entries.first { it.databaseValue == value }
    }
}

data class LocalLibraryItem(
    val localId: Long,
    val content: LibraryContentDraft,
    val isInWatchList: Boolean,
    val isFavorite: Boolean,
    val movieWatchedAtEpochMs: Long?,
    val moviePositionMs: Long?,
    val addedAtEpochMs: Long,
)

data class SavedPlaybackProgress(
    val positionMs: Long,
    val watchedAtEpochMs: Long?,
)

/**
 * Core's source-independent episode data for a Player host. A host maps this
 * to the player strip only after the Source Plugin resolves the episode.
 */
data class EpisodePlaybackState(
    val episodeId: Long,
    val seasonNumber: Long,
    val episodeNumber: Long,
    val title: String?,
    val watchedAtEpochMs: Long?,
    val positionMs: Long?,
    val airDate: String? = null,
    val stillPath: String? = null,
    val runtimeMinutes: Long? = null,
    val isFavorite: Boolean = false,
)

sealed interface PlaybackTarget {
    val id: Long

    data class Movie(override val id: Long) : PlaybackTarget
    data class Episode(override val id: Long) : PlaybackTarget
}

/**
 * A single sink is held by the playback session, so Full Player and Mini
 * Player save into the same target without resetting position or watched state.
 */
class RepositoryPlaybackProgressSink(
    private val repository: LibraryRepository,
    private val target: PlaybackTarget,
) : PlaybackProgressSink {
    override suspend fun save(snapshot: PlaybackSnapshot) {
        require(snapshot.contentId == target.id) { "Playback progress belongs to a different local target" }
        when (target) {
            is PlaybackTarget.Movie -> repository.saveMoviePlayback(target.id, snapshot)
            is PlaybackTarget.Episode -> repository.saveEpisodePlayback(target.id, snapshot)
        }
    }
}

data class UpcomingEpisode(
    val showContentId: Long,
    val showTitle: String,
    val posterPath: String?,
    val seasonNumber: Long,
    val episodeId: Long,
    val episodeNumber: Long,
    val episodeName: String?,
    val airDate: String,
)

/** A user-configured site, mapped to a compiled parser Source Plugin by ID. */
data class VideoSourceSite(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val parserPluginId: String,
    val isEnabled: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class VideoSourceSiteDraft(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val parserPluginId: String,
    val isEnabled: Boolean = true,
)

interface VideoSourceSiteRepository {
    suspend fun listVideoSourceSites(): List<VideoSourceSite>
    suspend fun saveVideoSourceSite(site: VideoSourceSiteDraft): VideoSourceSite
    suspend fun removeVideoSourceSite(siteId: String)
    suspend fun listSourceAdblockSettings(): Map<String, Boolean>
    suspend fun setSourceAdblockEnabled(siteId: String, enabled: Boolean)
}

interface PlaybackSettingsRepository {
    suspend fun playInsteadOfMark(): Boolean
    suspend fun setPlayInsteadOfMark(enabled: Boolean)
}

/**
 * Contract implemented by the SQLDelight-backed store.  Keeping this small
 * lets the Compose screens be tested without a device database.
 */
interface LibraryRepository {
    suspend fun listSeasons(showContentId: Long): List<TmdbSeasonSummary> = emptyList()
    suspend fun cachedSeason(showContentId: Long, seasonNumber: Long): TmdbSeasonDetails? = null
    suspend fun listItems(): List<LocalLibraryItem>
    suspend fun listWatchListItems(): List<LocalLibraryItem>
    suspend fun listUpcomingEpisodes(): List<UpcomingEpisode>
    suspend fun listEpisodesForShow(showContentId: Long): List<EpisodePlaybackState>
    suspend fun save(content: LibraryContentDraft, addToWatchList: Boolean): LocalLibraryItem
    suspend fun updateMetadataIfPresent(contentId: Long, content: LibraryContentDraft): Boolean
    suspend fun setWatchList(contentId: Long, isInWatchList: Boolean)
    suspend fun setFavorite(contentId: Long, isFavorite: Boolean)
    suspend fun setMovieWatched(contentId: Long, watchedAtEpochMs: Long?)
    suspend fun loadMoviePlayback(contentId: Long): SavedPlaybackProgress?
    suspend fun saveMoviePlayback(contentId: Long, snapshot: PlaybackSnapshot)
    suspend fun syncSeason(showContentId: Long, summary: TmdbSeasonSummary, details: TmdbSeasonDetails)
    suspend fun setEpisodeWatched(episodeId: Long, watchedAtEpochMs: Long?)
    suspend fun setEpisodeFavorite(episodeId: Long, isFavorite: Boolean)
    suspend fun loadEpisodePlayback(episodeId: Long): SavedPlaybackProgress?
    suspend fun saveEpisodePlayback(episodeId: Long, snapshot: PlaybackSnapshot)
    suspend fun remove(contentId: Long)
}
