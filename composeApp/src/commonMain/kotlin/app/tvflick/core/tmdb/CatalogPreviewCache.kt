package app.tvflick.core.tmdb

import app.tvflick.core.database.TvFlickDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface CatalogPreviewCache {
    suspend fun readPreview(category: CatalogCategory, seedId: Long?): TmdbCatalogPage?
    suspend fun writePreview(category: CatalogCategory, seedId: Long?, page: TmdbCatalogPage)
    suspend fun prunePreviews()
}

/** A bounded, replace-in-place metadata cache; it never stores image files or API credentials. */
@OptIn(ExperimentalTime::class)
class SqlDelightCatalogPreviewCache(
    private val database: TvFlickDatabase,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CatalogPreviewCache {
    companion object {
        const val TTL_MS = 24 * 60 * 60 * 1000L
        const val MAX_ENTRIES = 16L
        const val MAX_ITEMS = 20
        const val MAX_ENTRY_BYTES = 256 * 1024
    }
    private val queries = database.catalogPreviewQueries
    private fun key(category: CatalogCategory, seedId: Long?) = "v1:${category.path(seedId)}"
    private fun prune() {
        val time = now()
        queries.deleteExpired(time - TTL_MS, time)
        queries.trimPreviews(MAX_ENTRIES)
    }

    override suspend fun prunePreviews() = withContext(Dispatchers.Default) {
        database.transaction { prune() }
    }

    override suspend fun readPreview(category: CatalogCategory, seedId: Long?): TmdbCatalogPage? =
        withContext(Dispatchers.Default) {
            database.transactionWithResult {
                prune()
                val cacheKey = key(category, seedId)
                val body = queries.selectPreview(cacheKey).executeAsOneOrNull() ?: return@transactionWithResult null
                val page = runCatching {
                    check(body.encodeToByteArray().size <= MAX_ENTRY_BYTES)
                    parseCatalogPage(body, category.mediaType).also {
                        check(it.page == 1 && it.items.size <= MAX_ITEMS)
                    }
                }.getOrNull()
                if (page == null) queries.deletePreview(cacheKey)
                page
            }
        }

    override suspend fun writePreview(category: CatalogCategory, seedId: Long?, page: TmdbCatalogPage) {
        require(page.page == 1) { "Only the first catalog page can be cached" }
        withContext(Dispatchers.Default) {
            val body = buildJsonObject {
                put("page", 1)
                put("total_pages", page.totalPages)
                putJsonArray("results") {
                    page.items.take(MAX_ITEMS).forEach { item ->
                        addJsonObject {
                            put("id", item.id)
                            put("media_type", item.mediaType)
                            put(if (category.mediaType == "movie") "title" else "name", item.title)
                            put(if (category.mediaType == "movie") "release_date" else "first_air_date", item.releaseDate ?: item.subtitle)
                            item.posterPath?.let { put("poster_path", it) }
                            item.backdropPath?.let { put("backdrop_path", it) }
                            put("overview", item.overview)
                        }
                    }
                }
            }.toString()
            database.transaction {
                val cacheKey = key(category, seedId)
                if (body.encodeToByteArray().size <= MAX_ENTRY_BYTES) queries.replacePreview(cacheKey, now(), body)
                else queries.deletePreview(cacheKey)
                prune()
            }
        }
    }
}
