package app.tvflick.plugin.engine

import app.tvflick.plugin.api.MediaMatchQuery
import app.tvflick.plugin.api.ParsedMediaCandidate
import app.tvflick.plugin.api.MatchedMediaType

sealed interface MediaMatchResult {
    data class Confirmed(val candidate: ParsedMediaCandidate) : MediaMatchResult
    data class Ambiguous(val candidates: List<ParsedMediaCandidate>) : MediaMatchResult
    data object NotFound : MediaMatchResult
}

/**
 * Conservative, deterministic matcher shared by every site parser. It rejects
 * candidates whose known type/year/season/episode disagree with TMDB/Core.
 * A parser may impose additional site-specific checks, but never weaken these.
 */
object MediaCandidateMatcher {
    fun choose(query: MediaMatchQuery, candidates: Iterable<ParsedMediaCandidate>): ParsedMediaCandidate? =
        (evaluate(query, candidates) as? MediaMatchResult.Confirmed)?.candidate

    fun evaluate(query: MediaMatchQuery, candidates: Iterable<ParsedMediaCandidate>): MediaMatchResult {
        val scored = candidates.distinct().mapNotNull { candidate -> score(query, candidate)?.let { it to candidate } }
        val best = scored.maxOfOrNull { it.first } ?: return MediaMatchResult.NotFound
        val matches = scored.filter { it.first == best }.map { it.second }
        return if (matches.size == 1) MediaMatchResult.Confirmed(matches.single()) else MediaMatchResult.Ambiguous(matches)
    }

    fun score(query: MediaMatchQuery, candidate: ParsedMediaCandidate): Int? {
        if (query.mediaType != candidate.mediaType) return null
        if (query.tmdbId?.let { it <= 0 } == true || candidate.tmdbId?.let { it <= 0 } == true) return null
        if (query.tmdbId != null && candidate.tmdbId != null && query.tmdbId != candidate.tmdbId) return null
        if (query.releaseYear != null && candidate.releaseYear != null && query.releaseYear != candidate.releaseYear) return null
        if (query.mediaType == MatchedMediaType.TvEpisode) {
            if ((query.seasonNumber ?: -1) < 0 || (query.episodeNumber ?: 0) <= 0) return null
            if (candidate.seasonNumber != query.seasonNumber || candidate.episodeNumber != query.episodeNumber) return null
        }
        if (query.tmdbId != null && query.tmdbId == candidate.tmdbId) return 1000
        // Without a shared ID, both the year and an explicit title must agree.
        if ((query.releaseYear ?: 0) <= 0 || candidate.releaseYear != query.releaseYear) return null
        val expected = (listOfNotNull(query.title, query.originalTitle) + query.alternativeTitles)
            .map(::normalize).filter(String::isNotBlank).toSet()
        val actual = (listOf(candidate.title) + candidate.alternativeTitles).map(::normalize).filter(String::isNotBlank).toSet()
        return if (expected.intersect(actual).isNotEmpty()) 100 else null
    }

    private fun normalize(value: String): String = value.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
        .joinToString(" ")
}
