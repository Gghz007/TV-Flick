package app.tvflick.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Warm the first visible Search rows without composing a hidden Search screen. */
@Composable
fun PreloadSearchPosters(state: FlickState, androidPage: Boolean) {
    val context = LocalPlatformContext.current
    val loader = SingletonImageLoader.get(context)
    LaunchedEffect(state, loader, context, androidPage) {
        val slots = Semaphore(if (androidPage) 2 else 3)
        val requested = mutableSetOf<String>()
        snapshotFlow {
            if (state.destination == Destination.Search && state.query.isNotBlank()) emptyList()
            else state.catalogFeeds.take(3).flatMap { feed ->
                feed.items.take(if (androidPage) 4 else 8).mapNotNull { it.posterUrl }
            }.distinct()
        }.collectLatest { urls ->
            // First-screen images only; no background traversal of the 600-item collections.
            requested.retainAll(urls.toSet())
            coroutineScope {
                urls.filterNot(requested::contains).forEach { url ->
                    launch {
                        slots.withPermit {
                            loader.execute(ImageRequest.Builder(context).data(url).size(342, 512).build())
                            requested.add(url)
                        }
                    }
                }
            }
        }
    }
}
