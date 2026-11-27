package app.tvflick

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.*
import app.tvflick.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LibraryFailureTest {
    @Test fun `failed partial reload preserves visible library and retry recovers`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val stored = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            val first = stored.save(LibraryContentDraft(MediaType.Movie, 1, "First"), true)
            var broken = false
            val repository = object : LibraryRepository by stored {
                override suspend fun listUpcomingEpisodes(): List<UpcomingEpisode> {
                    check(!broken)
                    return stored.listUpcomingEpisodes()
                }
            }
            val state = FlickState(repository, null, this)
            assertTrue(state.reloadLibrary())
            stored.save(LibraryContentDraft(MediaType.Movie, 2, "Second"), true)
            broken = true
            assertFalse(state.reloadLibrary())
            assertEquals(listOf(first), state.library)
            assertFalse(state.showEmptyReference)
            assertEquals(UserProblem.LibraryUnavailable, state.notices.current?.problem)
            broken = false
            state.retryLibrary().join()
            assertEquals(2, state.library.size)
            assertNull(state.libraryError)
        } finally { driver.close() }
    }

    @Test fun `failed write reports a user problem without cancelling later library actions`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val stored = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            var broken = true
            val repository = object : LibraryRepository by stored {
                override suspend fun save(content: LibraryContentDraft, addToWatchList: Boolean): LocalLibraryItem {
                    check(!broken)
                    return stored.save(content, addToWatchList)
                }
            }
            val state = FlickState(repository, null, this)
            val draft = LibraryContentDraft(MediaType.Movie, 1, "Title")
            state.addToLibrary(draft).join()
            assertEquals(UserProblem.LibraryUpdateFailed, state.notices.current?.problem)
            assertTrue(stored.listItems().isEmpty())
            broken = false
            state.addToLibrary(draft).join()
            assertEquals(draft, state.library.single().content)
            assertNull(state.libraryError)
        } finally { driver.close() }
    }

    @Test fun `cancelled load is not presented as an error`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val stored = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            val repository = object : LibraryRepository by stored {
                override suspend fun listItems(): List<LocalLibraryItem> = throw CancellationException()
            }
            val state = FlickState(repository, null, this)
            assertFailsWith<CancellationException> { state.reloadLibrary() }
            assertNull(state.notices.current)
            assertNull(state.libraryError)
        } finally { driver.close() }
    }

    @Test fun `dismissal of an old notice cannot hide a newer error and duplicates do not reset it`() {
        val notices = UserNotices()
        notices.show(UserProblem.SourceUnavailable)
        val first = assertNotNull(notices.current)
        notices.show(UserProblem.SourceUnavailable)
        assertEquals(first, notices.current)
        notices.show(UserProblem.VideoNotFound)
        val next = assertNotNull(notices.current)
        notices.dismiss(first.id)
        assertEquals(next, notices.current)
        notices.dismiss(next.id)
        assertNull(notices.current)
        notices.show(UserProblem.VideoNotFound)
        assertTrue(assertNotNull(notices.current).id > next.id)
    }
}
