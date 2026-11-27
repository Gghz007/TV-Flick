package app.tvflick.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import tv_flick.composeapp.generated.resources.*

fun Modifier.referenceClick(action: () -> Unit): Modifier =
    referenceClick(RoundedCornerShape(5.dp), action = action)

fun Modifier.referenceClick(shape: Shape, selected: Boolean = false, enabled: Boolean = true, action: () -> Unit): Modifier = composed {
    val interactions = remember { MutableInteractionSource() }
    clickable(interactionSource = interactions, indication = null, enabled = enabled, onClick = action)
        .interactionHighlight(interactions, shape, enabled = enabled && !selected)
}

/** Row hover feedback does not introduce a click action or consume its child controls. */
fun Modifier.referenceHover(shape: Shape = RoundedCornerShape(5.dp)): Modifier = composed {
    val interactions = remember { MutableInteractionSource() }
    hoverable(interactions).interactionHighlight(interactions, shape)
}

internal fun Modifier.interactionHighlight(interactions: MutableInteractionSource, shape: Shape,
                                           enabled: Boolean = true): Modifier = composed {
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val touchFeedback = LocalTouchFeedback.current
    val touchPulse = touchPressPulse(interactions, enabled)
    val opacity = when {
        !enabled -> 0f
        touchPulse -> .055f
        !touchFeedback && pressed -> .09f
        hovered -> .055f
        else -> 0f
    }
    val animation = if (touchPulse) snap<Float>() else tween(120)
    val highlight by animateFloatAsState(opacity, animation, label = "Click highlight")
    val highlightColor = FlickDesign.foreground
    drawWithContent {
        drawContent()
        if (enabled && highlight > 0f) drawOutline(shape.createOutline(size, layoutDirection, this),
            highlightColor.copy(alpha = highlight))
    }
}

@Composable
internal fun touchPressPulse(interactions: MutableInteractionSource, enabled: Boolean = true): Boolean {
    val touchFeedback = LocalTouchFeedback.current
    var touchPulse by remember(interactions, enabled, touchFeedback) { mutableStateOf(false) }
    LaunchedEffect(interactions, enabled, touchFeedback) {
        if (!enabled || !touchFeedback) return@LaunchedEffect
        interactions.interactions.filterIsInstance<PressInteraction>().collectLatest { input ->
            when (input) {
                is PressInteraction.Press -> touchPulse = true
                is PressInteraction.Release -> {
                    touchPulse = true
                    delay(90)
                    touchPulse = false
                }
                is PressInteraction.Cancel -> touchPulse = false
                else -> Unit
            }
        }
    }
    return touchPulse
}

/** Modal surfaces consume clicks without highlighting the entire panel or dimmed backdrop. */
fun Modifier.panelClick(action: () -> Unit = {}): Modifier =
    clickable(interactionSource = null, indication = null, onClick = action)

@Composable
fun ScaledReference(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale), content = content)
}

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 13,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Color.Unspecified,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
    translate: Boolean = true,
) {
    Text(if (translate) appText(text) else text, modifier,
        color = if (color == Color.Unspecified) FlickDesign.foreground else color, fontSize = size.sp, fontWeight = weight,
        lineHeight = (size * 1.15f).sp, maxLines = maxLines, textAlign = align,
        overflow = TextOverflow.Ellipsis)
}

@Composable
fun IconAsset(asset: DrawableResource, description: String?, modifier: Modifier, tint: Color? = null) =
    Image(designAssetPainter(asset), description, modifier, colorFilter = tint?.let(ColorFilter::tint))

/** The complete 26×26 reference frame scales through the panel's LocalDensity. */
@Composable
fun SettingsButton(description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(26.dp).referenceClick(RoundedCornerShape(2.dp), action = onClick)) {
        IconAsset(Res.drawable.profile_menu, appText(description), Modifier.fillMaxSize(),
            tint = if (FlickDesign.isLight) FlickDesign.foreground else null)
    }
}

