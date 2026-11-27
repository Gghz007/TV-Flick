package app.tvflick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import app.tvflick.collectionPosterColumns
import tv_flick.composeapp.generated.resources.*

/** The user approved reusing the existing Profile / Shows panel and poster grid. */
@Composable
fun CatalogCollectionPanel(state: FlickState, feed: CatalogFeed, mobile: Boolean, width: Dp,
                           androidPage: Boolean = false, bottomPadding: Dp = bottomOverlayHeight(mobile)) {
    val close = { state.catalogCollection = null }
    val list = key(feed) { rememberLazyListState() }
    LaunchedEffect(feed) { state.loadCatalogPreview(feed) }
    val columns = collectionPosterColumns(width, mobile)
    Box(Modifier.width(width).fillMaxHeight()
        .background(FlickDesign.panel).panelClick()) {
        LazyColumn(Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(top = if (mobile) 82.dp else 87.dp, bottom = bottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(feed.items.chunked(columns), key = { row -> "${row.first().mediaType}:${row.first().id}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = if (mobile) 13.dp else 25.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (mobile) 8.73.dp else 8.dp)) {
                    row.forEach { item ->
                        PosterTile(item.draft(), false) { state.selectedTitle = item.draft() }
                    }
                }
            }
            if (feed.hasMore) item(key = "next-page") {
                LaunchedEffect(feed.page, feed.busy) {
                    if (!feed.busy && feed.error == null) state.loadCatalogNext(feed)
                }
                feed.error?.let { message ->
                    Label(message, Modifier.referenceClick { state.loadCatalogNext(feed) }, color = FlickDesign.muted)
                }
            }
        }
        val compact = !androidPage && list.hasScrolled()
        Box(Modifier.fillMaxWidth().height(if (compact) DesktopScrolledHeaderHeight else if (mobile) 74.dp else 79.dp).background(FlickDesign.panel)) {
            Label(appText(feed.category.label).uppercase(), Modifier.fillMaxWidth().padding(horizontal = if (androidPage) 8.dp else 40.dp)
                .then(if (compact) Modifier.align(Alignment.Center) else Modifier.padding(top = if (mobile) 51.dp else 49.dp)),
                15, FontWeight.SemiBold, align = TextAlign.Center, translate = false)
        }
        if (!androidPage) ScrollingPanelControls(list, onBack = close, onClose = close)
        if (!androidPage) BottomFade(Modifier.align(Alignment.BottomCenter), mobile)
    }
}
