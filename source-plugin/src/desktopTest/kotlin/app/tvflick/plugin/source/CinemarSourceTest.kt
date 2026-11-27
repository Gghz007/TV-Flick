package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class CinemarSourceTest {
    private fun playlistText(): String = """[{"id":"s01","title":"Сезон 1","folder":[
        {"id":"s01e01","title":"Серия 1","folder":[{"id":"dub","title":"Dub","data":"episode-one"}]},
        {"id":"s01e02","title":"Серия 2","folder":[{"id":"dub","title":"Dub","data":"episode-two"}]}]}]"""
    private fun encode(value: String): String = "#237" + Base64.encode(value.encodeToByteArray()).chunked(80).joinToString("%") { part ->
        if (part.length <= 32) part else part.takeLast(2) + "zz" + part.dropLast(2) + "xx" + "2"
    }
    private val query = MediaMatchQuery("Example", releaseYear = 2020, mediaType = MatchedMediaType.TvEpisode, seasonNumber = 1, episodeNumber = 2)

    @Test fun `public playlist rejects wrong episode contradictory IDs duplicates and movie confusion`() {
        val root = CinemarFormat.playlist(encode(playlistText()))
        assertEquals("episode-two", CinemarFormat.tracks(root, query).single().text("data"))
        assertEquals(SourceFailureReason.NotFound, assertFailsWith<SourceFailure> {
            CinemarFormat.tracks(root, query.copy(episodeNumber = 3)) }.reason)
        assertFailsWith<SourceFailure> { CinemarFormat.tracks(root, query.copy(mediaType = MatchedMediaType.Movie)) }
        assertFailsWith<SourceFailure> { CinemarFormat.tracks(CinemarFormat.playlist(encode(playlistText().replace("Серия 2", "Серия 8"))), query) }
        assertFailsWith<SourceFailure> { CinemarFormat.tracks(JsonArray(listOf(root[0], root[0])), query) }
        assertFailsWith<SourceFailure> { CinemarFormat.playlist("#2not-the-format") }
        assertFailsWith<SourceFailure> { parseCinemarOptions("<script>Cinemar(fetch('other'));</script>") }
    }

    @Test fun `domain independent search confirms the exact episode before JSON request and keeps policy context`() = runBlocking {
        val request = VideoRequest(77, "", "tvflick.source.structured-web", "user-site", "https://catalog.test", query)
        val page = """<script type="application/ld+json">{"@type":"TVEpisode","url":"https://catalog.test/show",
            "name":"Example (1 сезон)","dateCreated":"2020","partOfSeason":"1 сезон","episodeNumber":"1-2 серия"}</script>
            <div class="js-player-container"><iframe data-src="https://cinemar.cc/embed/123/token"></iframe></div>"""
        var resolved = 0
        val context = SourceRequestContext {
            assertEquals("user-site", it.sourceId)
            NetworkRequestDecision.Allow
        }
        val http = SiteHttp(HttpClient(MockEngine { incoming ->
            when (incoming.url.host + incoming.url.encodedPath) {
                "catalog.test/", "catalog.test" -> respond("<form class='js-lightsearch'><input class='js-lightsearch-input'></form>")
                "catalog.test/search/Example" -> respond("<div class='postItem'><a href='/show'>Example</a></div>")
                "catalog.test/show" -> respond(page)
                "cinemar.cc/embed/123/token" -> respond("<script>Cinemar(${buildJsonObject { put("vid",123); put("file",encode(playlistText())) }});</script>")
                "cinemar.cc/api/playlist/load" -> {
                    resolved++
                    assertEquals(HttpMethod.Post, incoming.method)
                    assertEquals(ContentType.Application.Json, incoming.body.contentType)
                    assertEquals(JsonPrimitive("episode-two"), Json.parseToJsonElement(
                        (incoming.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()))
                    respond("""{"file":"https://cdn.test/episode-two.m3u8"}""")
                }
                else -> error("Unexpected endpoint")
            }
        }))
        StructuredWebSource(http).use { source ->
            val candidate = source.search(request, context).single()
            assertEquals(1, candidate.seasonNumber)
            assertEquals(2, candidate.episodeNumber)
            assertEquals(0, resolved)
            val selected = request.copy(pageUrl = candidate.pageUrl)
            assertEquals(candidate, source.inspect(selected, context))
            val stream = source.resolve(selected, context)
            assertEquals("https://cdn.test/episode-two.m3u8", stream.url)
            assertEquals("Dub", stream.audioTrackOptions.single().label)
            assertEquals("user-site", stream.sourceId)
            assertEquals(1, resolved)
            assertFailsWith<SourceFailure> { source.resolve(selected.copy(mediaMatch = query.copy(releaseYear = 2021)), context) }
            assertFailsWith<SourceFailure> { source.resolve(selected, SourceRequestContext {
                if (it.url.contains("cinemar.cc")) NetworkRequestDecision.Block("test") else NetworkRequestDecision.Allow }) }
            assertEquals(1, resolved)
        }
    }
}
