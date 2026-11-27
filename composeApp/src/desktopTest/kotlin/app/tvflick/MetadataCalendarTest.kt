package app.tvflick

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.*
import app.tvflick.core.tmdb.*
import app.tvflick.ui.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class MetadataCalendarTest {
    @Test fun `unavailable metadata preserves local details and successful retry clears the error`() = runBlocking {
        Store().use { store ->
            var fail = true
            val client = HttpClient(MockEngine {
                if (fail) respond("{}", HttpStatusCode.ServiceUnavailable)
                else respond("""{"id":77,"title":"Saved movie","release_date":"2026-12-01","alternative_titles":{"titles":[{"title":"Alias"}]},"genres":[{"name":"Drama"}]}""", HttpStatusCode.OK)
            })
            try {
                store.repo.save(LibraryContentDraft(MediaType.Movie, 77, "Saved movie", overview = "Saved summary"), true)
                val state = FlickState(store.repo, TmdbClient("test", client), this)
                state.refreshStaleMetadata()
                assertNotNull(state.libraryError)
                assertNull(store.repo.listItems().single().content.metadataUpdatedAtEpochMs)
                assertEquals("Saved summary", store.repo.cachedTitleDetails(MediaType.Movie, 77)?.overview)
                fail = false
                state.refreshStaleMetadata()
                assertNull(state.libraryError)
                assertEquals("2026-12-01", store.repo.listItems().single().content.releaseDate)
                assertEquals(listOf("Alias"), store.repo.listItems().single().content.alternativeTitles)
            } finally { client.close() }
        }
    }

    @Test fun `wrong TMDB identity does not overwrite saved metadata`() = runBlocking {
        Store().use { store ->
            val client = HttpClient(MockEngine { respond("""{"id":999,"title":"Wrong movie"}""", HttpStatusCode.OK) })
            try {
                val draft = LibraryContentDraft(MediaType.Movie, 77, "Keep movie")
                store.repo.save(draft, true)
                val state = FlickState(store.repo, TmdbClient("test", client), this)
                state.refreshStaleMetadata()
                assertNotNull(state.libraryError)
                assertEquals(draft, store.repo.listItems().single().content)
            } finally { client.close() }
        }
    }

    private class Store : AutoCloseable {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        init { TvFlickDatabase.Schema.create(driver) }
        val repo = SqlDelightLibraryRepository(TvFlickDatabase(driver))
        override fun close() = driver.close()
    }

    @Test fun `release dates validate leap years and respect the supplied local calendar`() {
        assertNull(daysUntil("2026-02-29", "2026-02-28"))
        assertNull(daysUntil("2026-04-31", "2026-04-30"))
        assertNull(daysUntil("2026", "2026-11-13"))
        assertEquals(1L, daysUntil("2028-02-29", "2028-02-28"))
        assertEquals(1L, daysUntil("2027-01-01", "2026-12-31"))
        assertEquals(0L, daysUntil("2026-11-13", "2026-11-14"))
        assertEquals("TOMORROW", releaseSection("2026-11-14", "2026-11-13"))
    }

    @Test fun `movies move from Upcoming to Watch List at local midnight without a network request`() = runBlocking {
        Store().use { store ->
            val movie = store.repo.save(LibraryContentDraft(MediaType.Movie, 7, "Future", releaseDate = "2026-11-14"), true)
            val state = FlickState(store.repo, null, this)
            state.destination = Destination.Movies
            state.updateCalendar("2026-11-13")
            state.refresh()
            assertTrue(state.watchListEntries.isEmpty())
            assertEquals(movie.localId, state.visibleUpcomingMovies.single().localId)
            state.libraryTab = LibraryTab.Upcoming
            assertFalse(state.showEmptyReference)
            state.updateCalendar("2026-11-14")
            assertTrue(state.visibleUpcomingMovies.isEmpty())
            assertEquals(movie.localId, state.watchListEntries.single().first.localId)
            store.repo.setMovieWatched(movie.localId, 1)
            state.refresh()
            assertTrue(state.watchListEntries.isEmpty())
        }
    }

    @Test fun `scheduled TMDB refresh adds seasons while preserving marks favorites and stop state`() = runBlocking {
        Store().use { store ->
            var requests = 0
            var newSeason = false
            val http = HttpClient(MockEngine { request ->
                requests++
                val body = if (request.url.encodedPath.contains("/season/")) {
                    val number = request.url.encodedPath.substringAfterLast('/').toInt()
                    """{"season_number":$number,"name":"Season $number","episodes":[{"id":$number,"episode_number":1,"name":"Episode","air_date":"2026-11-14","runtime":40}]}"""
                } else {
                    assertContains(request.url.parameters["append_to_response"].orEmpty(), "alternative_titles")
                    val extra = if (newSeason) """,{"id":2,"season_number":2,"episode_count":1}""" else ""
                    """{"id":11,"name":"Show","original_name":"Original","first_air_date":"2020-01-01","genres":[{"id":1,"name":"Drama"}],"alternative_titles":{"results":[{"title":"Alias"}]},"seasons":[{"id":1,"season_number":1,"episode_count":1}$extra]}"""
                }
                respond(body, HttpStatusCode.OK)
            })
            try {
                val show = store.repo.save(LibraryContentDraft(MediaType.Tv, 11, "Show"), true)
                val state = FlickState(store.repo, TmdbClient("test-key", http), this)
                state.updateCalendar("2026-11-13")
                state.refreshStaleMetadata()
                val updated = store.repo.listItems().single()
                assertEquals("2020-01-01", updated.content.releaseDate)
                assertEquals(listOf("Alias"), updated.content.alternativeTitles)
                assertEquals(listOf("Drama"), updated.content.genres)
                assertNotNull(updated.content.metadataUpdatedAtEpochMs)
                assertTrue(state.watchListEntries.isEmpty())
                assertEquals(1, state.visibleUpcoming.size)
                val count = requests
                state.refreshStaleMetadata()
                assertEquals(count, requests)
                state.updateCalendar("2026-11-14")
                assertTrue(state.visibleUpcoming.isEmpty())
                assertEquals(1, state.watchListEntries.size)
                val episode = store.repo.listEpisodesForShow(show.localId).single()
                store.repo.setEpisodeWatched(episode.episodeId, 5)
                store.repo.setEpisodeFavorite(episode.episodeId, true)
                store.repo.setWatchList(show.localId, false)
                store.repo.updateMetadataIfPresent(show.localId, updated.content.copy(metadataUpdatedAtEpochMs = 1))
                newSeason = true
                state.refreshStaleMetadata()
                assertEquals(2, store.repo.listSeasons(show.localId).size)
                assertEquals(5L, store.repo.listEpisodesForShow(show.localId).first().watchedAtEpochMs)
                assertTrue(store.repo.listEpisodesForShow(show.localId).first().isFavorite)
                assertFalse(store.repo.listItems().single().isInWatchList)
                store.repo.remove(show.localId)
                assertFalse(store.repo.updateMetadataIfPresent(show.localId, updated.content))
                assertTrue(store.repo.listItems().isEmpty())
            } finally { http.close() }
        }
    }

    @Test fun `metadata migration preserves the old library and backups accept versions one and two`() = runBlocking {
        Store().use { source -> Store().use { target ->
            val draft = LibraryContentDraft(MediaType.Movie, 8, "Movie", releaseDate = "2026-12-01",
                alternativeTitles = listOf("Альтернатива"), genres = listOf("Drama"))
            source.repo.save(draft, true)
            val backup = source.repo.exportBackup()
            target.repo.restoreBackup(backup)
            assertEquals(draft, target.repo.listItems().single().content)
            val root = Json.parseToJsonElement(backup.decodeToString()).jsonObject
            val tables = root.getValue("tables").jsonObject
            val oldRows = JsonArray(tables.getValue("content").jsonArray.map {
                JsonObject(it.jsonObject - setOf("release_date", "alternative_titles", "genres"))
            })
            val oldBackup = JsonObject(root + mapOf("version" to JsonPrimitive(1),
                "tables" to JsonObject(tables + ("content" to oldRows)))).toString().encodeToByteArray()
            target.repo.restoreBackup(oldBackup)
            assertEquals("Movie", target.repo.listItems().single().content.title)
            assertNull(target.repo.listItems().single().content.releaseDate)
            assertTrue(target.repo.listItems().single().content.alternativeTitles.isEmpty())
            listOf("release_date", "alternative_titles", "genres").forEach {
                source.driver.execute(null, "ALTER TABLE content DROP COLUMN $it", 0)
            }
            TvFlickDatabase.Schema.migrate(source.driver, 3, 4)
            assertEquals("Movie", source.repo.listItems().single().content.title)
            assertTrue(source.repo.listItems().single().isInWatchList)
            assertNull(source.repo.listItems().single().content.releaseDate)
        } }
    }

    @Test fun `cached season retains TMDB identity description and progress without a client`() = runBlocking {
        Store().use { store ->
            val show = store.repo.save(LibraryContentDraft(MediaType.Tv, 9, "Offline"), true)
            val summary = TmdbSeasonSummary(20, 2, "Two", "2020-01-01", "/season.jpg")
            val season = TmdbSeasonDetails(2, "Two", listOf(TmdbEpisode(33, 5, "Five", "Summary", "2020-01-01", "/still.jpg", 42)))
            store.repo.syncSeason(show.localId, summary, season)
            store.repo.setEpisodeWatched(store.repo.listEpisodesForShow(show.localId).single().episodeId, 10)
            assertEquals(season, store.repo.cachedSeason(show.localId, 2))
            val offline = store.repo.cachedTitleDetails(MediaType.Tv, 9)
            assertEquals("Offline", offline?.title)
            assertEquals(2L, offline?.seasons?.single()?.seasonNumber)
            assertEquals(1, store.repo.listSeasons(show.localId).single().episodeCount)
            assertEquals(10L, store.repo.listEpisodesForShow(show.localId).single().watchedAtEpochMs)
        }
    }
}

