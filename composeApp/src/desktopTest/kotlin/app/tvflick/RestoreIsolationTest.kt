package app.tvflick

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.*
import app.tvflick.ui.FlickState
import kotlinx.coroutines.*
import kotlin.test.*

class RestoreIsolationTest {
    @Test fun `restore cancels pending writes blocks new actions and reloads committed data`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val repo = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            val state = FlickState(repo, null, this)
            val entered = CompletableDeferred<Unit>()
            val oldAction = state.launchLibraryAction { entered.complete(Unit); awaitCancellation() }
            entered.await()
            val incoming = LibraryContentDraft(MediaType.Movie, 2, "Restored")
            state.withLibraryRestore {
                assertTrue(oldAction.isCancelled)
                assertTrue(state.libraryRestoring)
                state.addToLibrary(LibraryContentDraft(MediaType.Movie, 3, "Stray click")).join()
                assertTrue(repo.listItems().isEmpty())
                repo.save(incoming, true)
            }
            assertFalse(state.libraryRestoring)
            assertEquals(incoming, state.library.single().content)
            state.addToLibrary(LibraryContentDraft(MediaType.Movie, 4, "After restore")).join()
            assertEquals(2, state.library.size)
        } finally { driver.close() }
    }

    @Test fun `failed restore releases the gate and preserves visible data`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val repo = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            val item = repo.save(LibraryContentDraft(MediaType.Movie, 1, "Keep"), true)
            val state = FlickState(repo, null, this)
            assertFailsWith<IllegalStateException> { state.withLibraryRestore { error("Recovery file failed") } }
            assertFalse(state.libraryRestoring)
            assertEquals(listOf(item), state.library)
            state.addToLibrary(LibraryContentDraft(MediaType.Movie, 2, "New")).join()
            assertEquals(2, state.library.size)
        } finally { driver.close() }
    }

    @Test fun `read started before restore cannot publish obsolete library afterwards`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val repo = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            val old = repo.save(LibraryContentDraft(MediaType.Movie, 1, "Old"), true)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var delayRead = true
            val delayed = object : LibraryRepository by repo {
                override suspend fun listUpcomingEpisodes(): List<UpcomingEpisode> {
                    if (delayRead) { delayRead = false; entered.complete(Unit); release.await() }
                    return emptyList()
                }
            }
            val state = FlickState(delayed, null, this)
            val read = launch { state.refresh() }
            entered.await()
            state.withLibraryRestore {
                repo.remove(old.localId)
                repo.save(LibraryContentDraft(MediaType.Movie, 2, "New"), true)
            }
            release.complete(Unit)
            read.join()
            assertEquals("New", state.library.single().content.title)
        } finally { driver.close() }
    }
}
