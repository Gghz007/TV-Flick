package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import app.tvflick.plugin.engine.MediaCandidateMatcher
import io.ktor.http.*

internal data class WebMedia(val identity: ParsedMediaCandidate, val streams: List<ResolvedStreamVariant>)
internal data class WebSearch(val url: String, val queryKey: String, val fields: Map<String, String> = emptyMap(), val post: Boolean = false,
    val pathQuery: Boolean = false)
internal data class WebDocument(val media: List<WebMedia>, val search: WebSearch?, val detailLinks: List<String>, val embedded: CinemarPage? = null)
internal expect fun parseWebDocument(html: String, pageUrl: String): WebDocument

/** Host-independent HTML/structured-data strategy. Never guesses that a trailer is the main film. */
internal class StructuredWebSource(private val http: SourceHttp = SiteHttp()) : VideoSourcePlugin, AutoCloseable {
    private val cinemar = CinemarResolver(http)
    override val id = "tvflick.source.structured-web"
    override val version = "0.1.0"
    override fun supports(baseUrl: String) = webUrl(baseUrl)
    override fun close() { (http as? AutoCloseable)?.close() }

    override suspend fun search(request: VideoRequest, context: SourceRequestContext): List<ParsedMediaCandidate> {
        val query = requireNotNull(request.mediaMatch)
        val initialUrl = request.pageUrl.ifBlank { request.sourceSiteBaseUrl }
        val initial = read(initialUrl, request, context)
        val direct = candidates(initial, request, context)
        if (direct.isNotEmpty()) return direct
        val search = initial.search ?: throw SourceFailure(SourceFailureReason.UnsupportedFormat)
        if (!sameSite(search.url, request.sourceSiteBaseUrl)) throw SourceFailure(SourceFailureReason.AccessDenied)
        val names = (listOfNotNull(query.title, query.originalTitle) + query.alternativeTitles).distinct().filter(String::isNotBlank).take(3)
        val visited = mutableSetOf<String>()
        val found = linkedMapOf<String, ParsedMediaCandidate>()
        for (name in names) {
            val fields = search.fields + (search.queryKey to name)
            val searchUrl = if (search.pathQuery) search.url + name.encodeURLPathPart() else if (search.post) search.url else URLBuilder(search.url).apply {
                fields.forEach { (k, v) -> parameters[k] = v }
            }.buildString()
            val results = read(searchUrl, request, context, if (search.post) fields else null)
            candidates(results, request, context)
                .forEach { found[it.pageUrl] = it }
            for (url in results.detailLinks) {
                if (!sameSite(url, request.sourceSiteBaseUrl) || !visited.add(url)) continue
                if (visited.size > 12) break
                val detail = try { read(url, request, context) } catch (e: SourceFailure) {
                    // A stale search result must not hide a later valid result. Policy failures still propagate.
                    if (e.reason in setOf(SourceFailureReason.NotFound, SourceFailureReason.Unavailable,
                            SourceFailureReason.UnsupportedFormat)) continue else throw e
                }
                candidates(detail, request, context)
                    .forEach { found[it.pageUrl] = it }
            }
            if (found.isNotEmpty()) break
        }
        return found.values.toList()
    }

    override suspend fun inspect(request: VideoRequest, context: SourceRequestContext): ParsedMediaCandidate? =
        matching(request, context).identity

    override suspend fun resolve(request: VideoRequest): ResolvedVideoSource = throw SourceFailure(SourceFailureReason.RequestBlocked)
    override suspend fun resolve(request: VideoRequest, context: SourceRequestContext): ResolvedVideoSource {
        val document = read(request.pageUrl, request, context)
        document.embedded?.let { return cinemar.resolve(it, request, context) }
        val media = matching(request, context, document)
        val stream = media.streams.firstOrNull() ?: throw SourceFailure(SourceFailureReason.UnsupportedFormat)
        return ResolvedVideoSource(stream.url, stream.kind, stream.headers, sourceId = request.sourceSiteId,
            qualityOptions = media.streams.map { it.qualityId }.filter(String::isNotBlank).distinct().map { PlaybackOption(it, it) },
            audioTrackOptions = media.streams.map { it.audioId }.filter(String::isNotBlank).distinct().map { PlaybackOption(it, it) },
            variants = media.streams)
    }

    private suspend fun candidates(document: WebDocument, request: VideoRequest, context: SourceRequestContext): List<ParsedMediaCandidate> {
        val query = requireNotNull(request.mediaMatch)
        document.embedded?.let { page ->
            if (!cinemar.matches(page, query)) return emptyList()
            return try { listOf(cinemar.inspect(page, request, context)) } catch (e: SourceFailure) {
                if (e.reason == SourceFailureReason.NotFound) emptyList() else throw e
            }
        }
        return document.media.map { it.identity }.filter { MediaCandidateMatcher.score(query, it) != null }
    }

    private suspend fun matching(request: VideoRequest, context: SourceRequestContext,
                                 document: WebDocument? = null): WebMedia {
        val query = requireNotNull(request.mediaMatch)
        val loaded = document ?: read(request.pageUrl, request, context)
        loaded.embedded?.let { return WebMedia(cinemar.inspect(it, request, context), emptyList()) }
        val candidates = loaded.media.filter { MediaCandidateMatcher.score(query, it.identity) != null }
        if (candidates.size != 1) throw SourceFailure(SourceFailureReason.NotFound)
        return candidates.single()
    }
    private suspend fun read(url: String, request: VideoRequest, context: SourceRequestContext,
                             form: Map<String, String>? = null): WebDocument {
        if (!sameSite(url, request.sourceSiteBaseUrl)) throw SourceFailure(SourceFailureReason.AccessDenied)
        val document = http.readDocument(url, request.sourceSiteBaseUrl, request, context, form)
        if (!sameSite(document.url, request.sourceSiteBaseUrl)) throw SourceFailure(SourceFailureReason.AccessDenied)
        return parseWebDocument(document.text, document.url)
    }
}
