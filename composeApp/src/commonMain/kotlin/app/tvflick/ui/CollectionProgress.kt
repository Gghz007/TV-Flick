package app.tvflick.ui

import app.tvflick.core.library.EpisodePlaybackState
import app.tvflick.core.library.LocalLibraryItem

internal enum class CollectionProgress(val label: String) {
    Watching("Watching"), WatchLater("Watch later"), NotStarted("Haven’t started"),
    Current("Up to date"), Finished("Finished"), Stopped("Stopped"),
}

internal fun collectionProgress(
    item: LocalLibraryItem,
    episodes: List<EpisodePlaybackState>,
    hasNextEpisode: Boolean,
): CollectionProgress {
    val watched = episodes.count { it.watchedAtEpochMs != null }
    val started = watched > 0 || episodes.any { (it.positionMs ?: 0) > 0 } || (item.moviePositionMs ?: 0) > 0
    return when {
        item.movieWatchedAtEpochMs != null || (episodes.isNotEmpty() && watched == episodes.size) -> CollectionProgress.Finished
        !item.isInWatchList && started -> CollectionProgress.Stopped
        !started && item.isInWatchList -> CollectionProgress.WatchLater
        !started -> CollectionProgress.NotStarted
        episodes.isNotEmpty() && !hasNextEpisode -> CollectionProgress.Current
        else -> CollectionProgress.Watching
    }
}

internal fun availableProgressFilters(statuses: Collection<CollectionProgress>): List<CollectionProgress> =
    CollectionProgress.entries.filter { it in statuses }

// The supplied FiltersContainer groups every unstarted title under Haven’t started.
// Keep WatchLater as a library state, rather than introducing a seventh design row.
internal fun CollectionProgress.filterGroup(): CollectionProgress =
    if (this == CollectionProgress.WatchLater) CollectionProgress.NotStarted else this
