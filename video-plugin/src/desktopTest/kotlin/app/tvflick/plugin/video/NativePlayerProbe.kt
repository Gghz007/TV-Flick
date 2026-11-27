package app.tvflick.plugin.video

import app.tvflick.plugin.api.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.mpv.MpvMediampPlayer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Explicit native acceptance probe. No window, screenshot, user media or audio output. */
fun main() = runBlocking(Dispatchers.Main) {
    val ffmpeg = File(requireNotNull(System.getenv("TVFLICK_TEST_FFMPEG")))
    check(ffmpeg.isFile)
    val root = Files.createTempDirectory("tvflick-player-probe-").toFile()
    fun encode(vararg args: String) {
        val process = ProcessBuilder(listOf(ffmpeg.absolutePath, "-nostdin", "-hide_banner", "-loglevel", "error", "-y") + args)
            .directory(root).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try { check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "Fixture generation failed" } }
        finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
    }
    var server: HttpServer? = null
    var player: MpvMediampPlayer? = null
    try {
        println("START fixture generation")
        encode("-f", "lavfi", "-i", "testsrc=size=96x64:rate=10", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", "20", "-c:v", "mpeg4", "-pix_fmt", "yuv420p", "-g", "10", "-c:a", "aac", "-movflags", "+faststart", "video.mp4")
        encode("-i", "video.mp4", "-c", "copy", "-f", "hls", "-hls_segment_type", "fmp4", "-hls_time", "2",
            "-hls_list_size", "0", "-hls_segment_filename", "clip%d.m4s", "index.m3u8")
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        println("PASS fixture generation")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> exchange.use {
                if (exchange.requestHeaders.getFirst("X-TVFlick-Probe") != "allowed") {
                    exchange.sendResponseHeaders(403, -1); return@createContext
                }
                val name = exchange.requestURI.path.removePrefix("/")
                requests += name
                val file = root.resolve(name)
                if (file.parentFile != root || !file.isFile) { exchange.sendResponseHeaders(404, -1); return@createContext }
                val bytes = file.readBytes()
                val range = exchange.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                val from = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val to = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                if (from !in bytes.indices || to < from) { exchange.sendResponseHeaders(416, -1); return@createContext }
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Type", when (file.extension) { "m3u8" -> "application/vnd.apple.mpegurl"; else -> "video/mp4" })
                if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${bytes.size}")
                exchange.sendResponseHeaders(if (range == null) 200 else 206, (to - from + 1).toLong())
                exchange.responseBody.write(bytes, from, to - from + 1)
            } }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}/"
        val headers = mapOf("X-TVFlick-Probe" to "allowed")
        val source = ResolvedVideoSource(base + "video.mp4", StreamKind.Mp4, headers, sourceId = "fixture",
            qualityOptions = listOf(PlaybackOption("mp4", "MP4 fixture"), PlaybackOption("hls", "HLS fixture")),
            variants = listOf(ResolvedStreamVariant(base + "video.mp4", StreamKind.Mp4, "a", "mp4", headers),
                ResolvedStreamVariant(base + "index.m3u8", StreamKind.Hls, "a", "hls", headers)))
        println("START native initialization")
        val native = MpvMediampPlayer(Unit, coroutineContext, Dispatchers.Main.immediate) { handle ->
            check(handle.option("ao", "null")) // Decode audio without using the user's speakers.
        }
        player = native
        println("PASS native initialization")
        // The app's Compose surface normally creates these resources. This opt-in probe has no UI.
        // MediaMP 0.5.1 exposes its offscreen D3D11 context only through these module-internal methods.
        check(native.javaClass.getMethod("createRenderContext" + '$' + "mediamp_mpv").invoke(native) == true)
        check(native.javaClass.getMethod("requestSurface" + '$' + "mediamp_mpv",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(native, 96, 64, 0L) == true)
        println("PASS offscreen render context")
        val session = FlickPlayerSession(native, MediaMpVideoEngine(native))
        val saved = mutableListOf<PlaybackSnapshot>()
        suspend fun awaitStage(stage: String, condition: () -> Boolean) {
            try { withTimeout(12_000) {
                while (!condition()) {
                    check(native.state.value.mediaStatus !is MediaStatus.Error) { "$stage: native decoder error" }
                    delay(50)
                }
            } } catch (e: TimeoutCancellationException) { error("$stage timed out; status=${native.state.value.mediaStatus::class.simpleName}; position=${native.currentPositionMillis.value}") }
            println("PASS $stage")
        }
        println("START MP4 open")
        withTimeout(15_000) { session.openPlayback(source, 1, 2_000, PlaybackProgressSink { saved += it }, true) }
        awaitStage("MP4 open/resume") { native.state.value.mediaStatus is MediaStatus.Ready && native.currentPositionMillis.value >= 2_000 }
        val audio = checkNotNull(native.features[AudioLevelController])
        audio.setVolume(.3f); audio.setMute(true)
        session.speed = 1.5f; session.engine.setSpeed(1.5f)
        session.engine.pause()
        awaitStage("pause") { !native.state.value.playWhenReady }
        delay(250)
        val paused = native.currentPositionMillis.value
        delay(300)
        check(kotlin.math.abs(native.currentPositionMillis.value - paused) < 200) { "Pause did not stop playback" }
        session.engine.seekTo(-1_000)
        awaitStage("seek lower bound") { native.currentPositionMillis.value < 700 }
        session.engine.seekTo(7_000)
        awaitStage("paused seek") { kotlin.math.abs(native.currentPositionMillis.value - 7_000) < 700 }
        check(session.switchVariant(source, 1, qualityId = "hls"))
        awaitStage("HLS switch/resume") { native.state.value.mediaStatus is MediaStatus.Ready && native.currentPositionMillis.value >= 6_500 }
        check(!native.state.value.playWhenReady) { "Quality switch lost pause" }
        check(kotlin.math.abs(audio.volume.value - .3f) < .02f && audio.isMute.value) { "Quality switch lost audio settings" }
        check(native.features[PlaybackSpeed]?.value == 1.5f) { "Quality switch lost speed" }
        // Match the SDK Compose surface's disposal path, then attach the same player at another size.
        // This validates render-resource handoff without creating either application window.
        for ((label, width, height) in listOf(Triple("Full to Mini", 48, 32), Triple("Mini to Full", 96, 64))) {
            val position = native.currentPositionMillis.value
            val media = native.mediaData.value
            session.persistPlayback()
            native.javaClass.getMethod("releaseSurface" + '$' + "mediamp_mpv").invoke(native)
            check(native.javaClass.getMethod("createRenderContext" + '$' + "mediamp_mpv").invoke(native) == true)
            check(native.javaClass.getMethod("requestSurface" + '$' + "mediamp_mpv",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                .invoke(native, width, height, 0L) == true)
            session.openPlayback(source, 1, 0, PlaybackProgressSink { saved += it }, true)
            check(native.mediaData.value === media) { "$label reopened the stream" }
            check(kotlin.math.abs(native.currentPositionMillis.value - position) < 200) { "$label lost position" }
            check(!native.state.value.playWhenReady && session.selectedQualityId == "hls") { "$label lost playback state" }
            println("PASS $label preserves session")
        }
        val before = native.currentPositionMillis.value
        session.engine.play()
        awaitStage("HLS advances") { native.currentPositionMillis.value > before + 500 }
        val playingMedia = native.mediaData.value
        val playingPosition = native.currentPositionMillis.value
        native.javaClass.getMethod("releaseSurface" + '$' + "mediamp_mpv").invoke(native)
        check(native.javaClass.getMethod("createRenderContext" + '$' + "mediamp_mpv").invoke(native) == true)
        check(native.javaClass.getMethod("requestSurface" + '$' + "mediamp_mpv",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(native, 48, 32, 0L) == true)
        session.openPlayback(source, 1, 0, PlaybackProgressSink { saved += it }, true)
        check(native.mediaData.value === playingMedia && native.state.value.playWhenReady) { "Active handoff reopened or paused the stream" }
        awaitStage("playing handoff continues") { native.currentPositionMillis.value > playingPosition + 500 }
        check(requests.any { it.endsWith(".m3u8") } && requests.any { it.endsWith(".m4s") }) { "HLS did not fetch manifest/segments with required header" }
        session.closeAndPersistPlayback()
        check(saved.isNotEmpty() && saved.last().contentId == 1L && saved.last().positionMs >= 7_000)
        println("PASS close/progress; native MP4/HLS transport verified without a window")
    } finally {
        player?.close()
        server?.stop(0)
        root.walkBottomUp().forEach { it.delete() }
    }
}
