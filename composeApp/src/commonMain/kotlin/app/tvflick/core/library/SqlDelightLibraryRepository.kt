package app.tvflick.core.library

import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.backup.BackupRepository
import app.tvflick.core.backup.SqlDelightBackupRepository
import app.tvflick.core.tmdb.TmdbSeasonDetails
import app.tvflick.core.tmdb.TmdbSeasonSummary
import app.tvflick.core.tmdb.CatalogPreviewCache
import app.tvflick.core.tmdb.SqlDelightCatalogPreviewCache
import app.tvflick.plugin.api.PlaybackSnapshot
import app.tvflick.plugin.api.reachesWatchedThreshold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import app.tvflick.core.tmdb.TmdbEpisode
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** SQLite implementation shared by Android and desktop. */
class SqlDelightLibraryRepository(
    private val database: TvFlickDatabase,
) : LibraryRepository, VideoSourceSiteRepository, PlaybackSettingsRepository, ProfilePreferencesRepository,
    CatalogPreviewCache by SqlDelightCatalogPreviewCache(database),
    BackupRepository by SqlDelightBackupRepository(database) {
    private val queries = database.libraryQueries

    override suspend fun profilePreferences() = databaseCall {
        val values = queries.selectProfileSetting("profile-preferences").executeAsOneOrNull()?.split('|').orEmpty()
        ProfilePreferences(
            lightTheme = values.getOrNull(0) == "1",
            language = AppLanguage.entries.firstOrNull { it.name == values.getOrNull(1) } ?: AppLanguage.English,
            releaseAlerts = values.getOrNull(2) == "1",
        )
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun saveProfilePreferences(preferences: ProfilePreferences) = databaseCall {
        val value = listOf(if (preferences.lightTheme) "1" else "0", preferences.language.name,
            if (preferences.releaseAlerts) "1" else "0").joinToString("|")
        queries.saveProfileSetting("profile-preferences", value, Clock.System.now().toEpochMilliseconds())
        Unit
    }

    override suspend fun resetLibrary(scopes: Set<LibraryResetScope>) = databaseCall {
        database.transaction {
            if (LibraryResetScope.Titles in scopes) {
                queries.clearLibraryEpisodeFavorites()
                queries.clearLibraryEpisodeProgress()
                queries.clearLibraryEpisodes()
                queries.clearLibrarySeasons()
                queries.clearLibraryEntries()
                queries.clearLibraryTitles()
            } else {
                if (LibraryResetScope.ShowViews in scopes) queries.clearShowWatchedMarks()
                if (LibraryResetScope.MovieViews in scopes) queries.clearMovieWatchedMarks()
            }
        }
    }

    override suspend fun releaseAlertHistory() = databaseCall {
        val value = queries.selectProfileSetting("profile-release-alert-history").executeAsOneOrNull()
        value.orEmpty().lineSequence().mapNotNull { row ->
            val parts = row.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun saveReleaseAlertHistory(history: Map<String, String>) = databaseCall {
        queries.saveProfileSetting("profile-release-alert-history",
            history.entries.joinToString("\n") { "${it.key}=${it.value}" }, Clock.System.now().toEpochMilliseconds())
        Unit
    }

    override suspend fun playInsteadOfMark() = databaseCall {
        queries.selectPlaybackAction().executeAsOneOrNull() == "play"
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun setPlayInsteadOfMark(enabled: Boolean) = databaseCall {
        queries.savePlaybackAction(if (enabled) "play" else "mark", Clock.System.now().toEpochMilliseconds())
        Unit
    }

    override suspend fun listSeasons(showContentId: Long): List<TmdbSeasonSummary> = databaseCall {
        queries.selectSeasonsForShow(showContentId).executeAsList().map {
            TmdbSeasonSummary(it.tmdb_season_id, it.season_number, it.name, it.air_date, it.poster_path, it.episode_count.toInt())
        }
    }

    override suspend fun cachedSeason(showContentId: Long, seasonNumber: Long): TmdbSeasonDetails? = databaseCall {
        val summary = queries.selectSeasonsForShow(showContentId).executeAsList().find { it.season_number == seasonNumber }
            ?: return@databaseCall null
        TmdbSeasonDetails(seasonNumber, summary.name, queries.selectSeasonEpisodes(showContentId, seasonNumber).executeAsList().map {
            TmdbEpisode(it.tmdb_episode_id, it.episode_number, it.name, it.overview, it.air_date, it.still_path, it.runtime_minutes)
        })
    }

    override suspend fun listItems(): List<LocalLibraryItem> = databaseCall {
        queries.selectLibraryItems(::mapLibraryItem).executeAsList()
    }

    override suspend fun listWatchListItems(): List<LocalLibraryItem> = databaseCall {
        queries.selectWatchListItems(::mapLibraryItem).executeAsList()
    }

    override suspend fun listUpcomingEpisodes(): List<UpcomingEpisode> = databaseCall {
        queries.selectUpcomingEpisodes { showContentId, showTitle, posterPath, seasonNumber, episodeId, episodeNumber, episodeName, airDate ->
            UpcomingEpisode(
                showContentId = showContentId,
                showTitle = showTitle,
                posterPath = posterPath,
                seasonNumber = seasonNumber,
                episodeId = episodeId,
                episodeNumber = episodeNumber,
                episodeName = episodeName,
                airDate = airDate.orEmpty(),
            )
        }.executeAsList()
    }

    override suspend fun listEpisodesForShow(showContentId: Long): List<EpisodePlaybackState> = databaseCall {
        queries.selectEpisodesForShow(showContentId) { seasonNumber, episodeId, episodeNumber, episodeName, airDate, stillPath, watchedAt, positionMs, runtime, favoriteId ->
            EpisodePlaybackState(
                episodeId = episodeId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                title = episodeName,
                watchedAtEpochMs = watchedAt,
                positionMs = positionMs,
                airDate = airDate,
                stillPath = stillPath,
                runtimeMinutes = runtime,
                isFavorite = favoriteId != 0L,
            )
        }.executeAsList()
    }

    override suspend fun listVideoSourceSites(): List<VideoSourceSite> = databaseCall {
        queries.selectVideoSourceSites(::mapVideoSourceSite).executeAsList()
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun saveVideoSourceSite(site: VideoSourceSiteDraft): VideoSourceSite = databaseCall {
        val now = Clock.System.now().toEpochMilliseconds()
        val existing = queries.selectVideoSourceSite(site.id).executeAsOneOrNull()
        if (existing == null) {
            queries.insertVideoSourceSite(
                site_id = site.id,
                display_name = site.displayName.trim(),
                base_url = site.baseUrl.trim(),
                parser_plugin_id = site.parserPluginId,
                is_enabled = if (site.isEnabled) 1 else 0,
                created_at_epoch_ms = now,
                updated_at_epoch_ms = now,
            )
        } else {
            queries.updateVideoSourceSite(
                display_name = site.displayName.trim(),
                base_url = site.baseUrl.trim(),
                parser_plugin_id = site.parserPluginId,
                is_enabled = if (site.isEnabled) 1 else 0,
                updated_at_epoch_ms = now,
                site_id = site.id,
            )
        }
        queries.selectVideoSourceSite(site.id).executeAsOne().let { row ->
            mapVideoSourceSite(
                id = row.site_id,
                displayName = row.display_name,
                baseUrl = row.base_url,
                parserPluginId = row.parser_plugin_id,
                isEnabled = row.is_enabled,
                createdAtEpochMs = row.created_at_epoch_ms,
                updatedAtEpochMs = row.updated_at_epoch_ms,
            )
        }
    }

    override suspend fun removeVideoSourceSite(siteId: String) = databaseCall {
        database.transaction {
            queries.deleteSourceAdblockSetting("source-adblock:$siteId")
            queries.deleteVideoSourceSite(siteId)
        }
        Unit
    }

    override suspend fun listSourceAdblockSettings(): Map<String, Boolean> = databaseCall {
        queries.selectSourceAdblockSettings().executeAsList().associate {
            it.setting_key.removePrefix("source-adblock:") to (it.setting_value != "0")
        }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun setSourceAdblockEnabled(siteId: String, enabled: Boolean) = databaseCall {
        requireNotNull(queries.selectVideoSourceSite(siteId).executeAsOneOrNull())
        queries.saveSourceAdblockSetting("source-adblock:$siteId", if (enabled) "1" else "0",
            Clock.System.now().toEpochMilliseconds())
        Unit
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun save(content: LibraryContentDraft, addToWatchList: Boolean): LocalLibraryItem = databaseCall {
        val now = Clock.System.now().toEpochMilliseconds()
        database.transactionWithResult {
            val storedContent = queries.selectContentByTmdb(
                media_type = content.mediaType.databaseValue,
                tmdb_id = content.tmdbId,
            ).executeAsOneOrNull()
            val contentId = if (storedContent == null) {
                queries.insertContent(
                    media_type = content.mediaType.databaseValue,
                    tmdb_id = content.tmdbId,
                    title = content.title,
                    original_title = content.originalTitle,
                    release_year = content.releaseYear?.toLong(),
                    poster_path = content.posterPath,
                    backdrop_path = content.backdropPath,
                    overview = content.overview,
                    runtime_minutes = content.runtimeMinutes,
                    metadata_updated_at_epoch_ms = content.metadataUpdatedAtEpochMs,
                    created_at_epoch_ms = now,
                    updated_at_epoch_ms = now,
                    release_date = content.releaseDate,
                    alternative_titles = encodeNames(content.alternativeTitles),
                    genres = encodeNames(content.genres),
                )
                queries.selectContentIdByTmdb(content.mediaType.databaseValue, content.tmdbId).executeAsOne()
            } else {
                queries.updateContentMetadata(
                    title = content.title,
                    original_title = content.originalTitle ?: storedContent.original_title,
                    release_year = content.releaseYear?.toLong() ?: storedContent.release_year,
                    poster_path = content.posterPath ?: storedContent.poster_path,
                    backdrop_path = content.backdropPath ?: storedContent.backdrop_path,
                    overview = content.overview ?: storedContent.overview,
                    runtime_minutes = content.runtimeMinutes ?: storedContent.runtime_minutes,
                    metadata_updated_at_epoch_ms = content.metadataUpdatedAtEpochMs ?: storedContent.metadata_updated_at_epoch_ms,
                    updated_at_epoch_ms = now,
                    release_date = content.releaseDate ?: storedContent.release_date,
                    alternative_titles = content.alternativeTitles.takeIf { it.isNotEmpty() }?.let(::encodeNames) ?: storedContent.alternative_titles,
                    genres = content.genres.takeIf { it.isNotEmpty() }?.let(::encodeNames) ?: storedContent.genres,
                    local_id = storedContent.local_id,
                )
                storedContent.local_id
            }

            val entry = queries.selectLibraryEntryState(contentId).executeAsOneOrNull()
            if (entry == null) {
                queries.insertLibraryEntry(
                    content_id = contentId,
                    is_in_watch_list = if (addToWatchList) 1 else 0,
                    is_favorite = 0,
                    movie_watched_at_epoch_ms = null,
                    movie_position_ms = null,
                    added_at_epoch_ms = now,
                    updated_at_epoch_ms = now,
                )
            } else {
                queries.updateLibraryFlags(
                    is_in_watch_list = if (addToWatchList || entry.is_in_watch_list != 0L) 1 else 0,
                    is_favorite = entry.is_favorite,
                    updated_at_epoch_ms = now,
                    content_id = contentId,
                )
            }
            queries.selectLibraryItems(::mapLibraryItem)
                .executeAsList()
                .first { it.localId == contentId }
        }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun updateMetadataIfPresent(contentId: Long, content: LibraryContentDraft): Boolean = databaseCall {
        database.transactionWithResult {
            val stored = queries.selectContentByTmdb(content.mediaType.databaseValue, content.tmdbId).executeAsOneOrNull()
            if (stored?.local_id != contentId || queries.selectLibraryEntryState(contentId).executeAsOneOrNull() == null)
                return@transactionWithResult false
            queries.updateContentMetadata(
                title = content.title, original_title = content.originalTitle, release_year = content.releaseYear?.toLong(),
                poster_path = content.posterPath, backdrop_path = content.backdropPath, overview = content.overview,
                runtime_minutes = content.runtimeMinutes, metadata_updated_at_epoch_ms = content.metadataUpdatedAtEpochMs,
                updated_at_epoch_ms = Clock.System.now().toEpochMilliseconds(), release_date = content.releaseDate,
                alternative_titles = encodeNames(content.alternativeTitles), genres = encodeNames(content.genres), local_id = contentId)
            true
        }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun setWatchList(contentId: Long, isInWatchList: Boolean) = updateFlags(
        contentId = contentId,
        newWatchList = isInWatchList,
        newFavorite = null,
    )

    @OptIn(ExperimentalTime::class)
    override suspend fun setFavorite(contentId: Long, isFavorite: Boolean) = updateFlags(
        contentId = contentId,
        newWatchList = null,
        newFavorite = isFavorite,
    )

    @OptIn(ExperimentalTime::class)
    override suspend fun setMovieWatched(contentId: Long, watchedAtEpochMs: Long?) = databaseCall {
        queries.updateMovieProgress(
            movie_watched_at_epoch_ms = watchedAtEpochMs,
            movie_position_ms = null,
            updated_at_epoch_ms = Clock.System.now().toEpochMilliseconds(),
            content_id = contentId,
        )
        Unit
    }

    override suspend fun loadMoviePlayback(contentId: Long): SavedPlaybackProgress? = databaseCall {
        queries.selectMoviePlayback(contentId).executeAsOneOrNull()?.let { row ->
            row.movie_position_ms?.let { SavedPlaybackProgress(it, row.movie_watched_at_epoch_ms) }
        }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun saveMoviePlayback(contentId: Long, snapshot: PlaybackSnapshot) = databaseCall {
        val current = queries.selectMoviePlayback(contentId).executeAsOneOrNull() ?: return@databaseCall
        val watchedAt = watchedAt(current.movie_watched_at_epoch_ms, snapshot)
        queries.updateMovieProgress(
            movie_watched_at_epoch_ms = watchedAt,
            movie_position_ms = snapshot.positionMs.coerceAtLeast(0),
            updated_at_epoch_ms = Clock.System.now().toEpochMilliseconds(),
            content_id = contentId,
        )
        Unit
    }

    override suspend fun syncSeason(
        showContentId: Long,
        summary: TmdbSeasonSummary,
        details: TmdbSeasonDetails,
    ) = databaseCall {
        database.transaction {
            val seasonId = queries.selectSeasonId(showContentId, summary.seasonNumber).executeAsOneOrNull()
                ?: run {
                    queries.insertSeason(
                        show_content_id = showContentId,
                        tmdb_season_id = summary.id,
                        season_number = summary.seasonNumber,
                        name = summary.name,
                        air_date = summary.airDate,
                        poster_path = summary.posterPath,
                    )
                    queries.selectSeasonId(showContentId, summary.seasonNumber).executeAsOne()
                }
            queries.updateSeason(summary.id, summary.name, summary.airDate, summary.posterPath, seasonId)
            details.episodes.forEach { episode ->
                val episodeId = queries.selectEpisodeId(seasonId, episode.episodeNumber).executeAsOneOrNull()
                if (episodeId == null) {
                    queries.insertEpisode(
                        season_id = seasonId,
                        tmdb_episode_id = episode.id,
                        episode_number = episode.episodeNumber,
                        name = episode.name,
                        overview = episode.overview,
                        air_date = episode.airDate,
                        still_path = episode.stillPath,
                        runtime_minutes = episode.runtimeMinutes,
                    )
                } else {
                    queries.updateEpisode(
                        episode.id, episode.name, episode.overview, episode.airDate, episode.stillPath,
                        episode.runtimeMinutes, episodeId,
                    )
                }
            }
        }
        Unit
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun setEpisodeWatched(episodeId: Long, watchedAtEpochMs: Long?) = databaseCall {
        val now = Clock.System.now().toEpochMilliseconds()
        if (queries.selectEpisodeProgress(episodeId).executeAsOneOrNull() == null) {
            queries.insertEpisodeProgress(episodeId, watchedAtEpochMs, null, now)
        } else {
            queries.updateEpisodeProgress(watchedAtEpochMs, null, now, episodeId)
        }
        Unit
    }

    override suspend fun setEpisodeFavorite(episodeId: Long, isFavorite: Boolean) = databaseCall {
        if (isFavorite) queries.insertEpisodeFavorite(episodeId) else queries.deleteEpisodeFavorite(episodeId)
        Unit
    }

    override suspend fun loadEpisodePlayback(episodeId: Long): SavedPlaybackProgress? = databaseCall {
        queries.selectEpisodeProgress(episodeId).executeAsOneOrNull()?.let { row ->
            row.position_ms?.let { SavedPlaybackProgress(it, row.watched_at_epoch_ms) }
        }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun saveEpisodePlayback(episodeId: Long, snapshot: PlaybackSnapshot) = databaseCall {
        val now = Clock.System.now().toEpochMilliseconds()
        val current = queries.selectEpisodeProgress(episodeId).executeAsOneOrNull()
        val watchedAt = watchedAt(current?.watched_at_epoch_ms, snapshot)
        if (current == null) {
            queries.insertEpisodeProgress(
                episode_id = episodeId,
                watched_at_epoch_ms = watchedAt,
                position_ms = snapshot.positionMs.coerceAtLeast(0),
                updated_at_epoch_ms = now,
            )
        } else {
            queries.updateEpisodeProgress(
                watched_at_epoch_ms = watchedAt,
                position_ms = snapshot.positionMs.coerceAtLeast(0),
                updated_at_epoch_ms = now,
                episode_id = episodeId,
            )
        }
        Unit
    }

    override suspend fun remove(contentId: Long) = databaseCall {
        queries.deleteLibraryEntry(contentId)
        Unit
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun updateFlags(contentId: Long, newWatchList: Boolean?, newFavorite: Boolean?) = databaseCall {
        val entry = queries.selectLibraryEntryState(contentId).executeAsOneOrNull() ?: return@databaseCall
        queries.updateLibraryFlags(
            is_in_watch_list = if (newWatchList ?: (entry.is_in_watch_list != 0L)) 1 else 0,
            is_favorite = if (newFavorite ?: (entry.is_favorite != 0L)) 1 else 0,
            updated_at_epoch_ms = Clock.System.now().toEpochMilliseconds(),
            content_id = contentId,
        )
        Unit
    }

    private fun mapLibraryItem(
        localId: Long,
        mediaType: String,
        tmdbId: Long,
        title: String,
        releaseYear: Long?,
        posterPath: String?,
        backdropPath: String?,
        isInWatchList: Long,
        isFavorite: Long,
        movieWatchedAtEpochMs: Long?,
        moviePositionMs: Long?,
        addedAtEpochMs: Long,
        originalTitle: String?,
        overview: String?,
        runtimeMinutes: Long?,
        metadataUpdatedAtEpochMs: Long?,
        releaseDate: String?,
        alternativeTitles: String?,
        genres: String?,
    ): LocalLibraryItem = LocalLibraryItem(
        localId = localId,
        content = LibraryContentDraft(
            mediaType = MediaType.fromDatabase(mediaType),
            tmdbId = tmdbId,
            title = title,
            releaseYear = releaseYear?.toInt(),
            posterPath = posterPath,
            backdropPath = backdropPath,
            originalTitle = originalTitle,
            overview = overview,
            runtimeMinutes = runtimeMinutes,
            metadataUpdatedAtEpochMs = metadataUpdatedAtEpochMs,
            releaseDate = releaseDate,
            alternativeTitles = decodeNames(alternativeTitles),
            genres = decodeNames(genres),
        ),
        isInWatchList = isInWatchList != 0L,
        isFavorite = isFavorite != 0L,
        movieWatchedAtEpochMs = movieWatchedAtEpochMs,
        moviePositionMs = moviePositionMs,
        addedAtEpochMs = addedAtEpochMs,
    )

    private fun mapVideoSourceSite(
        id: String,
        displayName: String,
        baseUrl: String,
        parserPluginId: String,
        isEnabled: Long,
        createdAtEpochMs: Long,
        updatedAtEpochMs: Long,
    ): VideoSourceSite = VideoSourceSite(
        id = id,
        displayName = displayName,
        baseUrl = baseUrl,
        parserPluginId = parserPluginId,
        isEnabled = isEnabled != 0L,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    @OptIn(ExperimentalTime::class)
    private fun watchedAt(existing: Long?, snapshot: PlaybackSnapshot): Long? {
        if (existing != null) return existing
        val shouldMarkInLibrary = snapshot.allowLibraryWatchedMark && snapshot.reachesWatchedThreshold()
        return if (shouldMarkInLibrary) Clock.System.now().toEpochMilliseconds() else null
    }

    private suspend fun <T> databaseCall(block: () -> T): T = withContext(Dispatchers.Default) { block() }

}
