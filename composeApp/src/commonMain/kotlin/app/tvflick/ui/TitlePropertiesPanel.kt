package app.tvflick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tvflick.core.library.LocalLibraryItem
import tv_flick.composeapp.generated.resources.*

internal enum class TitleAction(val label: String) {
    Favorite("Favorite"), WatchLater("Watch later"), StopWatching("Stop watching"),
    Remove("Remove"), ResetView("Reset view"),
}

/** PropertiesShowORMoviesContainer: all five rows keep their reference positions. */
@Composable
internal fun TitlePropertiesPanel(item: LocalLibraryItem?, hasProgress: Boolean, busy: Boolean, stretch: Boolean = false,
                                  onAction: (TitleAction) -> Unit) {
    val panelWidth = if (stretch) Modifier.fillMaxWidth() else Modifier.width(375.dp)
    Box(panelWidth.verticalScroll(rememberScrollState()).height(229.dp)
        .background(FlickDesign.neutral(Color(0xFF161616))).panelClick()) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(FlickDesign.accent))
        Label("Properties", Modifier.offset(9.dp, 26.dp), 16, FontWeight.SemiBold)
        TitleAction.entries.forEachIndexed { index, action ->
            val available = !busy && when (action) {
                TitleAction.Remove -> item != null
                TitleAction.ResetView -> hasProgress
                TitleAction.StopWatching -> item != null && (item.isInWatchList || hasProgress)
                else -> true
            }
            val checked = when (action) {
                TitleAction.Favorite -> item?.isFavorite == true
                TitleAction.WatchLater -> item?.isInWatchList == true && !hasProgress
                TitleAction.StopWatching -> item?.isInWatchList == false && hasProgress
                else -> false
            }
            val rowWidth = if (stretch) Modifier.fillMaxWidth().padding(horizontal = 9.dp) else Modifier.offset(x = 9.dp).width(357.125.dp)
            Box(rowWidth.offset(y = (49 + index * 31).dp).height(31.dp)
                .then(if (available) Modifier.referenceClick(RectangleShape) { onAction(action) } else Modifier)
                .alpha(if (available || busy) 1f else .55f)) {
                // Keep the icon's original bounds, including the remove icon's stroke overflow.
                when (action) {
                    TitleAction.Favorite -> IconAsset(Res.drawable.properties_favorite, null,
                        Modifier.offset(.677734.dp, 10.372559.dp).size(11.645834.dp, 10.137683.dp))
                    TitleAction.WatchLater -> IconAsset(Res.drawable.properties_watch_later, null,
                        Modifier.offset(2.301758.dp, 9.676758.dp).size(8.395833.dp, 11.645833.dp))
                    TitleAction.StopWatching -> IconAsset(Res.drawable.properties_stop, null,
                        Modifier.offset(1.166992.dp, 9.166504.dp).size(11.666667.dp))
                    TitleAction.Remove -> IconAsset(Res.drawable.properties_remove, null,
                        Modifier.offset(1.625.dp, 9.8125.dp).size(9.75.dp, 10.5625.dp))
                    TitleAction.ResetView -> IconAsset(Res.drawable.properties_reset, null,
                        Modifier.offset(.875.dp, 9.750366.dp).size(11.250009.dp, 12.50001.dp))
                }
                Label(action.label, Modifier.fillMaxWidth()
                    .padding(start = if (action == TitleAction.ResetView) 16.953125.dp else 16.dp, top = 8.dp,
                        end = if (index < 3) 24.dp else 4.dp), 12)
                if (index < 3) ReferenceSelectionMark(checked,
                    if (stretch) Modifier.align(Alignment.TopEnd).offset(y = 7.dp) else Modifier.offset(341.dp, 7.dp))
                Box(Modifier.offset(y = 30.5.dp).fillMaxWidth().height(.5.dp).background(FlickDesign.neutral(Color(0x40FFFFFF))))
            }
        }
    }
}

@Composable
internal fun ReferenceSelectionMark(checked: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(18.dp)) {
        IconAsset(if (checked) Res.drawable.filter_checked else Res.drawable.filter_unchecked, null,
            if (checked) Modifier.offset(1.875.dp, 1.875.dp).size(14.25.dp) else Modifier.fillMaxSize())
    }
}
