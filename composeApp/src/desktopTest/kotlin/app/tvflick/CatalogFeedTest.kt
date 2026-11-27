package app.tvflick

import app.tvflick.core.tmdb.*
import app.tvflick.ui.CatalogFeed
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.test.*

class CatalogFeedTest {
    private fun title(id: Int, type: String = "tv") = TmdbSearchResult(id, type, "Title $id", "2026", null, null, "")

    private class MemoryCache(var value: TmdbCatalogPage?) : CatalogPreviewCache {
        override suspend fun readPreview(category: CatalogCategory, seedId: Long?) = value
        override suspend fun writePreview(category: CatalogCategory, seedId: Long?, page: TmdbCatalogPage) { value = page }
        override suspend fun prunePreviews() = Unit
    }

    @Test
    fun `cached preview appears before network and is replaced before pagination`() = runBlocking {
        val cache = MemoryCache(TmdbCatalogPage(listOf(title(1)), 1, 5))
        val response = CompletableDeferred<TmdbCatalogPage>()
        val requests = mutableListOf<Int>()
        val feed = CatalogFeed(CatalogCategory.PopularShows, cache = cache) { _, page, _ ->
            requests += page
            if (page == 1) response.await() else TmdbCatalogPage(listOf(title(3)), page, 2)
        }
        val loading = launch(start = CoroutineStart.UNDISPATCHED) { feed.loadInitial() }
        assertEquals(listOf(1), feed.items.map { it.id })
        assertTrue(feed.busy)
        feed.loadNext()
        assertEquals(listOf(1), requests)
        response.complete(TmdbCatalogPage(listOf(title(2)), 1, 2))
        loading.join()
        assertEquals(listOf(2), feed.items.map { it.id })
        feed.loadInitial() // Reopening a preview in the same session must not fetch again.
        feed.loadNext()
        assertEquals(listOf(1, 2), requests)
        assertEquals(listOf(2, 3), feed.items.map { it.id })
        assertEquals(listOf(2), cache.value!!.items.map { it.id })
    }

    @Test
    fun `refresh failure keeps cached preview available`() = runBlocking {
        val cache = MemoryCache(TmdbCatalogPage(listOf(title(1)), 1, 2))
        val feed = CatalogFeed(CatalogCategory.PopularShows, cache = cache) { _, _, _ -> error("offline") }
        feed.loadInitial()
        assertEquals(listOf(1), feed.items.map { it.id })
        assertNull(feed.error)
        assertFalse(feed.busy)
        assertEquals(1, feed.page)
    }

    @Test
    fun `cache storage failures do not break online loading`() = runBlocking {
        val cache = object : CatalogPreviewCache {
            override suspend fun readPreview(category: CatalogCategory, seedId: Long?): TmdbCatalogPage? = error("unreadable")
            override suspend fun writePreview(category: CatalogCategory, seedId: Long?, page: TmdbCatalogPage) { error("read only") }
            override suspend fun prunePreviews() = Unit
        }
        val feed = CatalogFeed(CatalogCategory.PopularShows, cache = cache) { _, _, _ -> TmdbCatalogPage(listOf(title(1)), 1, 2) }
        feed.loadInitial()
        assertEquals(listOf(1), feed.items.map { it.id })
        assertNull(feed.error)
        assertFalse(feed.busy)
    }

    @Test
    fun `large catalog stops at 600 without requesting another page`() = runBlocking {
        var requests = 0
        val feed = CatalogFeed(CatalogCategory.TrendingMovies) { _, page, _ ->
            requests++
            TmdbCatalogPage((1..20).map { title((page - 1) * 20 + it, "movie") }, page, 10000)
        }
        repeat(100) { feed.loadNext() }
        assertEquals(600, feed.items.size)
        assertEquals(30, requests)
        assertFalse(feed.hasMore)
    }

