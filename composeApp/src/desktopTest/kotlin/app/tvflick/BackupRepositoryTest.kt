package app.tvflick

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.backup.*
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.*
import app.tvflick.core.tmdb.*
import app.tvflick.plugin.api.PlaybackSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class BackupRepositoryTest {
    private class Store : AutoCloseable {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db: TvFlickDatabase
        val repo: SqlDelightLibraryRepository
        init {
            TvFlickDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            db = TvFlickDatabase(driver)
            repo = SqlDelightLibraryRepository(db)
        }
        override fun close() = driver.close()
    }
    private fun tables(bytes: ByteArray) = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("tables")
    private fun change(bytes: ByteArray, key: String, value: JsonElement): ByteArray {
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        return JsonObject(root + (key to value)).toString().encodeToByteArray()
    }

    @Test
    fun `portable backup restores complete library episode identity progress and source settings`() = runBlocking {
        Store().use { source -> Store().use { target ->
            val repo = source.repo
            val movie = repo.save(LibraryContentDraft(MediaType.Movie, 10, "Movie", "Original", 2024,
                "/p.jpg", "/b.jpg", "Overview", 90), true)
            repo.setFavorite(movie.localId, true)
            repo.saveMoviePlayback(movie.localId, PlaybackSnapshot(movie.localId, 75_000, 100_000, false))
            val show = repo.save(LibraryContentDraft(MediaType.Tv, 20, "Show"), true)
            repo.syncSeason(show.localId, TmdbSeasonSummary(100, 2, "Season two", "2026-01-01", "/season.jpg"),
                TmdbSeasonDetails(2, "Season two", listOf(
                    TmdbEpisode(200, 5, "Episode five", "Episode overview", "2026-01-01", "/still.jpg", 42),
                    TmdbEpisode(201, 6, "Episode six", null, "2027-01-01", null, 43))))
            val episode = repo.listEpisodesForShow(show.localId).first()
            repo.setEpisodeFavorite(episode.episodeId, true)
            repo.saveEpisodePlayback(episode.episodeId, PlaybackSnapshot(episode.episodeId, 80_000, 100_000, false))
            val site = VideoSourceSettings(repo).add("example.com")
            repo.setSourceAdblockEnabled(site.id, false)
            repo.saveBackupPreference("backup.lastLocal", "12345")
            val snapshot = repo.exportBackup()
            assertEquals(BackupSummary(2, 2, 1, repo.inspectBackup(snapshot).createdAtEpochMs), repo.inspectBackup(snapshot))
            target.repo.restoreBackup(snapshot)
            assertEquals(tables(snapshot), tables(target.repo.exportBackup()))
            assertEquals(repo.listItems(), target.repo.listItems())
            assertEquals(repo.listEpisodesForShow(show.localId), target.repo.listEpisodesForShow(show.localId))
            assertEquals(false, target.repo.listSourceAdblockSettings()[site.id])
        } }
    }

    @Test
    fun `invalid and future backup files never replace current data`() = runBlocking {
        Store().use { store ->
            store.repo.save(LibraryContentDraft(MediaType.Movie, 1, "Keep"), true)
            val before = store.repo.exportBackup()
            val invalid = listOf(byteArrayOf(), "{broken".encodeToByteArray(),
                change(before, "version", JsonPrimitive(999)),
                change(before, "tables", buildJsonObject {}))
            invalid.forEach {
                assertFails { store.repo.restoreBackup(it) }
                assertEquals(tables(before), tables(store.repo.exportBackup()))
            }
        }
    }

    @Test
    fun `orphan references and invalid checkbox flags fail validation`() = runBlocking {
        Store().use { store ->
            store.repo.save(LibraryContentDraft(MediaType.Movie, 1, "Keep"), true)
            val bytes = store.repo.exportBackup()
            val data = tables(bytes).jsonObject
            val original = data.getValue("library_entry").jsonArray.single().jsonObject
            listOf("content_id" to JsonPrimitive(999), "is_favorite" to JsonPrimitive(2)).forEach { (key, value) ->
                val changed = JsonObject(data + ("library_entry" to JsonArray(listOf(JsonObject(original + (key to value))))))
                val invalid = change(bytes, "tables", changed)
                assertFails { store.repo.inspectBackup(invalid) }
                assertFails { store.repo.restoreBackup(invalid) }
                assertEquals(tables(bytes), tables(store.repo.exportBackup()))
            }
        }
    }

    @Test
    fun `SQL insertion failure rolls back deletions and keeps sources and preferences`() = runBlocking {
        Store().use { source -> Store().use { target ->
            source.repo.save(LibraryContentDraft(MediaType.Movie, 10, "Incoming"), true)
            target.repo.save(LibraryContentDraft(MediaType.Movie, 11, "Keep"), true)
            VideoSourceSettings(target.repo).add("keep.example")
            target.repo.saveBackupPreference("backup.lastLocal", "100")
            val before = target.repo.exportBackup()
            target.driver.execute(null,
                "CREATE TRIGGER fail_restore BEFORE INSERT ON content WHEN NEW.title = 'Incoming' BEGIN SELECT RAISE(ABORT, 'test failure'); END;", 0)
            assertFails { target.repo.restoreBackup(source.repo.exportBackup()) }
            assertEquals(tables(before), tables(target.repo.exportBackup()))
        } }
    }

    @Test
    fun `backup excludes credentials catalog cache and removed titles and preserves private settings`() = runBlocking {
        Store().use { store ->
            val removed = store.repo.save(LibraryContentDraft(MediaType.Movie, 10, "Removed"), true)
            store.repo.remove(removed.localId)
            store.db.libraryQueries.saveSourceAdblockSetting("oauth.refresh_token", "secret-marker", 1)
            store.repo.writePreview(CatalogCategory.PopularShows, null, TmdbCatalogPage(
                listOf(TmdbSearchResult(1, "tv", "Cached", "", null, null, "")), 1, 1))
            val bytes = store.repo.exportBackup()
            assertFalse(bytes.decodeToString().contains("secret-marker"))
            assertFalse(bytes.decodeToString().contains("Removed"))
            assertFalse(bytes.decodeToString().contains("Cached"))
            assertEquals(0, store.repo.inspectBackup(bytes).titles)
            store.repo.restoreBackup(bytes)
            assertEquals("secret-marker", store.db.backupQueries.backupPreference("oauth.refresh_token").executeAsOne())
        }
    }

    @Test
    fun `atomic backup writes replace whole file and leave no temporary data`() {
        val directory = Files.createTempDirectory("tvflick-backup-write-")
        val file = directory.resolve("snapshot.tvbackup").toFile()
        try {
            atomicWrite(file, ByteArray(1024) { 1 })
            atomicWrite(file, byteArrayOf(2, 3, 4))
            assertContentEquals(byteArrayOf(2, 3, 4), file.readBytes())
            assertEquals(listOf("snapshot.tvbackup"), directory.toFile().listFiles()!!.map { it.name })
        } finally {
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `restoring never imports automatic upload consent`() = runBlocking {
        Store().use { source -> Store().use { target ->
            source.repo.saveBackupPreference("backup.autoEnabled", "true")
            source.repo.saveBackupPreference("backup.intervalDays", "7")
            target.repo.restoreBackup(source.repo.exportBackup())
            assertEquals("false", target.repo.backupPreference("backup.autoEnabled"))
            assertEquals("7", target.repo.backupPreference("backup.intervalDays"))
        } }
    }

    @Test fun `invalid content identities and source addresses are rejected during inspection`() = runBlocking {
        Store().use { store ->
            store.repo.save(LibraryContentDraft(MediaType.Movie, 1, "Keep"), true)
            VideoSourceSettings(store.repo).add("example.com")
            val bytes = store.repo.exportBackup()
            val data = tables(bytes).jsonObject
            val edits = listOf(Triple("content", "tmdb_id", JsonPrimitive(0)),
                Triple("content", "title", JsonPrimitive(" ")),
                Triple("video_source_site", "base_url", JsonPrimitive("https://user:password@example.com")))
            edits.forEach { (table, key, value) ->
                val original = data.getValue(table).jsonArray.single().jsonObject
                val changed = JsonObject(data + (table to JsonArray(listOf(JsonObject(original + (key to value))))))
                assertFailsWith<IllegalArgumentException> { store.repo.inspectBackup(change(bytes, "tables", changed)) }
                assertEquals(tables(bytes), tables(store.repo.exportBackup()))
            }
        }
    }
}
