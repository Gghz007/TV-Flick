package app.tvflick.core.backup

import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.normalizeSourceAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

const val MAX_BACKUP_BYTES: Int = 32 * 1024 * 1024
data class BackupSummary(val titles: Int, val episodes: Int, val sources: Int, val createdAtEpochMs: Long)
interface BackupRepository {
    suspend fun exportBackup(): ByteArray
    suspend fun inspectBackup(bytes: ByteArray): BackupSummary
    suspend fun restoreBackup(bytes: ByteArray)
    suspend fun backupPreference(key: String): String?
    suspend fun saveBackupPreference(key: String, value: String)
}

/** Versioned, cross-platform user data. No SQL, tokens, stream URLs, or catalog cache is imported. */
class SqlDelightBackupRepository(private val database: TvFlickDatabase) : BackupRepository {
    private val queries = database.backupQueries
    private val json = Json { isLenient = false }
    private val schema = mapOf(
"content" to mapOf("local_id" to "L", "media_type" to "S", "tmdb_id" to "L", "title" to "S", "original_title" to "S?", "release_year" to "L?", "poster_path" to "S?", "backdrop_path" to "S?", "overview" to "S?", "runtime_minutes" to "L?", "metadata_updated_at_epoch_ms" to "L?", "created_at_epoch_ms" to "L", "updated_at_epoch_ms" to "L"),
"library_entry" to mapOf("content_id" to "L", "is_in_watch_list" to "L", "is_favorite" to "L", "movie_watched_at_epoch_ms" to "L?", "movie_position_ms" to "L?", "added_at_epoch_ms" to "L", "updated_at_epoch_ms" to "L"),
"show_season" to mapOf("local_id" to "L", "show_content_id" to "L", "tmdb_season_id" to "L?", "season_number" to "L", "name" to "S?", "air_date" to "S?", "poster_path" to "S?"),
"episode" to mapOf("local_id" to "L", "season_id" to "L", "tmdb_episode_id" to "L?", "episode_number" to "L", "name" to "S?", "overview" to "S?", "air_date" to "S?", "still_path" to "S?", "runtime_minutes" to "L?"),
"episode_progress" to mapOf("episode_id" to "L", "watched_at_epoch_ms" to "L?", "position_ms" to "L?", "updated_at_epoch_ms" to "L"),
"episode_favorite" to mapOf("episode_id" to "L"),
"video_source_site" to mapOf("site_id" to "S", "display_name" to "S", "base_url" to "S", "parser_plugin_id" to "S", "is_enabled" to "L", "created_at_epoch_ms" to "L", "updated_at_epoch_ms" to "L"),
"app_setting" to mapOf("setting_key" to "S", "setting_value" to "S", "updated_at_epoch_ms" to "L")
    ).let { tables -> tables + ("content" to (tables.getValue("content") +
        mapOf("release_date" to "S?", "alternative_titles" to "S?", "genres" to "S?"))) }

