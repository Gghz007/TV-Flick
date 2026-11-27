package app.tvflick

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.LibraryContentDraft
import app.tvflick.core.library.MediaType
import app.tvflick.core.library.SqlDelightLibraryRepository
import app.tvflick.core.library.VideoSourceSiteDraft
import app.tvflick.plugin.api.PlaybackSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalDatabaseTest {
    @Test
    fun `episode favorite migration preserves progress and favorites survive metadata refresh`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TvFlickDatabase.Schema.create(driver)
        val database = TvFlickDatabase(driver)
        val repository = SqlDelightLibraryRepository(database)
        val show = repository.save(LibraryContentDraft(MediaType.Tv, 94605, "Arcane"), true)
        val summary = app.tvflick.core.tmdb.TmdbSeasonSummary(1, 1, "Season 1", null, null)
        val details = app.tvflick.core.tmdb.TmdbSeasonDetails(1, "Season 1", listOf(
            app.tvflick.core.tmdb.TmdbEpisode(101, 1, "Episode 1", null, null, null, 45),
        ))
        repository.syncSeason(show.localId, summary, details)
        val episode = repository.listEpisodesForShow(show.localId).single()
        repository.saveEpisodePlayback(episode.episodeId,
            PlaybackSnapshot(episode.episodeId, 80000, 100000, completed = false))
        val before = repository.loadEpisodePlayback(episode.episodeId)

        // Simulate the existing version-one store without touching a user DB.
        driver.execute(null, "DROP TABLE episode_favorite", 0)
        TvFlickDatabase.Schema.migrate(driver, 1, 2)
        repository.setEpisodeFavorite(episode.episodeId, true)
        repository.syncSeason(show.localId, summary, details.copy(name = "Refreshed season"))
        val reopened = SqlDelightLibraryRepository(TvFlickDatabase(driver))
        assertTrue(reopened.listEpisodesForShow(show.localId).single().isFavorite)
        assertEquals(before, reopened.loadEpisodePlayback(episode.episodeId))
        assertEquals(show.localId, reopened.listWatchListItems().single().localId)
        reopened.setEpisodeFavorite(episode.episodeId, false)
        assertEquals(false, reopened.listEpisodesForShow(show.localId).single().isFavorite)
        assertEquals(before, reopened.loadEpisodePlayback(episode.episodeId))
        driver.close()
    }

    @Test
    fun `library reads preserve the metadata needed by offline cards and statistics`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TvFlickDatabase.Schema.create(driver)
        val repository = SqlDelightLibraryRepository(TvFlickDatabase(driver))
        val content = LibraryContentDraft(MediaType.Movie, 550, "Fight Club",
            originalTitle = "Fight Club", releaseYear = 1999, overview = "Stored description",
            runtimeMinutes = 139, metadataUpdatedAtEpochMs = 1234)
        repository.save(content, true)
        assertEquals(content, repository.listItems().single().content)
        assertEquals(content, repository.listWatchListItems().single().content)
        driver.close()
    }

    @Test
    fun `initial schema creates an empty local library`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TvFlickDatabase.Schema.create(driver)
        val database = TvFlickDatabase(driver)

        assertTrue(database.libraryQueries.selectLibraryItems().executeAsList().isEmpty())
        driver.close()
    }

    @Test
    fun `saved title remains in the watch list and retains its flags`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TvFlickDatabase.Schema.create(driver)
        val repository = SqlDelightLibraryRepository(TvFlickDatabase(driver))

        val saved = repository.save(
            content = LibraryContentDraft(
                mediaType = MediaType.Tv,
                tmdbId = 94605,
                title = "Arcane",
                releaseYear = 2021,
            ),
            addToWatchList = true,
        )
        repository.setFavorite(saved.localId, true)

        val item = repository.listWatchListItems().single()
        assertEquals("Arcane", item.content.title)
        assertTrue(item.isInWatchList)
        assertTrue(item.isFavorite)
        driver.close()
    }

    @Test
    fun `playback progress resumes and marks a movie watched at seventy percent`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TvFlickDatabase.Schema.create(driver)
        val repository = SqlDelightLibraryRepository(TvFlickDatabase(driver))
        val saved = repository.save(
            LibraryContentDraft(MediaType.Movie, 550, "Fight Club"),
            addToWatchList = false,
        )

        repository.saveMoviePlayback(
            saved.localId,
            PlaybackSnapshot(
                contentId = saved.localId,
                positionMs = 70_000,
                durationMs = 100_000,
                completed = false,
            ),
        )

        val playback = repository.loadMoviePlayback(saved.localId)
        assertEquals(70_000, playback?.positionMs)
        assertTrue(playback?.watchedAtEpochMs != null)
        driver.close()
    }

    @Test
    fun `disabled auto watchlist keeps resume progress without library watched mark`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TvFlickDatabase.Schema.create(driver)
        val repository = SqlDelightLibraryRepository(TvFlickDatabase(driver))
        val saved = repository.save(
            LibraryContentDraft(MediaType.Movie, 551, "Fight Club alternate session"),
            addToWatchList = false,
        )

        repository.saveMoviePlayback(
            saved.localId,
            PlaybackSnapshot(
                contentId = saved.localId,
                positionMs = 95_000,
                durationMs = 100_000,
                completed = false,
                allowLibraryWatchedMark = false,
            ),
        )

        val playback = repository.loadMoviePlayback(saved.localId)
        assertEquals(95_000, playback?.positionMs)
        assertNull(playback?.watchedAtEpochMs)
        driver.close()
    }

    @Test
    fun `watching three episodes in sequence stores three independent watched marks`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TvFlickDatabase.Schema.create(driver)
        val database = TvFlickDatabase(driver)
        val repository = SqlDelightLibraryRepository(database)
        val show = repository.save(
            LibraryContentDraft(MediaType.Tv, 1000, "Sequential show"),
            addToWatchList = true,
        )
        database.libraryQueries.insertSeason(show.localId, 2000, 1, "Season 1", null, null)
        val seasonId = database.libraryQueries.selectSeasonId(show.localId, 1).executeAsOne()
        val episodeIds = (1L..3L).map { number ->
            database.libraryQueries.insertEpisode(
                season_id = seasonId,
                tmdb_episode_id = 3000 + number,
                episode_number = number,
                name = "Episode $number",
                overview = null,
                air_date = null,
                still_path = null,
                runtime_minutes = 45,
            )
            database.libraryQueries.selectEpisodeId(seasonId, number).executeAsOne()
        }

        // Each entry represents the save performed before the player changes
        // to the next episode. Watch tracking must never collapse them into a
        // show-level flag.
        episodeIds.forEach { episodeId ->
            repository.saveEpisodePlayback(
                episodeId,
                PlaybackSnapshot(episodeId, positionMs = 90_000, durationMs = 100_000, completed = false),
            )
        }

        val watchedCount = database.libraryQueries.selectEpisodesForShow(show.localId)
            .executeAsList()
            .count { it.watched_at_epoch_ms != null }
        assertEquals(3, watchedCount)
        val playerStates = repository.listEpisodesForShow(show.localId)
        assertEquals(listOf(1L, 2L, 3L), playerStates.map { it.episodeNumber })
        assertTrue(playerStates.all { it.watchedAtEpochMs != null })
        driver.close()
    }

    @Test
    fun `user configured source site retains its parser plugin mapping`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TvFlickDatabase.Schema.create(driver)
        val repository = SqlDelightLibraryRepository(TvFlickDatabase(driver))

        repository.saveVideoSourceSite(
            VideoSourceSiteDraft(
                id = "my-source",
                displayName = "My Source",
                baseUrl = "https://example.test",
                parserPluginId = "tvflick.source.example",
            ),
        )

        val saved = repository.listVideoSourceSites().single()
        assertEquals("My Source", saved.displayName)
        assertEquals("tvflick.source.example", saved.parserPluginId)
        assertTrue(saved.isEnabled)
        driver.close()
    }
}
