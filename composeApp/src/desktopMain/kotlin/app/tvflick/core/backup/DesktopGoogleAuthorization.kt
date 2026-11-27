package app.tvflick.core.backup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.awt.Desktop
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Composable actual fun rememberGoogleAuthorization(): GoogleAuthorization = remember { DesktopGoogleAuthorization() }

internal class DesktopGoogleAuthorization(
    private val openBrowser: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
    private val consentTimeoutMillis: Long = 180_000,
) : GoogleAuthorization {
    private val directory = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "TV Flick")
    private val sessionFile = File(directory, "google-session.dat")
    private val http = HttpClient {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
    }
    private val mutex = Mutex()
    private fun config(): JsonObject? = runCatching {
        val file = File(System.getenv("TVFLICK_GOOGLE_OAUTH_CONFIG") ?: File(directory, "google-oauth.json").path)
        val text = if (file.exists()) file.readText() else javaClass.getResourceAsStream("/google-oauth.json")?.bufferedReader()?.use { it.readText() }
        text?.let { Json.parseToJsonElement(it).jsonObject.let { root -> root["installed"]?.jsonObject ?: root } }
    }.getOrNull()
    override val configured get() = !config()?.text("client_id").isNullOrBlank()
    private fun session(): JsonObject? = runCatching {
        Json.parseToJsonElement(Crypt32Util.cryptUnprotectData(sessionFile.readBytes()).decodeToString()).jsonObject
    }.getOrNull()
    private fun random(): String = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    override suspend fun token(interactive: Boolean): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = config() ?: error("Google OAuth is not configured for this build")
            val client = cfg.text("client_id").also { require(it.endsWith(".apps.googleusercontent.com")) }
            var saved = session()?.takeIf { it.text("client_id") == client }
            if (!interactive && !saved?.text("access_token").isNullOrBlank() &&
                saved?.text("expiresAt")?.toLongOrNull()?.let { it > System.currentTimeMillis() + 60_000 } == true)
                return@withContext saved.text("access_token")
            suspend fun exchange(params: Parameters): JsonObject {
                val response = http.submitForm("https://oauth2.googleapis.com/token", params)
                val body = Json.parseToJsonElement(response.readBounded(64 * 1024).decodeToString()).jsonObject
                if (!response.status.isSuccess()) {
                    if (body.text("error") == "invalid_grant") {
                        java.nio.file.Files.deleteIfExists(sessionFile.toPath())
                        throw GoogleSignInRequired()
                    }
                    error("Google authorization failed")
                }
                return body
            }
            val result: JsonObject
            if (!interactive) {
                val refresh = saved?.text("refresh_token").orEmpty().also { if (it.isBlank()) throw GoogleSignInRequired() }
                result = exchange(Parameters.build {
                    append("client_id", client); append("grant_type", "refresh_token"); append("refresh_token", refresh)
                    cfg.text("client_secret").takeIf { it.isNotEmpty() }?.let { append("client_secret", it) }
                })
            } else {
                val state = random()
                val verifier = random() + random()
                val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
                val code = CompletableDeferred<String>()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                val redirect = "http://127.0.0.1:${server.address.port}/oauth/callback"
                server.createContext("/oauth/callback") { request ->
                    val query = parseQueryString(request.requestURI.rawQuery.orEmpty())
                    val valid = request.requestMethod == "GET" && request.requestURI.path == "/oauth/callback" &&
                        query.getAll("state")?.size == 1 && MessageDigest.isEqual(
                        query["state"].orEmpty().toByteArray(), state.toByteArray())
                    val error = query["error"]
                    val value = query["code"]
                    val message = when {
                        !valid -> "Invalid authorization response."
                        error == "access_denied" -> "Google denied sign-in. Return to TV Flick."
                        error != null || value.isNullOrBlank() -> "Sign-in did not finish. Return to TV Flick."
                        else -> "Return to TV Flick."
                    }
                    try {
                        val body = message.toByteArray()
                        request.sendResponseHeaders(if (valid) 200 else 400, body.size.toLong())
                        request.responseBody.use { it.write(body) }
                        if (valid) {
                            when {
                                error == "access_denied" -> code.completeExceptionally(GoogleSignInDenied())
                                value.isNullOrBlank() || error != null -> code.completeExceptionally(GoogleSignInCancelled())
                                else -> code.complete(value)
                            }
                        }
                    } finally { request.close() }
                }
                try {
                    server.start()
                    val url = URLBuilder("https://accounts.google.com/o/oauth2/v2/auth").apply { parameters.apply {
                        append("client_id", client); append("redirect_uri", redirect); append("response_type", "code")
                        append("scope", GOOGLE_BACKUP_SCOPES.joinToString(" ")); append("access_type", "offline")
                        append("prompt", "consent select_account"); append("state", state)
                        append("code_challenge", challenge); append("code_challenge_method", "S256")
                    } }.buildString()
                    openBrowser(URI(url))
                    val received = withTimeoutOrNull(consentTimeoutMillis) { code.await() } ?: throw GoogleBrowserSignInIncomplete()
                    result = exchange(Parameters.build {
                        append("client_id", client); append("grant_type", "authorization_code")
                        append("code", received); append("code_verifier", verifier); append("redirect_uri", redirect)
                        cfg.text("client_secret").takeIf { it.isNotEmpty() }?.let { append("client_secret", it) }
                    })
                    saved = null // Never carry a refresh token from a previously selected account.
                } finally { server.stop(0) }
            }
            val access = result.text("access_token").also { check(it.isNotBlank()) }
            val stored = JsonObject(result + mapOf(
                "client_id" to JsonPrimitive(client),
                "refresh_token" to JsonPrimitive(result.text("refresh_token").ifBlank { saved?.text("refresh_token").orEmpty() }),
                "expiresAt" to JsonPrimitive(System.currentTimeMillis() + (result.text("expires_in").toLongOrNull() ?: 3600) * 1000)))
            atomicWrite(sessionFile, Crypt32Util.cryptProtectData(stored.toString().encodeToByteArray()))
            access
        }
    }
    override suspend fun invalidateToken(token: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val saved = session()
            if (saved?.text("access_token") == token) {
                val expired = JsonObject(saved + ("expiresAt" to JsonPrimitive(0)))
                atomicWrite(sessionFile, Crypt32Util.cryptProtectData(expired.toString().encodeToByteArray()))
            }
        }
    }
    override suspend fun signOut() = mutex.withLock {
        withContext(Dispatchers.IO) { java.nio.file.Files.deleteIfExists(sessionFile.toPath()); Unit }
    }
    override fun close() { http.close() }
}
