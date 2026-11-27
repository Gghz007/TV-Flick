package app.tvflick.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import tv_flick.composeapp.generated.resources.*

private const val SHOW_BACK_BUTTON = false
internal val DesktopScrolledHeaderHeight = 38.dp

@Composable
internal fun LazyListState.hasScrolled(): Boolean = remember(this) {
    derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0 }
}.value

@Composable
fun BoxScope.ScrollingPanelControls(
    list: LazyListState,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    pinned: Boolean = false,
    headerOnScroll: Boolean = false,
) {
    if (headerOnScroll && list.hasScrolled()) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(DesktopScrolledHeaderHeight)
            .background(FlickDesign.panel).panelClick())
    }
    onBack?.takeIf { SHOW_BACK_BUTTON && (pinned || list.firstVisibleItemIndex == 0) }?.let {
        val scrollY = with(LocalDensity.current) { list.firstVisibleItemScrollOffset.toDp() }
        IconAsset(if (FlickDesign.isLight) Res.drawable.profile_back_light else Res.drawable.profile_back, appText("Back"),
            Modifier.offset(20.dp, if (pinned) 20.dp else 20.dp - scrollY).size(36.dp)
                .referenceClick(CircleShape, action = it))
    }
    onClose?.let {
        Box(Modifier.align(Alignment.TopEnd).size(36.dp, DesktopScrolledHeaderHeight)
            .referenceClick(androidx.compose.ui.graphics.RectangleShape, action = it), contentAlignment = Alignment.Center) {
            IconAsset(Res.drawable.window_close, appText("Close"), Modifier.size(11.77.dp),
                tint = if (FlickDesign.isLight) FlickDesign.foreground else null)
        }
    }
}
