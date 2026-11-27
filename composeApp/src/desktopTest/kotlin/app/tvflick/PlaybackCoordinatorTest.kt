package app.tvflick

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.*
import app.tvflick.core.tmdb.*
import app.tvflick.plugin.api.*
import app.tvflick.plugin.engine.*
import app.tvflick.ui.FlickState
import app.tvflick.ui.PlaybackKey
import app.tvflick.ui.playerFailure
import app.tvflick.plugin.video.PlayerFailure
import kotlinx.coroutines.*
import kotlin.test.*

class PlaybackCoordinatorTest {
    @Test fun `late decoder error from replaced player cannot close current playback`() = runBlocking {
        fixture { repo, _, coordinator ->
            val key = PlaybackKey(movie(repo).localId)
            val state = FlickState(repo, null, this, coordinator)
            val flow = assertNotNull(state.playback)
            flow.start(key)
            waitForPlayback(state)
            val old = assertNotNull(flow.active)
            flow.start(key)
            waitForPlayback(state)
            val current = assertNotNull(flow.active)
            assertNotSame(old, current)
            state.playerFailure(PlayerFailure.Open, old)
            assertSame(current, flow.active)
            assertNull(state.notices.current)
            state.playerFailure(PlayerFailure.Open, current)
            assertNull(flow.active)
            assertNotNull(state.notices.current)
        }
    }

    @Test fun `player downloads exact current audio and quality without a second picker or source search`() = runBlocking {
        fixture { repo, source, coordinator ->
            val movie = movie(repo)
            source.onResolve = { request -> ResolvedVideoSource("https://cdn.test/a720.mp4", StreamKind.Mp4,
                sourceId = request.sourceSiteId, variants = listOf(
                    ResolvedStreamVariant("https://cdn.test/a720.mp4", StreamKind.Mp4, "a", "720"),
                    ResolvedStreamVariant("https://cdn.test/b1080.m3u8", StreamKind.Hls, "b", "1080", mapOf("Referer" to "https://source.test/title")))) }
            val state = FlickState(repo, null, this, coordinator)
            val flow = assertNotNull(state.playback)
            val key = PlaybackKey(movie.localId)
            flow.start(key)
            waitForPlayback(state)
            val resolves = source.resolves
            flow.downloadCurrent(key, "b", "1080")
            val download = assertNotNull(flow.downloadRequest)
            assertNull(flow.downloadSelection)
            assertEquals("https://cdn.test/b1080.m3u8", download.source.url)
            assertEquals(StreamKind.Hls, download.source.kind)
            assertEquals(mapOf("Referer" to "https://source.test/title"), download.source.headers)
            assertEquals(resolves, source.resolves)
            assertNull(repo.loadMoviePlayback(movie.localId))
            flow.consumeDownload(download)
            flow.downloadCurrent(key, "b", "720")
            assertNull(flow.downloadRequest) // Never silently fall back to another audio/quality.
            flow.downloadCurrent(PlaybackKey(movie.localId + 1), "b", "1080")
            assertNull(flow.downloadRequest) // A delayed old-player callback cannot download another title.
        }
    }

    private suspend fun waitForPlayback(state: FlickState) = withTimeout(5_000) {
        while (state.playback?.busy == true) delay(5)
    }

    @Test fun `episode changes only discover the source selected in the active player`() = runBlocking {
        fixture { repo, source, coordinator ->
            val second = VideoSourceSettings(repo).add("https://second.test")
            val item = show(repo)
            val episodes = repo.listEpisodesForShow(item.localId)
            val state = FlickState(repo, null, this, coordinator)
            val flow = assertNotNull(state.playback)
            flow.start(PlaybackKey(item.localId, episodes[0].episodeId))
            waitForPlayback(state)
            val selected = assertNotNull(flow.selection).discovery.choices.single { it.sourceId == second.id }
            flow.choose(selected)
            waitForPlayback(state)

            val searched = mutableListOf<String>()
            source.onSearch = { request ->
                searched.add(request.sourceSiteId)
                if (request.mediaMatch?.episodeNumber == episodes[2].episodeNumber.toInt()) emptyList()
                else listOf(Source.candidate(request))
            }
            flow.switchEpisode(episodes[1].episodeId)
            waitForPlayback(state)
            val playing = assertNotNull(flow.active)
            assertEquals(PlaybackKey(item.localId, episodes[1].episodeId), playing.key)
            assertEquals(second.id, playing.sourceId)
            assertNull(flow.selection)
            assertEquals(listOf(second.id), searched)

            flow.switchEpisode(episodes[2].episodeId)
            waitForPlayback(state)
            assertSame(playing, flow.active)
            assertNull(flow.selection)
            assertEquals(listOf(second.id, second.id), searched)
        }
    }

