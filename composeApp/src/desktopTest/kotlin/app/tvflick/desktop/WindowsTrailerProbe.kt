package app.tvflick.desktop

import app.tvflick.core.tmdb.YoutubeTrailer
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit real-provider probe. The hidden test window only waits for the official controls to load. */
fun main(args: Array<String>) {
    val host = File(args.single()).absoluteFile
    val child = ProcessBuilder(host.path, ProcessHandle.current().pid().toString(), "0", "--probe")
        .directory(host.parentFile).redirectErrorStream(true).start()
    try {
        child.outputStream.use { it.write(YoutubeTrailer("M7lc1UVf-VE").playerHtml().encodeToByteArray()) }
        check(child.waitFor(35, TimeUnit.SECONDS)) { "The WebView2 probe did not complete" }
        val output = child.inputStream.bufferedReader().use { it.readText() }
        check(child.exitValue() == 0 && "YOUTUBE_IFRAME_READY" in output) {
            "The WebView2/YouTube probe did not become ready: $output"
        }
        println("YOUTUBE_IFRAME_READY")
    } finally { if (child.isAlive) child.destroyForcibly() }
}
