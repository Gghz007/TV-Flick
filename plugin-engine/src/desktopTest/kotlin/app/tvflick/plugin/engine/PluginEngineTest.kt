package app.tvflick.plugin.engine

import app.tvflick.plugin.api.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PluginEngineTest {
    private val candidate = ParsedMediaCandidate("Show", releaseYear = 2020,
        mediaType = MatchedMediaType.TvEpisode, seasonNumber = 2, episodeNumber = 5,
        pageUrl = "https://source.test/show/s2e5", tmdbId = 123)
    private val request = VideoRequest(42, candidate.pageUrl, "source", "local-site", "https://source.test",
        MediaMatchQuery("Show", releaseYear = 2020, mediaType = MatchedMediaType.TvEpisode,
            seasonNumber = 2, episodeNumber = 5, tmdbId = 123))

    private inner class Source : VideoSourcePlugin {
        override val id = "source"
        override val version = "1.0"
        var identity: ParsedMediaCandidate? = candidate
        var results = listOf(candidate)
        var resolutions = 0
        var decision: NetworkRequestDecision? = null
        override fun supports(baseUrl: String) = baseUrl == "https://source.test"
        override suspend fun search(request: VideoRequest, context: SourceRequestContext) = results
        override suspend fun inspect(request: VideoRequest, context: SourceRequestContext): ParsedMediaCandidate? {
            decision = context.decide(VideoNetworkRequest("https://ads.test/ad", "wrong-site", VideoResourceKind.Ad))
            return identity
        }
        override suspend fun resolve(request: VideoRequest): ResolvedVideoSource {
            resolutions++
            return ResolvedVideoSource("https://media.test/master.m3u8", StreamKind.Hls,
                headers = mapOf("Referer" to request.pageUrl, "Cookie" to "session=fixture"),
                qualityOptions = listOf(PlaybackOption("hd", "720p")),
                audioTrackOptions = listOf(PlaybackOption("uk", "Українська")), sourceId = "plugin-value")
        }
    }

    private class Player : VideoEngine {
        var source: ResolvedVideoSource? = null
        var position = -1L
        override suspend fun open(source: ResolvedVideoSource, startPositionMs: Long) { this.source = source; position = startPositionMs }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setLocked(locked: Boolean) = Unit
        override fun close() = Unit
    }

    @Test fun `unverified or wrong episode never resolves or opens`() = runBlocking {
        val source = Source()
        val engine = PluginEngine(PluginRegistry(listOf(source)))
        val player = Player()
        for (identity in listOf(null, candidate.copy(episodeNumber = 6), candidate.copy(tmdbId = 999),
            candidate.copy(pageUrl = "https://source.test/other"))) {
            source.identity = identity
            assertFailsWith<IllegalArgumentException> { engine.openRequest(player, request) }
        }
        assertNull(player.source)
        assertEquals(0, source.resolutions)
    }

    @Test fun `discovery returns ambiguity without resolving any stream`() = runBlocking {
        val source = Source().apply { results = listOf(candidate, candidate.copy(pageUrl = "https://source.test/alternative")) }
        val engine = PluginEngine(PluginRegistry(listOf(source)))
        assertIs<MediaMatchResult.Ambiguous>(engine.discover(request.copy(pageUrl = "")))
        assertEquals(0, source.resolutions)
        source.results = listOf(candidate.copy(episodeNumber = 6))
        assertIs<MediaMatchResult.NotFound>(engine.discover(request))
        source.results = listOf(candidate)
        assertEquals(candidate, assertIs<MediaMatchResult.Confirmed>(engine.discover(request)).candidate)
    }

    @Test fun `verified playback preserves headers choices and resume with local source identity`() = runBlocking {
        val source = Source()
        val engine = PluginEngine(PluginRegistry(listOf(source)))
        val player = Player()
        engine.openRequest(player, request, 45_000)
        assertEquals(45_000, player.position)
        val opened = assertNotNull(player.source)
        assertEquals("local-site", opened.sourceId)
        assertEquals("session=fixture", opened.headers["Cookie"])
        assertEquals(request.pageUrl, opened.headers["Referer"])
        assertEquals(listOf(PlaybackOption("hd", "720p")), opened.qualityOptions)
        assertEquals(listOf(PlaybackOption("uk", "Українська")), opened.audioTrackOptions)
        engine.openRequest(player, request, -100)
        assertEquals(0, player.position)
    }

    @Test fun `adblock is scoped to the configured site and cannot block primary media`() = runBlocking {
        val seen = mutableListOf<String>()
        var filtered = 0
        val blocker = object : AdBlockPlugin {
            override val id = "blocker"
            override val version = "1"
            override fun filter(source: ResolvedVideoSource): ResolvedVideoSource { filtered++; return source }
            override fun decide(request: VideoNetworkRequest): NetworkRequestDecision {
                seen += request.sourceId
                return NetworkRequestDecision.Block("fixture")
            }
        }
        val source = Source()
        val engine = PluginEngine(PluginRegistry(listOf(source), listOf(blocker)))
        engine.resolveVerified(request)
        assertEquals(listOf("local-site"), seen)
        assertIs<NetworkRequestDecision.Block>(source.decision)
        engine.resolveVerified(request.copy(adblockEnabled = false))
        assertEquals(NetworkRequestDecision.Allow, source.decision)
        assertEquals(1, filtered)
        listOf(VideoResourceKind.MediaManifest, VideoResourceKind.MediaSegment).forEach {
            assertEquals(NetworkRequestDecision.Allow, engine.decideNetworkRequest(VideoNetworkRequest("https://media.test/ad", "site", it)))
        }
        assertEquals(listOf("local-site"), seen)
    }

    @Test fun `missing identity unsupported site and incompatible plugin are rejected`() = runBlocking {
        val source = Source()
        val engine = PluginEngine(PluginRegistry(listOf(source)))
        listOf(request.copy(mediaMatch = null), request.copy(sourceSiteBaseUrl = "https://other.test"),
            request.copy(contentId = 0), request.copy(sourceSiteId = ""), request.copy(pageUrl = "")).forEach {
            assertFailsWith<IllegalArgumentException> { engine.resolveVerified(it) }
        }
        assertFailsWith<IllegalStateException> { engine.resolveVerified(request.copy(sourcePluginId = "missing")) }
        assertEquals(0, source.resolutions)
        val incompatible = object : VideoSourcePlugin by source { override val apiVersion = 999 }
        assertFailsWith<IllegalArgumentException> { PluginRegistry(listOf(incompatible)) }
        assertFailsWith<IllegalArgumentException> { PluginRegistry().register(incompatible) }
        assertEquals(listOf("source"), PluginRegistry(listOf(source)).compatibleSources("https://source.test"))
    }
}