    @OptIn(ExperimentalTime::class)
    override suspend fun exportBackup(): ByteArray = withContext(Dispatchers.Default) {
        val tables = database.transactionWithResult { buildJsonObject {
put("content", JsonArray(queries.backupContent().executeAsList().map { row -> buildJsonObject {
put("local_id", JsonPrimitive(row.local_id))
put("media_type", JsonPrimitive(row.media_type))
put("tmdb_id", JsonPrimitive(row.tmdb_id))
put("title", JsonPrimitive(row.title))
put("original_title", row.original_title?.let(::JsonPrimitive) ?: JsonNull)
put("release_year", row.release_year?.let(::JsonPrimitive) ?: JsonNull)
put("poster_path", row.poster_path?.let(::JsonPrimitive) ?: JsonNull)
put("backdrop_path", row.backdrop_path?.let(::JsonPrimitive) ?: JsonNull)
put("overview", row.overview?.let(::JsonPrimitive) ?: JsonNull)
put("runtime_minutes", row.runtime_minutes?.let(::JsonPrimitive) ?: JsonNull)
put("metadata_updated_at_epoch_ms", row.metadata_updated_at_epoch_ms?.let(::JsonPrimitive) ?: JsonNull)
put("created_at_epoch_ms", JsonPrimitive(row.created_at_epoch_ms))
put("updated_at_epoch_ms", JsonPrimitive(row.updated_at_epoch_ms))
put("release_date", row.release_date?.let(::JsonPrimitive) ?: JsonNull)
put("alternative_titles", row.alternative_titles?.let(::JsonPrimitive) ?: JsonNull)
put("genres", row.genres?.let(::JsonPrimitive) ?: JsonNull)
} }))
put("library_entry", JsonArray(queries.backupLibraryEntry().executeAsList().map { row -> buildJsonObject {
put("content_id", JsonPrimitive(row.content_id))
put("is_in_watch_list", JsonPrimitive(row.is_in_watch_list))
put("is_favorite", JsonPrimitive(row.is_favorite))
put("movie_watched_at_epoch_ms", row.movie_watched_at_epoch_ms?.let(::JsonPrimitive) ?: JsonNull)
put("movie_position_ms", row.movie_position_ms?.let(::JsonPrimitive) ?: JsonNull)
put("added_at_epoch_ms", JsonPrimitive(row.added_at_epoch_ms))
put("updated_at_epoch_ms", JsonPrimitive(row.updated_at_epoch_ms))
} }))
put("show_season", JsonArray(queries.backupShowSeason().executeAsList().map { row -> buildJsonObject {
put("local_id", JsonPrimitive(row.local_id))
put("show_content_id", JsonPrimitive(row.show_content_id))
put("tmdb_season_id", row.tmdb_season_id?.let(::JsonPrimitive) ?: JsonNull)
put("season_number", JsonPrimitive(row.season_number))
put("name", row.name?.let(::JsonPrimitive) ?: JsonNull)
put("air_date", row.air_date?.let(::JsonPrimitive) ?: JsonNull)
put("poster_path", row.poster_path?.let(::JsonPrimitive) ?: JsonNull)
} }))
put("episode", JsonArray(queries.backupEpisode().executeAsList().map { row -> buildJsonObject {
put("local_id", JsonPrimitive(row.local_id))
put("season_id", JsonPrimitive(row.season_id))
put("tmdb_episode_id", row.tmdb_episode_id?.let(::JsonPrimitive) ?: JsonNull)
put("episode_number", JsonPrimitive(row.episode_number))
put("name", row.name?.let(::JsonPrimitive) ?: JsonNull)
put("overview", row.overview?.let(::JsonPrimitive) ?: JsonNull)
put("air_date", row.air_date?.let(::JsonPrimitive) ?: JsonNull)
put("still_path", row.still_path?.let(::JsonPrimitive) ?: JsonNull)
put("runtime_minutes", row.runtime_minutes?.let(::JsonPrimitive) ?: JsonNull)
} }))
put("episode_progress", JsonArray(queries.backupEpisodeProgress().executeAsList().map { row -> buildJsonObject {
put("episode_id", JsonPrimitive(row.episode_id))
put("watched_at_epoch_ms", row.watched_at_epoch_ms?.let(::JsonPrimitive) ?: JsonNull)
put("position_ms", row.position_ms?.let(::JsonPrimitive) ?: JsonNull)
put("updated_at_epoch_ms", JsonPrimitive(row.updated_at_epoch_ms))
} }))
put("episode_favorite", JsonArray(queries.backupEpisodeFavorite().executeAsList().map { row -> buildJsonObject {
put("episode_id", JsonPrimitive(row))
} }))
put("video_source_site", JsonArray(queries.backupVideoSourceSite().executeAsList().map { row -> buildJsonObject {
put("site_id", JsonPrimitive(row.site_id))
put("display_name", JsonPrimitive(row.display_name))
put("base_url", JsonPrimitive(row.base_url))
put("parser_plugin_id", JsonPrimitive(row.parser_plugin_id))
put("is_enabled", JsonPrimitive(row.is_enabled))
put("created_at_epoch_ms", JsonPrimitive(row.created_at_epoch_ms))
put("updated_at_epoch_ms", JsonPrimitive(row.updated_at_epoch_ms))
} }))
put("app_setting", JsonArray(queries.backupAppSetting().executeAsList().map { row -> buildJsonObject {
put("setting_key", JsonPrimitive(row.setting_key))
put("setting_value", JsonPrimitive(row.setting_value))
put("updated_at_epoch_ms", JsonPrimitive(row.updated_at_epoch_ms))
} }))
        } }
        buildJsonObject {
            put("format", "TVFlick_Backup")
            put("version", 2)
            put("createdAtEpochMs", Clock.System.now().toEpochMilliseconds())
            put("tables", tables)
        }.toString().encodeToByteArray().also { require(it.size <= MAX_BACKUP_BYTES) { "Backup is too large" } }
    }

    override suspend fun inspectBackup(bytes: ByteArray): BackupSummary = withContext(Dispatchers.Default) {
        val (root, tables) = parse(bytes)
        BackupSummary(tables.getValue("library_entry").size, tables.getValue("episode").size,
            tables.getValue("video_source_site").size, root.getValue("createdAtEpochMs").jsonPrimitive.long)
    }

