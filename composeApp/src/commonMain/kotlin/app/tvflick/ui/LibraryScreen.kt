package app.tvflick.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import app.tvflick.*
import app.tvflick.core.library.*
import tv_flick.composeapp.generated.resources.*

@Composable
fun LibraryScreen(state: FlickState, mobile: Boolean, androidPage: Boolean = false, bottomPadding: Dp = 100.dp) {
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(mobile, state.libraryTab) {
        if (mobile) {
            var drag = 0f
            detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                if (drag < -60.dp.toPx()) state.libraryTab = LibraryTab.Upcoming
                if (drag > 60.dp.toPx()) state.libraryTab = LibraryTab.WatchList
            }) { change, amount -> change.consume(); drag += amount }
        }
    }) {
        val cardInset = if (mobile) FlickDesign.androidContentInset else 20.dp
        val listInset = if (androidPage) cardInset else 0.dp
        val rowInset = if (androidPage) 0.dp else cardInset
        val grid = calculateAdaptiveCardGrid(maxWidth, 359.dp, if (androidPage) maxWidth else 440.dp, 10.dp, cardInset)
        val columns = if (mobile && !androidPage) 1 else grid.columns
        val cardWidth = if (mobile && !androidPage) calculateSingleCardWidth(maxWidth, cardInset, 440.dp) else grid.cardWidth
        LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(start = listInset, end = listInset, top = if (mobile) 82.dp else 86.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(if (mobile) 8.dp else 10.dp)) {
            state.libraryError?.let { message ->
                item {
                    Label("$message. Tap to retry.", Modifier.fillMaxWidth().referenceClick {
                        state.retryLibrary()
                    }.padding(12.dp), color = FlickDesign.muted, maxLines = 3)
                }
            }
            if (state.libraryTab == LibraryTab.WatchList) {
                
                val entries = state.watchListEntries
                if (entries.isNotEmpty()) item { SectionPill("WATCH NEXT") }
                items(entries.chunked(columns)) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = rowInset),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (item, episode) ->
                            val playbackKey = PlaybackKey(item.localId, episode?.episodeId)
                            val sources = state.hasVideoSources && (item.content.mediaType == MediaType.Movie || episode != null)
                            val plays = state.playInsteadOfMark
                            val started = (item.moviePositionMs ?: 0) > 0 || state.episodes[item.localId].orEmpty()
                                .any { it.watchedAtEpochMs != null || (it.positionMs ?: 0) > 0 }
                            val detail = episode?.let {
                                "S${it.seasonNumber.toString().padStart(2, '0')} | E${it.episodeNumber.toString().padStart(2, '0')}" +
                                    it.title?.let { title -> "  •  $title" }.orEmpty()
                            } ?: item.content.runtimeMinutes?.let { "${it / 60}h ${it % 60}m" }
                                ?: item.content.releaseYear?.toString().orEmpty()
                            CatalogCard(item.content, detail, if (started || episode != null) CardKind.InProgress else CardKind.NotStarted,
                                onOpen = { state.selectedTitle = item.content }, onAction = {
                                    if (plays) state.playback?.start(playbackKey)
                                    else if (episode != null) state.markEpisode(episode) else state.markNext(item)
                                }, playAction = plays,
                                sourceActions = if (sources) { modifier -> SourceActions(state, playbackKey, modifier) } else null,
                                modifier = Modifier.width(cardWidth), remainingEpisodes = if (episode != null) state.remainingAfterNext(item) else 0)
                        }
                    }
                }
            } else {
                state.visibleUpcomingMovies.groupBy { releaseSection(it.content.releaseDate!!, state.calendarDate) }.forEach { (section, movies) ->
                    item { SectionPill(section) }
                    items(movies.chunked(columns)) { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = rowInset),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { item ->
                                CatalogCard(item.content, item.content.releaseDate.orEmpty(), CardKind.Upcoming,
                                    onOpen = { state.selectedTitle = item.content }, modifier = Modifier.width(cardWidth),
                                    daysRemaining = daysUntil(item.content.releaseDate!!, state.calendarDate))
                            }
                        }
                    }
                }
                state.visibleUpcoming.sortedBy { it.airDate }.groupBy { releaseSection(it.airDate, state.calendarDate) }.forEach { (section, episodes) ->
                item { SectionPill(section) }
                items(episodes.chunked(columns)) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = rowInset),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { episode ->
                            val content = state.library.find { it.localId == episode.showContentId }?.content
                            if (content != null) CatalogCard(content,
                                "S${episode.seasonNumber.toString().padStart(2, '0')} | E${episode.episodeNumber.toString().padStart(2, '0')}" +
                                    episode.episodeName?.let { "  •  $it" }.orEmpty(), CardKind.Upcoming,
                                onOpen = { state.selectedTitle = content }, modifier = Modifier.width(cardWidth),
                                daysRemaining = daysUntil(episode.airDate, state.calendarDate))
                        }
                    }
                }
                }
            }
        }
        if (!mobile) Box(Modifier.align(Alignment.TopCenter).offset(y = 75.dp).width(1137.dp).height(1.dp)
            .background(Brush.horizontalGradient(0f to Color.Transparent, .25f to FlickDesign.neutral(Color(0x26FFFFFF)), .75f to FlickDesign.neutral(Color(0x26FFFFFF)), 1f to Color.Transparent)))
        Box(Modifier.align(Alignment.TopCenter).offset(y = if (mobile) 0.dp else 30.dp)) {
            LibraryTabs(state.libraryTab, mobile, stretch = androidPage) { state.libraryTab = it }
        }
    }
}

