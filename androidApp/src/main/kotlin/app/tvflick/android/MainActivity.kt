package app.tvflick.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.tvflick.App
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.SqlDelightLibraryRepository
import app.tvflick.ui.PlayerWindowOptions
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.Intent
import android.content.res.Configuration

class MainActivity : ComponentActivity() {
    private lateinit var playerPresentation: AndroidPlayerPresentationController
    private var playbackActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerPresentation = AndroidPlayerPresentationController(this)
        val driver = AndroidSqliteDriver(TvFlickDatabase.Schema, applicationContext, "tv-flick.db")
        val libraryRepository = SqlDelightLibraryRepository(TvFlickDatabase(driver))
        setContent {
            App(
                tmdbApiKey = BuildConfig.TMDB_API_KEY,
                libraryRepository = libraryRepository,
                playerWindowOptions = PlayerWindowOptions(
                    mini = playerPresentation.isInPictureInPictureMode,
                    onMini = playerPresentation::enterPictureInPicture,
                    onFull = { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) },
                    onRotation = playerPresentation::toggleOrientation,
                    onActiveChanged = { active ->
                        playbackActive = active
                        updatePlayerWindow()
                        if (!active) {
                            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    },
                ),
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerPresentation.onPictureInPictureModeChanged(isInPictureInPictureMode)
        updatePlayerWindow()
    }

    override fun onResume() {
        super.onResume()
        updatePlayerWindow()
    }

    private fun updatePlayerWindow() {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (playbackActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isInPictureInPictureMode) {
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                bars.hide(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
