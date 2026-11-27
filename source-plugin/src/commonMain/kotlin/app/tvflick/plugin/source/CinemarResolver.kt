package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import app.tvflick.plugin.engine.MediaCandidateMatcher
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

internal data class CinemarPage(val identity: ParsedMediaCandidate, val embedUrl: String)
internal expect fun parseCinemarOptions(html: String): JsonObject
internal fun isCinemarUrl(url: String) = webUrl(url) && Url(url).let {
    it.protocol == URLProtocol.HTTPS && it.host == "cinemar.cc" && it.port == 443 && it.encodedPath.startsWith("/embed/")
}

/** Public playlist serialization, decoded as data only. No eval/browser scripting. */
internal object CinemarFormat {
    private fun fail(): Nothing = throw SourceFailure(SourceFailureReason.UnsupportedFormat)
    fun playlist(value: String): JsonArray {
        if (!value.startsWith("#2") || value.length !in 5..(3 * 1024 * 1024)) fail()
        val delimiter = value.substring(2, 4).toIntOrNull()?.toChar() ?: fail()
        val encoded = value.substring(4).split(delimiter).joinToString("") { part ->
            if (part.length <= 32) part else {
                val n = part.last().digitToIntOrNull() ?: fail()
                val length = part.length - 3 * n - 1
                if (length < 0) fail()
                part.substring(2 * n, 2 * n + length) + part.take(n)
            }
        }
        return try {
            Json.parseToJsonElement(Base64.decode(encoded.padEnd((encoded.length + 3) / 4 * 4, '='))
                .decodeToString(throwOnInvalidSequence = true)) as? JsonArray ?: fail()
        } catch (_: Exception) { fail() }
    }

