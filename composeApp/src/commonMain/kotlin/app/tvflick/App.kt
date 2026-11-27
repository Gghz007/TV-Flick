package app.tvflick

import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import app.tvflick.core.library.LibraryRepository
import app.tvflick.plugin.video.PlayerAppearance
import app.tvflick.ui.*

@Composable
fun App(
    tmdbApiKey: String = "",
    libraryRepository: LibraryRepository? = null,
    topPanel: (@Composable () -> Unit)? = null,
    squareCorners: Boolean = false,
    playerWindowOptions: PlayerWindowOptions = PlayerWindowOptions(),
) {
    val state = rememberFlickState(tmdbApiKey, libraryRepository)
    val backup = rememberBackupController(state)
    ReleaseUpdates(state.notices)
    val activePlayback = state.playback?.active
    DisposableEffect(activePlayback != null) {
        playerWindowOptions.onActiveChanged(activePlayback != null)
        onDispose { if (activePlayback != null) playerWindowOptions.onActiveChanged(false) }
    }
    PreloadSearchPosters(state, androidPage = topPanel == null)
    val useLightTheme = THEME_SELECTION_AVAILABLE && state.profilePreferences.lightTheme
    FlickTheme(light = useLightTheme, language = state.profilePreferences.language, touchFeedback = topPanel == null) {
        val language = state.profilePreferences.language
        val translate: (String) -> String = remember(language) { { text -> appText(text, language) } }
        BoxWithConstraints(Modifier.fillMaxSize()
            .clip(RoundedCornerShape(if (topPanel == null || squareCorners) 0.dp else 10.dp))
            .background(FlickDesign.background)) {
            val hasSubpage = state.profileOpen || state.catalogCollection != null || state.selectedTitle != null
            PlatformBackHandler(hasSubpage) {
                when {
                    state.selectedTitle != null -> state.selectedTitle = null
                    state.profileOpen && state.profileCollection != null -> state.profileCollection = null
                    state.profileOpen && state.settingsOpen -> state.settingsOpen = false
                    state.profileOpen -> state.profileOpen = false
                    else -> state.catalogCollection = null
                }
            }
            if (topPanel == null) {
                AndroidPages(state, backup)
            } else {
                DesktopPages(state, backup, topPanel, hasSubpage)
            }
            PlayerAppearance(useLightTheme, translate) {
                activePlayback?.let { content -> PlatformPlaybackHost(state, content, playerWindowOptions, Modifier.fillMaxSize()) }
                if (!playerWindowOptions.mini) PlaybackMenus(state)
            }
            PlaybackDownloads(state)
            if (!playerWindowOptions.mini) {
                UserNoticeHost(state.notices, hasDesktopTitleBar = topPanel != null && activePlayback == null,
                    modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.DesktopPages(
    state: FlickState,
    backup: BackupController,
    topPanel: @Composable () -> Unit,
    hasSubpage: Boolean,
) {
    val mobile = maxWidth < 720.dp
    Box(Modifier.fillMaxSize().then(if (hasSubpage) Modifier.blur(3.dp) else Modifier)) {
        Column(Modifier.fillMaxSize()) {
            topPanel()
            PageEntrance(state.destination, Modifier.weight(1f).fillMaxWidth()) {
                when (state.destination) {
                    Destination.Shows, Destination.Movies -> LibraryScreen(state, mobile)
                    Destination.Search -> SearchScreen(state, mobile)
                    Destination.Profile -> Unit
                }
            }
        }
        if (state.showEmptyReference) ReferenceEmptyState(mobile,
            if (state.destination == Destination.Search) "No results found" else "EMPTY LIST",
            addLabel = when (state.destination) { Destination.Shows -> "ADD SHOWS"; Destination.Movies -> "ADD MOVIES"; else -> null },
            onAdd = { state.openRecommendations() })
        BottomFade(Modifier.align(Alignment.BottomCenter), mobile)
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = if (mobile) 8.dp else 20.dp)) {
            BottomNavigation(if (state.profileOpen) Destination.Profile else state.destination, mobile, state::select)
        }
    }
    if (hasSubpage) {
        Box(Modifier.fillMaxSize().background(Color(0x55101010)).panelClick {
            state.selectedTitle = null
            state.profileOpen = false
            state.catalogCollection = null
        })
        val collectionWidth = collectionPanelWidth(maxWidth, mobile, maxHeight)
        val panelScale = panelContentScale(collectionWidth, mobile)
        val baseDensity = LocalDensity.current
        // Re-measure text, vectors and hit targets at the same scale. A drawing
        // transform alone would leave layout and scrolling at their old sizes.
        CompositionLocalProvider(LocalDensity provides Density(
            baseDensity.density * panelScale, baseDensity.fontScale)) {
        val layoutWidth = collectionWidth / panelScale
        state.catalogCollection?.let { feed ->
            PageEntrance(feed, Modifier.align(Alignment.TopCenter)) { CatalogCollectionPanel(state, feed, mobile, layoutWidth) }
        }
        if (state.profileOpen) PageEntrance(state.profileCollection ?: state.settingsTab.takeIf { state.settingsOpen } ?: Destination.Profile,
            Modifier.align(Alignment.TopCenter)) { ProfilePanel(state, mobile, layoutWidth, backup) }
        state.selectedTitle?.let { title ->
            PageEntrance(title.mediaType to title.tmdbId, Modifier.align(Alignment.TopCenter)) { TitlePanel(state, title, mobile, layoutWidth) }
        }
        }
    }
}

@Composable
private fun AndroidPages(state: FlickState, backup: BackupController) {
    val mainPages = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val title = state.selectedTitle
        val feed = state.catalogCollection
        val navigationInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
        val navigationHeight = 68.dp + navigationInsets.asPaddingValues().calculateBottomPadding()
        val scrollsBehindNavigation = title == null &&
            (!state.profileOpen || (!state.settingsOpen && state.profileCollection == null))
        val fadeHeight = maxOf(bottomOverlayHeight(mobile = true), navigationHeight + 56.dp)
        val bottomPadding = if (scrollsBehindNavigation) fadeHeight + 8.dp else 24.dp
        BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = if (scrollsBehindNavigation) 0.dp else navigationHeight)) {
            val contentWidth = maxWidth
            val page = title?.let { it.mediaType to it.tmdbId } ?: if (state.profileOpen)
                state.profileCollection ?: state.settingsTab.takeIf { state.settingsOpen } ?: Destination.Profile
            else feed ?: state.destination
            PageEntrance(page, Modifier.fillMaxSize()) {
            when {
                title != null -> TitlePanel(state, title, mobile = true, width = contentWidth, androidPage = true)
                state.profileOpen -> ProfilePanel(state, mobile = true, width = contentWidth, backup = backup,
                    androidPage = true, overviewBottomPadding = bottomPadding)
                feed != null -> CatalogCollectionPanel(state, feed, mobile = true, width = contentWidth,
                    androidPage = true, bottomPadding = bottomPadding)
                else -> {
                    mainPages.SaveableStateProvider(state.destination.name) {
                        when (state.destination) {
                            Destination.Shows, Destination.Movies -> LibraryScreen(state, mobile = true,
                                androidPage = true, bottomPadding = bottomPadding)
                            Destination.Search -> SearchScreen(state, mobile = true, androidPage = true, bottomPadding = bottomPadding)
                            Destination.Profile -> Unit
                        }
                    }
                    if (state.showEmptyReference) ReferenceEmptyState(true,
                        if (state.destination == Destination.Search) "No results found" else "EMPTY LIST",
                        addLabel = when (state.destination) {
                            Destination.Shows -> "ADD SHOWS"
                            Destination.Movies -> "ADD MOVIES"
                            else -> null
                        },
                        onAdd = state::openRecommendations)
                }
            }
            }
        }
        if (scrollsBehindNavigation) {
            BottomFade(Modifier.align(Alignment.BottomCenter), mobile = true, androidPage = true, height = fadeHeight,
                opaqueFrom = 1f - navigationHeight / fadeHeight)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .windowInsetsPadding(navigationInsets)
            .padding(start = FlickDesign.androidContentInset, end = FlickDesign.androidContentInset, bottom = 8.dp)) {
            BottomNavigation(if (state.profileOpen) Destination.Profile else state.destination,
                mobile = true, onSelect = state::selectAndroidPage, stretch = true)
        }
    }
}

@Composable
private fun PageEntrance(page: Any, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val opacity = remember(page) { Animatable(.78f) }
    LaunchedEffect(page) { opacity.animateTo(1f, tween(160)) }
    Box(modifier.graphicsLayer { alpha = opacity.value }) { content() }
}
