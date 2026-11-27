package app.tvflick.ui

import androidx.compose.runtime.*
import app.tvflick.core.tmdb.*
import kotlinx.coroutines.CancellationException

/** Each category owns its data, pagination and retry state. */
@Stable
class CatalogFeed(
    val category: CatalogCategory,
    val seedId: Long? = null,
    private val cache: CatalogPreviewCache? = null,
    private val loadPage: suspend (CatalogCategory, Int, Long?) -> TmdbCatalogPage,
) {
    companion object {
        const val MAX_ITEMS = 600
        // TMDB returns 20 per page. Also bounds requests if pages repeat titles.
        const val MAX_PAGES = 30
    }
    var items by mutableStateOf(emptyList<TmdbSearchResult>())
        private set
    var page by mutableStateOf(0)
        private set
    var totalPages by mutableStateOf(1)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val loaded get() = page > 0
    val hasMore get() = items.size < MAX_ITEMS && page < minOf(totalPages, MAX_PAGES)

    suspend fun loadInitial() {
        if (busy || loaded) return
        busy = true
        error = null
        try {
            cacheSafely { cache?.readPreview(category, seedId) }?.let { applyPage(it, replace = true) }
            // Keep the cached preview visible while fetching its replacement, never append it.
            val fresh = loadPage(category, 1, seedId)
            check(fresh.page == 1) { "Unexpected catalog page" }
            applyPage(fresh, replace = true)
            cacheSafely { cache?.writePreview(category, seedId, fresh) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!loaded) error = "Could not load catalog"
        } finally {
            busy = false
        }
    }

    suspend fun loadNext() {
        if (!loaded) { loadInitial(); return }
        if (busy || !hasMore) return
        busy = true
        error = null
        try {
            val requestedPage = page + 1
            val result = loadPage(category, requestedPage, seedId)
            check(result.page == requestedPage) { "Unexpected catalog page" }
            applyPage(result, replace = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "Could not load catalog"
        } finally {
            busy = false
        }
    }

    private fun applyPage(result: TmdbCatalogPage, replace: Boolean) {
        items = ((if (replace) emptyList() else items) + result.items)
            .distinctBy { it.mediaType to it.id }.take(MAX_ITEMS)
        page = result.page
        totalPages = if (result.items.isEmpty()) page else result.totalPages.coerceAtLeast(page).coerceAtMost(MAX_PAGES)
    }

    private suspend fun <T> cacheSafely(action: suspend () -> T): T? = try { action() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null } // A cache failure must not prevent online catalog loading.
}
