package app.tvflick.plugin.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tvflick.plugin.api.PlaybackOption
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.Font
import tv_flick.video_plugin.generated.resources.*

internal val PlayerAccent = Color(0xFFFF0238)
private val PlayerForeground = Color(0xFFEAEAEA)
private val LocalPlayerLight = staticCompositionLocalOf { false }
private val LocalPlayerText = staticCompositionLocalOf<(String) -> String> { { it } }

@Composable
fun PlayerAppearance(light: Boolean, translate: (String) -> String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPlayerLight provides light, LocalPlayerText provides translate, content = content)
}

private val pickerSurface @Composable get() = if (LocalPlayerLight.current) Color(0xFFC5C5C5) else Color(0xFF3A3A3A)
private val pickerText @Composable get() = if (LocalPlayerLight.current) Color(0xFF151515) else PlayerForeground
private enum class OpenPicker { Volume, Audio, Speed, Quality }
private val speedOptions = listOf("0.5", "1.0", "1.5", "2.0").map { PlaybackOption(it, "${it}x") }

/** Frame geometry from FlickPlayerAndroid (814 × 375), with edge anchors. */
@Composable
internal fun FullControls(
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    locked: Boolean,
    volume: Float,
    maxVolume: Float,
    qualityOptions: List<PlaybackOption>,
    audioTrackOptions: List<PlaybackOption>,
    selectedQualityId: String?,
    selectedAudioId: String?,
    selectedSpeedId: String,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onLock: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onQualitySelected: (PlaybackOption) -> Unit,
    onAudioTrackSelected: (PlaybackOption) -> Unit,
    title: String,
    episodes: List<PlayerEpisode>,
    currentEpisodeId: Long,
    onEpisodeSelected: (PlayerEpisode) -> Unit,
    autoWatchListEnabled: Boolean,
    onAutoWatchListChanged: (Boolean) -> Unit,
    showRotation: Boolean,
    onRotation: () -> Unit,
    onMini: () -> Unit,
    onFullscreen: () -> Unit,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    unlockVisible: Boolean = true,
    showVolumeControl: Boolean = true,
) {
    var openPicker by remember { mutableStateOf<OpenPicker?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (locked) {
            if (unlockVisible) PlayerIconButton(Res.drawable.player_lock, "Unlock controls",
                Modifier.align(Alignment.TopEnd).offset((-53).dp, 16.dp)) { onLock(false) }
            return@BoxWithConstraints
        }
        PlayerShadows()
        PlayerTitle(title, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 12.dp, end = 94.dp))
        PlayerIconButton(Res.drawable.player_lock, "Lock controls",
            Modifier.align(Alignment.TopEnd).offset((-53).dp, 16.dp)) { openPicker = null; onLock(true) }
        PlayerIconButton(Res.drawable.player_close, "Close player",
            Modifier.align(Alignment.TopEnd).offset((-16).dp, 16.dp), onClose)
        // Keep the edge-aligned groups intact when the phone is too narrow for a single row.
        val rightLift = if (maxWidth < 358.dp) 37.dp else 0.dp
        val centerLift = if (maxWidth < 500.dp) 37.dp + rightLift else 0.dp
        if (episodes.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.align(Alignment.BottomStart).offset(y = -65.dp - centerLift)
                    .width((episodes.size * 84.75f + (episodes.size - 1) * 16f + 24f).dp.coerceAtMost(maxWidth)).height(25.dp),
            ) {
                items(episodes, key = { it.contentId }) { episode ->
                    EpisodeChip(episode, episode.contentId == currentEpisodeId) { onEpisodeSelected(episode) }
                }
            }
        }
        PlaybackTimeline(position, duration, onSeek,
            Modifier.align(Alignment.BottomCenter).offset(y = -43.dp - centerLift).padding(horizontal = 10.dp).fillMaxWidth())

        Row(Modifier.align(Alignment.BottomStart).offset(16.dp, (-12).dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showVolumeControl) {
                PlayerIconButton(if (volume <= 0f) Res.drawable.player_volume_off else Res.drawable.player_volume_on, "Volume") {
                    openPicker = toggle(openPicker, OpenPicker.Volume)
                }
            }
            PlayerIconButton(Res.drawable.player_speed, "Playback speed") { openPicker = toggle(openPicker, OpenPicker.Speed) }
            PlayerIconButton(Res.drawable.player_download, "Download", onClick = onDownload)
            AutoWatchListToggle(autoWatchListEnabled) { onAutoWatchListChanged(!autoWatchListEnabled) }
        }
        Row(Modifier.align(Alignment.BottomCenter).offset(y = -12.dp - centerLift).height(26.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom) {
            PlayerIconButton(Res.drawable.player_previous, "Seek backward 10 seconds") { onSeek((position - PLAYER_SEEK_STEP_MS).coerceAtLeast(0)) }
            PlayerIconButton(if (isPlaying) Res.drawable.player_pause else Res.drawable.player_play, if (isPlaying) "Pause" else "Play", onClick = onPlayPause)
            PlayerIconButton(Res.drawable.player_next, "Seek forward 10 seconds") { onSeek((position + PLAYER_SEEK_STEP_MS).coerceAtMost(duration.coerceAtLeast(0))) }
        }
        Row(Modifier.align(Alignment.BottomEnd).offset((-16).dp, -12.dp - rightLift),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerIconButton(Res.drawable.player_audio, "Audio track") {
                if (audioTrackOptions.isNotEmpty()) openPicker = toggle(openPicker, OpenPicker.Audio)
            }
            PlayerIconButton(Res.drawable.player_quality, "Video quality") {
                if (qualityOptions.isNotEmpty()) openPicker = toggle(openPicker, OpenPicker.Quality)
            }
            PlayerIconButton(Res.drawable.player_mini, "Mini player", onClick = onMini)
            PlayerIconButton(Res.drawable.player_fullscreen, "Fullscreen") {
                if (showRotation) onRotation() else onFullscreen()
            }
        }
        // Popup edges are aligned to the centers of their actual button frames.
        val menuBottom = 37.dp + centerLift
        val menuMaxHeight = (maxHeight - menuBottom - 50.dp).coerceAtLeast(33.dp)
        when (openPicker) {
            OpenPicker.Volume -> VolumePicker(volume, maxVolume, onVolumeChange,
                Modifier.align(Alignment.BottomStart).offset(10.dp, -menuBottom))
            OpenPicker.Speed -> OptionPicker(speedOptions, selectedSpeedId, 60.dp, menuMaxHeight,
                { onSpeedChange(it.id.toFloat()); openPicker = null },
                Modifier.align(Alignment.BottomStart).offset(if (showVolumeControl) 35.5.dp else 10.dp, -menuBottom))
            OpenPicker.Audio -> OptionPicker(audioTrackOptions, selectedAudioId, 133.333.dp, menuMaxHeight,
                { onAudioTrackSelected(it); openPicker = null },
                Modifier.align(Alignment.BottomEnd).offset((-72.833).dp, -menuBottom))
            OpenPicker.Quality -> OptionPicker(qualityOptions, selectedQualityId, 60.dp, menuMaxHeight,
                { onQualitySelected(it); openPicker = null },
                Modifier.align(Alignment.BottomEnd).offset((-72.5).dp, -menuBottom))
            null -> Unit
        }
    }
}

