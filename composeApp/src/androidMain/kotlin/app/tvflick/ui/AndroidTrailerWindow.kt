package app.tvflick.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.tvflick.core.tmdb.YoutubeTrailer

@Composable
internal actual fun PlatformTrailerWindow(trailer: YoutubeTrailer, notices: UserNotices, onDismiss: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(trailer) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trailer.watchUrl))
        try {
            try {
                context.startActivity(Intent(intent).setPackage("com.google.android.youtube"))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(intent)
            }
        } catch (_: ActivityNotFoundException) {
            notices.show(UserProblem.LinkUnavailable)
        } finally {
            onDismiss()
        }
    }
}
