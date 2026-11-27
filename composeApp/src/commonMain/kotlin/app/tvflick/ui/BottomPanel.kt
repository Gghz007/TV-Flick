package app.tvflick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun BoxScope.DismissibleBottomPanel(
    androidPage: Boolean,
    mobile: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (androidPage) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.matchParentSize().panelClick(onDismiss))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                    content()
                }
            }
        }
    } else {
        Box(Modifier.matchParentSize().background(Color(0x55101010)).panelClick(onDismiss))
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = if (mobile) 0.dp else 5.dp)) {
            content()
        }
    }
}
