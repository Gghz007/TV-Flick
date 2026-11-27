package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

internal data class FilmixPage(val siteId: String, val title: String, val year: Int,
    val type: MatchedMediaType, val alternatives: List<String>)
internal expect fun parseFilmixPage(html: String, url: String): FilmixPage

/** Public player-data serialization observed 2026-11-14. Never evaluates site JavaScript. */
internal object FilmixFormat {
    private val noise = listOf("2owKDUoGzsuLNEyhNx", "19n1iKBr89ubskS5zT", "IDaBt08C9Wf7lYr0eH",
        "lNjI9V5U1gMnsxt4Qr", "o9wPt0ii42GWeS7L7A")

    fun decode(value: String): String {
        val input = value.trim()
        if (!input.startsWith("#")) return input
        if (!input.startsWith("#2")) fail()
        var encoded = input.substring(2)
        noise.asReversed().forEach { encoded = encoded.replaceFirst(":<:" + Base64.encode(it.encodeToByteArray()), "") }
        return try { Base64.decode(encoded.padEnd((encoded.length + 3) / 4 * 4, '=')).decodeToString(throwOnInvalidSequence = true) }
        catch (_: Exception) { fail() }
    }

    fun json(value: String): JsonElement = try { Json.parseToJsonElement(decode(value)) }
        catch (e: SourceFailure) { throw e }
        catch (_: Exception) { fail() }

    fun episodeFile(playlist: String, season: Int, episode: Int): String? {
        val root = json(playlist) as? JsonArray ?: fail()
        if (root.size > 200) fail()
        val found = mutableListOf<String>()
        root.forEach { group ->
            val folder = group as? JsonObject ?: fail()
            val seasonNumber = Regex("(?:сезон|season)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(folder.text("title").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            if (seasonNumber != season) return@forEach
            val children = folder["folder"] as? JsonArray ?: fail()
            if (children.size > 2000) fail()
            for (child in children) {
                val row = child as? JsonObject ?: fail()
                val id = Regex("(?i)^s(\\d+)e(\\d+)$").matchEntire(row.text("id").orEmpty())
                val title = row.text("title").orEmpty()
                val fromTitle = Regex("(?:серия|episode)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull()
                val titleSeason = Regex("(?:сезон|season)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull()
                val idSeason = id?.groupValues?.get(1)?.toIntOrNull()
                val idEpisode = id?.groupValues?.get(2)?.toIntOrNull()
                if (id != null && (idSeason != seasonNumber || (fromTitle != null && fromTitle != idEpisode))) fail()
                if (titleSeason != null && titleSeason != seasonNumber) fail()
                val number = idEpisode ?: fromTitle
                if (number == episode) found += row.text("file") ?: fail()
            }
        }
        if (found.size > 1) fail()
        return found.singleOrNull()
    }

    fun variants(value: String, audio: String, headers: Map<String, String>): List<ResolvedStreamVariant> {
        val decoded = decode(value)
        val choices = if (decoded.startsWith("[")) {
            val parts = Regex("\\[([^\\]]+)\\]([^\\[]+)").findAll(decoded).toList()
            if (parts.isEmpty() || parts.joinToString("") { it.value } != decoded) fail()
            parts.map { it.groupValues[1].trim() to it.groupValues[2].trim().trimEnd(',') }
        } else listOf("" to decoded)
        if (choices.size > 40) fail()
        return choices.map { (quality, value) ->
            // Player fallback lists must be interpreted explicitly, never passed as a URL.
            if (":or:" in value || value.any(Char::isWhitespace)) fail()
            val url = runCatching { Url(value) }.getOrElse { fail() }
            if (url.protocol !in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) || url.host.isBlank() ||
                url.user != null || url.password != null) fail()
            val kind = when {
                url.encodedPath.endsWith(".mp4", true) -> StreamKind.Mp4
                url.encodedPath.endsWith(".m3u8", true) -> StreamKind.Hls
                else -> fail()
            }
            ResolvedStreamVariant(value, kind, audio, quality, headers)
        }
    }

    fun fail(): Nothing = throw SourceFailure(SourceFailureReason.UnsupportedFormat)
}

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
