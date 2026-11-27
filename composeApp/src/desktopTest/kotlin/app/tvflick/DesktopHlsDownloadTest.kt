package app.tvflick

import app.tvflick.plugin.api.*
import app.tvflick.ui.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class DesktopHlsDownloadTest {
    private val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nclip.ts\n#EXT-X-ENDLIST\n"
    private val source = ResolvedVideoSource("https://origin.test/start", StreamKind.Hls,
        headers = mapOf("Cookie" to "private", "Authorization" to "private"), sourceId = "test")

    @Test fun `redirect base and credential scope are retained through offline assembly`() = runBlocking {
        val folder = Files.createTempDirectory("hls-test").toFile()
        try {
            val destination = folder.resolve("movie.mp4").apply { writeText("old") }
            val http = HttpClient(MockEngine { request ->
                when (request.url.host) {
                    "origin.test" -> {
                        assertEquals("private", request.headers[HttpHeaders.Cookie])
                        assertEquals("private", request.headers[HttpHeaders.Authorization])
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.test/media/list.m3u8"))
                    }
                    else -> {
                        assertNull(request.headers[HttpHeaders.Cookie]); assertNull(request.headers[HttpHeaders.Authorization])
                        when (request.url.encodedPath) {
                            "/media/list.m3u8" -> respond(manifest)
                            "/media/clip.ts" -> respond("segment")
                            else -> error("Wrong relative URL")
                        }
                    }
                }
            })
            downloadHls(source, destination, http) { local, output ->
                assertFalse(local.readText().contains("https:"))
                assertEquals("segment", local.parentFile.resolve("asset-0.ts").readText())
                assertEquals("old", destination.readText())
                output.writeText("assembled")
            }
            assertEquals("assembled", destination.readText())
            assertEquals(listOf("movie.mp4"), folder.list()!!.toList())
        } finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }

    @Test fun `failed segment or mux and cancelled download preserve original and remove partials`() = runBlocking {
        for (failure in listOf("segment", "mux", "cancel")) {
            val folder = Files.createTempDirectory("hls-failure").toFile()
            try {
                val file = folder.resolve("movie.mp4").apply { writeText("old") }
                val http = HttpClient(MockEngine { request ->
                    if (request.url.encodedPath == "/start") respond(manifest)
                    else if (failure == "segment") respond("denied", HttpStatusCode.Forbidden)
                    else respond("segment")
                })
                val operation: suspend () -> Unit = { downloadHls(source, file, http) { _, output ->
                    output.writeText("partial mux")
                    if (failure == "cancel") throw CancellationException("cancelled")
                    error("mux failed")
                } }
                if (failure == "cancel") assertFailsWith<CancellationException> { operation() }
                else assertFailsWith<IllegalStateException> { operation() }
                assertEquals("old", file.readText())
                assertEquals(listOf("movie.mp4"), folder.list()!!.toList())
            } finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
        }
    }

    @Test fun `fmp4 maps become local and unsupported playlists fail without guessing`() {
        val fmp4 = parseOfflineHls(manifest.replace("#EXTINF", "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF"), source.url)
        assertEquals(listOf("asset-0.mp4", "asset-1.m4s"), fmp4.assets.map { it.name })
        assertTrue(fmp4.manifest.contains("#EXT-X-MAP:URI=\"asset-0.mp4\""))
        val unsupported = listOf(manifest.replace("#EXT-X-ENDLIST", ""),
            manifest.replace("clip.ts", "file:///C:/private/file"),
            manifest.replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF"),
            manifest.replace("#EXTINF", "#EXT-X-BYTERANGE:20@0\n#EXTINF"),
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=10000\nother.m3u8\n#EXT-X-ENDLIST")
        unsupported.forEach { assertFailsWith<UnsupportedMediaDownload> { parseOfflineHls(it, source.url) } }
    }
}
