package app.tvflick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun BoxScope.BackupRestoreConfirmation(backup: BackupController) {
    val summary = backup.pendingRestore ?: return
    PlatformBackHandler(true) { backup.cancelRestore() }
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).panelClick())
    Column(Modifier.align(Alignment.Center).width(359.dp)
        .background(FlickDesign.neutral(Color.Black), RoundedCornerShape(5.dp)).panelClick().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label("Restore backup", size = 20, weight = FontWeight.SemiBold)
        Label("${summary.titles} titles · ${summary.episodes} episodes · ${summary.sources} sources",
            maxLines = 2)
        Label("This replaces the current library and sources. A recovery copy will be saved first.",
            color = FlickDesign.muted, maxLines = 4)
        backup.message?.let { Label(it, color = FlickDesign.muted, maxLines = 4) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("CANCEL", if (backup.applyingBackup) "RESTORING…" else "RESTORE").forEachIndexed { index, text ->
                Box(Modifier.weight(1f).height(37.dp)
                    .background(if (index == 0) FlickDesign.neutral(Color(0xFF2D2D2D)) else FlickDesign.accent, RoundedCornerShape(5.dp))
                    .then(if (backup.applyingBackup) Modifier else Modifier.referenceClick {
                        if (index == 0) backup.cancelRestore() else backup.confirmRestore()
                    }), contentAlignment = Alignment.Center) {
                    Label(text, size = 13, weight = FontWeight.SemiBold, color = if (index == 0) FlickDesign.foreground else Color.White)
                }
            }
        }
    }
}