@Composable
private fun BoxScope.PlayerShadows() {
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(96.dp)
        .background(Brush.verticalGradient(listOf(Color(0x40000000), Color.Transparent))))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp)
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x80000000)))))
}

@Composable
private fun PlayerTitle(title: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(27.dp), contentAlignment = Alignment.CenterStart) {
        PlayerText(title, 14, color = Color.White, inter = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun MiniControls(title: String, position: Long, duration: Long, onSeek: (Long) -> Unit, onFull: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        PlayerShadows()
        PlayerTitle(title, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 12.dp, end = 53.dp))
        PlayerIconButton(Res.drawable.player_fullscreen, "Return to full player",
            Modifier.align(Alignment.TopEnd).offset((-16).dp, 16.dp), onFull)
        PlaybackTimeline(position, duration, onSeek,
            Modifier.align(Alignment.BottomCenter).offset(y = (-14).dp).padding(horizontal = 10.dp).fillMaxWidth())
    }
}

@Composable
private fun EpisodeChip(episode: PlayerEpisode, current: Boolean, onClick: () -> Unit) {
    val background = when { current -> PlayerAccent; episode.isWatched -> Color(0x8CFFE626); else -> pickerSurface }
    val foreground = when { current -> PlayerForeground; episode.isWatched -> Color(0xFF252525); else -> pickerText }
    Box(Modifier.size(84.75.dp, 25.dp).clip(RoundedCornerShape(100.dp)).background(background).plainClick(onClick),
        contentAlignment = Alignment.Center) {
        PlayerText(episode.label, 10, color = foreground,
            modifier = Modifier.padding(horizontal = 3.dp).fillMaxWidth(), align = TextAlign.Center)
    }
}

