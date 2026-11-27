package app.tvflick

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.*
import app.tvflick.core.tmdb.TmdbEpisode
import app.tvflick.core.tmdb.TmdbSeasonDetails
import app.tvflick.core.tmdb.TmdbSeasonSummary
import app.tvflick.ui.FlickState
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ProfileSettingsTest {
    @Test fun `reset scopes clear only the requested data and preserve user configuration`() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            TvFlickDatabase.Schema.create(driver)
            val db = TvFlickDatabase(driver)
            val repo = SqlDelightLibraryRepository(db)
            val expected = ProfilePreferences(lightTheme = true, language = AppLanguage.Ukrainian, releaseAlerts = true)
            repo.saveProfilePreferences(expected)
            val source = VideoSourceSettings(repo).add("https://example.com")
            val movie = repo.save(LibraryContentDraft(MediaType.Movie, 12, "Movie"), true)
            val show = repo.save(LibraryContentDraft(MediaType.Tv, 34, "Show"), true)
            repo.syncSeason(show.localId, TmdbSeasonSummary(5, 1, null, null, null, 1),
                TmdbSeasonDetails(1, null, listOf(TmdbEpisode(6, 1, null, null, null, null, null))))
            val episode = repo.listEpisodesForShow(show.localId).single()
            repo.setMovieWatched(movie.localId, 500)
            repo.setEpisodeWatched(episode.episodeId, 500)
            repo.setEpisodeFavorite(episode.episodeId, true)

            repo.resetLibrary(setOf(LibraryResetScope.ShowViews))
            assertNull(repo.listEpisodesForShow(show.localId).single().watchedAtEpochMs)
            assertTrue(repo.listEpisodesForShow(show.localId).single().isFavorite)
            assertEquals(500L, repo.listItems().first { it.localId == movie.localId }.movieWatchedAtEpochMs)
            repo.resetLibrary(setOf(LibraryResetScope.MovieViews))
            assertNull(repo.listItems().first { it.localId == movie.localId }.movieWatchedAtEpochMs)
            assertEquals(2, repo.listWatchListItems().size)

            repo.resetLibrary(setOf(LibraryResetScope.Titles))
            assertTrue(repo.listItems().isEmpty())
            assertTrue(repo.listEpisodesForShow(show.localId).isEmpty())
            assertEquals(source, repo.listVideoSourceSites().single())
            assertEquals(expected, SqlDelightLibraryRepository(db).profilePreferences())
        }
    }

    @Test fun `release alerts are opt in and do not repeat after restart`() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            TvFlickDatabase.Schema.create(driver)
            val repo = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            repo.save(LibraryContentDraft(MediaType.Movie, 12, "Today", releaseDate = "2026-11-27"), true)
            repo.save(LibraryContentDraft(MediaType.Movie, 23, "Tomorrow", releaseDate = "2026-11-28"), true)
            repo.save(LibraryContentDraft(MediaType.Movie, 34, "Old", releaseDate = "2026-11-12"), true)
            val show = repo.save(LibraryContentDraft(MediaType.Tv, 56, "Show"), true)
            repo.syncSeason(show.localId, TmdbSeasonSummary(5, 1, null, null, null, 1),
                TmdbSeasonDetails(1, null, listOf(TmdbEpisode(6, 1, null, null, "2026-11-27", null, null))))
            val state = FlickState(repo, null, this)
            state.updateCalendar("2026-11-27")
            assertTrue(state.reloadLibrary())
            state.checkReleaseAlerts()
            assertNull(state.notices.current)

            repo.saveProfilePreferences(ProfilePreferences(releaseAlerts = true))
            assertTrue(state.reloadLibrary())
            state.checkReleaseAlerts()
            assertEquals(2, state.notices.current?.releaseCount)

            val restarted = FlickState(repo, null, this)
            restarted.updateCalendar("2026-11-27")
            assertTrue(restarted.reloadLibrary())
            restarted.checkReleaseAlerts()
            assertNull(restarted.notices.current)
            restarted.updateCalendar("2026-11-28")
            restarted.checkReleaseAlerts()
            assertEquals(1, restarted.notices.current?.releaseCount)
        }
    }
}
