package app.tvflick.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import app.tvflick.plugin.api.ResolvedVideoSource
import app.tvflick.plugin.api.StreamKind
import app.tvflick.ui.FlickTheme
import app.tvflick.ui.rememberMediaDownloader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun Instrumentation.verifyAndroidDownload(activity: MainActivity) {
    val unexpectedIntent = AtomicReference<String>()
    val finished = CountDownLatch(1)
    val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_CHOOSER)) return null
            unexpectedIntent.set(intent.action)
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }
    addMonitor(monitor)
    try {
        runOnMainSync {
            activity.setContent {
                FlickTheme {
                    val download = rememberMediaDownloader()
                    LaunchedEffect(Unit) {
                        download(ResolvedVideoSource("https://example.test/clip.mp4", StreamKind.Mp4, sourceId = "probe"),
                            "TV Flick probe")
                        finished.countDown()
                    }
                }
            }
        }
        waitForNavigationNode("Download with")
        waitForNavigationNode("Android Downloads").clickTarget()
        check(waitForNavigationNode("Cancel").clickTarget().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        check(finished.await(5, TimeUnit.SECONDS)) { "Cancelling did not dismiss the Android download prompt" }
        check(unexpectedIntent.get() == null) { "Download started an external intent before any app was chosen" }
    } finally {
        removeMonitor(monitor)
    }
}
