package app.tvflick.ui

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import app.tvflick.core.library.*
import app.tvflick.core.tmdb.*
import app.tvflick.fittedStripCellWidth
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import tv_flick.composeapp.generated.resources.*

@OptIn(ExperimentalTime::class)
@Composable
fun TitlePanel(state: FlickState, title: LibraryContentDraft, mobile: Boolean, width: Dp, androidPage: Boolean = false) {
    val inset = if (mobile) 8.dp else 20.dp
    val contentWidth = width - inset * 2
    val list = rememberLazyListState()
    LaunchedEffect(title.tmdbId, title.mediaType) { list.scrollToItem(0) }
    var details by remember(title.tmdbId, title.mediaType) { mutableStateOf<TmdbTitleDetails?>(null) }
    var expanded by remember(title.tmdbId, title.mediaType) { mutableStateOf<Long?>(null) }
    val seasons = remember(title.tmdbId, title.mediaType) { mutableStateMapOf<Long, TmdbSeasonDetails>() }
    var properties by remember(title.tmdbId, title.mediaType) { mutableStateOf(false) }
    var error by remember(title.tmdbId, title.mediaType) { mutableStateOf<String?>(null) }
    LaunchedEffect(error) { if (error != null) state.notices.show(UserProblem.MetadataUnavailable) }
    var retry by remember(title.tmdbId, title.mediaType) { mutableStateOf(0) }
    val local = state.library.find { it.content.tmdbId == title.tmdbId && it.content.mediaType == title.mediaType }
    val watched = local?.let { state.episodes[it.localId] }.orEmpty()
    var trailer by remember(title.tmdbId, title.mediaType) { mutableStateOf<YoutubeTrailer?>(null) }
    trailer?.let { PlatformTrailerWindow(it, state.notices) { trailer = null } }
    fun launchTitleAction(block: suspend () -> Unit) {
        state.launchLibraryAction {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Could not update title. Tap to retry loading." }
        }
    }
    suspend fun saveSeason(summary: TmdbSeasonSummary): List<EpisodePlaybackState> {
        val repo = requireNotNull(state.repository) { "Local library is unavailable" }
        val saved = repo.listItems().find { it.content.tmdbId == title.tmdbId && it.content.mediaType == title.mediaType }
        val season = seasons[summary.seasonNumber] ?: saved?.let { repo.cachedSeason(it.localId, summary.seasonNumber) }
            ?: state.tmdb?.seasonDetails(title.tmdbId, summary.seasonNumber)
            ?.also { seasons[summary.seasonNumber] = it } ?: error("Season data is unavailable")
        val item = state.ensureLibraryItem(title)
        repo.syncSeason(item.localId, summary, season)
        return repo.listEpisodesForShow(item.localId).filter { it.seasonNumber == summary.seasonNumber }
    }
    LaunchedEffect(title.tmdbId, title.mediaType, retry) {
        error = null
        try {
            details = state.repository?.cachedTitleDetails(title.mediaType, title.tmdbId)
            val remote = state.tmdb?.titleDetails(title.mediaType.databaseValue, title.tmdbId)
            if (remote != null) details = remote
            else if (details == null) error = "No saved details. Connect to TMDB. Tap to retry."
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = if (details != null) "Showing saved details. Tap to retry online." else "Could not load title. Tap to retry." }
    }
    LaunchedEffect(expanded, retry, title.tmdbId, title.mediaType) {
        val number = expanded ?: return@LaunchedEffect
        try {
            local?.let { state.repository?.cachedSeason(it.localId, number) }?.let { seasons[number] = it }
            state.tmdb?.seasonDetails(title.tmdbId, number)?.let { seasons[number] = it }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = if (number in seasons) "Showing saved episodes. Tap to retry online." else "Could not load episodes. Tap to retry." }
    }
    Box(Modifier.width(width).fillMaxHeight().background(FlickDesign.panel).panelClick()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 20.dp)) {
            item {
                Box(Modifier.width(width).height(if (mobile) 237.dp else width * 237f / 516f).clip(RoundedCornerShape(0.dp))) {
                    (details?.backdropPath ?: title.backdropPath)?.let {
                        LibraryArtwork("https://image.tmdb.org/t/p/${if (androidPage && width < 600.dp) "w780" else "w1280"}$it",
                            null, Modifier.fillMaxSize())
                    }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.15f to Color(0x26101010), 1f to FlickDesign.background)))
                    // Keep the reference's bottom insets when the backdrop becomes taller.
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(237.dp)) {
                    (details?.posterPath ?: title.posterPath)?.let {
                        AsyncImage(app.tvflick.core.backup.libraryImageModel("https://image.tmdb.org/t/p/w342$it"), title.title, Modifier.offset(12.dp, 102.dp).size(81.dp, 120.dp), contentScale = ContentScale.Crop)
                    }
                    Label(title.title, Modifier.padding(start = 106.dp, end = 47.dp, top = 113.dp).width(215.dp),
                        20, FontWeight.SemiBold, translate = false)
                    val info = listOfNotNull(
                        details?.seasons?.count { it.seasonNumber > 0 }?.takeIf { it > 0 }?.let { "$it season" },
                        (details?.releaseDate?.take(4) ?: title.releaseYear?.toString()),
                    ).joinToString("  •  ") + details?.status?.let { "  |  $it" }.orEmpty()
                    Label(info, Modifier.padding(start = 106.dp, end = 47.dp, top = 139.dp).width(215.dp), 13)
                    details?.score?.let { score ->
                        IconAsset(Res.drawable.detail_star, null, Modifier.offset(109.dp, 167.dp).size(11.dp, 10.486.dp))
                        Label("${(score * 10).roundToInt()}%", Modifier.offset(122.dp, 162.dp), 15, FontWeight.Bold, FlickDesign.yellow)
                    }
                    Label(details?.networks?.joinToString(", ").orEmpty(),
                        Modifier.padding(start = 161.dp, end = 47.dp, top = 164.dp).width(160.dp), 13, FontWeight.Light)
                    Box(Modifier.offset(106.dp, 189.dp).size(86.dp, 33.dp)
                        .background(FlickDesign.panel, RoundedCornerShape(100.dp))
                        .referenceClick(RoundedCornerShape(100.dp)) {
                            val available = details?.trailer
                            if (available == null) state.notices.show(UserProblem.TrailerUnavailable)
                            else trailer = available
                        }) {
                        IconAsset(Res.drawable.detail_trailer, "Trailer", Modifier.offset(16.dp, 8.dp).size(17.dp))
                        Label("Trailer", Modifier.fillMaxWidth().padding(start = 33.5.dp, end = 5.dp, top = 8.dp), 13, FontWeight.Medium)
                    }
                    SettingsButton("Properties",
                        Modifier.align(Alignment.TopEnd).offset((-18).dp, if (mobile) 53.dp else 117.dp)) { properties = true }
                    if (local?.isFavorite == true) IconAsset(Res.drawable.detail_favorite, "Favorite",
                        Modifier.align(Alignment.TopEnd).offset((-20).dp, if (mobile) 132.dp else 162.dp).size(22.dp).referenceClick(CircleShape) {
                            launchTitleAction {
                                state.repository?.setFavorite(local.localId, false)
                                state.refresh()
                            }
                        })
                    }
                }
            }
            if (!mobile) item { Spacer(Modifier.height(8.dp)) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = inset + 6.dp)
                    .padding(top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Label("Description", size = 15, weight = FontWeight.SemiBold)
                    val overview = details?.overview ?: title.overview.orEmpty()
                    if (overview.isNotBlank()) Label(overview, Modifier.fillMaxWidth(),
                        13, FontWeight.Light, FlickDesign.muted, maxLines = Int.MAX_VALUE)
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            if (title.mediaType == MediaType.Tv) {
                item {
                    Box(Modifier.padding(horizontal = inset).width(contentWidth).height(41.dp)) {
                        Label("Episodes", Modifier.offset(5.dp, 16.dp), 15, FontWeight.SemiBold)
                    }
                }
                items(details?.seasons.orEmpty().filter { it.seasonNumber > 0 }, key = { it.seasonNumber }) { summary ->
                    val seasonWatched = watched.count { it.seasonNumber == summary.seasonNumber && it.watchedAtEpochMs != null }
                    val total = seasons[summary.seasonNumber]?.episodes?.size ?: summary.episodeCount
                    val progress by animateFloatAsState(
                        if (total > 0) (seasonWatched.toFloat() / total).coerceIn(0f, 1f) else 0f,
                        tween(380, easing = FastOutSlowInEasing), label = "Season progress")
                    val arrowRotation by animateFloatAsState(if (expanded == summary.seasonNumber) 0f else 180f,
                        tween(180), label = "Season arrow")
                    Column(Modifier.animateContentSize(tween(220, easing = FastOutSlowInEasing))) {
                    Box(Modifier.padding(horizontal = inset).width(contentWidth).height(46.dp)
                        .background(FlickDesign.neutral(Color(0xFF161616)), RoundedCornerShape(5.dp))
                        .referenceClick { expanded = if (expanded == summary.seasonNumber) null else summary.seasonNumber }) {
                        Label("${appText("Season")} ${summary.seasonNumber}", Modifier.padding(start = 13.dp, top = 15.dp).width(64.dp),
                            13, FontWeight.SemiBold, translate = false)
                        IconAsset(Res.drawable.season_arrow, null, Modifier.offset(80.dp, 15.dp).size(16.dp, 17.dp).rotate(arrowRotation))
                        Label("$seasonWatched / $total", Modifier.align(Alignment.TopEnd).padding(start = 100.dp, end = 51.dp, top = 15.dp)
                            .widthIn(max = 92.dp), 13, FontWeight.Light)
                        IconAsset(if (seasonWatched > 0 && seasonWatched == total) Res.drawable.episode_mark_active else Res.drawable.episode_mark,
                            "Mark season watched", Modifier.align(Alignment.TopEnd).offset((-8).dp, 8.dp).size(30.dp).referenceClick(CircleShape) {
                                launchTitleAction {
                                    val episodes = saveSeason(summary)
                                    val time = if (episodes.isNotEmpty() && episodes.all { it.watchedAtEpochMs != null }) null else Clock.System.now().toEpochMilliseconds()
                                    episodes.forEach { state.repository?.setEpisodeWatched(it.episodeId, time) }
                                    state.refresh()
                                }
                            })
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(FlickDesign.neutral(Color(0xFF4D4D4D))))
                        Box(Modifier.align(Alignment.BottomStart).width(contentWidth * progress).height(2.dp).background(FlickDesign.yellow))
                    }
                    Spacer(Modifier.height(8.dp))
                    if (expanded == summary.seasonNumber) seasons[summary.seasonNumber]?.episodes?.forEach { episode ->
                        val saved = watched.find { it.seasonNumber == summary.seasonNumber && it.episodeNumber == episode.episodeNumber }
                        val requestVideo: (Boolean) -> Unit = { download ->
                            launchTitleAction {
                                val record = saveSeason(summary).find { it.episodeNumber == episode.episodeNumber }
                                val item = state.ensureLibraryItem(title)
                                if (record != null) state.playback?.start(PlaybackKey(item.localId, record.episodeId), download)
                            }
                        }
                        EpisodeRow(contentWidth, inset, summary.seasonNumber, episode,
                            watched = saved?.watchedAtEpochMs != null,
                            playAction = state.playInsteadOfMark,
                            sourceActions = if (state.hasVideoSources) { modifier -> SourceActions(state, modifier = modifier, onRequest = requestVideo) } else null,
                            favorite = saved?.isFavorite == true,
                            onFavorite = {
                                launchTitleAction {
                                    val record = saveSeason(summary).find { it.episodeNumber == episode.episodeNumber }
                                    if (record != null) state.repository?.setEpisodeFavorite(record.episodeId, !record.isFavorite)
                                    state.refresh()
                                }
                            }, onMark = {
                            if (state.playInsteadOfMark) requestVideo(false) else
                            launchTitleAction {
                                val record = saveSeason(summary).find { it.episodeNumber == episode.episodeNumber }
                                if (record != null) state.repository?.setEpisodeWatched(record.episodeId,
                                    if (record.watchedAtEpochMs != null) null else Clock.System.now().toEpochMilliseconds())
                                state.refresh()
                            }
                        })
                        Spacer(Modifier.height(8.dp))
                    }
                    }
                }
            } else item {
                Box(Modifier.padding(horizontal = inset).width(contentWidth).height(98.dp).referenceHover()) {
                    (details?.backdropPath ?: title.backdropPath)?.let {
                        AsyncImage(app.tvflick.core.backup.libraryImageModel("https://image.tmdb.org/t/p/w342$it"), null, Modifier.offset(y = 13.dp).size(90.dp, 64.dp), contentScale = ContentScale.Crop)
                    }
                    val actionInset = if (state.hasVideoSources) 72.dp else 43.dp
                    Label(title.title, Modifier.padding(start = 102.dp, top = 25.dp, end = actionInset).fillMaxWidth(),
                        15, FontWeight.Medium, translate = false)
                    details?.runtimeMinutes?.let { Label("${it / 60}h ${it % 60}m", Modifier.offset(102.dp, 48.dp), 13, FontWeight.Medium) }
                    val requestVideo: (Boolean) -> Unit = { download -> launchTitleAction {
                        val item = state.ensureLibraryItem(title)
                        state.playback?.start(PlaybackKey(item.localId), download)
                    } }
                    if (state.hasVideoSources) SourceActions(state, modifier = Modifier.align(Alignment.TopEnd).offset((-8).dp, 33.dp), onRequest = requestVideo)
                    PlayOrMarkButton(state.playInsteadOfMark,
                        if (local?.movieWatchedAtEpochMs != null) Res.drawable.episode_mark_active else Res.drawable.episode_mark,
                        if (state.playInsteadOfMark) "Play movie" else "Mark watched",
                        Modifier.align(Alignment.TopEnd).offset((if (state.hasVideoSources) -37 else -8).dp, 30.dp).size(30.dp)) {
                            if (state.playInsteadOfMark) requestVideo(false) else
                            launchTitleAction {
                                val item = state.repository?.let { state.ensureLibraryItem(title) }
                                if (item != null) state.repository?.setMovieWatched(item.localId,
                                    if (item.movieWatchedAtEpochMs != null) null else Clock.System.now().toEpochMilliseconds())
                                state.refresh()
                            }
                        }
                }
            }
            item {
                val gap = if (mobile) 8.35.dp else 14.dp
                val cellWidth = fittedStripCellWidth(contentWidth, 83.488.dp, gap)
                val scale = cellWidth / 83.488.dp
                val rowHeight = 116.884.dp * scale
                Box(Modifier.padding(horizontal = inset).width(contentWidth).height(31.dp + rowHeight + 10.dp)) {
                    Label("Cast", Modifier.offset(5.dp, 6.dp), 15, FontWeight.SemiBold)
                    HorizontalStrip(mobile, Modifier.offset(y = 31.dp).width(contentWidth).height(rowHeight),
                        spacing = gap, itemWidth = cellWidth) {
                        details?.cast.orEmpty().forEach { person ->
                            ScaledReference(scale) {
                            Box(Modifier.size(83.488.dp, 116.884.dp)) {
                                person.photoPath?.let { AsyncImage(app.tvflick.core.backup.libraryImageModel("https://image.tmdb.org/t/p/w185$it"), person.name,
                                    Modifier.size(83.488.dp).clip(RoundedCornerShape(100.dp)), contentScale = ContentScale.Crop) }
                                Label(person.name, Modifier.offset(y = 88.498.dp).fillMaxWidth(), 12, FontWeight.Light, align = TextAlign.Center)
                                Label(person.character, Modifier.offset(y = 102.691.dp).fillMaxWidth(), 10, FontWeight.Light, align = TextAlign.Center)
                            }
                            }
                        }
                    }
                }
            }
            item {
                val stripWidth = contentWidth - 10.dp
                val gap = if (mobile) 8.73.dp else 8.dp
                val cellWidth = fittedStripCellWidth(stripWidth, 110.512.dp, gap)
                val scale = cellWidth / 110.512.dp
                val rowHeight = 196.47.dp * scale
                Box(Modifier.padding(horizontal = inset).width(contentWidth).height(31.dp + rowHeight + 8.dp)) {
                    Label(if (title.mediaType == MediaType.Tv) "Similar shows" else "Similar movies", Modifier.offset(5.dp, 6.dp), 15, FontWeight.SemiBold)
                    HorizontalStrip(mobile, Modifier.offset(5.dp, 31.dp).width(stripWidth).height(rowHeight),
                        spacing = gap, itemWidth = cellWidth) {
                        details?.similar.orEmpty().forEach { item ->
                            ScaledReference(scale) {
                            PosterTile(item.draft(), false) { state.selectedTitle = item.draft() }
                            }
                        }
                    }
                }
            }
            error?.let { item { Label(it, Modifier.fillMaxWidth().referenceClick { retry++ }.padding(inset),
                color = FlickDesign.muted, maxLines = 3) } }
        }
        if (!androidPage) ScrollingPanelControls(list,
            onBack = { state.selectedTitle = null }, onClose = { state.selectedTitle = null },
            pinned = true, headerOnScroll = true)
        if (properties) {
            val hasProgress = local?.movieWatchedAtEpochMs != null || (local?.moviePositionMs ?: 0) > 0 ||
                watched.any { it.watchedAtEpochMs != null || (it.positionMs ?: 0) > 0 }
            var actionBusy by remember { mutableStateOf(false) }
            DismissibleBottomPanel(androidPage, mobile, onDismiss = { properties = false }) {
                TitlePropertiesPanel(local, hasProgress, actionBusy, stretch = androidPage) { action ->
                    if (!actionBusy) {
                        actionBusy = true
                        launchTitleAction {
                          try {
                            if (state.repository == null) return@launchTitleAction
                            state.applyTitleAction(title, action)
                            properties = false
                          } catch (e: CancellationException) { throw e }
                          catch (_: Exception) { error = "Could not update title" }
                          finally { actionBusy = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(width: Dp, inset: Dp, season: Long, episode: TmdbEpisode, watched: Boolean,
                       favorite: Boolean, onFavorite: () -> Unit, onMark: () -> Unit,
                       playAction: Boolean = false, sourceActions: (@Composable (Modifier) -> Unit)? = null) {
    Box(Modifier.padding(horizontal = inset).width(width).height(64.dp)
        .background(FlickDesign.neutral(Color(0xFF161616)), RoundedCornerShape(5.dp)).referenceHover()) {
        episode.stillPath?.let { AsyncImage(app.tvflick.core.backup.libraryImageModel("https://image.tmdb.org/t/p/w342$it"), null, Modifier.size(90.dp, 64.dp), contentScale = ContentScale.Crop) }
        Box(Modifier.size(90.dp, 64.dp).referenceClick(onFavorite), contentAlignment = Alignment.Center) {
            IconAsset(if (favorite) Res.drawable.episode_favorite_active else Res.drawable.episode_favorite,
                appText(if (favorite) "Remove episode from favorites" else "Favorite episode"), Modifier.size(28.dp))
        }
        val actionInset = if (sourceActions != null) 72.dp else 43.dp
        Label("S${season.toString().padStart(2, '0')} | E${episode.episodeNumber.toString().padStart(2, '0')}",
            Modifier.padding(start = 102.dp, top = 12.dp, end = actionInset).fillMaxWidth(), 13)
        Label(episode.name.orEmpty(), Modifier.padding(start = 102.dp, top = 34.861.dp, end = actionInset).fillMaxWidth(),
            13, FontWeight.Light, translate = false)
        PlayOrMarkButton(playAction, if (watched) Res.drawable.episode_mark_active else Res.drawable.episode_mark,
            if (playAction) "Play episode" else "Mark episode watched",
            Modifier.align(Alignment.CenterEnd).offset(x = (if (sourceActions != null) -37 else -8).dp).size(30.dp), onMark)
        sourceActions?.invoke(Modifier.align(Alignment.CenterEnd).offset(x = (-8).dp))
    }
}
