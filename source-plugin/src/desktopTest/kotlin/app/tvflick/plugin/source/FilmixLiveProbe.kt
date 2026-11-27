package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import app.tvflick.plugin.engine.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking

/** Explicit developer probe; prints no signed stream URLs, cookies or response bodies. */
fun main() = runBlocking {
    AdaptiveSourcePlugin(listOf(FilmixSourcePlugin(FilmixHttp(diagnostic = ::println)))).use { plugin ->
        val engine = PluginEngine(PluginRegistry(videoSources = listOf(plugin)))
        val queries = listOf(
            MediaMatchQuery("Киборг 3: Переработчик", originalTitle = "Cyborg 3: The Recycler", releaseYear = 1994, mediaType = MatchedMediaType.Movie),
            MediaMatchQuery("Во все тяжкие", originalTitle = "Breaking Bad", releaseYear = 2008, mediaType = MatchedMediaType.TvEpisode,
                seasonNumber = 2, episodeNumber = 5),
        )
        HttpClient { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 15_000 } }.use { probe ->
            for ((index, query) in queries.withIndex()) {
                val request = VideoRequest(index + 1L, "", plugin.id, "probe", "https://filmix.gg", query)
                try {
                    val match = engine.discover(request)
                    check(match is MediaMatchResult.Confirmed) { "Discovery returned ${match::class.simpleName}" }
                    val source = engine.resolveVerified(request.copy(pageUrl = match.candidate.pageUrl))
                    println("${query.mediaType}: exact match; audio=${source.audioTrackOptions.size}; quality=${source.qualityOptions.map { it.label }}; variants=${source.variants.size}")
                    val response = probe.head(source.url) { source.headers.forEach { (key, value) -> header(key, value) } }
                    println("${query.mediaType}: stream HEAD=${response.status.value}; host=${Url(source.url).host}; kind=${source.kind}")
                } catch (e: SourceFailure) {
                    error("${query.mediaType}: source failure ${e.reason}")
                }
            }
        }
    }
}
