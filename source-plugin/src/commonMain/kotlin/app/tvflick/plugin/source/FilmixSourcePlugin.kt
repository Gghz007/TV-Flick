package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import app.tvflick.plugin.engine.MediaCandidateMatcher
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.TimeSource

/** Source metadata identifies a stream only. This module has no access to library/watch storage. */
class FilmixSourcePlugin(private val http: SourceHttp = FilmixHttp()) : VideoSourcePlugin, AutoCloseable {
    override val id = "tvflick.source.filmix"
    override val version = "0.1.0"
    private val mutex = Mutex()
    private data class Verified(val request: VideoRequest, val candidate: ParsedMediaCandidate,
        val source: ResolvedVideoSource, val created: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow())
    private var verified: Verified? = null
    override fun close() { (http as? AutoCloseable)?.close() }
    override fun supports(baseUrl: String): Boolean = isFilmixUrl(baseUrl) &&
        Url(baseUrl).encodedPath.trim('/') == "" && Url(baseUrl).encodedQuery.isEmpty()

    override suspend fun search(request: VideoRequest, context: SourceRequestContext): List<ParsedMediaCandidate> = mutex.withLock {
        validate(request)
        val query = requireNotNull(request.mediaMatch)
        http.read(BASE, BASE, request, context) // Establish the site's ordinary guest cookie session.
        val names = (listOfNotNull(query.originalTitle, query.title) + query.alternativeTitles)
            .map(String::trim).filter(String::isNotBlank).distinct().take(6)
        val results = linkedMapOf<String, ParsedMediaCandidate>()
        for (name in names) {
            val url = URLBuilder(BASE + "api/v2/suggestions").apply { parameters.append("search_word", name) }.buildString()
            val response = FilmixFormat.json(http.read(url, BASE, request, context)) as? JsonObject ?: FilmixFormat.fail()
            val posts = response["posts"] as? JsonArray ?: FilmixFormat.fail()
            for (element in posts.take(12)) {
                val row = element as? JsonObject ?: continue
                val pageUrl = row.text("link")?.takeIf(::isFilmixUrl) ?: continue
                val type = when {
                    Url(pageUrl).encodedPath.startsWith("/seria/") -> MatchedMediaType.TvEpisode
                    Url(pageUrl).encodedPath.startsWith("/film/") -> MatchedMediaType.Movie
                    else -> continue
                }
                if (type != query.mediaType) continue
                val candidate = ParsedMediaCandidate(row.text("title") ?: continue,
                    listOfNotNull(row.text("original_name")), row.text("year")?.toIntOrNull(), type, pageUrl = pageUrl)
                if (!titleMatches(query, candidate)) continue
                results[pageUrl] = candidate
            }
            if (results.isNotEmpty()) break
        }
        if (query.mediaType == MatchedMediaType.Movie) return@withLock results.values.toList()
        // A series search result is not an episode. Confirm the exact playlist entry first.
        results.values.take(6).mapNotNull { candidate ->
            try {
                load(request.copy(pageUrl = candidate.pageUrl), context, candidate.alternativeTitles).candidate
            } catch (e: SourceFailure) {
                if (e.reason == SourceFailureReason.NotFound) null else throw e
            }
        }
    }

    override suspend fun inspect(request: VideoRequest, context: SourceRequestContext): ParsedMediaCandidate = mutex.withLock {
        verified = null
        load(request, context).also { verified = it }.candidate
    }

    override suspend fun resolve(request: VideoRequest): ResolvedVideoSource =
        throw SourceFailure(SourceFailureReason.RequestBlocked) // Always use Engine's per-site request context.

    override suspend fun resolve(request: VideoRequest, context: SourceRequestContext): ResolvedVideoSource = mutex.withLock {
        val saved = verified
        verified = null
        if (saved != null && saved.request == request && saved.created.elapsedNow().inWholeSeconds < 30) saved.source
        else load(request, context).source
    }

    private fun validate(request: VideoRequest) {
        require(supports(request.sourceSiteBaseUrl) && request.sourcePluginId == id)
        val query = requireNotNull(request.mediaMatch)
        if (query.mediaType == MatchedMediaType.TvEpisode)
            require((query.seasonNumber ?: -1) >= 0 && (query.episodeNumber ?: 0) > 0)
    }

