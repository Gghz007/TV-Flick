package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class HlsChoice(val url: String, val bandwidth: Long, val height: Int?)
internal expect fun resolveHlsReference(base: String, reference: String): String

/** Only independent muxed renditions can become direct playback/download URLs. */
internal fun muxedHlsChoices(text: String, base: String): List<HlsChoice>? {
    val lines = text.trimStart('\uFEFF').lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.firstOrNull() != "#EXTM3U") return null
    if (lines.any { it.startsWith("#EXT-X-MEDIA:") || it.startsWith("#EXT-X-DEFINE:") ||
            it.startsWith("#EXT-X-SESSION-KEY:") || it.startsWith("#EXTINF:") }) return null
    val result = mutableListOf<HlsChoice>()
    var pending: Map<String, String>? = null
    for (line in lines.drop(1)) {
        if (line.startsWith("#EXT-X-STREAM-INF:")) {
            if (pending != null) return null
            pending = hlsAttributes(line.substringAfter(':')) ?: return null
            if (pending.keys.any { it in setOf("AUDIO", "VIDEO", "SUBTITLES") }) return null
            if (pending["CLOSED-CAPTIONS"]?.let { it != "NONE" } == true) return null
        } else if (!line.startsWith('#')) {
            val attributes = pending ?: return null
            val bandwidth = attributes["BANDWIDTH"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val resolution = attributes["RESOLUTION"]
            val dimensions = resolution?.split('x')?.map { it.toIntOrNull() }
            if (resolution != null && (dimensions?.size != 2 || dimensions.any { it == null || it <= 0 })) return null
            val url = runCatching { resolveHlsReference(base, line) }.getOrNull()?.takeIf(::webUrl) ?: return null
            if ("{$" in url) return null
            result += HlsChoice(url, bandwidth, dimensions?.get(1))
            if (result.size > 64) return null
            pending = null
        }
    }
    return result.takeIf { it.isNotEmpty() && pending == null }
}

private fun hlsAttributes(text: String): Map<String, String>? {
    if (text.endsWith(',')) return null
    val pattern = Regex("([A-Z0-9-]+)=(\"[^\"\\r\\n]*\"|[^,\\r\\n]+)(?:,|$)")
    val values = linkedMapOf<String, String>()
    var offset = 0
    for (match in pattern.findAll(text)) {
        if (match.range.first != offset || values.containsKey(match.groupValues[1])) return null
        values[match.groupValues[1]] = match.groupValues[2].removeSurrounding("\"")
        offset = match.range.last + 1
    }
    return values.takeIf { offset == text.length }
}

internal fun hlsHeaders(from: String, to: String, headers: Map<String, String>): Map<String, String> =
    if (sameSite(from, to)) headers else headers.filterKeys { !it.equals("Cookie", true) && !it.equals("Authorization", true) }

/** Enriches verified content only; never performs a second identity search or writes watch state. */
class HlsCatalogResolver(private val client: HttpClient = HttpClient()) : AutoCloseable {
    private val requests = Semaphore(3)
    private val http = client.config {
        followRedirects = false; expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 8_000; socketTimeoutMillis = 10_000 }
    }
    override fun close() { http.close(); client.close() }

    suspend fun expand(source: ResolvedVideoSource, request: VideoRequest, context: SourceRequestContext): ResolvedVideoSource {
        val originals = source.variants.ifEmpty { listOf(ResolvedStreamVariant(source.url, source.kind, "", "", source.headers)) }
        if (originals.none { it.kind == StreamKind.Hls } || originals.size > 64) return source
        val variants = mutableListOf<ResolvedStreamVariant>()
        val labels = source.qualityOptions.associate { it.id to it.label }.toMutableMap()
        // Catalog enrichment has a bounded latency; a slow CDN must not prevent
        // playback of the original source. Parent cancellation still propagates.
        val documents = withTimeoutOrNull(8_000) { coroutineScope {
            originals.filter { it.kind == StreamKind.Hls }.distinctBy { it.url to it.headers }.map { variant ->
                async { (variant.url to variant.headers) to requests.withPermit { read(variant, request, context) } }
            }.awaitAll().toMap()
        } } ?: return source
        var selected: ResolvedStreamVariant? = null
        var expanded = false
        for ((parentIndex, original) in originals.withIndex()) {
            val document = if (original.kind == StreamKind.Hls) documents[original.url to original.headers] else null
            val choices = document?.let { muxedHlsChoices(it.text, it.url) }
            val children = if (choices == null) listOf(original) else {
                expanded = true
                val manifest = requireNotNull(document)
                choices.mapIndexed { index, choice ->
                    val qualityId = "hls:$parentIndex:$index"
                    val bitrate = if (choice.bandwidth >= 1000) "${choice.bandwidth / 1000} kbps" else "${choice.bandwidth} bps"
                    labels[qualityId] = choice.height?.let { height ->
                        "${height}p" + if (choices.count { it.height == height } > 1) " · $bitrate" else ""
                    } ?: bitrate
                    val headers = hlsHeaders(manifest.url, choice.url, manifest.headers)
                    checkAllowed(choice.url, headers, request, context, VideoResourceKind.MediaManifest)
                    original.copy(url = choice.url, qualityId = qualityId, headers = headers)
                }
            }
            if (selected == null && original.url == source.url && original.headers == source.headers) selected = children.first()
            variants += children
        }
        if (!expanded) return source
        val initial = selected ?: variants.first()
        return source.copy(url = initial.url, kind = initial.kind, headers = initial.headers, variants = variants,
            qualityOptions = variants.map { it.qualityId }.filter(String::isNotBlank).distinct().map { PlaybackOption(it, labels[it] ?: it) })
    }

    private data class Manifest(val url: String, val headers: Map<String, String>, val text: String)
    private fun checkAllowed(url: String, headers: Map<String, String>, request: VideoRequest,
                             context: SourceRequestContext, kind: VideoResourceKind) {
        if (!webUrl(url)) throw SourceFailure(SourceFailureReason.AccessDenied)
        if (context.decide(VideoNetworkRequest(url, request.sourceSiteId, kind, headers)) is NetworkRequestDecision.Block)
            throw SourceFailure(SourceFailureReason.RequestBlocked)
    }
    private suspend fun read(variant: ResolvedStreamVariant, request: VideoRequest, context: SourceRequestContext): Manifest? {
        var url = variant.url
        var headers = variant.headers
        try {
            repeat(4) {
                // A CDN redirect still belongs to the primary manifest request.
                // Preserve its exemption from ad rules; URL validation and credential
                // stripping still apply independently at every hop.
                checkAllowed(url, headers, request, context, VideoResourceKind.MediaManifest)
                var next: String? = null
                val text = http.prepareGet(url) { headers.forEach { (key, value) -> header(key, value) } }.execute { response ->
                    when (response.status.value) {
                        301, 302, 303, 307, 308 -> {
                            next = resolveHlsReference(url, requireNotNull(response.headers[HttpHeaders.Location]))
                            ""
                        }
                        200 -> response.readBounded(1024 * 1024)
                        else -> null
                    }
                }
                if (next == null) return text?.let { Manifest(url, headers, it) }
                headers = hlsHeaders(url, next!!, headers)
                url = next!!
            }
        } catch (e: CancellationException) { throw e }
        catch (e: SourceFailure) { if (e.reason == SourceFailureReason.RequestBlocked || e.reason == SourceFailureReason.AccessDenied) throw e }
        catch (_: Exception) { /* Optional catalog discovery must not discard the original playable stream. */ }
        return null
    }
}
