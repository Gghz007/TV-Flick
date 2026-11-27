package app.tvflick.core.library

import app.tvflick.plugin.api.MediaMatchQuery
import app.tvflick.plugin.api.MatchedMediaType
import app.tvflick.plugin.api.VideoRequest
import app.tvflick.plugin.api.ADAPTIVE_SOURCE_PLUGIN_ID

/** Prepared by Core from SQLite, before source discovery. No source can replace the local target. */
data class LocalPlaybackRequest(
    val target: PlaybackTarget,
    val request: VideoRequest,
    val resumePositionMs: Long,
    val libraryItemAddedAtEpochMs: Long,
)

class PlaybackRequestFactory(
    private val library: LibraryRepository,
    private val sources: VideoSourceSiteRepository,
) {
    suspend fun create(contentId: Long, sourceSiteId: String, episodeId: Long? = null): LocalPlaybackRequest {
        val item = requireNotNull(library.listItems().singleOrNull { it.localId == contentId }) {
            "Selected title is no longer in the library"
        }
        val site = requireNotNull(sources.listVideoSourceSites().singleOrNull { it.id == sourceSiteId }) {
            "Selected source is no longer configured"
        }
        require(site.isEnabled) { "Selected source is disabled" }
        val episode = when (item.content.mediaType) {
            MediaType.Movie -> {
                require(episodeId == null) { "A movie cannot have an episode target" }
                null
            }
            MediaType.Tv -> requireNotNull(library.listEpisodesForShow(contentId).singleOrNull {
                it.episodeId == episodeId
            }) { "Select an existing episode of this show" }
        }
        val target = episode?.let { PlaybackTarget.Episode(it.episodeId) } ?: PlaybackTarget.Movie(contentId)
        val saved = when (target) {
            is PlaybackTarget.Movie -> library.loadMoviePlayback(target.id)
            is PlaybackTarget.Episode -> library.loadEpisodePlayback(target.id)
        }
        val content = item.content
        return LocalPlaybackRequest(
            target = target,
            request = VideoRequest(
                contentId = target.id,
                pageUrl = "",
                sourcePluginId = site.parserPluginId.ifBlank { ADAPTIVE_SOURCE_PLUGIN_ID },
                sourceSiteId = site.id,
                sourceSiteBaseUrl = site.baseUrl,
                mediaMatch = MediaMatchQuery(
                    title = content.title,
                    originalTitle = content.originalTitle,
                    alternativeTitles = content.alternativeTitles,
                    tmdbId = content.tmdbId,
                    releaseYear = content.releaseYear,
                    mediaType = if (episode == null) MatchedMediaType.Movie else MatchedMediaType.TvEpisode,
                    seasonNumber = episode?.seasonNumber?.toInt(),
                    episodeNumber = episode?.episodeNumber?.toInt(),
                    episodeTitle = episode?.title,
                    episodeAirDate = episode?.airDate,
                ),
                adblockEnabled = sources.listSourceAdblockSettings()[site.id] ?: true,
            ),
            resumePositionMs = saved?.positionMs?.coerceAtLeast(0) ?: 0,
            libraryItemAddedAtEpochMs = item.addedAtEpochMs,
        )
    }
}
