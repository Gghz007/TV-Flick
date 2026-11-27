package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import app.tvflick.plugin.engine.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*

class AdaptiveSourceTest {
    private val query = MediaMatchQuery("Example", releaseYear = 2020, mediaType = MatchedMediaType.Movie)
    private fun movie(url: String, encoding: String = """"encoding":{"@type":"VideoObject","contentUrl":"https://cdn.example/full.m3u8","videoQuality":"720p"}""") =
        """<script type="application/ld+json">{"@type":"Movie","name":"Example","datePublished":"2020-01-01","url":"$url",$encoding}</script>"""

    @Test fun `same adaptive parser discovers and resolves standard content on unrelated domains`() = runBlocking {
        for (host in listOf("https://alpha.example", "https://beta.example")) {
            val detailUrl = "$host/title"
            val transport = object : SourceHttp {
                override suspend fun read(url: String, referer: String, request: VideoRequest, context: SourceRequestContext, form: Map<String,String>?) = when {
                    url == detailUrl -> movie(detailUrl)
                    "/find?" in url -> """<main><article><a href="/title">Example</a></article></main>"""
                    else -> """<form role="search" action="/find"><input type="search" name="q"></form>"""
                }
            }
            AdaptiveSourcePlugin(emptyList(), StructuredWebSource(transport), HlsCatalogResolver(HttpClient(MockEngine {
                respond("#EXTM3U\n#EXTINF:1,\nclip.ts\n#EXT-X-ENDLIST")
            }))).use { plugin ->
                val engine = PluginEngine(PluginRegistry(listOf(plugin)))
                val request = VideoRequest(1, "", plugin.id, "local-site", host, query)
                val match = assertIs<MediaMatchResult.Confirmed>(engine.discover(request))
                assertEquals(detailUrl, match.candidate.pageUrl)
                val source = engine.resolveVerified(request.copy(pageUrl = detailUrl))
                assertEquals("https://cdn.example/full.m3u8", source.url)
                assertEquals(StreamKind.Hls, source.kind)
                assertEquals(listOf("720p"), source.qualityOptions.map { it.label })
            }
        }
    }

    @Test fun `trailer metadata and bare video do not become full content`() = runBlocking {
        val url = "https://example.test/movie"
        val html = movie(url, """"trailer":{"@type":"VideoObject","contentUrl":"https://cdn.example/trailer.mp4"}""") +
            """<video src="https://cdn.example/advert.mp4"></video>"""
        val doc = parseWebDocument(html, url)
        assertEquals(1, doc.media.size)
        assertTrue(doc.media.single().streams.isEmpty())
        assertTrue(parseWebDocument("<video src='https://cdn.example/full.mp4'></video>", url).media.isEmpty())
        val transport = object : SourceHttp {
            override suspend fun read(url: String, referer: String, request: VideoRequest, context: SourceRequestContext, form: Map<String,String>?) = html
        }
        val plugin = AdaptiveSourcePlugin(emptyList(), StructuredWebSource(transport))
        val engine = PluginEngine(PluginRegistry(listOf(plugin)))
        assertEquals(SourceFailureReason.UnsupportedFormat, assertFailsWith<SourceFailure> {
            engine.resolveVerified(VideoRequest(1, url, plugin.id, "site", "https://example.test", query))
        }.reason)
    }

    @Test fun `episode uses series year and explicit season not episode publication year`() {
        val html = """<script type="application/ld+json">{"@graph":[
            {"@id":"series","@type":"TVSeries","name":"Example","datePublished":"2020-01-01"},
            {"@type":"TVEpisode","name":"Episode five","datePublished":"2023-06-01","episodeNumber":5,
             "partOfSeries":{"@id":"series"},"partOfSeason":{"seasonNumber":2},
             "encoding":{"@type":"VideoObject","contentUrl":"https://cdn.example/e5.mp4"}}
        ]}</script>"""
        val candidate = parseWebDocument(html, "https://other.test/episode").media.single().identity
        assertEquals(2020, candidate.releaseYear)
        assertEquals(2, candidate.seasonNumber)
        assertEquals(5, candidate.episodeNumber)
        assertNotNull(MediaCandidateMatcher.score(query.copy(mediaType = MatchedMediaType.TvEpisode, seasonNumber = 2, episodeNumber = 5), candidate))
        assertNull(MediaCandidateMatcher.score(query.copy(mediaType = MatchedMediaType.TvEpisode, seasonNumber = 2, episodeNumber = 6), candidate))
    }

    @Test fun `microdata HTML5 source and declared search action are domain independent`() {
        val html = """<script type="application/ld+json">{"@type":"WebSite","potentialAction":{"@type":"SearchAction","target":"https://any.test/find?q={search_term_string}&type=video"}}</script>
          <article itemscope itemtype="https://schema.org/Movie"><h1 itemprop="name">Example</h1>
          <meta itemprop="datePublished" content="2020-01-01"><video itemprop="encoding">
          <source src="/media/movie.mp4" label="480p"></video></article>"""
        val doc = parseWebDocument(html, "https://any.test/title")
        assertEquals("https://any.test/media/movie.mp4", doc.media.single().streams.single().url)
        assertEquals(WebSearch("https://any.test/find", "q", mapOf("type" to "video")), doc.search)
    }

    @Test fun `known strategy is selected automatically while local target and context stay intact`() = runBlocking {
        val specialized = object : VideoSourcePlugin {
            override val id = "internal-format"
            override val version = "1"
            override fun supports(baseUrl: String) = baseUrl == "https://format.test"
            override suspend fun search(request: VideoRequest, context: SourceRequestContext): List<ParsedMediaCandidate> {
                assertEquals(id, request.sourcePluginId)
                assertEquals(42, request.contentId)
                assertEquals("configured-site", request.sourceSiteId)
                assertIs<NetworkRequestDecision.Block>(context.decide(VideoNetworkRequest("https://format.test/ad", request.sourceSiteId)))
                return emptyList()
            }
            override suspend fun resolve(request: VideoRequest): ResolvedVideoSource = error("unused")
        }
        AdaptiveSourcePlugin(listOf(specialized)).use { plugin ->
            plugin.search(VideoRequest(42, "", plugin.id, "configured-site", "https://format.test", query),
                SourceRequestContext { NetworkRequestDecision.Block("test") })
        }
        Unit
    }
}
