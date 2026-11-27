package app.tvflick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import tv_flick.composeapp.generated.resources.*

@Composable
fun SourceActions(state: FlickState, key: PlaybackKey? = null, modifier: Modifier = Modifier,
                  onRequest: ((Boolean) -> Unit)? = null) {
    var expanded by remember(key) { mutableStateOf(false) }
    Box(modifier.size(24.dp)) {
        IconAsset(Res.drawable.param_select, appText("Video actions"), Modifier.fillMaxSize().referenceClick { expanded = true })
        if (expanded) Popup(alignment = Alignment.TopEnd, onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true)) {
            Column(Modifier.size(188.dp, 155.dp).clip(RoundedCornerShape(5.dp)).background(FlickDesign.neutral(Color(0xFF141414)))
                .padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val icons = listOf(Res.drawable.menu_play, Res.drawable.menu_download, Res.drawable.menu_play_mark)
                listOf("PLAY", "DOWNLOAD", "PLAY&MARK").forEachIndexed { index, label ->
                    Row(Modifier.size(158.dp, 35.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (index == 2 && state.playInsteadOfMark) FlickDesign.neutral(Color(0xFF323232)) else Color.Transparent)
                        .semantics { if (index == 2) stateDescription = if (state.playInsteadOfMark) "On" else "Off" }
                        .referenceClick(RoundedCornerShape(5.dp), selected = index == 2 && state.playInsteadOfMark) {
                            when (index) {
                                0, 1 -> if (onRequest != null) onRequest(index == 1) else key?.let { state.playback?.start(it, download = index == 1) }
                                2 -> state.togglePlayInsteadOfMark()
                            }
                            expanded = false
                        }, verticalAlignment = Alignment.CenterVertically) {
                        IconAsset(icons[index], null, Modifier.padding(start = 5.dp).size(25.dp))
                        val localized = appText(label)
                        val text = buildAnnotatedString {
                            val separator = localized.indexOf('&')
                            if (separator < 0) append(localized) else {
                                append(localized.substring(0, separator))
                                withStyle(SpanStyle(fontSize = 14.sp)) { append('&') }
                                append(localized.substring(separator + 1))
                            }
                        }
                        Text(text, Modifier.weight(1f).padding(start = 5.dp, end = 5.dp), color = FlickDesign.foreground,
                            fontSize = 20.sp, lineHeight = 20.sp, letterSpacing = (-.396923).sp,
                            fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun PlaybackMenus(state: FlickState) {
    val controller = state.playback ?: return
    controller.selection?.let { selected ->
        Popup(alignment = Alignment.Center, onDismissRequest = controller::dismissSelection,
            properties = PopupProperties(focusable = true)) {
            val choices = selected.discovery.choices
            PlaybackChoiceList(choices, onSelect = controller::choose) { choice ->
                val candidate = choice.candidate
                Column {
                    Label(choice.sourceName, size = 15, weight = FontWeight.SemiBold, translate = false)
                    Label(candidate.title + (candidate.releaseYear?.let { " ($it)" } ?: "") +
                        (candidate.seasonNumber?.let { " · S${it}E${candidate.episodeNumber}" } ?: ""),
                        size = 10, color = FlickDesign.muted, translate = false)
                }
            }
        }
    }
    controller.downloadSelection?.let { selected ->
        val choices = remember(selected) { DownloadChoices(selected.source, selected.preferredAudioId) }
        var audioId by remember(selected) { mutableStateOf<String?>(null) }
        val selectAudio = choices.audio.size > 1 && audioId == null
        Popup(alignment = Alignment.Center,
            onDismissRequest = { if (audioId != null && choices.audio.size > 1) audioId = null else controller.dismissDownload() },
            properties = PopupProperties(focusable = true)) {
            if (selectAudio) {
                PlaybackChoiceList(choices.audio, onSelect = { audioId = it.id }) { option ->
                    Label(option.label, size = 20, weight = FontWeight.SemiBold,
                        color = if (option.id == choices.preferredAudio) FlickDesign.accent else FlickDesign.foreground,
                        translate = false)
                }
            } else {
                val chosenAudio = audioId ?: choices.audio.singleOrNull()?.id.orEmpty()
                val options = choices.qualities(chosenAudio)
                PlaybackChoiceList(options, onSelect = { option ->
                    choices.resolve(chosenAudio, option.id)?.let(controller::chooseDownload)
                }) { option ->
                    Label(option.label, size = 20, weight = FontWeight.SemiBold, translate = false)
                }
            }
        }
    }
}

@Composable
internal fun <T> PlaybackChoiceList(options: List<T>, onSelect: (T) -> Unit, content: @Composable (T) -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    LazyColumn(Modifier.width(188.dp).height((options.size * 45 + 20).coerceAtMost(260).dp)
        .clip(shape).background(FlickDesign.neutral(Color(0xFF141414))),
        contentPadding = PaddingValues(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(options) { option ->
            Box(Modifier.fillMaxWidth().height(35.dp).referenceClick(shape) { onSelect(option) }.padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart) {
                content(option)
            }
        }
    }
}
