package app.tvflick

import app.tvflick.core.library.*
import app.tvflick.ui.*
import kotlin.test.*

class CollectionProgressTest {
    @Test
    fun `reference unstarted filter includes watch later without changing local status`() {
        assertEquals(CollectionProgress.NotStarted, CollectionProgress.WatchLater.filterGroup())
        CollectionProgress.entries.filter { it != CollectionProgress.WatchLater }.forEach {
            assertEquals(it, it.filterGroup())
        }
    }

    private val movie = LocalLibraryItem(1, LibraryContentDraft(MediaType.Movie, 1, "Movie"),
        true, false, null, null, 1)

    @Test
    fun `watch later watching stopped and finished follow actual local state`() {
        assertEquals(CollectionProgress.WatchLater, collectionProgress(movie, emptyList(), false))
        assertEquals(CollectionProgress.Watching, collectionProgress(movie.copy(moviePositionMs = 1000), emptyList(), false))
        assertEquals(CollectionProgress.Stopped, collectionProgress(movie.copy(isInWatchList = false, moviePositionMs = 1000), emptyList(), false))
        assertEquals(CollectionProgress.Finished, collectionProgress(movie.copy(movieWatchedAtEpochMs = 50), emptyList(), false))
        assertEquals(CollectionProgress.NotStarted, collectionProgress(movie.copy(isInWatchList = false), emptyList(), false))
    }

    @Test
    fun `status options omit absent statuses and empty collections have no options`() {
        assertEquals(listOf(CollectionProgress.WatchLater, CollectionProgress.Finished),
            availableProgressFilters(listOf(CollectionProgress.Finished, CollectionProgress.WatchLater, CollectionProgress.Finished)))
        assertTrue(availableProgressFilters(emptyList()).isEmpty())
    }

    @Test
    fun `show awaiting the next episode differs from stopped and finished`() {
        val show = movie.copy(content = LibraryContentDraft(MediaType.Tv, 2, "Show"))
        val episodes = listOf(EpisodePlaybackState(1, 1, 1, "One", 50, 1000),
            EpisodePlaybackState(2, 1, 2, "Two", null, null))
        assertEquals(CollectionProgress.Current, collectionProgress(show, episodes, false))
        assertEquals(CollectionProgress.Watching, collectionProgress(show, episodes, true))
        assertEquals(CollectionProgress.Stopped, collectionProgress(show.copy(isInWatchList = false), episodes, false))
    }
}