    @Test fun `UI launch waits for candidate choice and download never starts or marks playback`() = runBlocking {
        fixture { repo, source, coordinator ->
            val movie = movie(repo)
            source.onSearch = { listOf(Source.candidate(it), Source.candidate(it).copy(pageUrl = "https://source.test/other")) }
            val state = FlickState(repo, null, this, coordinator)
            val flow = assertNotNull(state.playback)
            flow.start(PlaybackKey(movie.localId))
            waitForPlayback(state)
            assertNull(flow.active)
            assertEquals(0, source.resolves)
            val selection = assertNotNull(flow.selection)
            flow.choose(selection.discovery.choices.last())
            waitForPlayback(state)
            val active = assertNotNull(flow.active)
            assertEquals(PlaybackTarget.Movie(movie.localId), active.prepared.target)
            assertNull(repo.loadMoviePlayback(movie.localId))
            flow.start(PlaybackKey(movie.localId), download = true)
            waitForPlayback(state)
            flow.choose(assertNotNull(flow.selection).discovery.choices.first())
            waitForPlayback(state)
            val download = assertNotNull(flow.downloadSelection)
            assertSame(active, flow.active)
            flow.chooseDownload(download.source.copy(url = "https://other.test/forged.mp4"))
            assertNull(flow.downloadRequest)
            flow.chooseDownload(download.source)
            assertNotNull(flow.downloadRequest)
            assertNull(repo.loadMoviePlayback(movie.localId))
        }
    }

    @Test fun `PlayMark is reversible persistent and survives backup without marking anything`() = runBlocking {
        fixture { repo, _, coordinator ->
            val movie = movie(repo)
            val state = FlickState(repo, null, this, coordinator)
            state.refresh()
            assertFalse(state.playInsteadOfMark)
            state.togglePlayInsteadOfMark().join()
            assertTrue(state.playInsteadOfMark)
            val backup = repo.exportBackup()
            state.togglePlayInsteadOfMark().join()
            assertFalse(repo.playInsteadOfMark())
            state.withLibraryRestore { repo.restoreBackup(backup) }
            assertTrue(state.playInsteadOfMark)
            assertNull(repo.loadMoviePlayback(movie.localId))
        }
    }

    @Test fun `closing pending UI request prevents late reopening and failures keep the playing session`() = runBlocking {
        fixture { repo, source, coordinator ->
            val movie = movie(repo)
            val state = FlickState(repo, null, this, coordinator)
            val flow = assertNotNull(state.playback)
            flow.start(PlaybackKey(movie.localId))
            waitForPlayback(state)
            val active = assertNotNull(flow.active)
            source.onSearch = { throw SourceFailure(SourceFailureReason.Unavailable) }
            flow.start(PlaybackKey(movie.localId))
            waitForPlayback(state)
            assertSame(active, flow.active)
            assertNotNull(state.notices.current)
            source.onSearch = { throw SourceFailure(SourceFailureReason.UnsupportedFormat) }
            flow.start(PlaybackKey(movie.localId))
            waitForPlayback(state)
            assertSame(active, flow.active)
            assertEquals(app.tvflick.ui.UserProblem.SourceUnsupported, state.notices.current?.problem)
            val entered = CompletableDeferred<Unit>()
            source.onSearch = { entered.complete(Unit); awaitCancellation() }
            flow.start(PlaybackKey(movie.localId))
            entered.await()
            flow.closed()
            assertNull(flow.active)
            assertFalse(flow.busy)
            yield()
            assertNull(flow.active)
        }
    }