@Composable
private fun AutoWatchListToggle(enabled: Boolean, onToggle: () -> Unit) {
    val regular = FontFamily(Font(Res.font.player_inter_regular))
    val bold = FontFamily(Font(Res.font.player_inter_semibold, FontWeight.SemiBold))
    val translate = LocalPlayerText.current
    Row(Modifier.size(79.dp, 25.dp).plainClick(onToggle), verticalAlignment = Alignment.CenterVertically) {
        Text(buildAnnotatedString {
            append(translate("Auto"))
            append(' ')
            withStyle(SpanStyle(fontFamily = bold, fontWeight = FontWeight.SemiBold)) { append(translate("MARK")) }
        }, Modifier.width(54.dp), color = Color.White.copy(alpha = .75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = regular, fontSize = 10.sp, lineHeight = 12.1.sp, letterSpacing = (-.52226716).sp))
        Image(playerAssetPainter(if (enabled) Res.drawable.player_auto_watchlist_on else Res.drawable.player_auto_watchlist_off),
            translate(if (enabled) "Auto MARK enabled" else "Auto MARK disabled"), Modifier.size(25.dp))
    }
}

@Composable
private fun OptionPicker(options: List<PlaybackOption>, selected: String?, width: Dp, maxHeight: Dp,
                         onSelected: (PlaybackOption) -> Unit, modifier: Modifier) {
    val panelHeight = (options.size * 24 + 9).dp.coerceAtMost(maxHeight)
    LazyColumn(modifier.width(width).height(panelHeight).clip(RoundedCornerShape(5.dp)).background(pickerSurface),
        contentPadding = PaddingValues(vertical = 4.5.dp)) {
        items(options, key = { it.id }) { option ->
            Box(Modifier.fillMaxWidth().height(24.dp).plainClick { onSelected(option) }, contentAlignment = Alignment.Center) {
                PlayerText(option.label, 10, if (option.id == selected) PlayerAccent else pickerText,
                    modifier = Modifier.padding(horizontal = if (width > 60.dp) 16.667.dp else 0.dp).fillMaxWidth(),
                    align = if (width > 60.dp) TextAlign.Start else TextAlign.Center)
            }
        }
    }
}

/** Reuses the source-approved Audio/Quality menu styling for source candidates. */
@Composable
fun PlayerSourcePicker(options: List<PlaybackOption>, onSelected: (PlaybackOption) -> Unit, selected: String? = null) {
    BoxWithConstraints(Modifier.widthIn(max = 360.dp)) {
        OptionPicker(options, selected, maxWidth.coerceAtMost(360.dp), 249.dp, onSelected, Modifier)
    }
}

@Composable
private fun VolumePicker(volume: Float, maxVolume: Float, onVolumeChange: (Float) -> Unit, modifier: Modifier) {
    val changeVolume by rememberUpdatedState(onVolumeChange)
    val emptyTrack = if (LocalPlayerLight.current) Color(0xFF979797) else Color(0xFF686868)
    Box(modifier.size(60.dp, 105.dp).clip(RoundedCornerShape(5.dp)).background(pickerSurface)
        .pointerInput(maxVolume) {
            detectTapGestures { point -> changeVolume(((size.height - point.y - 10.dp.toPx()) / 85.dp.toPx()).coerceIn(0f, 1f) * maxVolume) }
        }.pointerInput(maxVolume) {
            fun update(y: Float) = changeVolume(((size.height - y - 10.dp.toPx()) / 85.dp.toPx()).coerceIn(0f, 1f) * maxVolume)
            detectDragGestures(onDragStart = { update(it.y) }) { change, _ -> change.consume(); update(change.position.y) }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val fraction = (volume / maxVolume.coerceAtLeast(.01f)).coerceIn(0f, 1f)
            val top = 10.dp.toPx(); val length = 85.dp.toPx(); val marker = top + (1f - fraction) * length
            drawRect(emptyTrack, Offset(26.667.dp.toPx(), top), Size(6.667.dp.toPx(), length))
            if (fraction > 0f) drawRoundRect(PlayerAccent, Offset(26.667.dp.toPx(), marker),
                Size(6.667.dp.toPx(), length * fraction), CornerRadius(1.dp.toPx()))
            drawRoundRect(PlayerAccent, Offset(20.dp.toPx(), marker.coerceAtMost(88.333.dp.toPx())),
                Size(20.dp.toPx(), 6.667.dp.toPx()), CornerRadius(1.dp.toPx()))
        }
    }
}

