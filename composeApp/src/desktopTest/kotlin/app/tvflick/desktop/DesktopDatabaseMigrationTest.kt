package app.tvflick.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.*
import app.tvflick.plugin.api.PlaybackSnapshot
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class DesktopDatabaseMigrationTest {
    @Test fun `versioned and unversioned old Windows databases migrate without losing library progress`() = runBlocking {
        for (oldVersion in 1L..4L) for (recorded in listOf(false, true)) {
            val file = Files.createTempFile("tvflick-migration-", ".db").toFile()
            try {
                val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}")
                val expected: LocalLibraryItem
                try {
                    TvFlickDatabase.Schema.create(driver)
                    val repo = SqlDelightLibraryRepository(TvFlickDatabase(driver))
                    val item = repo.save(LibraryContentDraft(MediaType.Movie, 44, "Keep", releaseYear = 2020), true)
                    repo.setFavorite(item.localId, true)
                    repo.saveMoviePlayback(item.localId, PlaybackSnapshot(item.localId, 75_000, 100_000, false))
                    expected = repo.listItems().single()
                    if (oldVersion < 4) listOf("release_date", "alternative_titles", "genres").forEach {
                        driver.execute(null, "ALTER TABLE content DROP COLUMN $it", 0)
                    }
                    if (oldVersion < 3) driver.execute(null, "DROP TABLE catalog_preview_cache", 0)
                    if (oldVersion < 2) driver.execute(null, "DROP TABLE episode_favorite", 0)
                    driver.execute(null, "PRAGMA user_version = ${if (recorded) oldVersion else 0}", 0)
                } finally { driver.close() }
                repeat(2) {
                    DesktopLibraryDatabase.open(file).use { opened ->
                        assertEquals(expected, opened.repository.listItems().single())
                        assertEquals(75_000, opened.repository.loadMoviePlayback(expected.localId)?.positionMs)
                    }
                }
                DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA user_version").use { result ->
                            assertTrue(result.next()); assertEquals(TvFlickDatabase.Schema.version, result.getLong(1))
                        }
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun `new database reopens and future schema is rejected without downgrade`() = runBlocking {
        val file = Files.createTempFile("tvflick-new-db-", ".db").toFile()
        try {
            DesktopLibraryDatabase.open(file).use { db ->
                assertTrue(db.repository.listItems().isEmpty())
                db.repository.save(LibraryContentDraft(MediaType.Movie, 1, "Keep"), true)
            }
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                connection.createStatement().use { it.execute("PRAGMA user_version = 999") }
            }
            assertFailsWith<IllegalArgumentException> { DesktopLibraryDatabase.open(file) }
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { result ->
                        assertTrue(result.next()); assertEquals(999, result.getInt(1))
                    }
                    statement.executeQuery("SELECT title FROM content").use { result ->
                        assertTrue(result.next()); assertEquals("Keep", result.getString(1))
                    }
                }
            }
        } finally { file.delete() }
    }
}
