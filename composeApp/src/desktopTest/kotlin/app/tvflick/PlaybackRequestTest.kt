package app.tvflick

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.*
import app.tvflick.core.tmdb.*
import app.tvflick.plugin.api.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PlaybackRequestTest {
    private suspend fun database(block: suspend (SqlDelightLibraryRepository) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val repository = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            repository.saveVideoSourceSite(VideoSourceSiteDraft("site", "Source", "https://source.test", "source"))
            block(repository)
        } finally { driver.close() }
    }

    private suspend fun show(repository: LibraryRepository, tmdbId: Long): LocalLibraryItem {
        val item = repository.save(LibraryContentDraft(MediaType.Tv, tmdbId, "Локальна назва",
            originalTitle = "Original Show", releaseYear = 2020, alternativeTitles = listOf("Alternate Show")), true)
        repository.syncSeason(item.localId, TmdbSeasonSummary(20, 2, "Season 2", null, null),
            TmdbSeasonDetails(2, "Season 2", listOf(
                TmdbEpisode(tmdbId * 10 + 5, 5, "Episode five", null, "2020-05-05", null, 45),
                TmdbEpisode(tmdbId * 10 + 6, 6, "Episode six", null, "2020-05-06", null, 45),
            )))
        return item
    }

    @Test fun `episode request carries TMDB identity and exact local target resume and site settings`() = runBlocking {
        database { repository ->
            val item = show(repository, 123)
            val episode = repository.listEpisodesForShow(item.localId).first()
            repository.saveEpisodePlayback(episode.episodeId, PlaybackSnapshot(episode.episodeId, 35_000, 100_000, false))
            repository.setSourceAdblockEnabled("site", false)
            val local = PlaybackRequestFactory(repository, repository).create(item.localId, "site", episode.episodeId)
            assertEquals(PlaybackTarget.Episode(episode.episodeId), local.target)
            assertEquals(episode.episodeId, local.request.contentId)
            assertEquals(35_000, local.resumePositionMs)
            assertFalse(local.request.adblockEnabled)
            assertEquals("", local.request.pageUrl)
            assertEquals("source", local.request.sourcePluginId)
            val query = assertNotNull(local.request.mediaMatch)
            assertEquals(123, query.tmdbId)
            assertEquals("Original Show", query.originalTitle)
            assertEquals(listOf("Alternate Show"), query.alternativeTitles)
            assertEquals(2020, query.releaseYear)
            assertEquals(2, query.seasonNumber)
            assertEquals(5, query.episodeNumber)
            assertEquals("Episode five", query.episodeTitle)
            assertEquals("2020-05-05", query.episodeAirDate)
            assertEquals(MatchedMediaType.TvEpisode, query.mediaType)
        }
    }

    @Test fun `another shows episode missing episode and disabled source never create requests`() = runBlocking {
        database { repository ->
            val first = show(repository, 123)
            val second = show(repository, 124)
            val factory = PlaybackRequestFactory(repository, repository)
            val episode = repository.listEpisodesForShow(first.localId).first()
            val foreign = repository.listEpisodesForShow(second.localId).first()
            assertFailsWith<IllegalArgumentException> { factory.create(first.localId, "site", foreign.episodeId) }
            assertFailsWith<IllegalArgumentException> { factory.create(first.localId, "site") }
            assertFailsWith<IllegalArgumentException> { factory.create(first.localId, "removed", episode.episodeId) }
            repository.saveVideoSourceSite(VideoSourceSiteDraft("site", "Source", "https://source.test", "source", false))
            assertFailsWith<IllegalArgumentException> { factory.create(first.localId, "site", episode.episodeId) }
            repository.saveVideoSourceSite(VideoSourceSiteDraft("site", "Source", "https://source.test", ""))
            assertEquals(ADAPTIVE_SOURCE_PLUGIN_ID, factory.create(first.localId, "site", episode.episodeId).request.sourcePluginId)
        }
    }

    @Test fun `movie uses its own resume and cannot acquire an episode target`() = runBlocking {
        database { repository ->
            val item = repository.save(LibraryContentDraft(MediaType.Movie, 99, "Movie", releaseYear = 2001), true)
            repository.saveMoviePlayback(item.localId, PlaybackSnapshot(item.localId, 12_000, 100_000, false))
            val factory = PlaybackRequestFactory(repository, repository)
            val local = factory.create(item.localId, "site")
            assertEquals(PlaybackTarget.Movie(item.localId), local.target)
            assertEquals(12_000, local.resumePositionMs)
            assertEquals(MatchedMediaType.Movie, local.request.mediaMatch?.mediaType)
            assertNull(local.request.mediaMatch?.seasonNumber)
            assertTrue(local.request.adblockEnabled)
            assertFailsWith<IllegalArgumentException> { factory.create(item.localId, "site", 42) }
            repository.remove(item.localId)
            assertFailsWith<IllegalArgumentException> { factory.create(item.localId, "site") }
        }
    }

    @Test fun `late progress from another episode cannot mark the active target`() = runBlocking {
        database { repository ->
            val item = show(repository, 123)
            val episodes = repository.listEpisodesForShow(item.localId)
            val sink = RepositoryPlaybackProgressSink(repository, PlaybackTarget.Episode(episodes[1].episodeId))
            assertFailsWith<IllegalArgumentException> {
                sink.save(PlaybackSnapshot(episodes[0].episodeId, 90_000, 100_000, false))
            }
            assertTrue(repository.listEpisodesForShow(item.localId).all { it.positionMs == null && it.watchedAtEpochMs == null })
            sink.save(PlaybackSnapshot(episodes[1].episodeId, 90_000, 100_000, false))
            assertNotNull(repository.loadEpisodePlayback(episodes[1].episodeId)?.watchedAtEpochMs)
            assertNull(repository.loadEpisodePlayback(episodes[0].episodeId))
        }
    }
}