    @Test
    fun `oversized page is truncated and duplicate pages cannot load endlessly`() = runBlocking {
        val oversized = CatalogFeed(CatalogCategory.PopularShows) { _, page, _ ->
            TmdbCatalogPage((1..700).map { title(it) }, page, 999)
        }
        oversized.loadNext()
        assertEquals(600, oversized.items.size)
        assertFalse(oversized.hasMore)
        var requests = 0
        val repeated = CatalogFeed(CatalogCategory.PopularShows) { _, page, _ ->
            requests++
            TmdbCatalogPage(listOf(title(1)), page, 10000)
        }
        repeat(100) { repeated.loadNext() }
        assertEquals(30, requests)
        assertEquals(1, repeated.items.size)
        assertFalse(repeated.hasMore)
    }

    @Test
    fun `empty page ends loading even if upstream reports more pages`() = runBlocking {
        val feed = CatalogFeed(CatalogCategory.PopularShows) { _, page, _ -> TmdbCatalogPage(emptyList(), page, 999) }
        feed.loadNext()
        assertTrue(feed.loaded)
        assertFalse(feed.hasMore)
    }

    @Test
    fun `failed next page keeps existing items and retry requests the same page`() = runBlocking {
        val requests = mutableListOf<Int>()
        var fail = true
        val feed = CatalogFeed(CatalogCategory.PopularShows) { _, page, _ ->
            requests += page
            if (page == 2 && fail) { fail = false; error("offline") }
            TmdbCatalogPage(if (page == 1) listOf(title(1), title(2)) else listOf(title(2), title(3)), page, 2)
        }
        feed.loadNext()
        feed.loadNext()
        assertEquals(listOf(1, 2), feed.items.map { it.id })
        assertEquals(1, feed.page)
        assertNotNull(feed.error)
        feed.loadNext()
        assertEquals(listOf(1, 2, 2), requests)
        assertEquals(listOf(1, 2, 3), feed.items.map { it.id })
        assertFalse(feed.hasMore)
        assertNull(feed.error)
    }

    @Test
    fun `catalog categories keep independent results and pagination`() = runBlocking {
        val load: suspend (CatalogCategory, Int, Long?) -> TmdbCatalogPage = { category, page, seed ->
            assertNull(seed)
            TmdbCatalogPage(listOf(title(if (category == CatalogCategory.PopularShows) page else page + 100)), page, 3)
        }
        val popular = CatalogFeed(CatalogCategory.PopularShows, loadPage = load)
        val rated = CatalogFeed(CatalogCategory.TopRatedShows, loadPage = load)
        popular.loadNext(); popular.loadNext(); rated.loadNext()
        assertEquals(listOf(1, 2), popular.items.map { it.id })
        assertEquals(listOf(101), rated.items.map { it.id })
        assertEquals(1, rated.page)
    }

    @Test
    fun `typed lists infer media identity and exclude wrong media from mixed responses`() {
        val result = parseCatalogPage("""{"page":2,"total_pages":4,"results":[
            {"id":7,"name":"Show","first_air_date":"2024-10-01"},
            {"id":8,"name":"Wrong","media_type":"movie"},
            {"id":9,"name":"Actor","media_type":"person"}]}""", "tv")
        assertEquals(2, result.page)
        assertEquals(4, result.totalPages)
        assertEquals("2024", result.items.single().subtitle)
        assertEquals("tv", result.items.single().mediaType)
        assertEquals(7, result.items.single().id)
    }

    @Test
    fun `recommendations require a real seed instead of silently returning popular titles`() {
        assertFailsWith<IllegalArgumentException> { CatalogCategory.RecommendedShows.path() }
        assertEquals("tv/42/recommendations", CatalogCategory.RecommendedShows.path(42))
        assertEquals("movie/43/recommendations", CatalogCategory.RecommendedMovies.path(43))
        assertEquals("movie/popular", CatalogCategory.PopularMovies.path())
    }
}
