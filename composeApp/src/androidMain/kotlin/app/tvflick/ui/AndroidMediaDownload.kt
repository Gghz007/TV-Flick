package app.tvflick.ui

import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.tvflick.plugin.api.ResolvedVideoSource
import app.tvflick.plugin.api.StreamKind
import kotlinx.coroutines.CompletableDeferred

private data class DownloadApp(val label: String, val launch: () -> Unit)
private data class DownloadPrompt(val apps: List<DownloadApp>, val choice: CompletableDeferred<DownloadApp?>)

private val oneDownloadManagerPackages = listOf(
    "idm.internet.download.manager", "idm.internet.download.manager.plus", "idm.internet.download.manager.adm.lite",
)

@Composable
actual fun rememberMediaDownloader(): suspend (ResolvedVideoSource, String) -> Unit {
    val context = LocalContext.current
    var prompt by remember(context) { mutableStateOf<DownloadPrompt?>(null) }
    prompt?.let { request ->
        AlertDialog(
            onDismissRequest = { request.choice.complete(null) },
            containerColor = FlickDesign.panel,
            title = { Label("Download with", size = 18) },
            text = {
                if (request.apps.isEmpty()) Label("No compatible download app", maxLines = 2)
                else PlaybackChoiceList(request.apps, onSelect = { request.choice.complete(it) }) {
                    Label(it.label, size = 16)
                }
            },
            confirmButton = {
                TextButton(onClick = { request.choice.complete(null) }) {
                    Label(if (request.apps.isEmpty()) "Close" else "Cancel")
                }
            },
        )
    }
    return remember(context) {
        { source, title ->
            val url = Uri.parse(source.url)
            require(url.scheme?.lowercase() in setOf("http", "https") && !url.host.isNullOrEmpty() && url.userInfo == null)
            val request = DownloadPrompt(downloadApps(context, source, url, videoFileStem(title)), CompletableDeferred())
            prompt = request
            try {
                request.choice.await()?.launch?.invoke()
            } finally {
                if (prompt === request) prompt = null
            }
        }
    }
}

private fun downloadApps(context: Context, source: ResolvedVideoSource, url: Uri, stem: String): List<DownloadApp> = buildList {
    val credentialHeaders = setOf("authorization", "proxy-authorization", "cookie", "cookie2")
    val headers = source.headers.filterKeys { it.lowercase() !in credentialHeaders }
    if (source.kind == StreamKind.Mp4 && headers.size == source.headers.size) {
        context.getSystemService(DownloadManager::class.java)?.let { manager ->
            add(DownloadApp("Android Downloads") {
                val request = DownloadManager.Request(url).setTitle(stem).setMimeType("video/mp4")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                headers.forEach(request::addRequestHeader)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$stem.mp4")
                } else {
                    request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "$stem.mp4")
                }
                manager.enqueue(request)
            })
        }
    }
    if (headers.size != source.headers.size) return@buildList
    for (packageName in oneDownloadManagerPackages) {
        val intent = Intent(Intent.ACTION_VIEW, url).apply {
            component = ComponentName(packageName, "idm.internet.download.manager.Downloader")
            putExtra("extra_filename", if (source.kind == StreamKind.Mp4) "$stem.mp4" else stem)
            putExtra("headers", Bundle().apply { headers.forEach { (key, value) -> putString(key, value) } })
            putExtra("force_editor", true)
            putExtra("hide_browser_option", true)
        }
        resolveDownloadApp(context, intent)?.let(::add)
    }
    if (source.kind == StreamKind.Mp4 && headers.isEmpty()) {
        val adm = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage("com.dv.adm")
            .putExtra(Intent.EXTRA_TEXT, url.toString())
        resolveDownloadApp(context, adm)?.let(::add)
    }
}

private fun resolveDownloadApp(context: Context, intent: Intent): DownloadApp? {
    val packageManager = context.packageManager
    val activity = packageManager.resolveActivity(intent, 0)?.activityInfo?.takeIf { it.exported } ?: return null
    return DownloadApp(activity.applicationInfo.loadLabel(packageManager).toString()) {
        context.startActivity(intent.setClassName(activity.packageName, activity.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
