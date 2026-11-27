package app.tvflick

import app.tvflick.core.update.GitHubReleaseChecker
import app.tvflick.ui.UserNotices
import app.tvflick.ui.UserProblem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class GitHubReleaseCheckerTest {
    @Test fun `only a newer stable release produces a canonical GitHub link`() = runBlocking {
        var tag = "v0.10.0"
        var preview = false
        val client = HttpClient(MockEngine { request ->
            assertEquals("https://api.github.com/repos/example/TV-Flick/releases/latest", request.url.toString())
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("""{"tag_name":"$tag","draft":false,"prerelease":$preview,"html_url":"https://untrusted.test"}""")
        })
        try {
            val checker = GitHubReleaseChecker(client)
            assertEquals("https://github.com/example/TV-Flick/releases/tag/v0.10.0",
                checker.check("example/TV-Flick", "0.9.0")?.url)
            assertNull(checker.check("example/TV-Flick", "0.10.0"))
            assertNull(checker.check("example/TV-Flick", "1.0.0"))
            preview = true
            assertNull(checker.check("example/TV-Flick", "0.9.0"))
            preview = false
            tag = "v1.0.0-beta.1"
            assertNull(checker.check("example/TV-Flick", "0.9.0"))
            tag = "v1.0.0"
            assertNotNull(checker.check("example/TV-Flick", "1.0.0-rc.1"))
            tag = "not-a-version"
            assertNull(checker.check("example/TV-Flick", "0.9.0"))
        } finally { client.close() }
    }

    @Test fun `disabled offline malformed and limited checks are quiet and notices preserve errors`() = runBlocking {
        var calls = 0
        var code = HttpStatusCode.Forbidden
        var body = "rate limited"
        val client = HttpClient(MockEngine { calls++; respond(body, code) })
        try {
            val checker = GitHubReleaseChecker(client)
            assertNull(checker.check("", "0.1.0"))
            assertNull(checker.check("https://wrong.test/repo", "0.1.0"))
            assertEquals(0, calls)
            assertNull(checker.check("example/TV-Flick", "0.1.0"))
            code = HttpStatusCode.OK
            assertNull(checker.check("example/TV-Flick", "0.1.0"))
            body = "x".repeat(256 * 1024 + 1)
            assertNull(checker.check("example/TV-Flick", "0.1.0"))
        } finally { client.close() }
        val cancelled = HttpClient(MockEngine { throw CancellationException("closed") })
        try {
            assertFailsWith<CancellationException> { GitHubReleaseChecker(cancelled).check("example/TV-Flick", "0.1.0") }
        } finally { cancelled.close() }
        val notices = UserNotices()
        notices.show(UserProblem.PlaybackFailed)
        val error = assertNotNull(notices.current)
        notices.showUpdate("https://github.com/example/TV-Flick/releases/tag/v1.0.0")
        assertSame(error, notices.current)
        notices.dismiss(error.id)
        notices.showUpdate("https://github.com/example/TV-Flick/releases/tag/v1.0.0")
        assertEquals(UserProblem.UpdateAvailable, notices.current?.problem)
        notices.dismiss(assertNotNull(notices.current).id)
        assertNull(notices.current)
    }
}
