package app.tvflick.core.backup

import kotlinx.serialization.json.*

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    Backup("tvbackup", "tvbackup", "application/octet-stream"),
    Json("JSON", "json", "application/json"),
    Csv("CSV", "csv", "text/csv"),
    Pdf("PDF", "pdf", "application/pdf"),
}

data class LibraryExportRow(val kind: String, val title: String, val originalTitle: String,
    val year: String, val tmdbId: String, val season: String, val episode: String,
    val episodeTitle: String, val watched: Boolean, val favorite: Boolean,
    val watchList: Boolean, val positionMs: String, val posterUrl: String)

fun libraryExportRows(snapshot: ByteArray): List<LibraryExportRow> {
    val tables = Json.parseToJsonElement(snapshot.decodeToString()).jsonObject.getValue("tables").jsonObject
    fun rows(name: String) = tables.getValue(name).jsonArray.map { it.jsonObject }
    val entries = rows("library_entry").associateBy { it.text("content_id") }
    val seasons = rows("show_season").associateBy { it.text("local_id") }
    val progress = rows("episode_progress").associateBy { it.text("episode_id") }
    val favorites = rows("episode_favorite").map { it.text("episode_id") }.toSet()
    val episodes = rows("episode").groupBy { seasons[it.text("season_id")]?.text("show_content_id") }
    return buildList {
        rows("content").forEach { title ->
            val entry = entries.getValue(title.text("local_id"))
            val base = LibraryExportRow(title.text("media_type"), title.text("title"), title.text("original_title"),
                title.text("release_year"), title.text("tmdb_id"), "", "", "",
                entry.text("movie_watched_at_epoch_ms").isNotEmpty(), entry.text("is_favorite") == "1",
                entry.text("is_in_watch_list") == "1", entry.text("movie_position_ms"),
                title.text("poster_path").takeIf { it.isNotEmpty() }?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty())
            add(base)
            episodes[title.text("local_id")].orEmpty().sortedWith(compareBy(
                { seasons[it.text("season_id")]?.text("season_number")?.toLongOrNull() ?: 0 },
                { it.text("episode_number").toLongOrNull() ?: 0 })).forEach { ep ->
                val saved = progress[ep.text("local_id")]
                add(base.copy(kind = "episode", season = seasons.getValue(ep.text("season_id")).text("season_number"),
                    episode = ep.text("episode_number"), episodeTitle = ep.text("name"),
                    watched = saved?.text("watched_at_epoch_ms")?.isNotEmpty() == true,
                    favorite = ep.text("local_id") in favorites, positionMs = saved?.text("position_ms").orEmpty()))
            }
        }
    }
}

fun csvLibraryExport(rows: List<LibraryExportRow>): ByteArray {
    fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
    val header = listOf("type", "title", "original_title", "year", "tmdb_id", "season", "episode",
        "episode_title", "watched", "favorite", "watch_list", "position_ms", "poster_url")
    return ("\uFEFF" + (listOf(header) + rows.map { listOf(it.kind, it.title, it.originalTitle, it.year,
        it.tmdbId, it.season, it.episode, it.episodeTitle, it.watched.toString(), it.favorite.toString(),
        it.watchList.toString(), it.positionMs, it.posterUrl) })
        .joinToString("\r\n", postfix = "\r\n") { it.joinToString(",", transform = ::cell) }).encodeToByteArray()
}

fun readableJsonExport(snapshot: ByteArray): ByteArray =
    Json { prettyPrint = true }.encodeToString(JsonElement.serializer(),
        Json.parseToJsonElement(snapshot.decodeToString())).encodeToByteArray()

internal fun JsonObject.text(key: String): String = get(key)?.let { if (it == JsonNull) "" else it.jsonPrimitive.content }.orEmpty()

expect suspend fun renderLibraryPdf(rows: List<LibraryExportRow>, font: ByteArray,
                                    images: Map<String, ByteArray>): ByteArray
