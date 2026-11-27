package app.tvflick.core.backup

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlin.random.Random

val GOOGLE_BACKUP_SCOPES = setOf("openid", "email", "profile", "https://www.googleapis.com/auth/drive.appdata")
interface GoogleAuthorization {
    val configured: Boolean
    suspend fun token(interactive: Boolean): String
    suspend fun invalidateToken(token: String) {}
    suspend fun signOut()
    fun close() {}
}
class GoogleSignInRequired : Exception("Войдите в Google снова")
class GoogleSignInCancelled : Exception("Вход отменён")
class GoogleSignInDenied : Exception("Google denied sign-in. Open the browser for details.")
class GoogleBrowserSignInIncomplete : Exception("Google sign-in did not finish. Check the browser and try again.")
@Composable expect fun rememberGoogleAuthorization(): GoogleAuthorization

data class GoogleProfile(val name: String, val email: String, val picture: String?)
data class CloudBackup(val id: String, val name: String, val createdTime: String)

class GoogleBackupStore(private val auth: GoogleAuthorization, private val http: HttpClient = HttpClient {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 120_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 30_000 }
}) {
    val configured get() = auth.configured
    private suspend fun <T> authorized(interactive: Boolean = false, action: suspend (String) -> T): T {
        val token = auth.token(interactive)
        return try { action(token) } catch (_: GoogleSignInRequired) {
            auth.invalidateToken(token)
            action(auth.token(false)) // One silent refresh, never an automatic consent popup.
        }
    }
    private fun HttpResponse.requireSuccess() {
        if (status == HttpStatusCode.Unauthorized) throw GoogleSignInRequired()
        check(status.isSuccess()) { "Google request failed" }
    }
    suspend fun profile(interactive: Boolean): GoogleProfile = authorized(interactive) { token ->
        http.prepareGet("https://www.googleapis.com/oauth2/v3/userinfo") { bearerAuth(token) }.execute { response ->
            response.requireSuccess()
            val json = Json.parseToJsonElement(response.readBounded(64 * 1024).decodeToString()).jsonObject
            val email = json.text("email").also { check(it.isNotBlank()) { "Google did not return an account email" } }
            GoogleProfile(json.text("name").ifBlank { email }, email, json.text("picture").takeIf { it.isNotEmpty() })
        }
    }
    suspend fun list(): List<CloudBackup> {
        val files = mutableListOf<CloudBackup>()
        val pages = mutableSetOf<String>()
        var page: String? = null
        do {
            val json = authorized { token -> http.prepareGet("https://www.googleapis.com/drive/v3/files") {
                bearerAuth(token); parameter("spaces", "appDataFolder")
                parameter("q", "trashed = false and appProperties has { key='tvflickBackup' and value='1' }")
                parameter("fields", "nextPageToken,files(id,name,createdTime)")
                parameter("orderBy", "createdTime desc"); parameter("pageSize", 100)
                page?.let { parameter("pageToken", it) }
            }.execute { response ->
                response.requireSuccess()
                Json.parseToJsonElement(response.readBounded(512 * 1024).decodeToString()).jsonObject
            } }
            json["files"]?.jsonArray?.forEach { item -> item.jsonObject.let {
                files += CloudBackup(it.text("id"), it.text("name"), it.text("createdTime"))
            } }
            page = json.text("nextPageToken").takeIf { it.isNotEmpty() }
            check(page == null || (pages.add(page) && pages.size <= 100)) { "Google backup pagination did not complete" }
        } while (page != null)
        return files
    }
    suspend fun upload(bytes: ByteArray, name: String) {
        require(bytes.size <= MAX_BACKUP_BYTES)
        val metadata = buildJsonObject {
            put("name", name); put("parents", buildJsonArray { add("appDataFolder") })
            put("appProperties", buildJsonObject { put("tvflickBackup", "1") })
        }
        val boundary = "tvflick_${Random.nextLong().toULong()}_${Random.nextLong().toULong()}"
        val prefix = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".encodeToByteArray()
        val suffix = "\r\n--$boundary--\r\n".encodeToByteArray()
        authorized { token -> http.preparePost("https://www.googleapis.com/upload/drive/v3/files") {
            bearerAuth(token); parameter("uploadType", "multipart")
            contentType(ContentType.MultiPart.Related.withParameter("boundary", boundary))
            setBody(prefix + bytes + suffix)
        }.execute { it.requireSuccess() } }
    }
    suspend fun download(file: CloudBackup): ByteArray {
        require(file.id.matches(Regex("[A-Za-z0-9_-]+")))
        return authorized { token -> http.prepareGet("https://www.googleapis.com/drive/v3/files/${file.id}") {
            bearerAuth(token); parameter("alt", "media")
        }.execute { response ->
            response.requireSuccess()
            response.readBounded(MAX_BACKUP_BYTES)
        } }
    }
    suspend fun signOut() = auth.signOut()
    fun close() { http.close() }
}
