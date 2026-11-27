package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import app.tvflick.plugin.engine.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Opt-in developer check of the user's public source. Never executes provider JS or plays video. */
fun main() = runBlocking {
    AdaptiveSourcePlugin().use { plugin ->
        val engine = PluginEngine(PluginRegistry(videoSources = listOf(plugin)))
        val queries = listOf(
            MediaMatchQuery("Гангстерленд", originalTitle = "MobLand", releaseYear = 2025,
                mediaType = MatchedMediaType.TvEpisode, seasonNumber = 1, episodeNumber = 1),
            MediaMatchQuery("Матрица", originalTitle = "The Matrix", releaseYear = 1999, mediaType = MatchedMediaType.Movie),
        )
        var failed = false
        HttpClient { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 10_000 } }.use { http ->
            for ((index, query) in queries.withIndex()) try { withTimeout(45_000) {
                val request = VideoRequest(index + 1L, "", plugin.id, "probe", "https://hdrezka.inc", query)
                val match = engine.discover(request)
                if (match !is MediaMatchResult.Confirmed) { println("${query.mediaType}: discovery=${match::class.simpleName}"); failed = true }
                else {
                    val source = engine.resolveVerified(request.copy(pageUrl = match.candidate.pageUrl))
                    val response = http.head(source.url) { source.headers.forEach { (key, value) -> header(key, value) } }
                    println("${query.mediaType}: confirmed; season=${match.candidate.seasonNumber}; episode=${match.candidate.episodeNumber}; audio=${source.audioTrackOptions.size}; quality=${source.qualityOptions.size}; kind=${source.kind}; HEAD=${response.status.value}")
                    if (response.status.value !in 200..299) failed = true
                }
            } } catch (e: SourceFailure) { failed = true; println("${query.mediaType}: ${e.reason}") }
            catch (e: Exception) { failed = true; println("${query.mediaType}: ${e::class.simpleName}") }
        }
        check(!failed) { "Source probe incomplete; see sanitized results above" }
    }
}
