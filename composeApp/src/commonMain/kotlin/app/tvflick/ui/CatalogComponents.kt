package app.tvflick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import app.tvflick.core.library.LibraryContentDraft
import org.jetbrains.compose.resources.DrawableResource
import tv_flick.composeapp.generated.resources.*

enum class CardKind { NotStarted, InProgress, Upcoming, Search }

@Composable
fun CatalogCard(
    content: LibraryContentDraft, detail: String, kind: CardKind,
    onOpen: () -> Unit, onAction: () -> Unit = {}, modifier: Modifier = Modifier,
    added: Boolean = false, daysRemaining: Long? = null,
    remainingEpisodes: Int = 0,
    playAction: Boolean = false,
    sourceActions: (@Composable (Modifier) -> Unit)? = null,
) {
    BoxWithConstraints(modifier.height(118.dp).clip(RoundedCornerShape(5.dp))
        .background(FlickDesign.neutral(Color(0xFF121212))).referenceClick(onOpen)) {
        val width = maxWidth
        content.backdropPath?.let {
            LibraryArtwork("https://image.tmdb.org/t/p/w780$it", null,
                modifier = Modifier.offset(80.dp, (-34).dp)
                    .wrapContentSize(Alignment.TopStart, unbounded = true).size(width - 80.dp, 186.dp))
        }
        Box(Modifier.offset(x = 80.dp).width(width - 80.dp).height(118.dp)
            .background(Brush.verticalGradient(listOf(FlickDesign.neutral(Color(0x40121212)), FlickDesign.neutral(Color(0xFF121212))))))
        content.posterPath?.let {
            LibraryArtwork("https://image.tmdb.org/t/p/w342$it", content.title,
                modifier = Modifier.size(80.dp, 118.dp))
        }
        Label(content.title, Modifier.offset(96.dp, 63.dp).width((width - 205.dp).coerceAtLeast(30.dp)), 20, FontWeight.SemiBold, translate = false)
        Row(Modifier.offset(96.dp, 86.dp).width((width - 194.dp).coerceAtLeast(30.dp))) {
            Label(detail, Modifier.weight(1f, fill = false), 13, FontWeight.Light, FlickDesign.muted)
            if (remainingEpisodes > 0) Label(" | +$remainingEpisodes", size = 13, weight = FontWeight.Light, color = FlickDesign.muted)
        }
        if (kind == CardKind.Search || kind == CardKind.InProgress || (kind == CardKind.NotStarted && (sourceActions != null || playAction))) {
            if (kind == CardKind.Search) {
                IconAsset(if (added) Res.drawable.card_added_button else Res.drawable.card_add_button,
                    appText("Add to watch list"), Modifier.align(Alignment.CenterEnd).offset(x = (-39).dp).size(40.dp)
                        .referenceClick(RectangleShape, action = onAction))
            } else PlayOrMarkButton(playAction, Res.drawable.card_mark_button,
                if (playAction) "Play" else "Mark watched",
                Modifier.align(Alignment.CenterEnd).offset(x = (-35).dp).size(42.dp), onAction)
        }
        sourceActions?.invoke(Modifier.align(Alignment.CenterEnd).offset(x = (-8).dp))
        if (kind == CardKind.Upcoming && daysRemaining != null) {
            Box(Modifier.align(Alignment.CenterEnd).offset(x = (-41).dp).size(36.dp)) {
                Label("$daysRemaining\n${appText("DAYS")}", Modifier.align(Alignment.Center).fillMaxWidth(), 13,
                    align = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
            }
        }
    }
}

@Composable
internal fun PlayOrMarkButton(play: Boolean, markAsset: DrawableResource, description: String,
                              modifier: Modifier, onClick: () -> Unit) {
    if (!play) {
        IconAsset(markAsset, appText(description), modifier.referenceClick(CircleShape, action = onClick))
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val touchPulse = touchPressPulse(interaction)
    IconAsset(if (hovered || pressed || touchPulse) Res.drawable.card_play_button else Res.drawable.card_play_neutral,
        appText(description), modifier.clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick))
}

@Composable
fun EmptyPosterTile() {
    Box(Modifier.size(110.512.dp, 163.722.dp).clip(RoundedCornerShape(5.dp))
        .background(FlickDesign.neutral(Color(0xFF323232)).copy(alpha = .2f)))
}

@Composable
fun PosterTile(content: LibraryContentDraft, desktop: Boolean, onOpen: (() -> Unit)? = null) {
    val width = if (desktop) 129.683.dp else 110.512.dp
    val posterHeight = if (desktop) 192.123.dp else 163.722.dp
    val contentHeight = posterHeight + if (desktop) 33.6.dp else 27.69.dp
    Box(Modifier.width(width).height(if (desktop) 230.55.dp else 196.47.dp)) {
    Box(Modifier.fillMaxWidth().height(contentHeight)
        .then(if (onOpen != null) Modifier.referenceClick(onOpen) else Modifier)) {
        Box(Modifier.width(width).height(posterHeight).clip(RoundedCornerShape(5.dp)).background(FlickDesign.neutral(Color(0xFF121212)))) {
            content.posterPath?.let {
                LibraryArtwork("https://image.tmdb.org/t/p/w342$it", content.title, Modifier.fillMaxSize())
            }
        }
        Label(content.title, Modifier.offset(y = posterHeight).fillMaxWidth(), if (desktop) 15 else 13, FontWeight.Light, translate = false)
        Label(content.releaseYear?.toString().orEmpty(),
            Modifier.offset(y = posterHeight + if (desktop) 16.65.dp else 14.19.dp).fillMaxWidth(),
            if (desktop) 13 else 10, FontWeight.Light)
    }
    }
}

