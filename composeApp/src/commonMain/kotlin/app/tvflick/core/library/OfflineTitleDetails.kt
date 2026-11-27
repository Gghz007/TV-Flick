package app.tvflick.core.library

import app.tvflick.core.tmdb.TmdbTitleDetails

/** Only normalized saved metadata; no network or requirement to sign in. */
suspend fun LibraryRepository.cachedTitleDetails(mediaType: MediaType, tmdbId: Long): TmdbTitleDetails? {
    val item = listItems().find { it.content.mediaType == mediaType && it.content.tmdbId == tmdbId } ?: return null
    val title = item.content
    return TmdbTitleDetails(title.tmdbId, title.mediaType.databaseValue, title.title, title.originalTitle,
        title.overview.orEmpty(), title.releaseDate, title.posterPath, title.backdropPath, title.runtimeMinutes,
        seasons = if (mediaType == MediaType.Tv) listSeasons(item.localId) else emptyList(),
        alternativeTitles = title.alternativeTitles, genres = title.genres)
}
