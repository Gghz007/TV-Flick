package app.tvflick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import app.tvflick.core.backup.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.tvflick.core.library.*
import app.tvflick.collectionPosterColumns
import app.tvflick.calculateGridColumns
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import tv_flick.composeapp.generated.resources.*

@Composable
fun ProfilePanel(state: FlickState, mobile: Boolean, width: Dp, backup: BackupController,
                 androidPage: Boolean = false, overviewBottomPadding: Dp = 24.dp) {
    LaunchedEffect(state.settingsTab, state.sourceSites.isEmpty()) {
        if (state.settingsTab == SettingsTab.ADBLOCK && state.sourceSites.isEmpty()) state.settingsTab = SettingsTab.DATABASE
    }
    val list = key(state.profileCollection, state.settingsOpen, state.settingsTab) { rememberLazyListState() }
    Box(Modifier.width(width).fillMaxHeight().background(FlickDesign.panel)
        .then(if (androidPage) Modifier else Modifier.panelClick())) {
        if (state.profileCollection != null) ProfileCollection(state, mobile, list, width, androidPage)
        else if (!state.settingsOpen) ProfileOverview(state, mobile, list, width, androidPage, overviewBottomPadding) else {
            if (mobile) SettingsTabs(state, true)
            val bodyWidth = if (androidPage || state.settingsTab == SettingsTab.PROFILE) width else minOf(width, 375.dp)
            Box(Modifier.width(bodyWidth).align(Alignment.TopCenter)
                .padding(top = if (mobile) 74.dp else 79.dp, bottom = if (mobile && !androidPage) 74.dp else 0.dp)) {
                when (state.settingsTab) {
                    SettingsTab.PROFILE -> ProfileSettings(state, mobile, list, backup)
                    SettingsTab.BACKUP -> BackupPanels(list, backup)
                    SettingsTab.DATABASE, SettingsTab.ADBLOCK -> {
                        val manager = state.settingsTab == SettingsTab.ADBLOCK
                        if (androidPage) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                                SourceSettingsPanel(state, manager, list, sidePadding = 0.dp)
                            }
                        } else SourceSettingsPanel(state, manager, list)
                    }
                }
            }
            if (state.settingsTab == SettingsTab.DATABASE && state.sourceSites.isEmpty())
                BottomFade(Modifier.align(Alignment.BottomCenter), mobile, androidPage)
            if (!mobile) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(79.dp).background(FlickDesign.panel)
                    .border(1.dp, Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .1375f), Color.Transparent)), RectangleShape))
            }
        }
        if (!androidPage) ScrollingPanelControls(list,
            onBack = if (state.profileCollection != null) ({ state.profileCollection = null }) else null,
            onClose = { state.profileOpen = false }, pinned = true,
            headerOnScroll = state.profileCollection == null && !state.settingsOpen)
        if (!mobile && state.settingsOpen && state.profileCollection == null) {
            Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 38.dp).padding(top = 27.dp)) {
                SettingsTabs(state, false)
            }
        }
        if (state.settingsOpen && state.settingsTab == SettingsTab.BACKUP) {
            BackupRestoreConfirmation(backup)
        }
    }
}

