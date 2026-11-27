package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class StructuredSearchHttpTest {
    private val request = VideoRequest(7, "", "tvflick.source.structured-web", "configured-site", "https://site.test",
        MediaMatchQuery("Example & Co", releaseYear = 2020, mediaType = MatchedMediaType.Movie))
    private val allow = SourceRequestContext { NetworkRequestDecision.Allow }

    @Test fun `POST search follows relative redirects skips stale results and resolves final page media`() = runBlocking {
        val calls = mutableListOf<String>()
        val transport = SiteHttp(HttpClient(MockEngine { incoming ->
            val path = incoming.url.encodedPath
            calls += path
            when (path) {
                "", "/" -> respond("""<form role="search" method="post" action="/find?area=movies">
                    <input type="search" name="q"><input type="hidden" name="token" value="guest">
                    <input type="hidden" name="q" value="stale"><input type="hidden" name="ignore" value="x" disabled>
                    </form>""")
                "/find" -> {
                    assertEquals(HttpMethod.Post, incoming.method)
                    assertEquals("movies", incoming.url.parameters["area"])
                    assertNull(incoming.url.parameters["q"])
                    val form = parseQueryString((incoming.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    assertEquals(listOf("Example & Co"), form.getAll("q"))
                    assertEquals("guest", form["token"])
                    assertNull(form["ignore"])
                    respond("", HttpStatusCode.SeeOther, headersOf(HttpHeaders.Location, "results/list"))
                }
                "/results/list" -> {
                    assertEquals(HttpMethod.Get, incoming.method)
                    respond("""<main><article><a href="/gone">stale</a><a href="../old">Example</a></article></main>""")
                }
                "/gone" -> respond("missing", HttpStatusCode.NotFound)
                "/old" -> respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/catalog/title/"))
                "/catalog/title/" -> respond("""<script type="application/ld+json">{
                    "@type":"Movie","name":"Example & Co","datePublished":"2020-01-01",
                    "encoding":{"@type":"VideoObject","contentUrl":"video.mp4"}}
                    </script>""")
                else -> error("Unexpected path")
            }
        }))
        StructuredWebSource(transport).use { source ->
            val candidate = source.search(request, allow).single()
            assertEquals("https://site.test/catalog/title/", candidate.pageUrl)
            val selected = request.copy(pageUrl = candidate.pageUrl)
            assertEquals(candidate, source.inspect(selected, allow))
            val stream = source.resolve(selected, allow)
            assertEquals("https://site.test/catalog/title/video.mp4", stream.url)
            assertEquals(candidate.pageUrl, stream.headers["Referer"])
            assertTrue(calls.indexOf("/gone") < calls.indexOf("/old"))
        }
    }

    @Test fun `redirect methods are preserved only for 307 and 308 and policy is checked before forwarding`() = runBlocking {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val seen = mutableListOf<VideoNetworkRequest>()
            SiteHttp(HttpClient(MockEngine { incoming ->
                if (incoming.url.encodedPath == "/form")
                    respond("", HttpStatusCode.fromValue(status), headersOf(HttpHeaders.Location, "nested/result"))
                else {
                    assertEquals("/nested/result", incoming.url.encodedPath)
                    assertEquals(if (status >= 307) HttpMethod.Post else HttpMethod.Get, incoming.method)
                    if (status >= 307) assertEquals("q=hello", (incoming.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    respond("done")
                }
            })).use { http ->
                val result = http.readDocument("https://site.test/form", request.sourceSiteBaseUrl, request,
                    SourceRequestContext { seen += it; NetworkRequestDecision.Allow }, mapOf("q" to "hello"))
                assertEquals(SourceDocument("done", "https://site.test/nested/result"), result)
                assertEquals(VideoResourceKind.Redirect, seen.last().kind)
            }
        }
        for (target in listOf("https://other.test/collect", "/blocked")) {
            var calls = 0
            SiteHttp(HttpClient(MockEngine {
                calls++
                respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, target))
            })).use { http ->
                val failure = assertFailsWith<SourceFailure> {
                    http.readDocument("https://site.test/form", request.sourceSiteBaseUrl, request,
                        SourceRequestContext { if (it.url.endsWith("/blocked")) NetworkRequestDecision.Block("test") else NetworkRequestDecision.Allow },
                        mapOf("token" to "guest"))
                }
                assertEquals(if (target.startsWith("https")) SourceFailureReason.AccessDenied else SourceFailureReason.RequestBlocked, failure.reason)
                assertEquals(1, calls)
            }
        }
    }
}
