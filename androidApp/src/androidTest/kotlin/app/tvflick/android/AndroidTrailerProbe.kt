package app.tvflick.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import app.tvflick.core.library.LibraryContentDraft
import app.tvflick.core.library.MediaType
import app.tvflick.core.tmdb.TmdbClient
import app.tvflick.ui.FlickState
import app.tvflick.ui.FlickTheme
import app.tvflick.ui.TitlePanel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.util.concurrent.atomic.AtomicReference

internal fun Instrumentation.verifyAndroidTrailer(activity: MainActivity) {
    val json = """{"id":42,"title":"Trailer probe","videos":{"results":[
        {"site":"YouTube","type":"Trailer","key":"M7lc1UVf-VE","official":true}]}}"""
    val opened = AtomicReference<Intent>()
    val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_VIEW || intent.data?.host != "www.youtube.com") return null
            opened.set(Intent(intent))
            return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        }
    }
    addMonitor(monitor)
    try {
        HttpClient(MockEngine { respond(json) }).use { http ->
            val tmdb = TmdbClient("fixture", http)
            runOnMainSync {
                activity.setContent {
                    val scope = rememberCoroutineScope()
                    val state = remember { FlickState(null, tmdb, scope) }
                    FlickTheme { TitlePanel(state, LibraryContentDraft(MediaType.Movie, 42, "Trailer probe"), true, 350.dp) }
                }
            }
            val end = SystemClock.elapsedRealtime() + 8_000
            while (opened.get() == null && SystemClock.elapsedRealtime() < end) {
                uiAutomation.rootInActiveWindow.findTrailerButton()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                SystemClock.sleep(180)
            }
            val intent = requireNotNull(opened.get()) { "Trailer did not open YouTube" }
            check(intent.`package` == "com.google.android.youtube")
            check(intent.data?.getQueryParameter("v") == "M7lc1UVf-VE")
        }
    } finally { removeMonitor(monitor) }
}

private fun AccessibilityNodeInfo?.findTrailerButton(): AccessibilityNodeInfo? {
    val node = this ?: return null
    if (node.text?.toString() == "Trailer" || node.contentDescription?.toString() == "Trailer")
        return generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
    return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it).findTrailerButton() }
}