    fun tracks(root: JsonArray, query: MediaMatchQuery): List<JsonObject> {
        fun rows(array: JsonArray, limit: Int): List<JsonObject> {
            if (array.size > limit) fail()
            return array.map { it as? JsonObject ?: fail() }
        }
        val selected = if (query.mediaType == MatchedMediaType.Movie) root else {
            val matches = mutableListOf<JsonArray>()
            for (season in rows(root, 200)) {
                val number = Regex("(?i)^s(\\d+)$").matchEntire(season.text("id").orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull() ?: fail()
                val label = Regex("^(?:сезон|season)\\s+(\\d+)$", RegexOption.IGNORE_CASE).matchEntire(season.text("title").orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
                if (label != null && label != number) fail()
                if (number != query.seasonNumber) continue
                for (episode in rows(season["folder"] as? JsonArray ?: fail(), 2000)) {
                    val id = Regex("(?i)^s(\\d+)e(\\d+)$").matchEntire(episode.text("id").orEmpty()) ?: fail()
                    val episodeNumber = id.groupValues[2].toIntOrNull() ?: fail()
                    val titleNumber = Regex("^(?:серия|episode)\\s+(\\d+)$", RegexOption.IGNORE_CASE).matchEntire(episode.text("title").orEmpty())
                        ?.groupValues?.get(1)?.toIntOrNull()
                    if (id.groupValues[1].toIntOrNull() != number || (titleNumber != null && titleNumber != episodeNumber)) fail()
                    if (episodeNumber == query.episodeNumber) matches += episode["folder"] as? JsonArray ?: fail()
                }
            }
            if (matches.isEmpty()) throw SourceFailure(SourceFailureReason.NotFound)
            if (matches.size != 1) fail()
            matches.single()
        }
        val tracks = rows(selected, 32)
        if (tracks.isEmpty()) throw SourceFailure(SourceFailureReason.NotFound)
        if (tracks.any { it.containsKey("folder") || it.text("id").isNullOrBlank() || it.text("title").isNullOrBlank() ||
                it["data"] !is JsonPrimitive || it.text("data").isNullOrBlank() } ||
            tracks.map { it.text("id") }.distinct().size != tracks.size) fail()
        return tracks
    }
}

internal class CinemarResolver(private val http: SourceHttp) {
    fun matches(page: CinemarPage, query: MediaMatchQuery): Boolean =
        page.identity.mediaType == query.mediaType && MediaCandidateMatcher.score(
            query.copy(mediaType = MatchedMediaType.Movie), page.identity.copy(mediaType = MatchedMediaType.Movie)) != null

    private suspend fun load(page: CinemarPage, request: VideoRequest, context: SourceRequestContext): List<JsonObject> {
        if (!isCinemarUrl(page.embedUrl)) throw SourceFailure(SourceFailureReason.AccessDenied)
        val query = requireNotNull(request.mediaMatch)
        if (!matches(page, query)) throw SourceFailure(SourceFailureReason.NotFound)
        val provider = request.copy(sourceSiteBaseUrl = "https://cinemar.cc")
        val options = parseCinemarOptions(http.read(page.embedUrl, page.identity.pageUrl, provider, context))
        // The provider's content ID must agree with the explicitly linked embed, not Core's local ID.
        if (options.text("vid") != Url(page.embedUrl).encodedPath.removePrefix("/embed/").substringBefore('/'))
            throw SourceFailure(SourceFailureReason.UnsupportedFormat)
        return CinemarFormat.tracks(CinemarFormat.playlist(options.text("file")
            ?: throw SourceFailure(SourceFailureReason.UnsupportedFormat)), query)
    }

    suspend fun inspect(page: CinemarPage, request: VideoRequest, context: SourceRequestContext): ParsedMediaCandidate {
        load(page, request, context)
        val query = requireNotNull(request.mediaMatch)
        return page.identity.copy(seasonNumber = query.seasonNumber, episodeNumber = query.episodeNumber)
    }

    suspend fun resolve(page: CinemarPage, request: VideoRequest, context: SourceRequestContext): ResolvedVideoSource {
        val tracks = load(page, request, context)
        val provider = request.copy(sourceSiteBaseUrl = "https://cinemar.cc")
        val permits = Semaphore(3)
        val headers = mapOf("Referer" to page.embedUrl, "Origin" to "https://cinemar.cc")
        val variants = coroutineScope { tracks.map { track -> async { permits.withPermit {
            val response = Json.parseToJsonElement(http.postJson("https://cinemar.cc/api/playlist/load", page.embedUrl,
                provider, context, track.getValue("data").toString())) as? JsonObject
                ?: throw SourceFailure(SourceFailureReason.UnsupportedFormat)
            if (response["success"] == JsonPrimitive(false)) throw SourceFailure(SourceFailureReason.Unavailable)
            val media = response["data"] as? JsonObject ?: response
            val file = media.text("file") ?: throw SourceFailure(SourceFailureReason.UnsupportedFormat)
            // Accept ordinary URLs only; an unknown encoding is never executed or guessed.
            if (!webUrl(file)) throw SourceFailure(SourceFailureReason.UnsupportedFormat)
            val kind = when {
                Url(file).encodedPath.endsWith(".m3u8", true) -> StreamKind.Hls
                Url(file).encodedPath.endsWith(".mp4", true) -> StreamKind.Mp4
                else -> throw SourceFailure(SourceFailureReason.UnsupportedFormat)
            }
            if (context.decide(VideoNetworkRequest(file, request.sourceSiteId,
                    if (kind == StreamKind.Hls) VideoResourceKind.MediaManifest else VideoResourceKind.MediaSegment, headers)) is NetworkRequestDecision.Block)
                throw SourceFailure(SourceFailureReason.RequestBlocked)
            ResolvedStreamVariant(file, kind, requireNotNull(track.text("id")), "", headers)
        } } }.awaitAll() }
        val initial = variants.first()
        return ResolvedVideoSource(initial.url, initial.kind, initial.headers, sourceId = request.sourceSiteId,
            audioTrackOptions = tracks.map { PlaybackOption(requireNotNull(it.text("id")), requireNotNull(it.text("title"))) }, variants = variants)
    }
}