@Composable
private fun ProfileOverview(state: FlickState, mobile: Boolean, list: LazyListState, width: Dp,
                            androidPage: Boolean, bottomPadding: Dp) {
    val inset = if (mobile) FlickDesign.androidContentInset else 20.dp
    val posterGap = 8.dp
    val columns = if (androidPage) calculateGridColumns(width, 110.512.dp, posterGap, inset).coerceAtLeast(3)
        else collectionPosterColumns(width, mobile)
    val categoryInnerPadding = if (androidPage) 0.dp else 5.dp
    val posterStripWidth = (width - (inset + categoryInnerPadding) * 2 - posterGap * (columns - 1)).coerceAtLeast(1.dp)
    val posterScale = if (androidPage)
        (posterStripWidth / columns) / 110.512.dp
        else 1f
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottomPadding)) {
        item {
            Box(Modifier.fillMaxWidth().height(if (mobile) 237.dp else width * 237f / 516f)) {
                state.library.firstOrNull { it.content.backdropPath != null }?.content?.backdropPath?.let {
                    AsyncImage(app.tvflick.core.backup.libraryImageModel("https://image.tmdb.org/t/p/w780$it"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x26101010), FlickDesign.background))))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(237.dp)) {
                    SettingsButton("Settings", Modifier.align(Alignment.TopEnd).offset((-18).dp, 92.dp)) {
                        state.settingsOpen = true
                    }
                    ProfileStatistics(state, mobile, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        val groups = listOf(
            "Shows" to state.library.filter { it.content.mediaType == MediaType.Tv },
            "Favorite Shows" to state.library.filter { it.content.mediaType == MediaType.Tv && it.isFavorite },
            "Movies" to state.library.filter { it.content.mediaType == MediaType.Movie },
            "Favorite Movies" to state.library.filter { it.content.mediaType == MediaType.Movie && it.isFavorite },
        )
        items(groups) { (label, titles) ->
            Column(Modifier.padding(horizontal = inset).fillMaxWidth().clip(RoundedCornerShape(5.dp))
                .alpha(if (titles.isEmpty()) .55f else 1f)
                .then(if (titles.isNotEmpty()) Modifier.referenceClick { state.profileCollection = label } else Modifier)
                .padding(horizontal = categoryInnerPadding, vertical = 4.dp)) {
                val favorite = label.startsWith("Favorite")
                Box(Modifier.fillMaxWidth().padding(horizontal = if (androidPage) 5.dp else 0.dp).height(27.dp)) {
                    if (favorite) IconAsset(Res.drawable.profile_favorite, null, Modifier.offset(.5.dp, 2.dp).size(17.dp))
                    Label(label, Modifier.offset(if (favorite) 22.dp else 0.dp, 2.dp), 15, FontWeight.SemiBold)
                }
                Row(Modifier.then(if (androidPage) Modifier.fillMaxWidth() else Modifier).heightIn(min = 196.47.dp * posterScale),
                    horizontalArrangement = Arrangement.spacedBy(posterGap)) {
                    titles.take(columns).forEach { item ->
                        if (androidPage) ScaledReference(posterScale) {
                            PosterTile(item.content, false) { state.selectedTitle = item.content }
                        } else PosterTile(item.content, false) { state.selectedTitle = item.content }
                    }
                    repeat((columns - titles.size).coerceAtLeast(0)) {
                        if (androidPage) ScaledReference(posterScale) { EmptyPosterTile() }
                        else EmptyPosterTile()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        item {
            Box(Modifier.padding(horizontal = inset + 12.dp).fillMaxWidth().height(1.dp).background(FlickDesign.neutral(Color(0xFF323232))))
            Column(Modifier.fillMaxWidth().padding(horizontal = inset + 12.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconAsset(Res.drawable.tmdb_logo, "TMDB", Modifier.size(57.072.dp, 24.456.dp))
                Label("Metadata and artwork provided by TMDB.", Modifier.fillMaxWidth(), size = 9,
                    color = FlickDesign.muted, align = TextAlign.Center, maxLines = 2)
                Label("This product uses the TMDB API but is not endorsed or certified by TMDB.", Modifier.fillMaxWidth(), size = 9,
                    color = FlickDesign.muted, align = TextAlign.Center, maxLines = 3)
            }
        }
    }
}

@Composable
private fun SettingsTabs(state: FlickState, mobile: Boolean) {
    Row(Modifier.then(if (mobile) Modifier.fillMaxWidth() else Modifier.width(359.dp))
        .height(if (mobile) 74.dp else 26.dp)) {
        SettingsTab.entries.forEach { tab ->
            val active = tab == state.settingsTab
            val available = tab != SettingsTab.ADBLOCK || state.sourceSites.isNotEmpty()
            val shape = RoundedCornerShape(if (mobile) 0.dp else 100.dp)
            Box(Modifier.weight(1f).fillMaxHeight()
                .background(if (active && !mobile) FlickDesign.neutral(Color(0xFF1C1C1C)) else Color.Transparent, RoundedCornerShape(100.dp))
                .alpha(if (available) 1f else .4f)
                .then(if (LocalTouchFeedback.current) Modifier.clickable(interactionSource = null, indication = null,
                    enabled = available) { state.settingsTab = tab }
                    else Modifier.referenceClick(shape, selected = active, enabled = available) { state.settingsTab = tab })) {
                Text(settingsTabLabel(tab), Modifier.fillMaxWidth().padding(horizontal = 3.dp).padding(top = if (mobile) 53.dp else 6.dp),
                    color = if (active) FlickDesign.foreground else FlickDesign.muted,
                    fontSize = if (mobile) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.sp,
                    letterSpacing = (-.22215913).sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (mobile && active) Box(Modifier.align(Alignment.BottomCenter).height(1.dp).fillMaxWidth().background(FlickDesign.foreground))
            }
        }
    }
}

@Composable
internal fun GoogleAccountPanel(backup: BackupController, mobile: Boolean) {
    val avatarX = if (mobile) 12.dp else 10.dp
    val textX = if (mobile) 66.dp else 64.dp
    val actionEnd = if (mobile) 12.dp else 27.dp
    Box(Modifier.fillMaxWidth().height(80.dp)) {
        Box(Modifier.offset(avatarX, 15.dp).size(50.dp).background(FlickDesign.neutral(Color(0xFF323232)), RoundedCornerShape(100.dp)))
        backup.account?.picture?.let { AsyncImage(it, null, Modifier.offset(avatarX, 15.dp).size(50.dp)
            .clip(RoundedCornerShape(100.dp)), contentScale = ContentScale.Crop) }
        Label(backup.account?.name ?: appText("Local profile"), Modifier.padding(start = textX, end = actionEnd + 34.dp)
            .offset(y = if (backup.account == null) 31.dp else 21.dp), 15, FontWeight.SemiBold, translate = false)
        backup.account?.let {
            Row(Modifier.padding(start = textX, end = actionEnd + 34.dp).offset(y = 43.dp)
                .border(1.dp, FlickDesign.neutral(Color(0x40FFFFFF)), RoundedCornerShape(100.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Label(it.email, size = 13, weight = FontWeight.Medium, translate = false)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(end = actionEnd).offset(y = 27.dp).size(26.dp)
            .referenceClick(RoundedCornerShape(2.dp), action = backup::signInOrOut)) {
            IconAsset(if (backup.account == null) Res.drawable.google_login else Res.drawable.google_logout,
                appText(if (backup.account == null) "Sign in to Google" else "Sign out of Google"), Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun BackupPanels(list: LazyListState, backup: BackupController) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackupBlock(cloud = true, backup) }
        item { BackupBlock(cloud = false, backup) }
        backup.message?.let { message -> item { Label(message, Modifier.fillMaxWidth().padding(12.dp), 12, color = FlickDesign.muted, maxLines = 4) } }
        if (backup.busy) item { Label("Working…", Modifier.padding(12.dp), 12, color = FlickDesign.muted) }
    }
}

@Composable
private fun BackupBlock(cloud: Boolean, backup: BackupController) {
    var optionsOpen by remember { mutableStateOf(false) }
    var restoreOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(if (cloud) 246.dp else 153.dp)) {
        val dateWidth = if (cloud) 83.dp else 46.dp
        val optionsWidth = (maxWidth - 129.dp).coerceAtLeast(0.dp)
        Label(if (cloud) "Google Backup" else "Local Backup",
            Modifier.fillMaxWidth().padding(start = 12.dp, end = dateWidth + 20.dp).offset(y = 7.dp), 20, FontWeight.SemiBold)
        Label(if (cloud) "Create a GoogleDrive data backup" else "Create a local data backup",
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 37.dp), 12, FontWeight.Light)
        Box(Modifier.align(Alignment.TopEnd).padding(end = 12.dp).offset(y = 11.dp).size(dateWidth, 17.dp)
            .background(FlickDesign.neutral(Color(0xFF3F3F3F)), RoundedCornerShape(100.dp)), contentAlignment = Alignment.Center) {
            Label(if (cloud) backupDate(backup.lastCloudBackup) else backupDate(backup.lastLocalBackup).let { if (it == "...") it else it.take(5) },
                Modifier.padding(horizontal = 3.dp), size = 12, weight = FontWeight.Light)
        }
        Box(Modifier.offset(12.dp, 59.dp).size(optionsWidth, 37.dp).background(FlickDesign.neutral(Color(0xFF0B0B0B)), RoundedCornerShape(5.dp))
            .border(1.dp, FlickDesign.neutral(Color(0x40FFFFFF)), RoundedCornerShape(5.dp)).referenceClick { optionsOpen = true }) {
            Label(if (cloud) "Auto:" else "Format:", Modifier.padding(start = if (cloud) 54.dp else 10.dp, top = 10.dp, end = 29.dp)
                .width(42.dp), 13, FontWeight.ExtraLight, FlickDesign.muted)
            Label(if (cloud) backup.interval.label else backup.format.label,
                Modifier.fillMaxWidth().padding(start = if (cloud) 100.dp else 60.dp, end = 29.dp, top = 10.dp),
                13, FontWeight.ExtraLight)
            if (cloud) BackupToggle(Modifier.size(51.dp, 37.dp), backup.autoExport,
                appText("AutoExport:"), onToggle = backup::toggleAutoExport)
            IconAsset(Res.drawable.dropdown_arrow, null,
                Modifier.align(Alignment.TopEnd).padding(end = 12.dp).offset(y = 12.dp).size(14.dp), tint = FlickDesign.foreground)
            DropdownMenu(optionsOpen, { optionsOpen = false }, containerColor = FlickDesign.neutral(Color(0xFF0B0B0B))) {
                if (cloud) BackupInterval.entries.forEach { option ->
                    BackupMenuItem(option.label, option == backup.interval) { backup.chooseInterval(option); optionsOpen = false }
                } else ExportFormat.entries.forEach { option ->
                    BackupMenuItem(option.label, option == backup.format) { backup.chooseFormat(option); optionsOpen = false }
                }
            }
        }
        val imageAvailable = cloud || backup.format != ExportFormat.Csv
        BackupToggle(Modifier.align(Alignment.TopEnd).padding(end = 12.dp).offset(y = 58.dp).size(102.dp, 37.dp),
            checked = if (cloud) backup.cloudImages else backup.localImages && imageAvailable,
            description = appText("Image"), enabled = imageAvailable, onToggle = { backup.toggleImages(cloud) }) {
            Label("Image", Modifier.fillMaxWidth().padding(start = 51.dp, top = 10.dp, end = 3.dp), 13, FontWeight.ExtraLight, FlickDesign.muted)
        }
        if (cloud) {
            BackupButton("EXPORT", Modifier.fillMaxWidth().padding(horizontal = 12.dp).offset(y = 105.dp).height(38.dp),
                onClick = backup::exportCloud)
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).offset(y = 152.dp).height(37.dp)
                .background(FlickDesign.neutral(Color(0xFF0B0B0B)))
                .referenceClick { if (!backup.busy) { restoreOpen = true; backup.listCloudBackups() } }) {
                Label("SelectRestore:", Modifier.padding(start = 10.dp, top = 10.dp).width(76.dp), 13, FontWeight.ExtraLight, FlickDesign.muted)
                Label(backup.selectedCloudBackup?.createdTime?.take(19)?.replace('T', ' ') ?: "...",
                    Modifier.fillMaxWidth().padding(start = 93.dp, end = 32.dp, top = 10.dp), 13, FontWeight.ExtraLight)
                IconAsset(Res.drawable.dropdown_arrow, null,
                    Modifier.align(Alignment.TopEnd).padding(end = 12.dp).offset(y = 12.dp).size(14.dp), tint = FlickDesign.foreground)
                DropdownMenu(restoreOpen, { restoreOpen = false }, Modifier.heightIn(max = 240.dp), containerColor = FlickDesign.neutral(Color(0xFF0B0B0B))) {
                    if (backup.cloudBackups.isEmpty()) Label(if (backup.busy) "Loading…" else "No backups", Modifier.padding(12.dp), 13)
                    backup.cloudBackups.forEach { file ->
                        BackupMenuItem(file.createdTime.take(19).replace('T', ' '), file.id == backup.selectedCloudBackup?.id) {
                            backup.selectCloudBackup(file); restoreOpen = false
                        }
                    }
                }
            }
            BackupButton("RESTORE", Modifier.fillMaxWidth().padding(horizontal = 12.dp).offset(y = 198.dp).height(38.dp),
                accent = true, onClick = backup::importCloud)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).offset(y = 106.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                BackupButton("IMPORT", Modifier.weight(1f).height(37.dp), onClick = backup::importLocal)
                BackupButton("EXPORT", Modifier.weight(1f).height(37.dp), onClick = backup::exportLocal)
            }
        }
    }
}

@Composable
private fun BackupButton(label: String, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Box(modifier.background(if (accent) FlickDesign.accent else FlickDesign.neutral(Color(0xFF2D2D2D)), RoundedCornerShape(5.dp))
        .referenceClick(onClick), contentAlignment = Alignment.Center) {
        Label(label, Modifier.padding(horizontal = 6.dp), size = 16, weight = FontWeight.SemiBold,
            color = if (accent) Color.White else FlickDesign.foreground, align = TextAlign.Center)
    }
}

@Composable
private fun BackupToggle(
    modifier: Modifier,
    checked: Boolean,
    description: String,
    enabled: Boolean = true,
    onToggle: () -> Unit,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val interactions = remember { MutableInteractionSource() }
    Box(modifier.alpha(if (enabled) 1f else .35f).semantics { contentDescription = description }
        .toggleable(value = checked, interactionSource = interactions, indication = null, enabled = enabled, role = Role.Switch,
            onValueChange = { onToggle() })) {
        IconAsset(if (checked) Res.drawable.source_toggle_on else Res.drawable.source_toggle_off, null,
            Modifier.offset(10.dp, 10.dp).size(33.32.dp, 17.dp)
                .interactionHighlight(interactions, RoundedCornerShape(100.dp), enabled))
        content()
    }
}

@Composable
private fun BackupMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.widthIn(min = 140.dp).height(32.dp).referenceClick(onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        Label(label, size = 13, color = if (selected) FlickDesign.accent else FlickDesign.foreground)
    }
}


