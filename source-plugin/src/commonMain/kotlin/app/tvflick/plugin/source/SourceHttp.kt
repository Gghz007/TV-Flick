package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException

data class SourceDocument(val text: String, val url: String)

interface SourceHttp {
    suspend fun postJson(url: String, referer: String, request: VideoRequest, context: SourceRequestContext,
        json: String): String = throw SourceFailure(SourceFailureReason.UnsupportedFormat)
    suspend fun read(url: String, referer: String, request: VideoRequest, context: SourceRequestContext,
        form: Map<String, String>? = null): String
    suspend fun readDocument(url: String, referer: String, request: VideoRequest, context: SourceRequestContext,
        form: Map<String, String>? = null): SourceDocument = SourceDocument(read(url, referer, request, context, form), url)
}

/** One cookie jar per source adapter; no persistent browser/account cookies are imported. */
open class SiteHttp(client: HttpClient = HttpClient(), private val diagnostic: (String) -> Unit = {},
    private val permits: (String, VideoRequest) -> Boolean = { url, request -> sameSite(url, request.sourceSiteBaseUrl) },
) : SourceHttp, AutoCloseable {
    private val baseClient = client
    private val http = client.config {
        followRedirects = false
        expectSuccess = false
        install(HttpCookies)
        install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 15_000 }
    }
    override fun close() { http.close(); baseClient.close() }

    override suspend fun read(url: String, referer: String, request: VideoRequest, context: SourceRequestContext,
        form: Map<String, String>?): String = readDocument(url, referer, request, context, form).text

    override suspend fun readDocument(url: String, referer: String, request: VideoRequest, context: SourceRequestContext,
        form: Map<String, String>?): SourceDocument = execute(url, referer, request, context, form, null)

    override suspend fun postJson(url: String, referer: String, request: VideoRequest, context: SourceRequestContext,
        json: String): String = execute(url, referer, request, context, null, json).text

    private suspend fun execute(url: String, referer: String, request: VideoRequest, context: SourceRequestContext,
        form: Map<String, String>?, json: String?): SourceDocument {
        var target = url
        var currentForm = form
        var currentJson = json
        try {
            repeat(4) { redirect ->
                if (!permits(target, request)) throw SourceFailure(SourceFailureReason.AccessDenied)
                val stage = when {
                    "suggestions" in Url(target).encodedPath -> "search"
                    "player-data" in Url(target).encodedPath -> "player-data"
                    Url(target).encodedPath.endsWith(".html") -> "detail"
                    Url(target).encodedPath == "/" -> "guest-session"
                    else -> "playlist"
                }
                diagnostic("request=$stage redirect=$redirect")
                val headers = mapOf("Referer" to referer, "X-Requested-With" to "XMLHttpRequest")
                if (context.decide(VideoNetworkRequest(target, request.sourceSiteId,
                        if (redirect == 0) VideoResourceKind.Api else VideoResourceKind.Redirect, headers)) is NetworkRequestDecision.Block)
                    throw SourceFailure(SourceFailureReason.RequestBlocked)
                var next: String? = null
                val text = http.prepareRequest(target) {
                    headers.forEach { (key, value) -> header(key, value) }
                    currentForm?.let { fields ->
                        method = HttpMethod.Post
                        setBody(FormDataContent(Parameters.build { fields.forEach { (k,v) -> append(k,v) } }))
                    }
                    currentJson?.let { body -> method = HttpMethod.Post; contentType(ContentType.Application.Json); setBody(body) }
                }.execute { response ->
                    diagnostic("response=$stage status=${response.status.value}")
                    when (response.status.value) {
                        301, 302, 303, 307, 308 -> {
                            next = response.headers[HttpHeaders.Location]?.let { location -> resolveHlsReference(target, location) }
                                ?: throw SourceFailure(SourceFailureReason.Unavailable)
                            // Browser-compatible POST -> GET; 307/308 preserve the original body.
                            if (response.status.value in setOf(301, 302, 303)) { currentForm = null; currentJson = null }
                            ""
                        }
                        401, 403 -> throw SourceFailure(SourceFailureReason.AccessDenied)
                        404 -> throw SourceFailure(SourceFailureReason.NotFound)
                        !in 200..299 -> throw SourceFailure(SourceFailureReason.Unavailable)
                        else -> response.readBounded()
                    }
                }
                if (next == null) return SourceDocument(text, target)
                target = next
            }
            throw SourceFailure(SourceFailureReason.Unavailable)
        } catch (e: CancellationException) { throw e }
        catch (e: SourceFailure) { throw e }
        catch (e: Exception) {
            diagnostic("transport-error=${e::class.simpleName}; cause=${e.cause?.let { it::class.simpleName }}")
            throw SourceFailure(SourceFailureReason.Unavailable)
        }
    }
}

class FilmixHttp(client: HttpClient = HttpClient(), diagnostic: (String) -> Unit = {}) :
    SiteHttp(client, diagnostic, { url, _ -> isFilmixUrl(url) })

internal fun webUrl(value: String): Boolean = runCatching {
    val url = Url(value)
    url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) && url.host.isNotBlank() &&
        url.user == null && url.password == null && url.fragment.isEmpty()
}.getOrDefault(false)

internal fun sameSite(first: String, second: String): Boolean = webUrl(first) && webUrl(second) &&
    Url(first).let { a -> Url(second).let { b -> a.protocol == b.protocol && a.host == b.host && a.port == b.port } }

internal fun isFilmixUrl(value: String): Boolean = runCatching {
    val url = Url(value)
    url.protocol == URLProtocol.HTTPS && url.host == "filmix.gg" && url.port == 443 &&
        url.user == null && url.password == null && url.fragment.isEmpty()
}.getOrDefault(false)

internal suspend fun HttpResponse.readBounded(limit: Int = 4 * 1024 * 1024): String {
    if ((contentLength() ?: 0) > limit) throw SourceFailure(SourceFailureReason.UnsupportedFormat)
    val channel = bodyAsChannel()
    val chunks = mutableListOf<ByteArray>()
    var size = 0
    while (!channel.isClosedForRead) {
        val chunk = ByteArray(8192)
        val count = channel.readAvailable(chunk)
        if (count < 0) break
        if (count == 0) continue
        size += count
        if (size > limit) throw SourceFailure(SourceFailureReason.UnsupportedFormat)
        chunks += chunk.copyOf(count)
    }
    val bytes = ByteArray(size)
    var offset = 0
    for (chunk in chunks) { chunk.copyInto(bytes, offset); offset += chunk.size }
    return decodeSourceText(bytes, contentType()?.parameter("charset") ?: "UTF-8")
}

internal expect fun decodeSourceText(bytes: ByteArray, charsetName: String): String