    private class Source : VideoSourcePlugin {
        override val id = ADAPTIVE_SOURCE_PLUGIN_ID
        override val version = "1"
        override fun supports(baseUrl: String) = true
        var onSearch: suspend (VideoRequest) -> List<ParsedMediaCandidate> = { listOf(candidate(it)) }
        var onInspect: suspend (VideoRequest) -> ParsedMediaCandidate? = { candidate(it).copy(pageUrl = it.pageUrl) }
        var onResolve: suspend (VideoRequest) -> ResolvedVideoSource = {
            ResolvedVideoSource("https://cdn.test/video.mp4", StreamKind.Mp4, sourceId = "remote-id")
        }
        var resolves = 0
        override suspend fun search(request: VideoRequest, context: SourceRequestContext) = onSearch(request)
        override suspend fun inspect(request: VideoRequest, context: SourceRequestContext) = onInspect(request)
        override suspend fun resolve(request: VideoRequest): ResolvedVideoSource { resolves++; return onResolve(request) }

        companion object {
            fun candidate(request: VideoRequest): ParsedMediaCandidate {
                val query = requireNotNull(request.mediaMatch)
                return ParsedMediaCandidate(query.title, releaseYear = query.releaseYear, mediaType = query.mediaType,
                    seasonNumber = query.seasonNumber, episodeNumber = query.episodeNumber,
                    pageUrl = "${request.sourceSiteBaseUrl}/selected", tmdbId = query.tmdbId)
            }
        }
    }

