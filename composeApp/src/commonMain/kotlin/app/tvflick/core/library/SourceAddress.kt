package app.tvflick.core.library

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import app.tvflick.plugin.api.ADAPTIVE_SOURCE_PLUGIN_ID

/** A site configuration is not evidence that a playable, matching video exists. */
data class SourceAddress(val url: String, val displayName: String)

fun normalizeSourceAddress(input: String): SourceAddress {
    val value = input.trim()
    require(value.isNotEmpty() && value.none { it.isWhitespace() || it.isISOControl() } && '\\' !in value)
    val candidate = if ("://" in value) value else "https://$value"
    // Ktor defaults an absent authority to localhost; an empty user URL must stay invalid.
    require(candidate.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').isNotEmpty())
    val parsed = Url(candidate)
    require(parsed.protocol == URLProtocol.HTTP || parsed.protocol == URLProtocol.HTTPS)
    require(parsed.user == null && parsed.password == null && parsed.encodedQuery.isEmpty() && parsed.fragment.isEmpty())
    val host = parsed.host.lowercase().removeSuffix(".")
    require(host.isNotBlank() && host.all { it.isLetterOrDigit() || it in ".-:[]" })
    require(host == "localhost" || '.' in host || ':' in host)
    require(parsed.port in 1..65535)
    val authority = if (':' in host && !host.startsWith("[")) "[$host]" else host
    val port = if (parsed.port == parsed.protocol.defaultPort) "" else ":${parsed.port}"
    val path = parsed.encodedPath.trimEnd('/')
    return SourceAddress("${parsed.protocol.name}://$authority$port$path", host)
}

class VideoSourceSettings(private val repository: VideoSourceSiteRepository) {
    suspend fun add(input: String): VideoSourceSite {
        val address = normalizeSourceAddress(input)
        require(repository.listVideoSourceSites().none {
            runCatching { normalizeSourceAddress(it.baseUrl).url }.getOrNull() == address.url
        }) { "Source already added" }
        // One automatic entry point for all sites. Assignment is not evidence of a playable stream.
        return repository.saveVideoSourceSite(VideoSourceSiteDraft(
            id = "site:${address.url}", displayName = address.displayName,
            baseUrl = address.url, parserPluginId = ADAPTIVE_SOURCE_PLUGIN_ID,
        ))
    }

    suspend fun setEnabled(site: VideoSourceSite, enabled: Boolean) = repository.saveVideoSourceSite(
        VideoSourceSiteDraft(site.id, site.displayName, site.baseUrl, site.parserPluginId, enabled))
}
