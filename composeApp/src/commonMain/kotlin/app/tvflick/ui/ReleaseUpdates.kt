package app.tvflick.ui

import androidx.compose.runtime.*
import app.tvflick.core.update.GitHubReleaseChecker
import app.tvflick.core.update.ReleaseConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
internal fun ReleaseUpdates(notices: UserNotices) {
    LaunchedEffect(notices) {
        if (ReleaseConfig.repository.isBlank()) return@LaunchedEffect
        delay(5_000) // Main screen and its data have priority.
        val client = HttpClient { followRedirects = false; expectSuccess = false }
        val release = try { GitHubReleaseChecker(client).check(ReleaseConfig.repository, ReleaseConfig.version) }
            finally { client.close() }
        release ?: return@LaunchedEffect
        // An optional update must not replace a source/library error the user is reading.
        snapshotFlow { notices.current }.first { it == null }
        notices.showUpdate(release.url)
    }
}