@Composable
private fun PlaybackTimeline(position: Long, duration: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    Row(modifier.height(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        PlayerText(formatTime(position), 8, modifier = Modifier.width(30.dp), align = TextAlign.Center)
        SeekBar(position, duration, onSeek, Modifier.weight(1f).height(13.dp))
        PlayerText(formatTime((duration - position).coerceAtLeast(0)), 8, modifier = Modifier.width(30.dp), align = TextAlign.Center)
    }
}

@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    val seek by rememberUpdatedState(onSeek)
    Canvas(modifier.pointerInput(duration) {
        detectTapGestures { if (duration > 0 && size.width > 0) seek((it.x / size.width * duration).toLong().coerceIn(0, duration)) }
    }.pointerInput(duration) {
        fun update(x: Float) { if (duration > 0 && size.width > 0) seek((x / size.width * duration).toLong().coerceIn(0, duration)) }
        detectDragGestures(onDragStart = { update(it.x) }) { change, _ -> change.consume(); update(change.position.x) }
    }) {
        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val played = size.width * fraction
        val trackHeight = 3.dp.toPx()
        val trackStart = Offset(0f, 5.dp.toPx())
        val trackCorner = CornerRadius(trackHeight / 2)
        drawRoundRect(Color(0x33EAEAEA), trackStart, Size(size.width, trackHeight), trackCorner)
        if (played > 0f) drawRoundRect(PlayerAccent, trackStart, Size(played, trackHeight), trackCorner)
        val radius = 6.5.dp.toPx().coerceAtMost(size.width / 2)
        val center = (played - radius).coerceIn(radius, (size.width - radius).coerceAtLeast(radius))
        drawCircle(PlayerAccent, radius, Offset(center, size.height / 2))
    }
}

@Composable
private fun PlayerIconButton(resource: DrawableResource, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val height = if (resource == Res.drawable.player_pause) 26.dp else 25.dp
    Box(modifier.size(25.dp, height).background(if (pressed) Color(0x8CAFAFAF) else Color.Transparent)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        // SVG viewBox is the entire exported Lunacy frame, including transparent insets.
        Image(playerAssetPainter(resource), LocalPlayerText.current(description), Modifier.fillMaxSize())
    }
}

@Composable
internal fun SeekFeedback(label: String, modifier: Modifier) {
    val backward = label.startsWith("−")
    val colors = listOf(Color(0xFF848484), Color(0x00FFFFFF)).let { if (backward) it else it.reversed() }
    Box(modifier.fillMaxWidth(.5f).fillMaxHeight().background(Brush.horizontalGradient(colors)), contentAlignment = Alignment.Center) {
        PlayerText(label, 20, Color.White, inter = true, letterSpacing = .2f)
    }
}

@Composable
private fun PlayerText(text: String, size: Int, color: Color = PlayerForeground, inter: Boolean = false,
                       modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start, letterSpacing: Float = size * .01f) {
    val family = FontFamily(Font(if (inter) Res.font.player_inter_regular else Res.font.player_roboto_flex))
    Text(text, modifier, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align,
        style = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = size.sp,
            lineHeight = (size * 1.2102273f).sp, letterSpacing = letterSpacing.sp))
}

@Composable
private fun Modifier.plainClick(onClick: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

private fun toggle(current: OpenPicker?, requested: OpenPicker): OpenPicker? = requested.takeUnless { it == current }
private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}
