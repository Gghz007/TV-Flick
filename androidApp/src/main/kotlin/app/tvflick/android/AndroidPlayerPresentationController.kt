package app.tvflick.android

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Android's system host for the shared playback session.  It owns platform
 * presentation only; the video engine and player controls remain in
 * `video-plugin` so entering PiP never reopens the stream.
 */
class AndroidPlayerPresentationController(private val activity: Activity) {
    var isInPictureInPictureMode by mutableStateOf(activity.isInPictureInPictureMode)
        private set

    fun enterPictureInPicture(): Boolean {
        if (activity.isInPictureInPictureMode) return true
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        val bounds = Rect()
        activity.window.decorView.getGlobalVisibleRect(bounds)
        val params = PictureInPictureParams.Builder().apply {
            // Full Player fills the activity. Preserve its current orientation/window ratio,
            // including foldables, within Android's supported PiP interval.
            if (!bounds.isEmpty) {
                val ratio = Rational(bounds.width(), bounds.height())
                // Keep exact fractions: truncating the portrait bound to 4 decimals
                // puts it below Android's accepted minimum and rejects narrow phones.
                setAspectRatio(ratio.coerceIn(Rational(100, 239), Rational(239, 100)))
                setSourceRectHint(bounds)
            }
            setActions(emptyList()) // No pause/seek buttons in Mini, as requested.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(false)
        }.build()
        return try {
            activity.enterPictureInPictureMode(params)
        } catch (_: Exception) {
            false
        }
    }

    fun toggleOrientation() {
        activity.requestedOrientation = when (activity.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean) {
        isInPictureInPictureMode = inPictureInPictureMode
    }
}
