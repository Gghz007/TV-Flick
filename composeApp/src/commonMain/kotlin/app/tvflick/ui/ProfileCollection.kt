package app.tvflick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import app.tvflick.collectionPosterColumns
import app.tvflick.core.library.*
import tv_flick.composeapp.generated.resources.*

@Composable
fun ProfileCollection(state: FlickState, mobile: Boolean, list: LazyListState, width: Dp, androidPage: Boolean = false) {
    val name = state.profileCollection ?: return
    var filtersOpen by remember(name) { mutableStateOf(false) }
    var sort by remember(name) { mutableStateOf(1) }
    var filters by remember(name) { mutableStateOf(emptySet<CollectionProgress>()) }
    val collection = state.library.filter {
        it.content.mediaType == (if (name.endsWith("Movies")) MediaType.Movie else MediaType.Tv) &&
            (!name.startsWith("Favorite") || it.isFavorite)
    }
    val statuses = collection.associate { item -> item.localId to collectionProgress(item,
        state.episodes[item.localId].orEmpty(), state.nextEpisode(item) != null).filterGroup() }
    // Derive choices from the whole collection, not the currently filtered subset.
    val available = availableProgressFilters(statuses.values)
    val activeFilters = filters.intersect(available.toSet())
    LaunchedEffect(available) { filters = filters.intersect(available.toSet()) }
    val columns = collectionPosterColumns(width, mobile)
    val items = collection.filter { activeFilters.isEmpty() || statuses[it.localId] in activeFilters }.let { items ->
        when (sort) {
            0 -> items.sortedByDescending { item ->
                maxOf(item.movieWatchedAtEpochMs ?: 0, state.episodes[item.localId].orEmpty().maxOfOrNull { it.watchedAtEpochMs ?: 0 } ?: 0)
            }
            2 -> items.sortedBy { it.content.title.lowercase() }
            else -> items.sortedByDescending { it.addedAtEpochMs }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = if (mobile) 82.dp else 87.dp, bottom = if (androidPage) 60.dp else bottomOverlayHeight(mobile)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true, false).forEach { completed ->
                val group = items.filter { (statuses[it.localId] == CollectionProgress.Finished) == completed }
                if (group.isNotEmpty()) {
                item {
                    val text = if (completed) "WATCHED" else "NOT WATCHED"
                    Box(Modifier.size(if (completed) 78.dp else 85.dp, 17.dp).background(FlickDesign.neutral(Color(0xFF3F3F3F)), RoundedCornerShape(100.dp)),
                        contentAlignment = Alignment.Center) { Label(text, Modifier.padding(horizontal = 4.dp), size = 10) }
                }
                items(group.chunked(columns), key = { row -> "${completed}:${row.first().localId}" }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = if (mobile) 13.dp else 25.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (mobile) 8.73.dp else 8.dp)) {
                        row.forEach { item -> PosterTile(item.content, false) { state.selectedTitle = item.content } }
                    }
                }
                }
            }
        }
        val compact = !androidPage && list.hasScrolled()
        Box(Modifier.fillMaxWidth().height(if (compact) DesktopScrolledHeaderHeight else if (mobile) 74.dp else 79.dp).background(FlickDesign.panel)) {
            Label(appText(name).uppercase(), Modifier.fillMaxWidth().padding(horizontal = if (androidPage) 8.dp else 40.dp)
                .then(if (compact) Modifier.align(Alignment.Center) else Modifier.padding(top = if (mobile) 51.dp else 49.dp)),
                15, FontWeight.SemiBold, align = TextAlign.Center, translate = false)
        }
        BottomFade(Modifier.align(Alignment.BottomCenter), mobile, androidPage)
        if (collection.isNotEmpty()) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = if (mobile) 27.dp else 45.dp).size(80.dp, 25.dp)
            .background(FlickDesign.accent, RoundedCornerShape(30.dp)).referenceClick(RoundedCornerShape(30.dp)) { filtersOpen = true }) {
            IconAsset(Res.drawable.profile_filter, null, Modifier.offset(11.09375.dp, 6.25.dp).size(13.125.dp, 12.5.dp))
            Label("FILTERS", Modifier.fillMaxWidth().padding(start = 26.dp, end = 4.dp, top = 5.dp), 13, FontWeight.SemiBold, Color.White)
        }
        if (filtersOpen) {
            DismissibleBottomPanel(androidPage, mobile, onDismiss = { filtersOpen = false }) {
                CollectionFilters(sort, activeFilters, CollectionProgress.entries.filter { it != CollectionProgress.WatchLater }, stretch = androidPage, onApply = { nextSort, nextFilters ->
                    sort = nextSort; filters = nextFilters; filtersOpen = false
                })
            }
        }
    }
}