    override suspend fun restoreBackup(bytes: ByteArray) = withContext(Dispatchers.Default) {
        val (_, tables) = parse(bytes) // Validate every table before touching current data.
        database.transaction {
queries.clearBackupAppSetting()
queries.clearBackupVideoSourceSite()
queries.clearBackupEpisodeFavorite()
queries.clearBackupEpisodeProgress()
queries.clearBackupEpisode()
queries.clearBackupShowSeason()
queries.clearBackupLibraryEntry()
queries.clearBackupContent()
tables.getValue("content").forEach { row -> queries.restoreContent(
row.longValue("local_id")!!,
row.stringValue("media_type")!!,
row.longValue("tmdb_id")!!,
row.stringValue("title")!!,
row.stringValue("original_title"),
row.longValue("release_year"),
row.stringValue("poster_path"),
row.stringValue("backdrop_path"),
row.stringValue("overview"),
row.longValue("runtime_minutes"),
row.longValue("metadata_updated_at_epoch_ms"),
row.longValue("created_at_epoch_ms")!!,
row.longValue("updated_at_epoch_ms")!!,
row.stringValue("release_date"),
row.stringValue("alternative_titles"),
row.stringValue("genres")) }
tables.getValue("library_entry").forEach { row -> queries.restoreLibraryEntry(
row.longValue("content_id")!!,
row.longValue("is_in_watch_list")!!,
row.longValue("is_favorite")!!,
row.longValue("movie_watched_at_epoch_ms"),
row.longValue("movie_position_ms"),
row.longValue("added_at_epoch_ms")!!,
row.longValue("updated_at_epoch_ms")!!) }
tables.getValue("show_season").forEach { row -> queries.restoreShowSeason(
row.longValue("local_id")!!,
row.longValue("show_content_id")!!,
row.longValue("tmdb_season_id"),
row.longValue("season_number")!!,
row.stringValue("name"),
row.stringValue("air_date"),
row.stringValue("poster_path")) }
tables.getValue("episode").forEach { row -> queries.restoreEpisode(
row.longValue("local_id")!!,
row.longValue("season_id")!!,
row.longValue("tmdb_episode_id"),
row.longValue("episode_number")!!,
row.stringValue("name"),
row.stringValue("overview"),
row.stringValue("air_date"),
row.stringValue("still_path"),
row.longValue("runtime_minutes")) }
tables.getValue("episode_progress").forEach { row -> queries.restoreEpisodeProgress(
row.longValue("episode_id")!!,
row.longValue("watched_at_epoch_ms"),
row.longValue("position_ms"),
row.longValue("updated_at_epoch_ms")!!) }
tables.getValue("episode_favorite").forEach { row -> queries.restoreEpisodeFavorite(
row.longValue("episode_id")!!) }
tables.getValue("video_source_site").forEach { row -> queries.restoreVideoSourceSite(
row.stringValue("site_id")!!,
row.stringValue("display_name")!!,
row.stringValue("base_url")!!,
row.stringValue("parser_plugin_id")!!,
row.longValue("is_enabled")!!,
row.longValue("created_at_epoch_ms")!!,
row.longValue("updated_at_epoch_ms")!!) }
tables.getValue("app_setting").forEach { row -> queries.restoreAppSetting(
row.stringValue("setting_key")!!,
if (row.stringValue("setting_key") == "backup.autoEnabled") "false" else row.stringValue("setting_value")!!,
row.longValue("updated_at_epoch_ms")!!) }
        }
    }