@Composable
fun BottomNavigation(selected: Destination, mobile: Boolean, onSelect: (Destination) -> Unit, stretch: Boolean = false) {
    BoxWithConstraints(Modifier.then(if (stretch) Modifier.fillMaxWidth().height(60.dp) else Modifier.size(358.dp, 60.dp)).background(
        FlickDesign.neutral(Color(if (mobile) 0xBF141414 else 0xD9141414)), RoundedCornerShape(100.dp))) {
        val buttonWidth = if (stretch) (maxWidth - 22.dp) / Destination.entries.size else 84.dp
        Destination.entries.forEachIndexed { index, destination ->
            val active = selected == destination
            val x = 8.dp + (buttonWidth + 2.dp) * index
            val top = if (destination == Destination.Profile) 5.dp else 6.dp
            Box(Modifier.offset(x, top).size(buttonWidth, 48.dp)
                .background(if (active) FlickDesign.neutral(Color(if (mobile) 0xBF323232 else 0xD9323232)) else Color.Transparent, RoundedCornerShape(100.dp))
                .referenceClick(RoundedCornerShape(100.dp), selected = active) { onSelect(destination) }) {
                val asset = when (destination) {
                    Destination.Shows -> Res.drawable.nav_shows
                    Destination.Movies -> Res.drawable.nav_movies
                    Destination.Search -> Res.drawable.nav_search
                    Destination.Profile -> Res.drawable.nav_profile
                }
                // SVG bounds include strokes outside the vector's nominal frame.
                // Use each reference's offset and rectangular size, not a shared square.
                val iconX = when (destination) {
                    Destination.Shows -> 31.dp
                    Destination.Movies -> 31.57.dp
                    Destination.Search -> 32.5.dp
                    Destination.Profile -> 33.dp
                }
                val iconY = when (destination) {
                    Destination.Shows -> 8.dp
                    Destination.Profile -> 11.dp
                    else -> 9.dp
                }
                val iconWidth = when (destination) {
                    Destination.Shows -> 22.dp
                    Destination.Movies -> 20.43.dp
                    Destination.Search -> 19.dp
                    Destination.Profile -> 18.dp
                }
                val iconHeight = if (destination == Destination.Movies) 19.82.dp else iconWidth
                IconAsset(asset, appText(destination.label), Modifier.offset(iconX + (buttonWidth - 84.dp) / 2, iconY).size(iconWidth, iconHeight),
                    if (active) FlickDesign.foreground else FlickDesign.muted)
                val textTop = when (destination) {
                    Destination.Movies -> 29.82.dp
                    Destination.Profile -> 31.dp
                    else -> 30.dp
                }
                Label(destination.label, Modifier.offset(y = textTop).fillMaxWidth(), 8, FontWeight.Light,
                    if (active) FlickDesign.foreground else FlickDesign.muted, TextAlign.Center)
            }
        }
    }
}

@Composable
fun LibraryTabs(selected: LibraryTab, mobile: Boolean, stretch: Boolean = false, onSelect: (LibraryTab) -> Unit) {
    val height = if (mobile) 74.dp else 26.dp
    Row(Modifier.then(if (stretch) Modifier.fillMaxWidth() else Modifier.width(if (mobile) 375.dp else 340.dp)).height(height)
        .background(if (mobile) FlickDesign.panel else Color.Transparent)) {
        LibraryTab.entries.forEachIndexed { index, tab ->
            val active = tab == selected
            val width = if (mobile) (if (index == 0) 188.dp else 187.dp) else (if (index == 0) 188.dp else 152.dp)
            Box(Modifier.then(if (stretch) Modifier.weight(1f) else Modifier.width(width)).height(height)
                .background(if (active && !mobile) FlickDesign.neutral(Color(0xFF1C1C1C)) else Color.Transparent, RoundedCornerShape(100.dp))
                .then(if (LocalTouchFeedback.current) Modifier.panelClick { onSelect(tab) }
                    else Modifier.referenceClick(RoundedCornerShape(if (mobile) 0.dp else 100.dp), selected = active) { onSelect(tab) })) {
                Label(tab.label, Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = if (mobile) 52.dp else 5.dp),
                    13, FontWeight.SemiBold, if (active) FlickDesign.foreground else FlickDesign.muted, TextAlign.Center)
                if (active && mobile) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(FlickDesign.foreground))
            }
        }
    }
}

@Composable
fun SectionPill(text: String, modifier: Modifier = Modifier) {
    val width = if (text.length > 20) 180.dp else 78.dp
    Box(modifier.size(width, 17.dp).background(FlickDesign.neutral(Color(0xFF3F3F3F)), RoundedCornerShape(100.dp)), contentAlignment = Alignment.Center) {
        Label(text, Modifier.fillMaxWidth().padding(horizontal = 4.dp), 10, align = TextAlign.Center)
    }
}

@Composable
fun BottomFade(
    modifier: Modifier = Modifier,
    mobile: Boolean,
    androidPage: Boolean = false,
    height: Dp = if (androidPage) 56.dp else bottomOverlayHeight(mobile),
    opaqueFrom: Float = 1f,
) {
    val background = FlickDesign.background
    val transparent = background.copy(alpha = 0f)
    val brush = if (androidPage) Brush.verticalGradient(0f to transparent, opaqueFrom to background, 1f to background)
        else Brush.verticalGradient(0f to Color.Transparent, .3f to Color.Transparent, 1f to background)
    Box(modifier.fillMaxWidth().height(height).background(brush))
}

fun bottomOverlayHeight(mobile: Boolean): Dp = if (mobile) 201.dp else 225.dp

