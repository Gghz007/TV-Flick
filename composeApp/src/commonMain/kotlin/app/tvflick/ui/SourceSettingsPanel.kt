package app.tvflick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv_flick.composeapp.generated.resources.*

@Composable
internal fun SourceSettingsPanel(state: FlickState, manager: Boolean, list: LazyListState, sidePadding: Dp = 8.dp) {
    LaunchedEffect(state.sourceSites.isEmpty()) {
        if (state.sourceSites.isEmpty()) list.requestScrollToItem(0)
    }
    Column(Modifier.fillMaxSize().padding(start = sidePadding, end = sidePadding, top = 10.dp).clipToBounds()) {
            Box(Modifier.fillMaxWidth().height(if (manager) 40.dp else 82.dp)) {
                Label(if (manager) "Adblock" else "ADD", Modifier.offset(2.dp, 20.dp),
                    if (manager) 20 else 10,
                    if (manager) FontWeight.SemiBold else FontWeight.Normal,
                    if (manager) FlickDesign.foreground else FlickDesign.muted)
                if (!manager) SourceInput(state, Modifier.offset(y = 39.dp))
            }
        Spacer(Modifier.height(8.dp))
        state.sourceError?.let { message ->
            Label(message, Modifier.fillMaxWidth(), 12, color = FlickDesign.muted, maxLines = 2)
            Spacer(Modifier.height(8.dp))
        }
        if (!manager) {
            Box(Modifier.fillMaxWidth().height(39.dp)) {
                Label("DATABASE", Modifier.offset(2.dp, 20.dp), 10, color = FlickDesign.muted)
            }
        }
        if (!manager && state.sourceSites.isEmpty()) {
            // Decorative empty-state artwork: no scroll state or scrolling input at all.
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                repeat(12) { index ->
                    Box(Modifier.offset(y = (index * 51).dp).fillMaxWidth().requiredHeight(43.dp)
                        .background(FlickDesign.neutral(Color(0xFF1A1A1A)), RoundedCornerShape(5.dp)))
                }
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
          items(state.sourceSites, key = { it.id }) { site ->
            Box(Modifier.fillMaxWidth().height(43.dp).background(FlickDesign.neutral(Color(0xFF151515)), RoundedCornerShape(5.dp))) {
                IconAsset(Res.drawable.search_icon, null, Modifier.offset(17.dp, 13.dp).size(18.dp), tint = FlickDesign.foreground)
                Label(site.displayName, Modifier.fillMaxWidth().padding(start = 48.dp, end = if (manager) 76.dp else 83.dp).offset(y = 13.dp),
                    13, FontWeight.Light, FlickDesign.muted, translate = false)
                if (manager) {
                    val enabled = state.sourceAdblockSettings[site.id] ?: true
                    IconAsset(if (enabled) Res.drawable.source_toggle_on else Res.drawable.source_toggle_off,
                        if (enabled) "Disable Adblock for ${site.displayName}" else "Enable Adblock for ${site.displayName}",
                        Modifier.align(Alignment.TopEnd).padding(end = 14.dp).offset(y = 9.dp).size(49.dp, 25.dp)
                            .referenceClick(RoundedCornerShape(100.dp)) { state.toggleSourceAdblock(site) })
                } else {
                    Box(Modifier.align(Alignment.TopEnd).padding(end = 46.dp).offset(y = 9.dp).size(25.dp)
                        .referenceClick(RoundedCornerShape(2.dp)) { state.removeSource(site) },
                        contentAlignment = Alignment.Center) {
                        IconAsset(Res.drawable.properties_remove, "Delete ${site.displayName}",
                            Modifier.size(9.75.dp, 10.5625.dp))
                    }
                    Box(Modifier.align(Alignment.TopEnd).padding(end = 14.dp).offset(y = 9.dp).size(25.dp)
                        .referenceClick(RoundedCornerShape(2.dp)) { state.toggleSource(site) }) {
                        SettingsCheck(site.isEnabled,
                            "${appText(if (site.isEnabled) "Disable" else "Enable")} ${site.displayName}", Modifier.fillMaxSize())
                    }
                }
            }
          }
        }
    }
}

@Composable
private fun SourceInput(state: FlickState, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().height(43.dp).background(FlickDesign.neutral(Color.Black), RoundedCornerShape(5.dp))) {
        BasicTextField(state.sourceInput, { state.sourceInput = it; state.sourceError = null },
            modifier = Modifier.fillMaxWidth().padding(end = 47.dp).fillMaxHeight().onFocusChanged { focused = it.isFocused },
            enabled = !state.sourceBusy, singleLine = true, cursorBrush = SolidColor(FlickDesign.foreground),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.Light, color = FlickDesign.foreground),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { state.addSource() }),
            decorationBox = { field ->
                Box(Modifier.fillMaxSize()) {
                    IconAsset(Res.drawable.search_icon, null, Modifier.offset(17.dp, 13.dp).size(18.dp), tint = FlickDesign.foreground)
                    Box(Modifier.fillMaxWidth().padding(start = 48.dp, end = 5.dp).offset(y = 13.dp).height(18.dp)) {
                        if (state.sourceInput.isEmpty() && !focused) Label("Add Link", weight = FontWeight.Light, color = FlickDesign.muted)
                        field()
                    }
                }
            })
        IconAsset(Res.drawable.database_add, "Add source", Modifier.align(Alignment.TopEnd).padding(end = 13.5.dp)
            .offset(y = 9.dp).size(25.dp)
            .referenceClick(RoundedCornerShape(2.dp)) { state.addSource() })
    }
}
