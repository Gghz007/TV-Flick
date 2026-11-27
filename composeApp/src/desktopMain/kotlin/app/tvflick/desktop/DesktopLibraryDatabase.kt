package app.tvflick.desktop

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.SqlDelightLibraryRepository
import java.io.File

class DesktopLibraryDatabase private constructor(private val driver: JdbcSqliteDriver) : AutoCloseable {
    val repository = SqlDelightLibraryRepository(TvFlickDatabase(driver))
    override fun close() = driver.close()

    companion object {
        fun open(file: File): DesktopLibraryDatabase {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}")
            try {
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                val tables = driver.strings("SELECT name FROM sqlite_master WHERE type = 'table'")
                val storedVersion = driver.strings("PRAGMA user_version").single().toLong()
                val currentVersion = TvFlickDatabase.Schema.version
                require(storedVersion <= currentVersion) { "Database was created by a newer TV Flick version" }
                // Older Windows builds never wrote user_version. Detect their actual schema once.
                val metadataColumns = setOf("release_date", "alternative_titles", "genres")
                val columns = if ("content" in tables) driver.strings("PRAGMA table_info(content)", 1).toSet() else emptySet()
                val version = if (storedVersion != 0L) storedVersion else when {
                    "content" !in tables -> 0L
                    columns.containsAll(metadataColumns) -> currentVersion
                    columns.intersect(metadataColumns).isNotEmpty() -> error("Incomplete metadata schema")
                    "catalog_preview_cache" in tables -> 3L
                    "episode_favorite" in tables -> 2L
                    else -> 1L
                }
                TvFlickDatabase(driver).transaction {
                    if (version == 0L) TvFlickDatabase.Schema.create(driver)
                    else if (version < currentVersion) TvFlickDatabase.Schema.migrate(driver, version, currentVersion)
                    val updatedColumns = driver.strings("PRAGMA table_info(content)", 1).toSet()
                    check(updatedColumns.containsAll(metadataColumns)) { "Database schema update failed" }
                    driver.execute(null, "PRAGMA user_version = $currentVersion", 0)
                }
                return DesktopLibraryDatabase(driver)
            } catch (e: Exception) {
                driver.close()
                throw e
            }
        }
    }
}

private fun JdbcSqliteDriver.strings(sql: String, column: Int = 0): List<String> =
    executeQuery(null, sql, { cursor ->
        val values = mutableListOf<String>()
        while (cursor.next().value) values += checkNotNull(cursor.getString(column))
        QueryResult.Value(values)
    }, 0).value