    private fun parse(bytes: ByteArray): Pair<JsonObject, Map<String, List<JsonObject>>> {
        require(bytes.size in 1..MAX_BACKUP_BYTES) { "Invalid backup size" }
        val root = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        require(root["format"]?.jsonPrimitive?.content == "TVFlick_Backup") { "Not a TV Flick backup" }
        val version = root["version"]?.jsonPrimitive?.intOrNull
        require(version == 1 || version == 2) { "Unsupported backup version" }
        require((root["createdAtEpochMs"]?.jsonPrimitive?.longOrNull ?: -1) >= 0)
        val raw = root.getValue("tables").jsonObject
        require(raw.keys == schema.keys) { "Incomplete backup tables" }
        val tables = raw.mapValues { (table, data) ->
            require(data.jsonArray.size <= 200_000) { "Too many backup rows" }
            val columns = schema.getValue(table)
            data.jsonArray.map { element ->
                val original = element.jsonObject
                val addedFields = setOf("release_date", "alternative_titles", "genres")
                if (version == 1 && table == "content") require(original.keys == columns.keys - addedFields) { "Invalid v1 backup columns" }
                val row = if (version == 1 && table == "content") JsonObject(original + addedFields.associateWith { JsonNull }) else original
                require(row.keys == columns.keys) { "Invalid backup columns" }
                columns.forEach { (column, type) ->
                    val value = row.getValue(column)
                    if (value == JsonNull) require(type.endsWith("?")) { "Missing required value" }
                    else {
                        require(value is JsonPrimitive)
                        if (type.startsWith("L")) require(!value.isString && value.longOrNull != null)
                        else require(value.isString && value.content.length <= 1_000_000)
                    }
                }
                row
            }
        }
        fun ids(table: String, key: String) = tables.getValue(table).map { it.getValue(key) }.toSet()
        val primaryKeys = mapOf("content" to "local_id", "library_entry" to "content_id",
            "show_season" to "local_id", "episode" to "local_id", "episode_progress" to "episode_id",
            "episode_favorite" to "episode_id", "video_source_site" to "site_id", "app_setting" to "setting_key")
        primaryKeys.forEach { (table, key) ->
            require(ids(table, key).size == tables.getValue(table).size) { "Duplicate backup IDs" }
            require(tables.getValue(table).all { row ->
                if (key in setOf("site_id", "setting_key")) !row.stringValue(key).isNullOrBlank()
                else (row.longValue(key) ?: 0) > 0
            }) { "Invalid backup identity" }
        }
        fun unique(table: String, vararg columns: String) {
            val rows = tables.getValue(table)
            require(rows.map { row -> columns.map { row.getValue(it) } }.toSet().size == rows.size) {
                "Duplicate backup content"
            }
        }
        unique("content", "media_type", "tmdb_id")
        unique("show_season", "show_content_id", "season_number")
        unique("episode", "season_id", "episode_number")
        val sourceAddresses = tables.getValue("video_source_site").map {
            require(!it.stringValue("display_name").isNullOrBlank()) { "Missing source name" }
            normalizeSourceAddress(it.stringValue("base_url")!!).url
        }
        require(sourceAddresses.distinct().size == sourceAddresses.size) { "Duplicate source address" }
        tables.values.flatten().forEach { row ->
            row.forEach { (key, value) ->
                if (key in setOf("is_favorite", "is_in_watch_list", "is_enabled"))
                    require(value.jsonPrimitive.long in 0..1)
                if (value != JsonNull && (key.endsWith("_epoch_ms") || key.endsWith("position_ms") ||
                    key == "season_number" || key == "episode_number" || key == "runtime_minutes"))
                    require(value.jsonPrimitive.long >= 0)
                if (value != JsonNull && key in setOf("tmdb_id", "tmdb_season_id", "tmdb_episode_id"))
                    require(value.jsonPrimitive.long > 0) { "Invalid TMDB identity" }
                if (key == "season_number") require(value.jsonPrimitive.long in 0..Int.MAX_VALUE.toLong())
                if (key == "episode_number") require(value.jsonPrimitive.long in 1..Int.MAX_VALUE.toLong())
            }
        }
        val content = ids("content", "local_id")
        tables.getValue("content").forEach { row ->
            require(!row.stringValue("title").isNullOrBlank()) { "Missing title" }
            listOf("alternative_titles", "genres").forEach { key ->
                row.stringValue(key)?.let { encoded ->
                    val names = json.parseToJsonElement(encoded).jsonArray
                    require(names.size <= 1000 && names.all { it is JsonPrimitive && it.isString && it.content.length <= 2000 }) {
                        "Invalid metadata names"
                    }
                }
            }
        }
        require(ids("library_entry", "content_id") == content)
        require(tables.getValue("content").all { it.stringValue("media_type") in setOf("movie", "tv") })
        val shows = tables.getValue("content").filter { it.stringValue("media_type") == "tv" }.map { it.getValue("local_id") }.toSet()
        require(tables.getValue("show_season").all { it.getValue("show_content_id") in shows })
        val seasons = ids("show_season", "local_id")
        require(tables.getValue("episode").all { it.getValue("season_id") in seasons })
        val episodes = ids("episode", "local_id")
        require(tables.getValue("episode_progress").all { it.getValue("episode_id") in episodes })
        require(tables.getValue("episode_favorite").all { it.getValue("episode_id") in episodes })
        val sources = ids("video_source_site", "site_id").map { it.jsonPrimitive.content }.toSet()
        require(tables.getValue("app_setting").all {
            val key = it.stringValue("setting_key")!!
            key.startsWith("backup.") || (key.startsWith("source-adblock:") && key.removePrefix("source-adblock:") in sources) ||
                (key == "playback:primary-action" && it.stringValue("setting_value") in setOf("play", "mark"))
        })
        return root to tables
    }

    override suspend fun backupPreference(key: String): String? = withContext(Dispatchers.Default) {
        require(key.startsWith("backup."))
        queries.backupPreference(key).executeAsOneOrNull()
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun saveBackupPreference(key: String, value: String) = withContext(Dispatchers.Default) {
        require(key.startsWith("backup."))
        database.libraryQueries.saveSourceAdblockSetting(key, value, Clock.System.now().toEpochMilliseconds())
        Unit
    }
}

private fun JsonObject.longValue(key: String): Long? = getValue(key).let { if (it == JsonNull) null else it.jsonPrimitive.long }
private fun JsonObject.stringValue(key: String): String? = getValue(key).let { if (it == JsonNull) null else it.jsonPrimitive.content }
