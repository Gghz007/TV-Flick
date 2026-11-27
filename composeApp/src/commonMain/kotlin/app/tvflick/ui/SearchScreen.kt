package app.tvflick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import app.tvflick.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import tv_flick.composeapp.generated.resources.*

@Composable
fun SearchScreen(state: FlickState, mobile: Boolean, androidPage: Boolean = false, bottomPadding: Dp = bottomOverlayHeight(mobile)) {
    LaunchedEffect(state.query) {
        val query = state.query.trim()
        if (query.isNotBlank() && state.completedSearchQuery == query) return@LaunchedEffect
        state.completedSearchQuery = null
        state.results = emptyList()
        state.searchError = null
        if (query.length < 2) { state.searchBusy = false; return@LaunchedEffect }
        state.searchBusy = true
        try {
            delay(350)
            val client = state.tmdb
            if (client == null) {
                state.searchError = "TMDB is not configured"
            } else {
                state.results = client.search(query)
                state.completedSearchQuery = query
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { state.searchError = "Could not load catalog"; state.notices.show(UserProblem.SearchUnavailable) }
        finally { state.searchBusy = false }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!mobile) Spacer(Modifier.height(21.dp))
        SearchField(state, mobile)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val availableWidth = maxWidth
            val previewWidth = availableWidth - if (mobile) 26.dp else 40.dp
            val posterBaseWidth = if (mobile) 110.512.dp else 129.683.dp
            val previewColumns = calculateGridColumns(availableWidth, posterBaseWidth, 8.489.dp, if (mobile) 13.dp else 20.dp)
            val posterWidth = (previewWidth - 8.489.dp * (previewColumns - 1)) / previewColumns
            val posterScale = posterWidth / posterBaseWidth
            if (state.query.isBlank()) {
                LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(top = if (mobile) 8.dp else 31.dp, bottom = bottomPadding),
                    verticalArrangement = Arrangement.spacedBy(if (mobile) 8.dp else 10.dp)) {
                    items(state.catalogFeeds, key = { it.category.name + it.seedId }) { feed ->
                        LaunchedEffect(feed) { state.loadCatalogPreview(feed) }
                        Column(Modifier.width(previewWidth + 10.dp)
                            .referenceClick { state.catalogCollection = feed }
                            .padding(start = 5.dp, end = 5.dp, top = 4.dp, bottom = 7.dp)) {
                            Box(Modifier.fillMaxWidth().height(if (mobile) 29.dp else 35.dp)) {
                            Label(feed.category.label, Modifier.offset(y = if (mobile) 3.dp else 0.dp),
                                if (mobile) 15 else 24, FontWeight.SemiBold)
                            }
                            val reservedHeight = (if (mobile) 196.47.dp else 230.55.dp) * posterScale
                            Row(Modifier.then(if (!feed.loaded && feed.error == null) Modifier.height(reservedHeight) else Modifier),
                                horizontalArrangement = Arrangement.spacedBy(8.489.dp)) {
                                feed.items.take(previewColumns).forEach { item ->
                                    ScaledReference(posterScale) {
                                        PosterTile(item.draft(), !mobile) { state.selectedTitle = item.draft() }
                                    }
                                }
                            }
                            feed.error?.let { Label(it, Modifier.align(Alignment.CenterHorizontally), color = FlickDesign.muted) }
                        }
                    }
                }
            } else {
                val grid = calculateAdaptiveCardGrid(maxWidth, 359.dp, if (androidPage) maxWidth else 440.dp, 10.dp, if (mobile) 8.dp else 20.dp)
                val columns = if (mobile && !androidPage) 1 else grid.columns
                val width = if (mobile && !androidPage) calculateSingleCardWidth(maxWidth, 8.dp, 440.dp) else grid.cardWidth
                LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (mobile) 8.dp else 10.dp),
                    contentPadding = PaddingValues(top = if (mobile) 8.dp else 31.dp, bottom = bottomPadding)) {
                    items(state.results.chunked(columns)) { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = if (mobile) 8.dp else 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { item -> CatalogCard(item.draft(), item.subtitle, CardKind.Search,
                                { state.selectedTitle = item.draft() }, { state.addToLibrary(item.draft()) }, Modifier.width(width),
                                added = state.library.any { it.content.tmdbId == item.id.toLong() && it.content.mediaType.databaseValue == item.mediaType && it.isInWatchList }) }
                        }
                    }
                }
            }
            state.searchError?.let { Label(it, Modifier.align(Alignment.Center), color = FlickDesign.muted) }
        }
    }
}

@Composable
private fun SearchField(state: FlickState, mobile: Boolean) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(state.query, { state.query = it }, singleLine = true,
        cursorBrush = SolidColor(FlickDesign.foreground),
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Light, color = FlickDesign.foreground),
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (mobile) 0.dp else 20.dp)
            .height(if (mobile) 118.dp else 44.dp)
            .background(FlickDesign.panel, RoundedCornerShape(5.dp))
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { field ->
            // The whole reference field, including its icon and padding, focuses text input.
            Box(Modifier.fillMaxSize()) {
                IconAsset(Res.drawable.search_icon, null, Modifier.align(Alignment.BottomStart).offset(13.dp, (-13).dp).size(18.dp))
                Box(Modifier.align(Alignment.BottomStart).padding(start = 44.dp, end = 13.dp, bottom = 13.dp)
                    .fillMaxWidth().height(18.dp)) {
                    if (state.query.isEmpty() && !focused) Label("Search", weight = FontWeight.Light, color = FlickDesign.muted)
                    field()
                }
            }
        })
}

