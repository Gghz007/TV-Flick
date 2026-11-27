package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlin.test.*

class HlsCatalogTest {
    private val request = VideoRequest(42, "https://site.test/title", ADAPTIVE_SOURCE_PLUGIN_ID, "local-site", "https://site.test")
    private val allow = SourceRequestContext { NetworkRequestDecision.Allow }
    private val master = """#EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
        low/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
        high/index.m3u8
    """.trimIndent()

    @Test fun `redirected HLS catalogs preserve audio groups and expose exact quality URLs`() = runBlocking {
        val source = ResolvedVideoSource("https://site.test/a/master.m3u8", StreamKind.Hls,
            headers = mapOf("Cookie" to "private", "Referer" to "https://site.test/title"), sourceId = "local-site",
            audioTrackOptions = listOf(PlaybackOption("a", "Original"), PlaybackOption("b", "Dub")),
            variants = listOf(
                ResolvedStreamVariant("https://site.test/a/master.m3u8", StreamKind.Hls, "a", "default", mapOf("Cookie" to "private", "Referer" to "https://site.test/title")),
                ResolvedStreamVariant("https://cdn.test/b/master.m3u8", StreamKind.Hls, "b", "default")))
        val seen = mutableListOf<VideoNetworkRequest>()
        val http = HttpClient(MockEngine { incoming ->
            if (incoming.url.host == "site.test") {
                assertEquals("private", incoming.headers[HttpHeaders.Cookie])
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.test/a/master.m3u8"))
            } else {
                assertNull(incoming.headers[HttpHeaders.Cookie])
                respond(if (incoming.url.encodedPath.startsWith("/b/")) master.substringBefore("        #EXT-X-STREAM-INF:BANDWIDTH=3000000") else master)
            }
        })
        HlsCatalogResolver(http).use { resolver ->
            val result = resolver.expand(source, request, SourceRequestContext {
                seen += it
                // An aggressive secondary-resource rule must not intercept primary CDN hops.
                if (it.kind == VideoResourceKind.MediaManifest) NetworkRequestDecision.Allow
                else NetworkRequestDecision.Block("secondary request")
            })
            assertEquals("https://cdn.test/a/low/index.m3u8", result.url)
            assertEquals(listOf("a", "a", "b"), result.variants.map { it.audioId })
            assertEquals(listOf("720p", "1080p", "720p"), result.qualityOptions.map { it.label })
            assertEquals(source.audioTrackOptions, result.audioTrackOptions)
            assertEquals("https://site.test/title", result.headers["Referer"])
            assertFalse(result.headers.containsKey("Cookie"))
            assertTrue(seen.all { it.sourceId == "local-site" })
            assertTrue(seen.any { it.url == "https://cdn.test/a/master.m3u8" })
            assertTrue(seen.all { it.kind == VideoResourceKind.MediaManifest })
            assertTrue(seen.any { it.url.endsWith("high/index.m3u8") && it.kind == VideoResourceKind.MediaManifest })
        }
    }

    @Test fun `external audio malformed and media playlists are never flattened`() = runBlocking {
        val source = ResolvedVideoSource("https://cdn.test/master.m3u8", StreamKind.Hls, sourceId = "local-site")
        val documents = listOf(
            master.replace("BANDWIDTH=1500000", "BANDWIDTH=1500000,AUDIO=\"separate\""),
            master.replace("#EXTM3U", "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"English\",URI=\"audio.m3u8\""),
            master.replace("BANDWIDTH=1500000", "BANDWIDTH=1500000,BANDWIDTH=2000000"),
            master.replace("low/index.m3u8", "file:///private/file"),
            "#EXTM3U\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST")
        for (text in documents) HlsCatalogResolver(HttpClient(MockEngine { respond(text) })).use {
            assertSame(source, it.expand(source, request, allow))
        }
    }

    @Test fun `blocked manifests and cancellation do not silently fall back to original URL`() = runBlocking {
        val source = ResolvedVideoSource("https://cdn.test/master.m3u8", StreamKind.Hls, sourceId = "local-site")
        HlsCatalogResolver(HttpClient(MockEngine { respond(master) })).use {
            val failure = assertFailsWith<SourceFailure> { it.expand(source, request, SourceRequestContext {
                if (it.url.endsWith("high/index.m3u8")) NetworkRequestDecision.Block("blocked") else NetworkRequestDecision.Allow
            }) }
            assertEquals(SourceFailureReason.RequestBlocked, failure.reason)
        }
        HlsCatalogResolver(HttpClient(MockEngine { throw CancellationException("cancelled") })).use {
            assertFailsWith<CancellationException> { it.expand(source, request, allow) }
        }
        HlsCatalogResolver(HttpClient(MockEngine { respond("offline", HttpStatusCode.ServiceUnavailable) })).use {
            assertSame(source, it.expand(source, request, allow))
        }
    }
}
