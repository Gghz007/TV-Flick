package app.tvflick.core.update

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

internal data class AppRelease(val version: String, val url: String)

/** Release notification only: never downloads or executes an installer. */
internal class GitHubReleaseChecker(private val client: HttpClient) {
    suspend fun check(repository: String, currentVersion: String): AppRelease? {
        if (!repository.matches(Regex("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+"))) return null
        val current = version(currentVersion) ?: return null
        return try { withTimeoutOrNull(8_000) {
            client.prepareGet("https://api.github.com/repos/$repository/releases/latest") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "TV-Flick/$currentVersion")
                header("X-GitHub-Api-Version", "2026-03-10")
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) return@execute null
                // Release notes are untrusted, unused, and must not cause an unbounded allocation.
                if ((response.contentLength() ?: 0L) > 256 * 1024) return@execute null
                val channel = response.bodyAsChannel()
                val bytes = ByteArray(256 * 1024 + 1)
                var size = 0
                while (size < bytes.size) {
                    val count = channel.readAvailable(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size == bytes.size) return@execute null
                val data = Json.parseToJsonElement(bytes.decodeToString(0, size)).jsonObject
                if (data["draft"]?.jsonPrimitive?.booleanOrNull != false ||
                    data["prerelease"]?.jsonPrimitive?.booleanOrNull != false) return@execute null
                val tag = data["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@execute null
                val next = version(tag) ?: return@execute null
                if (next.preview || !next.newerThan(current)) return@execute null
                // Construct a canonical link; don't navigate to an arbitrary html_url from the response.
                AppRelease(tag.removePrefix("v"), "https://github.com/$repository/releases/tag/${tag.encodeURLPathPart()}")
            }
        } } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null } // Offline/rate limiting must not interrupt ordinary app startup.
    }

    private data class Version(val numbers: List<Long>, val preview: Boolean) {
        fun newerThan(other: Version): Boolean {
            for (i in numbers.indices) if (numbers[i] != other.numbers[i]) return numbers[i] > other.numbers[i]
            return !preview && other.preview
        }
    }

    private fun version(text: String): Version? {
        val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$")
            .matchEntire(text) ?: return null
        val numbers = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
        return Version(numbers, match.groupValues[4].isNotEmpty())
    }
}