    private suspend fun load(request: VideoRequest, context: SourceRequestContext,
        searchAlternatives: List<String> = emptyList()): Verified {
        validate(request)
        if (!isFilmixUrl(request.pageUrl)) throw SourceFailure(SourceFailureReason.AccessDenied)
        val query = requireNotNull(request.mediaMatch)
        val page = parseFilmixPage(http.read(request.pageUrl, BASE, request, context), request.pageUrl)
        var candidate = ParsedMediaCandidate(page.title, (page.alternatives + searchAlternatives).distinct(), page.year, page.type,
            pageUrl = request.pageUrl)
        if (page.type == query.mediaType && !titleMatches(query, candidate)) {
            // Some detail pages omit the original name. Read it independently from the site's
            // search result for this exact page ID/year, rather than copying Core's expected title.
            val lookup = URLBuilder(BASE + "api/v2/suggestions").apply { parameters.append("search_word", page.title) }.buildString()
            val response = FilmixFormat.json(http.read(lookup, request.pageUrl, request, context)) as? JsonObject ?: FilmixFormat.fail()
            val names = (response["posts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.text("id") == page.siteId && it.text("year")?.toIntOrNull() == page.year && it.text("link") == request.pageUrl }
                .flatMap { listOfNotNull(it.text("title"), it.text("original_name")) }
            candidate = candidate.copy(alternativeTitles = (candidate.alternativeTitles + names).distinct())
        }
        if (page.type != query.mediaType || !titleMatches(query, candidate)) throw SourceFailure(SourceFailureReason.NotFound)
        val data = FilmixFormat.json(http.read(BASE + "api/movies/player-data", request.pageUrl, request, context,
            mapOf("post_id" to page.siteId, "showfull" to "true"))) as? JsonObject ?: FilmixFormat.fail()
        if (data.text("type") != "success") throw SourceFailure(SourceFailureReason.Unavailable)
        val translations = (data["message"] as? JsonObject)?.get("translations") as? JsonObject ?: FilmixFormat.fail()
        if (translations["lv"]?.let { it != JsonNull && it.toString() !in listOf("false", "0", "\"\"") } == true)
            throw SourceFailure(SourceFailureReason.Restricted)
        val videos = translations["video"] as? JsonObject ?: throw SourceFailure(SourceFailureReason.NotFound)
        if (videos.size > 24) FilmixFormat.fail()
        val variants = mutableListOf<ResolvedStreamVariant>()
        val headers = mapOf("Referer" to request.pageUrl, "Origin" to BASE.trimEnd('/'))
        for ((label, raw) in videos) {
            if (label.isBlank()) continue
            var value = (raw as? JsonPrimitive)?.contentOrNull ?: FilmixFormat.fail()
            if (page.type == MatchedMediaType.TvEpisode) {
                val playlistUrl = FilmixFormat.decode(value)
                if (!isFilmixUrl(playlistUrl)) FilmixFormat.fail()
                val playlist = http.read(playlistUrl, request.pageUrl, request, context)
                value = FilmixFormat.episodeFile(playlist, query.seasonNumber!!, query.episodeNumber!!) ?: continue
            } else if (translations.text("pl") == "yes") FilmixFormat.fail()
            variants += FilmixFormat.variants(value, label, headers)
        }
        val selected = variants.firstOrNull() ?: throw SourceFailure(SourceFailureReason.NotFound)
        if (page.type == MatchedMediaType.TvEpisode) candidate = candidate.copy(seasonNumber = query.seasonNumber, episodeNumber = query.episodeNumber)
        if (MediaCandidateMatcher.score(query, candidate) == null) throw SourceFailure(SourceFailureReason.NotFound)
        val source = ResolvedVideoSource(selected.url, selected.kind, selected.headers,
            qualityOptions = variants.map { it.qualityId }.filter(String::isNotBlank).distinct().map { PlaybackOption(it, it) },
            audioTrackOptions = variants.map { it.audioId }.distinct().map { PlaybackOption(it, it) },
            sourceId = request.sourceSiteId, variants = variants)
        return Verified(request, candidate, source)
    }

    private fun titleMatches(query: MediaMatchQuery, candidate: ParsedMediaCandidate): Boolean =
        MediaCandidateMatcher.score(query.copy(mediaType = MatchedMediaType.Movie), candidate.copy(mediaType = MatchedMediaType.Movie)) != null

    companion object { private const val BASE = "https://filmix.gg/" }
}
