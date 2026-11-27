package app.tvflick.ui

import app.tvflick.plugin.api.ResolvedVideoSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

internal data class HlsAsset(val url: String, val name: String, val initialization: Boolean = false)
internal data class OfflineHls(val manifest: String, val assets: List<HlsAsset>)

/** Finite, clear MPEG-TS/fMP4 media playlists. No guessed quality/audio from a master. */
internal fun parseOfflineHls(text: String, baseUrl: String): OfflineHls {
    val lines = text.trimStart('\uFEFF').lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.firstOrNull() != "#EXTM3U" || "#EXT-X-ENDLIST" !in lines) throw UnsupportedMediaDownload()
    val local = mutableListOf("#EXTM3U")
    val assets = mutableListOf<HlsAsset>()
    var pendingSegment = false
    var fragmented = false
    var segments = 0
    fun addAsset(reference: String, init: Boolean): String {
        val uri = URI(baseUrl).resolve(reference)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.userInfo != null) throw UnsupportedMediaDownload()
        val name = "asset-${assets.size}." + if (init) "mp4" else if (fragmented) "m4s" else "ts"
        assets += HlsAsset(uri.toASCIIString(), name, init)
        if (assets.size > 20_000) throw UnsupportedMediaDownload()
        return name
    }
    for (line in lines.drop(1)) when {
        line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-I-FRAME") ||
            line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-BYTERANGE") ||
            line.startsWith("#EXT-X-GAP") || line.startsWith("#EXT-X-SESSION-KEY") -> throw UnsupportedMediaDownload()
        line.startsWith("#EXT-X-KEY:") -> if (line != "#EXT-X-KEY:METHOD=NONE") throw UnsupportedMediaDownload()
        line.startsWith("#EXT-X-MAP:") -> {
            val reference = Regex("^#EXT-X-MAP:URI=\"([^\"]+)\"$").matchEntire(line)?.groupValues?.get(1)
                ?: throw UnsupportedMediaDownload()
            fragmented = true
            local += "#EXT-X-MAP:URI=\"${addAsset(reference, true)}\""
        }
        line.startsWith("#EXTINF:") -> {
            if (pendingSegment) throw UnsupportedMediaDownload()
            val duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
            if (duration == null || !duration.isFinite() || duration <= 0) throw UnsupportedMediaDownload()
            local += "#EXTINF:$duration,"
            pendingSegment = true
        }
        line.matches(Regex("#EXT-X-(VERSION|TARGETDURATION|MEDIA-SEQUENCE|DISCONTINUITY-SEQUENCE):[0-9]+")) -> local.add(line)
        line == "#EXT-X-DISCONTINUITY" -> local.add(line)
        line.startsWith('#') -> Unit // Never copy unknown URI-bearing directives into the local playlist.
        else -> {
            if (!pendingSegment) throw UnsupportedMediaDownload()
            local += addAsset(line, false)
            pendingSegment = false
            segments++
        }
    }
    if (pendingSegment || segments == 0) throw UnsupportedMediaDownload()
    local += "#EXT-X-ENDLIST"
    return OfflineHls(local.joinToString("\n", postfix = "\n"), assets)
}

private fun scopedHeaders(from: String, to: String, headers: Map<String, String>): Map<String, String> {
    val a = Url(from); val b = Url(to)
    return if (a.host == b.host && a.port == b.port && a.protocol == b.protocol) headers
    else headers.filterKeys { !it.equals("Cookie", true) && !it.equals("Authorization", true) }
}

private data class DownloadResponse(val url: String, val headers: Map<String, String>)

private suspend fun fetchHlsFile(http: HttpClient, initialUrl: String, initialHeaders: Map<String, String>,
                                 file: File, limit: Long): DownloadResponse {
    var url = initialUrl
    var headers = initialHeaders
    repeat(6) {
        val uri = URI(url)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null)
        var next: String? = null
        http.prepareGet(url) { headers { headers.forEach { (key, value) -> append(key, value) } } }.execute { response ->
            if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                next = uri.resolve(requireNotNull(response.headers[HttpHeaders.Location])).toASCIIString()
            } else {
                check(response.status == HttpStatusCode.OK)
                val length = response.contentLength()
                check(length == null || length in 1..limit)
                var bytes = 0L
                val channel = response.bodyAsChannel()
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        bytes += count
                        check(bytes <= limit)
                        output.write(buffer, 0, count)
                    }
                }
                check(bytes > 0 && (length == null || length == bytes))
            }
        }
        if (next == null) return DownloadResponse(url, headers)
        headers = scopedHeaders(url, next!!, headers)
        url = next!!
    }
    error("Too many media redirects")
}

internal suspend fun downloadHls(source: ResolvedVideoSource, destination: File,
    client: HttpClient = HttpClient(), mux: suspend (File, File) -> Unit = { input, output -> muxOfflineHls(input, output) }) {
    val http = client.config {
        followRedirects = false; expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 10_000; socketTimeoutMillis = 30_000 }
    }
    var directory: File? = null
    try { withContext(Dispatchers.IO) {
        val working = Files.createTempDirectory(destination.absoluteFile.parentFile.toPath(), ".tvflick-hls-").toFile()
        directory = working
        val downloaded = working.resolve("remote.m3u8")
        val response = fetchHlsFile(http, source.url, source.headers, downloaded, 2L * 1024 * 1024)
        val playlist = parseOfflineHls(downloaded.readText(), response.url)
        // Only a bounded number of segment buffers/requests exists at once.
        for (batch in playlist.assets.chunked(3)) coroutineScope {
            batch.map { asset -> async {
                fetchHlsFile(http, asset.url, scopedHeaders(response.url, asset.url, response.headers),
                    working.resolve(asset.name), if (asset.initialization) 8L * 1024 * 1024 else 128L * 1024 * 1024)
            } }.awaitAll()
        }
        downloaded.delete()
        val manifest = working.resolve("local.m3u8").apply { writeText(playlist.manifest) }
        val output = working.resolve("video.mp4")
        mux(manifest, output)
        check(output.isFile && output.length() > 0)
        currentCoroutineContext().ensureActive()
        Files.move(output.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } } finally {
        http.close(); client.close()
        // Only our freshly created, flat temporary directory is removed.
        withContext(NonCancellable + Dispatchers.IO) {
            directory?.let { folder -> folder.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }; Files.deleteIfExists(folder.toPath()) }
        }
    }
}

internal fun bundledFfmpeg(): File {
    val root = System.getProperty("compose.application.resources.dir") ?: throw UnsupportedMediaDownload()
    return File(root, "ffmpeg/bin/ffmpeg.exe").takeIf { it.isFile } ?: throw UnsupportedMediaDownload()
}

internal suspend fun muxOfflineHls(manifest: File, output: File, executable: File = bundledFfmpeg()) = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(executable.absolutePath, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-protocol_whitelist", "file", "-allowed_extensions", "ALL", "-i", manifest.absolutePath,
        "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-movflags", "+faststart", "-f", "mp4", output.absolutePath)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        withTimeout(15 * 60_000L) { while (process.isAlive) delay(100) }
        currentCoroutineContext().ensureActive()
        check(process.exitValue() == 0 && output.length() > 0) { "Video assembly failed" }
    } finally {
        if (process.isAlive) withContext(NonCancellable) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Video assembly did not stop" }
        }
    }
}
