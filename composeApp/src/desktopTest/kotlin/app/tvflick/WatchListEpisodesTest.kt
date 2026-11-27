package app.tvflick

import app.tvflick.core.library.*
import app.tvflick.ui.FlickState
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WatchListEpisodesTest {
    @Test fun `favorites and manual episode marks queue new titles and preserve explicit stops`() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        try {
            app.tvflick.core.database.TvFlickDatabase.Schema.create(driver)
            val repo = SqlDelightLibraryRepository(app.tvflick.core.database.TvFlickDatabase(driver))
            val state = FlickState(repo, null, this)
            val show = LibraryContentDraft(MediaType.Tv, 11, "Favorite show")
            state.applyTitleAction(show, app.tvflick.ui.TitleAction.Favorite)
            assertTrue(state.watchListEntries.single().first.isFavorite)
            val item = state.ensureLibraryItem(show)
            repo.syncSeason(item.localId, app.tvflick.core.tmdb.TmdbSeasonSummary(10, 1, "One", "2000-01-01", null),
                app.tvflick.core.tmdb.TmdbSeasonDetails(1, "One", (1L..3L).map {
                    app.tvflick.core.tmdb.TmdbEpisode(it, it, "Episode $it", null, "2000-01-01", null, 40)
                }))
            repo.setEpisodeWatched(repo.listEpisodesForShow(item.localId).first().episodeId, 1)
            state.refresh()
            assertEquals(2L, state.watchListEntries.single().second!!.episodeNumber)
            state.applyTitleAction(show, app.tvflick.ui.TitleAction.StopWatching)
            assertTrue(state.watchListEntries.isEmpty())
            state.applyTitleAction(show, app.tvflick.ui.TitleAction.Favorite)
            assertFalse(state.ensureLibraryItem(show).isInWatchList)
            state.applyTitleAction(show, app.tvflick.ui.TitleAction.StopWatching)
            assertEquals(2L, state.watchListEntries.single().second!!.episodeNumber)

            val markedShow = state.ensureLibraryItem(LibraryContentDraft(MediaType.Tv, 12, "Manual mark"))
            assertTrue(markedShow.isInWatchList)
            val movie = LibraryContentDraft(MediaType.Movie, 13, "Favorite movie")
            state.destination = app.tvflick.ui.Destination.Movies
            state.applyTitleAction(movie, app.tvflick.ui.TitleAction.Favorite)
            assertEquals("Favorite movie", state.watchListEntries.single().first.content.title)
            val savedMovie = state.ensureLibraryItem(movie)
            repo.setMovieWatched(savedMovie.localId, 1)
            state.applyTitleAction(movie, app.tvflick.ui.TitleAction.ResetView)
            assertEquals("Favorite movie", state.watchListEntries.single().first.content.title)
            state.applyTitleAction(LibraryContentDraft(MediaType.Movie, 14, "Watch later"), app.tvflick.ui.TitleAction.WatchLater)
            assertEquals(2, state.watchListEntries.size)
            state.applyTitleAction(LibraryContentDraft(MediaType.Movie, 15, "Never saved"), app.tvflick.ui.TitleAction.Remove)
            assertFalse(repo.listItems().any { it.content.tmdbId == 15L })
        } finally { driver.close() }
    }

    @Test fun `one next episode per show with remaining released count across seasons`() = runBlocking {
        val state = FlickState(null, null, this)
        val show = LocalLibraryItem(1, LibraryContentDraft(MediaType.Tv, 1, "Show"), true, false, null, null, 1)
        val episodes = (1L..110L).map {
            EpisodePlaybackState(it, (it - 1) / 25 + 1, (it - 1) % 25 + 1, "Episode $it", null, null,
                if (it > 100) "2999-01-01" else "2000-01-01")
        }.reversed()
        state.episodes = mapOf(1L to episodes)
        state.library = listOf(show)
        assertEquals(1L, state.watchListEntries.single().second!!.episodeId)
        assertEquals(99, state.remainingAfterNext(show))
        state.episodes = mapOf(1L to episodes.map { if (it.episodeId <= 15) it.copy(watchedAtEpochMs = 1) else it })
        assertEquals(16L, state.watchListEntries.single().second!!.episodeId)
        assertEquals(84, state.remainingAfterNext(show))
        state.episodes = mapOf(1L to episodes.map { if (it.episodeId <= 25) it.copy(watchedAtEpochMs = 1) else it })
        assertEquals(2L, state.watchListEntries.single().second!!.seasonNumber)
        assertEquals(1L, state.watchListEntries.single().second!!.episodeNumber)
        state.episodes = mapOf(1L to episodes.map { if (it.episodeId <= 95) it.copy(watchedAtEpochMs = 1) else it })
        assertEquals(96L, state.watchListEntries.single().second!!.episodeId)
        assertEquals(4, state.remainingAfterNext(show))
        state.episodes = mapOf(1L to episodes.map { if (it.episodeId <= 99) it.copy(watchedAtEpochMs = 1) else it })
        assertEquals(100L, state.watchListEntries.single().second!!.episodeId)
        assertEquals(0, state.remainingAfterNext(show))
        state.episodes = mapOf(1L to episodes.map { if (it.episodeId <= 100) it.copy(watchedAtEpochMs = 1) else it })
        assertTrue(state.watchListEntries.isEmpty())
    }

    @Test fun `new titles are visible before episode metadata arrives and adding leaves Upcoming`() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        try {
            app.tvflick.core.database.TvFlickDatabase.Schema.create(driver)
            val repo = SqlDelightLibraryRepository(app.tvflick.core.database.TvFlickDatabase(driver))
            val state = FlickState(repo, null, this)
            state.libraryTab = app.tvflick.ui.LibraryTab.Upcoming
            state.addToLibrary(LibraryContentDraft(MediaType.Tv, 1, "New show")).join()
            assertEquals(app.tvflick.ui.LibraryTab.WatchList, state.libraryTab)
            assertEquals("New show", state.watchListEntries.single().first.content.title)
            assertNull(state.watchListEntries.single().second)
            assertFalse(state.showEmptyReference)
            state.destination = app.tvflick.ui.Destination.Movies
            state.addToLibrary(LibraryContentDraft(MediaType.Movie, 2, "New movie")).join()
            assertEquals("New movie", state.watchListEntries.single().first.content.title)
            assertFalse(state.showEmptyReference)
        } finally { driver.close() }
    }

    @Test fun `nine released episodes remain until individually watched`() = runBlocking {
        val state = FlickState(null, null, this)
        val show = LocalLibraryItem(1, LibraryContentDraft(MediaType.Tv, 1, "Show"), true, false, null, null, 1)
        val episodes = (1L..10L).map { EpisodePlaybackState(it, 1, it, "Episode $it", null, null,
            if (it == 10L) "2999-01-01" else "2000-01-01") }
        state.episodes = mapOf(1L to episodes)
        assertEquals((1L..9L).toList(), state.releasedEpisodes(show).map { it.episodeId })
        state.episodes = mapOf(1L to episodes.map { if (it.episodeId == 5L) it.copy(watchedAtEpochMs = 1) else it })
        assertEquals(8, state.releasedEpisodes(show).size)
        assertFalse(state.releasedEpisodes(show).any { it.episodeId == 5L || it.episodeId == 10L })
    }
}
