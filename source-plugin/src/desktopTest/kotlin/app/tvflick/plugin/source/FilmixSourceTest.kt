package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class FilmixSourceTest {
    private val allow = SourceRequestContext { NetworkRequestDecision.Allow }
    private val movieUrl = "https://filmix.gg/film/drama/44-title.html"
    private val seriesUrl = "https://filmix.gg/seria/drama/44-title.html"
    private fun request(tv: Boolean = false) = VideoRequest(20, if (tv) seriesUrl else movieUrl, "tvflick.source.filmix", "site", "https://filmix.gg",
        MediaMatchQuery("Title", releaseYear = 2008, mediaType = if (tv) MatchedMediaType.TvEpisode else MatchedMediaType.Movie,
            seasonNumber = if (tv) 2 else null, episodeNumber = if (tv) 5 else null))
    private fun html(year: Int = 2008) = """<article class="fullstory" data-id="44"><h1 class="name">Title</h1><a itemprop="copyrightYear">$year</a></article>"""
    private fun encoded(value: String) = "#2" + Base64.encode(value.encodeToByteArray()).let {
        it.take(8) + ":<:" + Base64.encode("2owKDUoGzsuLNEyhNx".encodeToByteArray()) + it.drop(8)
    }
    private fun payload(value: String, restricted: Boolean = false) = buildJsonObject {
        put("type", "success")
        putJsonObject("message") { putJsonObject("translations") {
            if (restricted) put("lv", true)
            putJsonObject("video") { put("Original audio", value) }
        } }
    }.toString()
    private val playlist = """[{"title":"Сезон 2","folder":[
        {"title":"Серия 6 (Сезон 2)","id":"s2e6","file":"[720p]https://cdn.example/wrong.mp4"},
        {"title":"Серия 5 (Сезон 2)","id":"s2e5","file":"[480p]https://cdn.example/right.mp4,[720p]https://cdn.example/right.m3u8"}]}]"""

    @Test fun `movie decoding preserves actual choices and required headers without reloading after inspection`() = runBlocking {
        var calls = 0
        val transport = object : SourceHttp {
            override suspend fun read(url: String, referer: String, request: VideoRequest, context: SourceRequestContext, form: Map<String,String>?): String {
                calls++
                return if (url == movieUrl) html() else payload(encoded("[480p]https://cdn.example/movie.mp4,[1080p]https://cdn.example/movie.m3u8"))
            }
        }
        FilmixSourcePlugin(transport).use { plugin ->
            assertEquals("Title", plugin.inspect(request(), allow).title)
            val source = plugin.resolve(request(), allow)
            assertEquals(2, calls)
            assertEquals(listOf("480p", "1080p"), source.qualityOptions.map { it.label })
            assertEquals(listOf("Original audio"), source.audioTrackOptions.map { it.label })
            assertEquals(StreamKind.Hls, source.variants.last().kind)
            assertEquals(movieUrl, source.headers["Referer"])
            assertEquals("site", source.sourceId)
        }
    }

    @Test fun `series resolves exact numbered episode regardless of playlist order`() = runBlocking {
        val transport = object : SourceHttp {
            override suspend fun read(url: String, referer: String, request: VideoRequest, context: SourceRequestContext, form: Map<String,String>?) = when {
                url == seriesUrl -> html()
                url.endsWith("playlist.txt") -> encoded(playlist)
                else -> payload(encoded("https://filmix.gg/playlist.txt"))
            }
        }
        FilmixSourcePlugin(transport).use { plugin ->
            val identity = plugin.inspect(request(true), allow)
            assertEquals(2, identity.seasonNumber)
            assertEquals(5, identity.episodeNumber)
            assertTrue(plugin.resolve(request(true), allow).variants.all { "right" in it.url })
            val missing = request(true).copy(mediaMatch = request(true).mediaMatch!!.copy(episodeNumber = 7))
            assertEquals(SourceFailureReason.NotFound, assertFailsWith<SourceFailure> { plugin.inspect(missing, allow) }.reason)
        }
        assertFailsWith<SourceFailure> { FilmixFormat.episodeFile(playlist.replace("s2e5", "s2e6"), 2, 5) }
        assertFailsWith<SourceFailure> { FilmixFormat.episodeFile(playlist.replace("Серия 6", "Серия 5").replace("s2e6", "s2e5"), 2, 5) }
        Unit
    }

    @Test fun `wrong year and source restrictions never produce a stream`() = runBlocking {
        var year = 2009
        var playerRequests = 0
        val transport = object : SourceHttp {
            override suspend fun read(url: String, referer: String, request: VideoRequest, context: SourceRequestContext, form: Map<String,String>?) = when {
                url == movieUrl -> html(year)
                "suggestions" in url -> """{"posts":[]}"""
                else -> { playerRequests++; payload("https://cdn.example/movie.mp4", restricted = true) }
            }
        }
        FilmixSourcePlugin(transport).use { plugin ->
            assertEquals(SourceFailureReason.NotFound, assertFailsWith<SourceFailure> { plugin.inspect(request(), allow) }.reason)
            assertEquals(0, playerRequests)
            year = 2008
            assertEquals(SourceFailureReason.Restricted, assertFailsWith<SourceFailure> { plugin.inspect(request(), allow) }.reason)
        }
    }

    @Test fun `search rejects remakes and different media types`() = runBlocking {
        val posts = """{"posts":[
            {"title":"Title","original_name":"Original","year":2008,"link":"$movieUrl"},
            {"title":"Title","year":2020,"link":"https://filmix.gg/film/drama/88-remake.html"},
            {"title":"Title","year":2008,"link":"$seriesUrl"}]}"""
        val transport = object : SourceHttp {
            override suspend fun read(url: String, referer: String, request: VideoRequest, context: SourceRequestContext, form: Map<String,String>?) =
                if ("suggestions" in url) posts else "homepage"
        }
        FilmixSourcePlugin(transport).use { plugin ->
            val found = plugin.search(request().copy(pageUrl = ""), allow)
            assertEquals(listOf(movieUrl), found.map { it.pageUrl })
            assertEquals(listOf("Original"), found.single().alternativeTitles)
        }
    }

    @Test fun `transport filters before requests and refuses cross site redirects and denial`() = runBlocking {
        var hits = 0
        val http = FilmixHttp(HttpClient(MockEngine {
            hits++
            if (it.url.encodedPath == "/denied") respond("denied", HttpStatusCode.Forbidden)
            else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.example/redirect"))
        }))
        try {
            val blocked = SourceRequestContext { NetworkRequestDecision.Block("test") }
            assertEquals(SourceFailureReason.RequestBlocked, assertFailsWith<SourceFailure> { http.read(movieUrl, movieUrl, request(), blocked) }.reason)
            assertEquals(0, hits)
            assertEquals(SourceFailureReason.AccessDenied, assertFailsWith<SourceFailure> { http.read(movieUrl, movieUrl, request(), allow) }.reason)
            assertEquals(1, hits)
            assertEquals(SourceFailureReason.AccessDenied, assertFailsWith<SourceFailure> { http.read("https://filmix.gg/denied", movieUrl, request(), allow) }.reason)
        } finally { http.close() }
    }

    @Test fun `transport decodes the websites declared Windows charset and JSON UTF8`() = runBlocking {
        val title = "Во все тяжкие"
        val http = FilmixHttp(HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith(".html")) respond(title.toByteArray(java.nio.charset.Charset.forName("windows-1251")),
                headers = headersOf(HttpHeaders.ContentType, "text/html; charset=windows-1251"))
            else respond(title.encodeToByteArray(), headers = headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"))
        }))
        try {
            assertEquals(title, http.read(movieUrl, movieUrl, request(), allow))
            assertEquals(title, http.read("https://filmix.gg/json", movieUrl, request(), allow))
        } finally { http.close() }
    }
}
