package app.tvflick.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import app.tvflick.core.library.PlaybackTarget
import app.tvflick.core.library.PreparedPlayback
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.tvflick.core.backup.GoogleBackupStore
import app.tvflick.core.backup.GoogleAuthorization
import app.tvflick.core.backup.GoogleSignInRequired
import app.tvflick.core.backup.rememberGoogleAuthorization
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.LibraryContentDraft
import app.tvflick.core.library.MediaType
import app.tvflick.core.library.SqlDelightLibraryRepository
import app.tvflick.plugin.api.PlaybackProgressSink
import app.tvflick.plugin.api.PlaybackSnapshot
import app.tvflick.plugin.api.ResolvedVideoSource
import app.tvflick.plugin.api.StreamKind
import app.tvflick.ui.FlickState
import app.tvflick.ui.PlatformPlaybackHost
import app.tvflick.ui.PlaybackKey
import app.tvflick.ui.PlayerWindowOptions
import app.tvflick.ui.PlayingContent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Probes the real window manager on a phone emulator with PiP enabled. */
class PlayerWindowProbe : Instrumentation() {
    private var cloudBackup: String? = null
    private var cloudSignOut = false
    private var trailerProbe = false
    private var navigationProbe = false
    private var downloadProbe = false
    private var playerControlsProbe = false

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        cloudBackup = arguments.getString("cloudBackup")
        cloudSignOut = arguments.getString("cloudSignOut") == "true"
        trailerProbe = arguments.getString("trailerProbe") == "true"
        navigationProbe = arguments.getString("navigationProbe") == "true"
        downloadProbe = arguments.getString("downloadProbe") == "true"
        playerControlsProbe = arguments.getString("playerControlsProbe") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: MainActivity? = null
        try {
            val intent = Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val launched = startActivitySync(intent) as MainActivity
            activity = launched
            waitForIdleSync()
            if (downloadProbe) {
                verifyAndroidDownload(launched)
                result.putString("stream", "\nTVFLICK_ANDROID_DOWNLOAD_OK downloader selection and cancel\n")
                runOnMainSync { launched.finish() }
                finish(Activity.RESULT_OK, result)
                return
            }
            if (navigationProbe) {
                verifyAndroidNavigation(launched)
                result.putString("stream", "\nTVFLICK_ANDROID_NAVIGATION_OK edge-aligned tabs and persistent bottom navigation\n")
                runOnMainSync { launched.finish() }
                finish(Activity.RESULT_OK, result)
                return
            }
            if (trailerProbe) {
                verifyAndroidTrailer(launched)
                result.putString("stream", "\nTVFLICK_ANDROID_TRAILER_OK title button opens the YouTube app\n")
                runOnMainSync { launched.finish() }
                finish(Activity.RESULT_OK, result)
                return
            }
            val backupName = cloudBackup
            if (backupName != null) {
                verifyCloudBackup(launched, backupName)
                result.putString("stream", "\nTVFLICK_ANDROID_GOOGLE_OK saved session, Drive upload and download\n")
                runOnMainSync { launched.finish() }
                finish(Activity.RESULT_OK, result)
                return
            }
            if (cloudSignOut) {
                verifyGoogle(launched) { authorization, store ->
                    store.profile(interactive = false)
                    authorization.invalidateToken(authorization.token(interactive = false))
                    store.profile(interactive = false)
                    store.signOut()
                    val failure = runCatching { store.profile(interactive = false) }.exceptionOrNull()
                    check(failure is GoogleSignInRequired)
                }
                result.putString("stream", "\nTVFLICK_ANDROID_GOOGLE_LIFECYCLE_OK refresh and sign-out\n")
                runOnMainSync { launched.finish() }
                finish(Activity.RESULT_OK, result)
                return
            }
            // Use the same controller wired into the production activity's configuration callback.
            val controller = MainActivity::class.java.getDeclaredField("playerPresentation").let { field ->
                field.isAccessible = true
                field.get(launched) as AndroidPlayerPresentationController
            }
            val lastProgress = AtomicReference<PlaybackSnapshot>()
            showPlayback(launched, controller, lastProgress)
            if (playerControlsProbe) {
                waitForNavigationNode("Playback speed")
                waitForNavigationNode("Audio track")
                waitForNavigationNode("Download")
                check(uiAutomation.rootInActiveWindow.findNavigationLabel("Volume") == null) {
                    "The Android player still exposes its own volume button"
                }
                result.putString("stream", "\nTVFLICK_ANDROID_PLAYER_CONTROLS_OK speed, download and audio track are available\n")
                runOnMainSync { launched.finish() }
                finish(Activity.RESULT_OK, result)
                return
            }
            check(targetContext.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                "The test device does not support PiP"
            }
            await("native MP4 playback and progress") { (lastProgress.get()?.positionMs ?: 0L) >= 2_000 }
            val portraitFirst = launched.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val rotated = if (portraitFirst) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            runOnMainSync { controller.toggleOrientation() }
            await("screen rotation") { launched.resources.configuration.orientation == rotated }
            runOnMainSync { controller.toggleOrientation() }
            await("return to original orientation") { launched.resources.configuration.orientation != rotated }

            val accepted = AtomicBoolean()
            runOnMainSync { accepted.set(controller.enterPictureInPicture()) }
            check(accepted.get()) { "Android rejected the PiP request" }
            await("system PiP entry and Compose callback") {
                launched.isInPictureInPictureMode && controller.isInPictureInPictureMode
            }
            val positionInMini = lastProgress.get().positionMs
            await("continued playback in PiP") {
                (lastProgress.get()?.positionMs ?: 0L) > positionInMini + 500
            }
            val savedPosition = lastProgress.get().positionMs
            targetContext.startActivity(intent)
            await("return to the original activity from PiP") {
                !launched.isInPictureInPictureMode && !controller.isInPictureInPictureMode &&
                    !launched.isFinishing && !launched.isDestroyed
            }
            await("continued playback after returning to Full") {
                (lastProgress.get()?.positionMs ?: 0L) > savedPosition + 500
            }
            runOnMainSync { launched.finish() }
            await("activity closes cleanly") { launched.isDestroyed }
            check(lastProgress.get().positionMs > savedPosition)
            result.putString("stream", "\nTVFLICK_ANDROID_WINDOW_OK MP4 playback, rotation, PiP progress, Full return, close\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            activity?.let { instance -> runOnMainSync { instance.finish() } }
            result.putString("stream", "\nTVFLICK_ANDROID_WINDOW_FAILED: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun verifyCloudBackup(activity: MainActivity, name: String) {
        require(name.matches(Regex("TV-Flick-ANDROID-TEST-[0-9a-f-]{36}\\.tvbackup")))
        verifyGoogle(activity) { _, store ->
            store.profile(interactive = false)
            check(store.list().none { it.name == name })
            // An in-memory library: do not read or export the real library on the device.
            val driver = AndroidSqliteDriver(TvFlickDatabase.Schema, targetContext, null)
            try {
                val library = SqlDelightLibraryRepository(TvFlickDatabase(driver))
                library.save(LibraryContentDraft(MediaType.Movie, 1, "TV Flick Android synthetic verification"), true)
                val bytes = library.exportBackup()
                store.upload(bytes, name)
                val uploaded = store.list().single { it.name == name }
                check(bytes.contentEquals(store.download(uploaded)))
            } finally { driver.close() }
        }
    }

    private fun verifyGoogle(
        activity: MainActivity,
        action: suspend (GoogleAuthorization, GoogleBackupStore) -> Unit,
    ) {
        val outcome = AtomicReference<Result<Unit>>()
        runOnMainSync {
            activity.setContent {
                val authorization = rememberGoogleAuthorization()
                val store = remember(authorization) { GoogleBackupStore(authorization) }
                DisposableEffect(store) { onDispose { store.close(); authorization.close() } }
                LaunchedEffect(store) {
                    outcome.set(runCatching { action(authorization, store) })
                }
            }
        }
        await("Google Drive verification", timeoutMs = 45_000) { outcome.get() != null }
        val error = outcome.get().exceptionOrNull()
        check(error == null) { "Google verification failed: ${error?.javaClass?.simpleName}" }
    }

    private fun showPlayback(
        activity: MainActivity,
        controller: AndroidPlayerPresentationController,
        lastProgress: AtomicReference<PlaybackSnapshot>,
    ) {
        val clip = File(targetContext.cacheDir, "tvflick-test-tone-free.mp4")
        context.assets.open("player-probe.mp4").use { input ->
            clip.outputStream().use { input.copyTo(it) }
        }
        val target = PlaybackTarget.Movie(1L)
        val source = ResolvedVideoSource(clip.toURI().toString(), StreamKind.Mp4, sourceId = "instrumentation")
        val sink = PlaybackProgressSink { snapshot -> lastProgress.set(snapshot) }
        // PreparedPlayback is intentionally internal to the app's playback coordinator.
        val prepared = PreparedPlayback::class.java.getDeclaredConstructor(
            PlaybackTarget::class.java, ResolvedVideoSource::class.java, Long::class.javaPrimitiveType,
            PlaybackProgressSink::class.java,
        ).newInstance(target, source, 0L, sink)
        val content = PlayingContent(PlaybackKey(target.id), "TV Flick test clip", emptyList(), prepared, sink)
        runOnMainSync {
            activity.setContent {
                val scope = rememberCoroutineScope()
                val state = remember { FlickState(null, null, scope) }
                PlatformPlaybackHost(
                    state, content,
                    PlayerWindowOptions(mini = controller.isInPictureInPictureMode),
                    Modifier,
                )
            }
        }
    }

    private fun await(description: String, timeoutMs: Long = 12_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val satisfied = AtomicBoolean()
        do {
            runOnMainSync { satisfied.set(condition()) }
            if (satisfied.get()) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Timed out waiting for $description")
    }
}
