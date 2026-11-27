package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Reads declared relationships, not arbitrary URLs from scripts/ads/related posters. */
internal actual fun parseWebDocument(html: String, pageUrl: String): WebDocument {
    val document = Jsoup.parse(html, pageUrl)
    fun absolute(value: String?): String? = value?.takeIf(String::isNotBlank)?.let {
        document.createElement("a").attr("href", it).absUrl("href").takeIf(::webUrl)
    }
    val objects = mutableListOf<JsonObject>()
    fun collect(value: JsonElement) {
        when (value) {
            is JsonArray -> value.forEach(::collect)
            is JsonObject -> {
                objects += value
                value["@graph"]?.let(::collect)
                value["mainEntity"]?.let(::collect)
            }
            else -> Unit
        }
    }
    document.select("script[type=application/ld+json]").take(30).forEach {
        runCatching { Json.parseToJsonElement(it.data()) }.getOrNull()?.let(::collect)
    }
    val byId = objects.mapNotNull { it.text("@id")?.let { id -> id to it } }.toMap()
    fun objectOf(value: JsonElement?): JsonObject? {
        val obj = value as? JsonObject ?: return null
        return obj.text("@id")?.let { byId[it] } ?: obj
    }
    fun encodings(node: JsonObject, ownerId: String?): List<JsonObject> {
        val declared = node["encoding"]
        val entries = when (declared) { is JsonArray -> declared; is JsonObject -> listOf(declared); else -> emptyList() }
        val linked = if (ownerId == null) emptyList() else objects.filter {
            it.hasType("VideoObject") && (it["encodesCreativeWork"] as? JsonObject)?.text("@id") == ownerId
        }
        return (entries.mapNotNull(::objectOf) + linked).distinct()
    }
    fun variants(node: JsonObject, candidateUrl: String): List<ResolvedStreamVariant> = encodings(node, node.text("@id")).mapNotNull { video ->
        // 'video' and 'trailer' are deliberately not treated as encoding/full content.
        val url = absolute(video.text("contentUrl")) ?: return@mapNotNull null
        streamVariant(url, video.text("inLanguage").orEmpty(), video.text("videoQuality").orEmpty(), candidateUrl)
    }
    val media = mutableListOf<WebMedia>()
    for (node in objects) {
        val type = when { node.hasType("Movie") -> MatchedMediaType.Movie; node.hasType("TVEpisode") -> MatchedMediaType.TvEpisode; else -> continue }
        val series = objectOf(node["partOfSeries"])
        val season = objectOf(node["partOfSeason"])
        val identity = if (type == MatchedMediaType.TvEpisode) series ?: continue else node
        val title = identity.text("name")?.takeIf(String::isNotBlank) ?: continue
        val year = (identity.text("datePublished") ?: identity.text("startDate") ?: identity.text("copyrightYear"))?.take(4)?.toIntOrNull()
        val url = absolute(node.text("url")) ?: pageUrl
        val candidate = ParsedMediaCandidate(title, identity.names("alternateName"), year, type,
            seasonNumber = season?.text("seasonNumber")?.toIntOrNull(), episodeNumber = node.text("episodeNumber")?.toIntOrNull(),
            pageUrl = url, tmdbId = identity.tmdbId())
        media += WebMedia(candidate, variants(node, url))
    }
    // Microdata is another standard; support HTML5 <video>/<source> explicitly marked as the encoding.
    for (scope in document.select("[itemscope][itemtype]").filter {
        it.attr("itemtype").substringAfterLast('/') in listOf("Movie", "TVEpisode") && it.parents().none { parent -> parent.hasAttr("itemscope") }
    }) {
        fun property(name: String): Element? = scope.select("[itemprop=$name]").firstOrNull { element ->
            element.parents().firstOrNull { it.hasAttr("itemscope") } == scope
        }
        fun value(name: String): String? = property(name)?.propertyValue()
        val tv = scope.attr("itemtype").endsWith("TVEpisode")
        val series = property("partOfSeries")
        val title = if (tv) series?.selectFirst("[itemprop=name]")?.propertyValue() else value("name")
        if (title.isNullOrBlank()) continue
        val year = (if (tv) series?.selectFirst("[itemprop=datePublished], [itemprop=startDate]")?.propertyValue() else value("datePublished") ?: value("copyrightYear"))?.take(4)?.toIntOrNull()
        val candidate = ParsedMediaCandidate(title, releaseYear = year, mediaType = if (tv) MatchedMediaType.TvEpisode else MatchedMediaType.Movie,
            seasonNumber = property("partOfSeason")?.selectFirst("[itemprop=seasonNumber]")?.propertyValue()?.toIntOrNull(),
            episodeNumber = value("episodeNumber")?.toIntOrNull(), pageUrl = absolute(value("url")) ?: pageUrl)
        val videos = scope.select("video[itemprop=encoding], [itemprop=encoding] video").filter { video ->
            video.parents().none { it.attr("itemprop") in listOf("trailer", "video") }
        }
        val streams = videos.flatMap { video ->
            val sources = video.select("source[src]").toList() + listOfNotNull(video.takeIf { it.hasAttr("src") })
            sources.mapNotNull { source -> absolute(source.attr("src"))?.let {
                streamVariant(it, source.attr("srclang"), source.attr("label"), candidate.pageUrl)
            } }
        }
        media += WebMedia(candidate, streams)
    }
    var search: WebSearch? = null
    val actions = objects.flatMap { obj ->
        val action = obj["potentialAction"]
        (when (action) { is JsonArray -> action.mapNotNull { it as? JsonObject }; is JsonObject -> listOf(action); else -> emptyList() }) + listOf(obj)
    }.filter { it.hasType("SearchAction") }
    for (action in actions) {
        val target = action.text("target") ?: (action["target"] as? JsonObject)?.text("urlTemplate") ?: continue
        val replaced = target.replace(Regex("\\{[^}]+}"), "TVFLICKSEARCH")
        val targetUrl = absolute(replaced)?.let(::Url) ?: continue
        val key = targetUrl.parameters.entries().firstOrNull { it.value == listOf("TVFLICKSEARCH") }?.key ?: continue
        val base = URLBuilder(targetUrl).apply { parameters.clear() }.buildString()
        search = WebSearch(base, key, targetUrl.parameters.entries().filter { it.key != key }.associate { it.key to it.value.first() })
        break
    }
    if (search == null) {
        for (form in document.select("form[role=search], form:has(input[type=search])")) {
            val method = form.attr("method").ifBlank { "get" }.lowercase()
            if (method !in setOf("get", "post")) continue
            if (method == "post" && form.attr("enctype").ifBlank { "application/x-www-form-urlencoded" }
                    .lowercase() != "application/x-www-form-urlencoded") continue
            val input = form.selectFirst("input[type=search][name]:not([disabled]), input[name=q]:not([disabled]), input[name=search]:not([disabled])") ?: continue
            if (input.attr("name").isBlank()) continue
            val action = absolute(form.attr("action").ifBlank { pageUrl }) ?: continue
            search = WebSearch(action, input.attr("name"), form.select("input[type=hidden][name]:not([disabled])")
                .associate { it.attr("name") to it.attr("value") }, post = method == "post")
            break
        }
    }
    val links = linkedSetOf<String>()
    // Known DLE lightsearch protocol, recognized by its form rather than a site hostname.
    if (search == null && document.selectFirst("form.js-lightsearch input.js-lightsearch-input") != null)
        absolute("/search/")?.let { search = WebSearch(it, "", pathQuery = true) }
    objects.filter { it.hasType("ItemList") }.forEach { list ->
        (list["itemListElement"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach {
            absolute((it["item"] as? JsonObject)?.text("url") ?: it.text("url") ?: it.text("item"))?.let(links::add)
        }
    }
    document.select("main article a[href], [role=main] article a[href], [itemprop=itemListElement] a[href], .postItem a[href]")
        .forEach { absolute(it.attr("href"))?.let(links::add) }
    val merged = media.groupBy { it.identity }.map { (identity, values) -> WebMedia(identity, values.flatMap { it.streams }.distinct()) }
    return WebDocument(merged, search, links.take(12), cinemarPage(document, objects, pageUrl))
}

private fun cinemarPage(document: org.jsoup.nodes.Document, objects: List<JsonObject>, pageUrl: String): CinemarPage? {
    val frame = document.selectFirst(".js-player-container iframe") ?: return null
    val embed = frame.absUrl(if (frame.hasAttr("data-src")) "data-src" else "src").takeIf(::isCinemarUrl) ?: return null
    val nodes = objects.filter { it.hasType("Movie") || it.hasType("TVSeries") || it.hasType("TVEpisode") }
        .filter { it.text("url")?.let { url -> document.createElement("a").attr("href", url).absUrl("href") == pageUrl } == true }
    if (nodes.size != 1) return null
    val node = nodes.single()
    if (node["isAccessibleForFree"] == JsonPrimitive(false)) throw SourceFailure(SourceFailureReason.Restricted)
    val tv = !node.hasType("Movie")
    // This site's TVEpisode schema describes a series range. Do not turn it into an exact episode.
    // Exact S/E is established only by the provider's explicit playlist IDs.
    if (tv && node.hasType("TVEpisode") && node.text("partOfSeason") == null) return null
    fun title(value: String) = value.replace(Regex("(?i)\\s*\\(\\d+(?:-\\d+)?\\s+сезон\\)\\s*$"), "").trim()
    val name = node.text("name")?.let(::title)?.takeIf(String::isNotBlank) ?: return null
    val year = (node.text("dateCreated") ?: node.text("datePublished"))?.take(4)?.toIntOrNull() ?: return null
    val alternatives = (node.names("alternateName") + node.names("alternativeHeadline")).map(::title).distinct()
    return CinemarPage(ParsedMediaCandidate(name, alternatives, year,
        if (tv) MatchedMediaType.TvEpisode else MatchedMediaType.Movie, pageUrl = pageUrl, tmdbId = node.tmdbId()), embed)
}

internal actual fun parseCinemarOptions(html: String): JsonObject {
    val entries = Jsoup.parse(html).select("script").mapNotNull { script ->
        val body = script.data().trim().removeSuffix(";").trim()
        if (!body.startsWith("Cinemar(") || !body.endsWith(')')) null else
            runCatching { Json.parseToJsonElement(body.removePrefix("Cinemar(").dropLast(1)) as? JsonObject }.getOrNull()
    }
    return entries.singleOrNull() ?: throw SourceFailure(SourceFailureReason.UnsupportedFormat)
}

private fun JsonObject.hasType(type: String): Boolean = names("@type").any { it.substringAfterLast('/') == type }
private fun JsonObject.names(key: String): List<String> = when (val value = get(key)) {
    is JsonPrimitive -> listOfNotNull(value.contentOrNull)
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    else -> emptyList()
}
private fun JsonObject.tmdbId(): Long? {
    val entries = when (val value = get("identifier")) { is JsonObject -> listOf(value); is JsonArray -> value.mapNotNull { it as? JsonObject }; else -> emptyList() }
    return entries.firstOrNull { it.text("propertyID")?.equals("TMDB", true) == true }?.text("value")?.toLongOrNull()
}
private fun Element.propertyValue(): String = when {
    hasAttr("content") -> attr("content")
    hasAttr("datetime") -> attr("datetime")
    hasAttr("href") -> attr("href")
    else -> text()
}
private fun streamVariant(url: String, audio: String, quality: String, referer: String): ResolvedStreamVariant? {
    val path = Url(url).encodedPath
    val kind = when { path.endsWith(".mp4", true) -> StreamKind.Mp4; path.endsWith(".m3u8", true) -> StreamKind.Hls; else -> return null }
    return ResolvedStreamVariant(url, kind, audio, quality, mapOf("Referer" to referer))
}