@Composable
private fun CollectionFilters(sort: Int, filters: Set<CollectionProgress>, available: List<CollectionProgress>, stretch: Boolean,
                              onApply: (Int, Set<CollectionProgress>) -> Unit) {
    var pendingSort by remember { mutableStateOf(sort) }
    var pendingFilters by remember { mutableStateOf(filters) }
    val choices = listOf<CollectionProgress?>(null) + available
    val actionsY = 107 + choices.size * 31 + 20
    val panelWidth = if (stretch) Modifier.fillMaxWidth() else Modifier.width(375.dp)
    BoxWithConstraints(panelWidth.verticalScroll(rememberScrollState()).height((actionsY + 55).dp)
        .background(FlickDesign.neutral(Color(0xFF161616))).panelClick()) {
        val extraWidth = if (stretch) maxWidth - 375.dp else 0.dp
        val extraPerSort = extraWidth / 3
        Box(Modifier.fillMaxWidth().height(2.dp).background(FlickDesign.accent))
        Label("Sort by", Modifier.offset(8.dp, 30.dp), 16, FontWeight.SemiBold)
        listOf("Last watched", "Last added", "Alphabetical").forEachIndexed { i, label ->
            Box(Modifier.offset(listOf(8, 141, 257)[i].dp + extraPerSort * i, 53.dp)
                .size((if (i == 0) 126.dp else 110.dp) + extraPerSort, 25.dp)
                .background(if (pendingSort == i) FlickDesign.neutral(Color(0xFF323232)) else FlickDesign.neutral(Color(0xFF1B1B1B)), RoundedCornerShape(30.dp))
                .referenceClick(RoundedCornerShape(30.dp), selected = pendingSort == i) { pendingSort = i }, contentAlignment = Alignment.Center) {
                Label(label, Modifier.padding(horizontal = 5.dp), size = 12, align = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Label("Progress", Modifier.offset(8.dp, 84.dp), 16, FontWeight.SemiBold)
        choices.forEachIndexed { i, progress ->
            val checked = if (progress == null) pendingFilters.isEmpty() else progress in pendingFilters
            Box(Modifier.offset(8.dp, (107 + i * 31).dp).size(359.dp + extraWidth, 31.dp).referenceClick(RectangleShape) {
                pendingFilters = if (progress == null) emptySet()
                    else if (progress in pendingFilters) pendingFilters - progress else pendingFilters + progress
            }) {
                Label(progress?.label ?: "All", Modifier.fillMaxWidth().padding(top = 8.dp, end = 24.dp), 12)
                ReferenceSelectionMark(checked, Modifier.align(Alignment.TopEnd).offset(y = 7.dp))
                Box(Modifier.offset(y = 30.5.dp).fillMaxWidth().height(.5.dp).background(FlickDesign.neutral(Color(0x40FFFFFF))))
            }
        }
        listOf("RESET", "APPY").forEachIndexed { i, label ->
            Box(Modifier.offset(if (i == 0) 8.dp else 197.059.dp + extraWidth / 2, actionsY.dp)
                .size(169.941.dp + extraWidth / 2, 25.dp)
                .background(FlickDesign.neutral(Color(0xFF1B1B1B)), RoundedCornerShape(30.dp))
                .referenceClick(RoundedCornerShape(30.dp)) {
                    if (i == 0) { pendingSort = 1; pendingFilters = emptySet() }
                    else onApply(pendingSort, pendingFilters)
                }, contentAlignment = Alignment.Center) {
                Label(label, Modifier.padding(horizontal = 5.dp), size = 12, weight = FontWeight.SemiBold, color = FlickDesign.foreground)
            }
        }
    }
}
