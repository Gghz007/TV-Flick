package app.tvflick

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.*
import app.tvflick.core.tmdb.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class CatalogPreviewCacheTest {
    private fun page(id: Int, count: Int = 1) = TmdbCatalogPage((0 until count).map {
        TmdbSearchResult(id + it, "tv", "Show ${id + it}", "2026", "/poster.jpg", null, "Overview")
    }, 1, 10)

    @Test
    fun `preview persists after reopening and refresh replaces instead of accumulating`() = runBlocking {
        val file = Files.createTempFile("tv-flick-cache-test-", ".db").toFile()
        var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}")
        try {
            TvFlickDatabase.Schema.create(driver)
            var cache = SqlDelightCatalogPreviewCache(TvFlickDatabase(driver)) { 1000L }
            cache.writePreview(CatalogCategory.PopularShows, null, page(1))
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}")
            val database = TvFlickDatabase(driver)
            cache = SqlDelightCatalogPreviewCache(database) { 1001L }
            assertEquals(page(1), cache.readPreview(CatalogCategory.PopularShows, null))
            cache.writePreview(CatalogCategory.PopularShows, null, page(100, 30))
            assertEquals((100..119).toList(), cache.readPreview(CatalogCategory.PopularShows, null)!!.items.map { it.id })
            assertEquals(1L, database.catalogPreviewQueries.previewCount().executeAsOne())
        } finally { driver.close(); file.delete() }
    }

    @Test
    fun `all stale entries are deleted at expiry including categories not opened`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val database = TvFlickDatabase(driver)
            var time = 1000L
            val cache = SqlDelightCatalogPreviewCache(database) { time }
            cache.writePreview(CatalogCategory.PopularShows, null, page(1))
            cache.writePreview(CatalogCategory.TrendingShows, null, page(2))
            time += SqlDelightCatalogPreviewCache.TTL_MS - 1
            assertNotNull(cache.readPreview(CatalogCategory.PopularShows, null))
            time++
            cache.prunePreviews()
            assertEquals(0L, database.catalogPreviewQueries.previewCount().executeAsOne())
            assertNull(cache.readPreview(CatalogCategory.PopularShows, null))
        } finally { driver.close() }
    }

    @Test
    fun `personalized seed cache evicts oldest entries and isolates titles`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val database = TvFlickDatabase(driver)
            var time = 1000L
            val cache = SqlDelightCatalogPreviewCache(database) { time }
            for (seed in 1L..25L) {
                cache.writePreview(CatalogCategory.RecommendedShows, seed, page(seed.toInt()))
                time++
            }
            assertEquals(16L, database.catalogPreviewQueries.previewCount().executeAsOne())
            assertNull(cache.readPreview(CatalogCategory.RecommendedShows, 9))
            assertEquals(page(10), cache.readPreview(CatalogCategory.RecommendedShows, 10))
            assertEquals(page(25), cache.readPreview(CatalogCategory.RecommendedShows, 25))
        } finally { driver.close() }
    }

    @Test
    fun `corrupt oversized and future dated cache is discarded`() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val database = TvFlickDatabase(driver)
            val cache = SqlDelightCatalogPreviewCache(database) { 1000L }
            val key = "v1:${CatalogCategory.PopularShows.path()}"
            database.catalogPreviewQueries.replacePreview(key, 1000L, "broken-json")
            assertNull(cache.readPreview(CatalogCategory.PopularShows, null))
            assertEquals(0L, database.catalogPreviewQueries.previewCount().executeAsOne())
            cache.writePreview(CatalogCategory.PopularShows, null,
                page(1).copy(items = listOf(page(1).items.single().copy(overview = "x".repeat(300_000)))))
            assertNull(cache.readPreview(CatalogCategory.PopularShows, null))
            database.catalogPreviewQueries.replacePreview(key, 2000L, "{}")
            cache.prunePreviews()
            assertEquals(0L, database.catalogPreviewQueries.previewCount().executeAsOne())
            assertFailsWith<IllegalArgumentException> { cache.writePreview(CatalogCategory.PopularShows, null, page(1).copy(page = 2)) }
        } finally { driver.close() }
    }

    @Test
    fun `version two cache migration preserves library entries`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val database = TvFlickDatabase(driver)
            val repository = SqlDelightLibraryRepository(database)
            val saved = repository.save(LibraryContentDraft(MediaType.Tv, 99, "Keep me"), true)
            driver.execute(null, "DROP TABLE catalog_preview_cache", 0)
            TvFlickDatabase.Schema.migrate(driver, 2, 3)
            val cache = SqlDelightCatalogPreviewCache(database)
            cache.writePreview(CatalogCategory.PopularShows, null, page(1))
            assertEquals(saved.localId, repository.listWatchListItems().single().localId)
            assertEquals(page(1), cache.readPreview(CatalogCategory.PopularShows, null))
        } finally { driver.close() }
    }
}