    private suspend fun fixture(block: suspend (SqlDelightLibraryRepository, Source, PlaybackCoordinator) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val repo = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            VideoSourceSettings(repo).add("https://source.test")
            val source = Source()
            block(repo, source, PlaybackCoordinator(repo, repo, PluginEngine(PluginRegistry(videoSources = listOf(source)))))
        } finally { driver.close() }
    }

    private suspend fun movie(repo: LibraryRepository) = repo.save(
        LibraryContentDraft(MediaType.Movie, 100, "Movie", releaseYear = 2020), true)

    private suspend fun show(repo: LibraryRepository): LocalLibraryItem {
        val item = repo.save(LibraryContentDraft(MediaType.Tv, 200, "Show", releaseYear = 2020), true)
        repo.syncSeason(item.localId, TmdbSeasonSummary(20, 2, "Season 2", null, null),
            TmdbSeasonDetails(2, "Season 2", (5..7).map {
                TmdbEpisode(200L + it, it.toLong(), "Episode $it", null, "2020-05-0$it", null, 45)
            }))
        return item
    }

    @Test fun `unavailable site does not hide healthy source and three episodes keep separate progress`() = runBlocking {
        fixture { repo, source, coordinator ->
            val broken = VideoSourceSettings(repo).add("https://broken.test")
            source.onSearch = {
                if (it.sourceSiteId == broken.id) throw SourceFailure(SourceFailureReason.AccessDenied)
                listOf(Source.candidate(it))
            }
            val item = show(repo)
            val episodes = repo.listEpisodesForShow(item.localId)
            for (episode in episodes) {
                val discovery = coordinator.discover(item.localId, episode.episodeId)
                assertEquals(listOf(SourceLookupFailure(broken.id, SourceFailureReason.AccessDenied)), discovery.failures)
                val choice = assertNotNull(discovery.automaticChoice)
                // Preparation takes the most recent position, not discovery's older snapshot.
                repo.saveEpisodePlayback(episode.episodeId, PlaybackSnapshot(episode.episodeId, 25_000, 100_000, false))
                val session = coordinator.prepare(discovery, choice)
                assertEquals(25_000, session.resumePositionMs)
                assertEquals(PlaybackTarget.Episode(episode.episodeId), session.target)
                assertEquals(choice.sourceId, session.source.sourceId)
                session.progressSink.save(PlaybackSnapshot(episode.episodeId, 75_000, 100_000, false))
            }
            assertTrue(repo.listEpisodesForShow(item.localId).all { it.watchedAtEpochMs != null })
            assertNull(repo.loadMoviePlayback(item.localId))
        }
    }

    @Test fun `ambiguous matches require choice and still undergo independent identity inspection`() = runBlocking {
        fixture { repo, source, coordinator ->
            val movie = movie(repo)
            source.onSearch = { listOf(Source.candidate(it), Source.candidate(it).copy(pageUrl = "https://source.test/second")) }
            val discovery = coordinator.discover(movie.localId)
            assertNull(discovery.automaticChoice)
            assertEquals(2, discovery.choices.size)
            source.onInspect = { Source.candidate(it).copy(pageUrl = it.pageUrl, releaseYear = 1999) }
            assertFailsWith<IllegalArgumentException> { coordinator.prepare(discovery, discovery.choices.last()) }
            assertEquals(0, source.resolves)
            source.onInspect = { Source.candidate(it).copy(pageUrl = it.pageUrl) }
            val session = coordinator.prepare(discovery, discovery.choices.last())
            assertEquals(PlaybackTarget.Movie(movie.localId), session.target)
            val another = coordinator.discover(movie.localId)
            assertFailsWith<PlaybackSelectionExpired> { coordinator.prepare(another, discovery.choices.first()) }
        }
    }

    @Test fun `disabling source while resolve is running prevents delivery of prepared session`() = runBlocking {
        fixture { repo, source, coordinator ->
            val movie = movie(repo)
            val discovery = coordinator.discover(movie.localId)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            source.onResolve = {
                entered.complete(Unit); release.await()
                ResolvedVideoSource("https://cdn.test/video.mp4", StreamKind.Mp4, sourceId = "remote")
            }
            val result = async { runCatching { coordinator.prepare(discovery, discovery.choices.single()) } }
            entered.await()
            val site = repo.listVideoSourceSites().single()
            VideoSourceSettings(repo).setEnabled(site, false)
            release.complete(Unit)
            assertIs<PlaybackSelectionExpired>(result.await().exceptionOrNull())
            assertNull(repo.loadMoviePlayback(movie.localId))
        }
    }

    @Test fun `restore invalidates pending discovery and progress but source disable preserves already viewed progress`() = runBlocking {
        fixture { repo, source, coordinator ->
            val movie = movie(repo)
            val discovery = coordinator.discover(movie.localId)
            val session = coordinator.prepare(discovery, discovery.choices.single())
            val site = repo.listVideoSourceSites().single()
            VideoSourceSettings(repo).setEnabled(site, false)
            session.progressSink.save(PlaybackSnapshot(movie.localId, 20_000, 100_000, false))
            assertEquals(20_000, repo.loadMoviePlayback(movie.localId)?.positionMs)
            VideoSourceSettings(repo).setEnabled(site, true)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            source.onSearch = { entered.complete(Unit); release.await(); listOf(Source.candidate(it)) }
            val pending = async { runCatching { coordinator.discover(movie.localId) } }
            entered.await()
            val state = FlickState(repo, null, this, coordinator)
            state.withLibraryRestore {
                // A disposed player's late callback may arrive during the actual restore.
                session.progressSink.save(PlaybackSnapshot(movie.localId, 95_000, 100_000, false))
                assertEquals(20_000, repo.loadMoviePlayback(movie.localId)?.positionMs)
            }
            release.complete(Unit)
            assertIs<PlaybackSelectionExpired>(pending.await().exceptionOrNull())
            session.progressSink.save(PlaybackSnapshot(movie.localId, 95_000, 100_000, false))
            assertEquals(20_000, repo.loadMoviePlayback(movie.localId)?.positionMs)
            assertNull(repo.loadMoviePlayback(movie.localId)?.watchedAtEpochMs)
            assertFailsWith<PlaybackSelectionExpired> { coordinator.prepare(discovery, discovery.choices.single()) }
        }
    }

    @Test fun `cancellation propagates without becoming unavailable or falling through to playback`() = runBlocking {
        fixture { repo, source, coordinator ->
            val movie = movie(repo)
            val entered = CompletableDeferred<Unit>()
            source.onSearch = { entered.complete(Unit); awaitCancellation() }
            val pending = async { coordinator.discover(movie.localId) }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(0, source.resolves)
        }
    }
}
