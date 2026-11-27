package app.tvflick

import app.tvflick.plugin.api.*
import app.tvflick.ui.downloadHls
import app.tvflick.ui.muxOfflineHls
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Opt-in real encoder/muxer/decoder smoke test; no app, windows or external website. */
class DesktopHlsNativeTest {
    @Test fun `TS and fMP4 download into independently decodable video and audio`() = runBlocking {
        val path = System.getenv("TVFLICK_TEST_FFMPEG")
        assumeTrue("Set TVFLICK_TEST_FFMPEG for the native HLS smoke test", path != null)
        val ffmpeg = File(path!!)
        check(ffmpeg.isFile)
        val root = Files.createTempDirectory("tvflick-native-hls-").toFile()
        fun run(directory: File, vararg args: String) {
            val process = ProcessBuilder(listOf(ffmpeg.absolutePath, "-nostdin", "-hide_banner", "-loglevel", "error", "-y") + args)
                .directory(directory).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try {
                check(process.waitFor(30, TimeUnit.SECONDS)) { "Native media test timed out" }
                check(process.exitValue() == 0) { "Native media test failed" }
            } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
        }
        try {
            for (type in listOf("mpegts", "fmp4")) {
                val input = root.resolve(type).apply { mkdir() }
                val manifest = input.resolve("index.m3u8")
                run(input, "-f", "lavfi", "-i", "testsrc=size=32x32:rate=10", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                    "-t", "3", "-c:v", if (type == "mpegts") "mpeg2video" else "mpeg4", "-pix_fmt", "yuv420p", "-g", "10",
                    "-c:a", "aac", "-f", "hls", "-hls_segment_type", type, "-hls_time", "1", "-hls_list_size", "0",
                    "-hls_segment_filename", input.resolve("clip%d.${if (type == "mpegts") "ts" else "m4s"}").absolutePath, manifest.absolutePath)
                val http = HttpClient(MockEngine { request -> respond(input.resolve(request.url.encodedPath.removePrefix("/")).readBytes()) })
                val output = root.resolve("$type.mp4")
                downloadHls(ResolvedVideoSource("https://fixture.test/index.m3u8", StreamKind.Hls, sourceId = "fixture"), output, http) { local, video ->
                    muxOfflineHls(local, video, ffmpeg)
                }
                val decoded = root.resolve("$type.framemd5")
                run(root, "-i", output.absolutePath, "-map", "0:v:0", "-map", "0:a:0", "-f", "framemd5", decoded.absolutePath)
                val frames = decoded.readLines().filterNot { it.startsWith('#') }
                assertTrue(frames.count { it.startsWith("0,") } >= 25, "Video frames survived $type assembly")
                assertTrue(frames.count { it.startsWith("1,") } > 0, "Audio survived $type assembly")
                assertFalse(root.list()!!.any { it.startsWith(".tvflick-") })
            }
        } finally { root.walkBottomUp().forEach { it.delete() } }
    }
}
