package app.tvflick.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tvflick.core.library.AppLanguage
import app.tvflick.core.library.LibraryResetScope
import org.jetbrains.compose.resources.DrawableResource
import tv_flick.composeapp.generated.resources.*

@Composable
internal fun ProfileSettings(state: FlickState, mobile: Boolean, list: LazyListState, backup: BackupController) {
    val preferences = state.profilePreferences
    var languageOpen by remember { mutableStateOf(false) }
    var resetOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var scopes by remember { mutableStateOf(emptySet<LibraryResetScope>()) }
    val inset = if (mobile) 8.dp else 20.dp
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(inset, 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { GoogleAccountPanel(backup, mobile) }
        if (state.hasProfileSettings) {
            item {
                SettingsRow("New episode or movie releases", Res.drawable.settings_notification, action = { state.toggleReleaseAlerts() }) {
                    IconAsset(if (preferences.releaseAlerts) Res.drawable.source_toggle_on else Res.drawable.source_toggle_off,
                        appText(if (preferences.releaseAlerts) "Notifications on" else "Notifications off"), Modifier.size(49.dp, 25.dp))
                }
            }
            if (THEME_SELECTION_AVAILABLE) item {
                SettingsRow("Theme", Res.drawable.settings_theme, action = { state.toggleLightTheme() }, iconSize = 19.dp) {
                    IconAsset(if (preferences.lightTheme) Res.drawable.settings_theme_switch_light else Res.drawable.settings_theme_switch,
                        appText(if (preferences.lightTheme) "Light theme" else "Dark theme"), Modifier.size(49.dp, 25.dp))
                }
            }
            item {
                Column {
                    SettingsRow(preferences.language.label, Res.drawable.settings_language,
                        action = { languageOpen = !languageOpen }, iconSize = 23.dp) { SettingsArrow(languageOpen, mobile) }
                    AnimatedVisibility(languageOpen) {
                        Column(Modifier.padding(top = 4.dp).background(FlickDesign.settingsRow, RoundedCornerShape(5.dp))) {
                            AppLanguage.entries.forEach { language ->
                                Row(Modifier.fillMaxWidth().height(42.dp).referenceClick(selected = language == preferences.language,
                                    shape = RoundedCornerShape(5.dp)) {
                                    state.chooseAppLanguage(language)
                                    languageOpen = false
                                }.padding(start = 14.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Label(language.label, Modifier.weight(1f), 13, FontWeight.Medium)
                                    SettingsCheck(language == preferences.language, language.label, Modifier.size(25.dp))
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column {
                    SettingsRow("Reset Library", Res.drawable.settings_reset, action = { resetOpen = !resetOpen },
                        iconSize = 19.dp, iconHeight = 20.dp,
                        shape = if (resetOpen) RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp) else RoundedCornerShape(5.dp)) {
                        SettingsArrow(resetOpen, mobile)
                    }
                    AnimatedVisibility(resetOpen) {
                        Column(Modifier.background(FlickDesign.settingsRow, RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))) {
                            LibraryResetScope.entries.forEach { scope ->
                                val (title, hint) = when (scope) {
                                    LibraryResetScope.Titles -> "Shows and Movies" to "Clear the library, statuses in the list."
                                    LibraryResetScope.ShowViews -> "Shows views" to "Remove all \"watched\" marks for Shows Episodes."
                                    LibraryResetScope.MovieViews -> "Movie views" to "Remove all \"watched\" marks for Movies"
                                }
                                val checked = scope in scopes
                                ResetScopeRow(title, hint, checked) {
                                    scopes = if (checked) scopes - scope else scopes + scope
                                }
                            }
                            ResetAcceptButton(scopes.isNotEmpty() && !state.resettingLibrary && !state.libraryRestoring) {
                                confirming = true
                            }
                        }
                    }
                }
            }
        }
        backup.message?.let { message -> item { Label(message, Modifier.padding(horizontal = 12.dp), 12,
            color = FlickDesign.muted, maxLines = 4) } }
        if (backup.busy) item { Label("Working…", Modifier.padding(horizontal = 12.dp), 12, color = FlickDesign.muted) }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(appText("Reset Library")) },
        text = { Text(appText("Clear the selected library data? This cannot be undone without a backup.")) },
        confirmButton = { TextButton(onClick = {
            state.resetLibrary(scopes)
            scopes = emptySet()
            confirming = false
        }) { Text(appText("Reset"), color = FlickDesign.accent) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(appText("Cancel")) } },
    )
}

@Composable
private fun ResetScopeRow(title: String, hint: String, checked: Boolean, onToggle: () -> Unit) {
    val frame = Modifier.fillMaxWidth().height(58.dp).referenceClick(RoundedCornerShape(5.dp), action = onToggle)
    val description = appText(if (checked) "Selected" else "Unselected")
    Box(frame.padding(horizontal = 14.dp)) {
        Text(appText(title), Modifier.fillMaxWidth().padding(end = 35.dp).offset(y = 14.dp),
            color = FlickDesign.foreground, fontSize = 13.sp, lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-.222159).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(appText(hint), Modifier.fillMaxWidth().padding(end = 35.dp).offset(y = 31.dp),
            color = FlickDesign.neutral(Color(0xFFA3A3A3)), fontSize = 10.sp, lineHeight = 13.sp,
            fontWeight = FontWeight.Light, letterSpacing = (-.222159).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        SettingsCheck(checked, description, Modifier.align(Alignment.TopEnd).offset(y = 16.dp).size(25.dp))
    }
}

@Composable
private fun ResetAcceptButton(enabled: Boolean, onAccept: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp)
        .height(38.dp).background(Color(0xFFE70737), shape)
        .then(if (enabled) Modifier.referenceClick(shape, action = onAccept) else Modifier)
        .semantics {
            role = Role.Button
            if (!enabled) disabled()
        }, contentAlignment = Alignment.Center) {
        Text(appText("ACCEPT"), Modifier.padding(horizontal = 8.dp), color = Color.White, fontSize = 16.sp,
            lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.396923).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsRow(label: String, icon: DrawableResource, action: () -> Unit,
                        iconSize: Dp = 20.dp, iconHeight: Dp = iconSize,
                        shape: RoundedCornerShape = RoundedCornerShape(5.dp), control: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(42.dp).background(FlickDesign.settingsRow, shape).referenceClick(shape, action = action)) {
        Box(Modifier.align(Alignment.CenterStart).width(42.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            IconAsset(icon, null, Modifier.size(iconSize, iconHeight), tint = FlickDesign.foreground)
        }
        Label(label, Modifier.align(Alignment.CenterStart).padding(start = 46.dp, end = 66.dp), size = 13, weight = FontWeight.Medium)
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { control() }
    }
}

@Composable
private fun SettingsArrow(expanded: Boolean, mobile: Boolean) {
    val rotation by animateFloatAsState(if (expanded) -90f else 0f, label = "Settings arrow")
    Box(Modifier.size(25.dp), contentAlignment = Alignment.Center) {
        IconAsset(Res.drawable.settings_arrow, null, Modifier.offset(x = if (mobile) 2.dp else 1.dp, y = .5.dp)
            .size(5.dp, 8.dp).rotate(rotation), tint = FlickDesign.foreground)
    }
}

@Composable
internal fun SettingsCheck(checked: Boolean, description: String, modifier: Modifier) {
    val asset = when {
        checked -> Res.drawable.settings_check_active
        FlickDesign.isLight -> Res.drawable.settings_check_inactive_light
        else -> Res.drawable.settings_check_inactive
    }
    IconAsset(asset, description, modifier)
}
