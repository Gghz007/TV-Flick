package app.tvflick.android

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.tvflick.App
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.LibraryContentDraft
import app.tvflick.core.library.MediaType
import app.tvflick.core.library.SqlDelightLibraryRepository
import app.tvflick.core.tmdb.TmdbEpisode
import app.tvflick.core.tmdb.TmdbSeasonDetails
import app.tvflick.core.tmdb.TmdbSeasonSummary
import app.tvflick.ui.FlickState
import app.tvflick.ui.FlickTheme
import app.tvflick.ui.ProfilePanel
import app.tvflick.ui.SettingsTab
import app.tvflick.ui.rememberBackupController
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun Instrumentation.verifyAndroidNavigation(activity: MainActivity) {
    AndroidSqliteDriver(TvFlickDatabase.Schema, activity, name = null).use { driver ->
        val repository = SqlDelightLibraryRepository(TvFlickDatabase(driver))
        val showsInProfile = listOf("Margin probe show", "Margin probe future show") +
            (3..12).map { "Margin probe show $it" }
        runBlocking {
            showsInProfile.forEachIndexed { index, title ->
                val saved = repository.save(LibraryContentDraft(MediaType.Tv, 100L + index, title), addToWatchList = index < 2)
                if (index == 1) repository.syncSeason(saved.localId, TmdbSeasonSummary(null, 1, null, null, null),
                    TmdbSeasonDetails(1, null, listOf(TmdbEpisode(null, 1, null, null, "2099-01-01", null, null))))
            }
            repository.save(LibraryContentDraft(MediaType.Movie, 200, "Margin probe movie"), addToWatchList = true)
            repository.save(LibraryContentDraft(MediaType.Movie, 201, "Margin probe future movie", releaseDate = "2099-01-01"),
                addToWatchList = true)
        }
        runOnMainSync { activity.setContent { App(libraryRepository = repository) } }
        val leadingTab = waitForNavigationNode("WATCH LIST").clickTarget().screenBounds()
        val trailingTab = waitForNavigationNode("UPCOMING").clickTarget().screenBounds()
        val safeFrame = requireNotNull(uiAutomation.rootInActiveWindow).screenBounds()
        check(abs(leadingTab.left - safeFrame.left) <= 2 && abs(trailingTab.right - safeFrame.right) <= 2) {
            "Watch List tabs do not fill the Android screen width: $leadingTab, $trailingTab, $safeFrame"
        }
        val density = activity.resources.displayMetrics.density
        val innerMargin = (16 * density).roundToInt()
        val shows = waitForNavigationNode("Shows").clickTarget().screenBounds()
        val profile = waitForNavigationNode("Profile").clickTarget().screenBounds()
        check(abs(shows.left - leadingTab.left - innerMargin) <= 2 &&
            abs(trailingTab.right - profile.right - innerMargin) <= 2) {
            "Bottom navigation does not preserve the external and internal 8 dp margins"
        }
        val cardMargin = (8 * density).roundToInt()
        assertHorizontalMargins(waitForNavigationNode("Margin probe show").clickTarget().screenBounds(), safeFrame, cardMargin, "show Watch List card")
        check(waitForNavigationNode("UPCOMING").clickTarget().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertHorizontalMargins(waitForNavigationNode("Margin probe future show").clickTarget().screenBounds(), safeFrame, cardMargin, "show Upcoming card")
        check(waitForNavigationNode("Movies").clickTarget().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertHorizontalMargins(waitForNavigationNode("Margin probe future movie").clickTarget().screenBounds(), safeFrame, cardMargin, "movie Upcoming card")
        check(waitForNavigationNode("WATCH LIST").clickTarget().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val movieCard = waitForNavigationNode("Margin probe movie").clickTarget()
        assertHorizontalMargins(movieCard.screenBounds(), safeFrame, cardMargin, "movie Watch List card")
        var bottomInset = 0
        runOnMainSync {
            bottomInset = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        }
        val usableBottom = safeFrame.bottom - bottomInset
        check(movieCard.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        check(waitForNavigationNode("Properties").clickTarget().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitForNavigationNode("Watch later")
        assertBottomPanel("Properties", profile, usableBottom)
        check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        waitForNavigationNode("Properties")
        check(waitForNavigationNode("Profile").clickTarget().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitForNavigationNode("Favorite Shows")
        val profileTree = uiAutomation.rootInActiveWindow
        val posters = showsInProfile.mapNotNull { profileTree.findNavigationLabel(it)?.clickTarget()?.screenBounds() }
            .sortedBy { it.left }
        val expectedColumns = ((safeFrame.width() / density - 8f) / 118.512f).toInt().coerceAtLeast(3)
        check(posters.size == expectedColumns) {
            "Expected $expectedColumns profile posters for ${safeFrame.width() / density} dp, found ${posters.size}"
        }
        posters.zipWithNext().forEach { (left, right) ->
            check(abs(right.left - left.right - cardMargin) <= 1) { "Profile poster gap is not 8 dp: $left, $right" }
        }
        val posterRow = Rect(posters.first().left, 0, posters.last().right, 0)
        assertHorizontalMargins(posterRow, safeFrame, cardMargin, "profile poster row")
        check(waitForNavigationNode("Shows").clickTarget().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        check(waitForNavigationNode("FILTERS").clickTarget().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertBottomPanel("Sort by", profile, usableBottom)
        check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        waitForNavigationNode("FILTERS")
        check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        check(waitForNavigationNode("Settings").clickTarget().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val profileTab = waitForNavigationNode("PROFILE").clickTarget().screenBounds()
        val adBlock = waitForNavigationNode("ADBLOCK")
        // Android accessibility reports the padded label for a disabled tab.
        val labelInset = (3 * density).roundToInt()
        check(abs(profileTab.left - leadingTab.left) <= 2 && abs(adBlock.screenBounds().right - trailingTab.right) <= labelInset + 2) {
            "Settings tabs do not fill the Android screen width: $profileTab, ${adBlock.screenBounds()}, $safeFrame"
        }
        val adBlockNodes = generateSequence(adBlock) { it.parent }
        check(adBlockNodes.any { !it.isEnabled } && adBlockNodes.none { it.isEnabled && it.isClickable }) {
            "AdBlock is active with an empty source list"
        }
        check(waitForNavigationNode("Shows").clickTarget().performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "Profile covers the bottom navigation"
        }
        waitForNavigationNode("WATCH LIST")
        runOnMainSync {
            activity.setContent {
                val scope = rememberCoroutineScope()
                val state = remember { FlickState(repository, null, scope).apply {
                    settingsOpen = true
                    settingsTab = SettingsTab.DATABASE
                } }
                FlickTheme { ProfilePanel(state, mobile = true, width = (safeFrame.width() / density).dp,
                    backup = rememberBackupController(state), androidPage = true) }
            }
        }
        val sourceAction = waitForNavigationNode("Add source").clickTarget().screenBounds()
        // Compose expands a 25 dp icon to a 48 dp accessibility hit target. Measure the artwork edge.
        val expandedHitArea = (sourceAction.width() - (25 * density).roundToInt()).coerceAtLeast(0) / 2
        val inputRight = sourceAction.right - expandedHitArea + (13.5 * density).roundToInt()
        val inputLeft = requireNotNull(uiAutomation.rootInActiveWindow.findEditableField()).screenBounds().left
        assertHorizontalMargins(Rect(inputLeft, 0, inputRight, 0), safeFrame, (20 * density).roundToInt(), "Database input")
        runOnMainSync { activity.setContent {} }
    }
}

private fun Instrumentation.assertBottomPanel(heading: String, navigation: Rect, usableBottom: Int) {
    val panel = waitForNavigationNode(heading).clickTarget().screenBounds()
    check(panel.top < navigation.top && panel.bottom > navigation.bottom && abs(panel.bottom - usableBottom) <= 2) {
        "$heading panel is not anchored over the navigation at $usableBottom: $panel, $navigation"
    }
    check(uiAutomation.rootInActiveWindow.findNavigationLabel("Profile") == null) {
        "Bottom navigation is still exposed while $heading is open"
    }
}

private fun assertHorizontalMargins(bounds: Rect, screen: Rect, margin: Int, label: String) {
    check(abs(bounds.left - screen.left - margin) <= 2 && abs(screen.right - bounds.right - margin) <= 2) {
        "$label is not symmetrically inset by $margin px: $bounds, $screen"
    }
}


internal fun Instrumentation.waitForNavigationNode(label: String): AccessibilityNodeInfo {
    val deadline = SystemClock.elapsedRealtime() + 8_000
    while (SystemClock.elapsedRealtime() < deadline) {
        uiAutomation.rootInActiveWindow.findNavigationLabel(label)?.let { return it }
        SystemClock.sleep(100)
    }
    error("Missing Android navigation node: $label")
}

internal fun AccessibilityNodeInfo?.findNavigationLabel(label: String): AccessibilityNodeInfo? {
    val node = this ?: return null
    if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
    return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it).findNavigationLabel(label) }
}

private fun AccessibilityNodeInfo?.findEditableField(): AccessibilityNodeInfo? {
    val node = this ?: return null
    if (node.isEditable) return node
    return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it).findEditableField() }
}

internal fun AccessibilityNodeInfo.clickTarget(): AccessibilityNodeInfo =
    generateSequence(this) { it.parent }.firstOrNull { it.isClickable } ?: error("Navigation item has no click action")

private fun AccessibilityNodeInfo.screenBounds(): Rect = Rect().also(::getBoundsInScreen)
