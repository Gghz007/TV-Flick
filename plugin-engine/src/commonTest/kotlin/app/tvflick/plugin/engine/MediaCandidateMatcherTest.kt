package app.tvflick.plugin.engine

import app.tvflick.plugin.api.MatchedMediaType
import app.tvflick.plugin.api.MediaMatchQuery
import app.tvflick.plugin.api.ParsedMediaCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs

class MediaCandidateMatcherTest {
    @Test fun `title alone never confirms and equal candidates remain ambiguous`() {
        val query = MediaMatchQuery("Dune", releaseYear = 2021, mediaType = MatchedMediaType.Movie)
        assertNull(MediaCandidateMatcher.choose(query, listOf(movie("Dune", 2021, "a").copy(releaseYear = null))))
        assertNull(MediaCandidateMatcher.choose(query.copy(releaseYear = null), listOf(movie("Dune", 2021, "a"))))
        val candidates = listOf(movie("Dune", 2021, "a"), movie("Dune", 2021, "b"))
        assertIs<MediaMatchResult.Ambiguous>(MediaCandidateMatcher.evaluate(query, candidates))
        assertNull(MediaCandidateMatcher.choose(query, candidates))
        assertNull(MediaCandidateMatcher.choose(query, candidates.reversed()))
    }

    @Test fun `TMDB identity takes priority but contradictory metadata is rejected`() {
        val query = MediaMatchQuery("Dune", releaseYear = 2021, mediaType = MatchedMediaType.Movie, tmdbId = 438631)
        val idMatch = movie("Дюна", 2021, "id").copy(tmdbId = 438631)
        assertEquals(idMatch, MediaCandidateMatcher.choose(query, listOf(movie("Dune", 2021, "title"), idMatch)))
        assertNull(MediaCandidateMatcher.choose(query, listOf(idMatch.copy(tmdbId = 1))))
        assertNull(MediaCandidateMatcher.choose(query, listOf(idMatch.copy(releaseYear = 1984))))
    }

    @Test fun `original and alternative titles work in both directions without fuzzy matches`() {
        val query = MediaMatchQuery("Локальное", releaseYear = 2021, mediaType = MatchedMediaType.Movie, originalTitle = "Original")
        assertEquals("original", MediaCandidateMatcher.choose(query, listOf(movie("Original", 2021, "original")))?.pageUrl)
        assertEquals("alias", MediaCandidateMatcher.choose(query, listOf(movie("Other", 2021, "alias").copy(alternativeTitles = listOf("Локальное"))))?.pageUrl)
        assertNull(MediaCandidateMatcher.choose(query, listOf(movie("Original 2", 2021, "sequel"))))
    }

    @Test fun `TV identity is insufficient without the exact season and episode`() {
        val query = MediaMatchQuery("Show", mediaType = MatchedMediaType.TvEpisode, tmdbId = 1, seasonNumber = 2, episodeNumber = 5)
        val candidate = ParsedMediaCandidate("Show", mediaType = MatchedMediaType.TvEpisode, pageUrl = "episode",
            tmdbId = 1, seasonNumber = 2, episodeNumber = 5)
        assertEquals(candidate, MediaCandidateMatcher.choose(query, listOf(candidate)))
        assertNull(MediaCandidateMatcher.choose(query, listOf(candidate.copy(episodeNumber = 6))))
        assertNull(MediaCandidateMatcher.choose(query.copy(episodeNumber = null), listOf(candidate)))
        assertNull(MediaCandidateMatcher.choose(query, listOf(candidate.copy(seasonNumber = null))))
    }

    @Test
    fun `selects the same title only when the release year matches`() {
        val query = MediaMatchQuery("Dune", releaseYear = 2021, mediaType = MatchedMediaType.Movie)
        val selected = MediaCandidateMatcher.choose(query, listOf(
            movie("Dune", 1984, "https://source.test/dune-1984"),
            movie("Dune", 2021, "https://source.test/dune-2021"),
        ))

        assertEquals("https://source.test/dune-2021", selected?.pageUrl)
    }

    @Test
    fun `rejects a matching show title when the episode differs`() {
        val query = MediaMatchQuery(
            title = "Arcane",
            releaseYear = 2021,
            mediaType = MatchedMediaType.TvEpisode,
            seasonNumber = 1,
            episodeNumber = 2,
        )
        val wrongEpisode = ParsedMediaCandidate(
            title = "Arcane",
            releaseYear = 2021,
            mediaType = MatchedMediaType.TvEpisode,
            seasonNumber = 1,
            episodeNumber = 1,
            pageUrl = "https://source.test/arcane-s1e1",
        )

        assertNull(MediaCandidateMatcher.choose(query, listOf(wrongEpisode)))
    }

    private fun movie(title: String, year: Int, url: String) = ParsedMediaCandidate(
        title = title,
        releaseYear = year,
        mediaType = MatchedMediaType.Movie,
        pageUrl = url,
    )
}
