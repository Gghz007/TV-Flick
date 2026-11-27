package app.tvflick.core.tmdb

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

data class TmdbSearchResult(
    val id: Int,
    val mediaType: String,
    val title: String,
    val subtitle: String,
    val posterPath: String?,
    val backdropPath: String?,
    val overview: String,
    val releaseDate: String? = null,
) {
    val posterUrl: String?
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
}

data class TmdbTitleDetails(
    val id: Long,
    val mediaType: String,
    val title: String,
    val originalTitle: String?,
    val overview: String,
    val releaseDate: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val runtimeMinutes: Long?,
    val seasons: List<TmdbSeasonSummary> = emptyList(),
    val score: Double? = null,
    val status: String? = null,
    val networks: List<String> = emptyList(),
    val cast: List<TmdbCastMember> = emptyList(),
    val similar: List<TmdbSearchResult> = emptyList(),
    val trailer: YoutubeTrailer? = null,
    val alternativeTitles: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
)

data class TmdbCastMember(val name: String, val character: String, val photoPath: String?)

data class TmdbSeasonSummary(
    val id: Long?,
    val seasonNumber: Long,
    val name: String?,
    val airDate: String?,
    val posterPath: String?,
    val episodeCount: Int = 0,
)

data class TmdbSeasonDetails(
    val seasonNumber: Long,
    val name: String?,
    val episodes: List<TmdbEpisode>,
)

data class TmdbEpisode(
    val id: Long?,
    val episodeNumber: Long,
    val name: String?,
    val overview: String?,
    val airDate: String?,
    val stillPath: String?,
    val runtimeMinutes: Long?,
)

class TmdbClient(
    private val apiKey: String,
    private val httpClient: HttpClient = createTmdbHttpClient(),
) {
    suspend fun catalogPage(category: CatalogCategory, page: Int = 1, seedId: Long? = null): TmdbCatalogPage {
        require(page > 0)
        check(apiKey.isNotBlank()) { "TMDB is not configured" }
        val response = httpClient.get("https://api.themoviedb.org/3/${category.path(seedId)}") {
            parameter("api_key", apiKey)
            parameter("page", page)
        }
        if (!response.status.isSuccess()) error("TMDB catalog is temporarily unavailable (${response.status.value}).")
        return parseCatalogPage(response.body<String>(), category.mediaType)
    }

    suspend fun search(query: String): List<TmdbSearchResult> {
        if (apiKey.isBlank() || query.isBlank()) return emptyList()

        val response = httpClient.get("https://api.themoviedb.org/3/search/multi") {
            parameter("api_key", apiKey)
            parameter("query", query.trim())
            parameter("include_adult", false)
        }
        if (!response.status.isSuccess()) {
            error("TMDB search is temporarily unavailable (${response.status.value}).")
        }

        val root = Json.parseToJsonElement(response.body<String>()).jsonObject
        return root["results"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val mediaType = item["media_type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (mediaType !in setOf("movie", "tv")) return@mapNotNull null

            val title = item[if (mediaType == "movie") "title" else "name"]
                ?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val date = item[if (mediaType == "movie") "release_date" else "first_air_date"]
                ?.jsonPrimitive?.contentOrNull
                ?.take(4)
                .orEmpty()

            TmdbSearchResult(
                id = item["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                mediaType = mediaType,
                title = title,
                subtitle = date,
                posterPath = item["poster_path"]?.jsonPrimitive?.contentOrNull,
                backdropPath = item["backdrop_path"]?.jsonPrimitive?.contentOrNull,
                overview = item["overview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                releaseDate = item[if (mediaType == "movie") "release_date" else "first_air_date"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /** Public discovery data for the designed Search start page. */
    suspend fun trending(): List<TmdbSearchResult> {
        if (apiKey.isBlank()) return emptyList()
        val response = httpClient.get("https://api.themoviedb.org/3/trending/all/week") {
            parameter("api_key", apiKey)
        }
        if (!response.status.isSuccess()) error("TMDB is temporarily unavailable (${response.status.value}).")

        val root = Json.parseToJsonElement(response.body<String>()).jsonObject
        return root["results"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val mediaType = item["media_type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (mediaType !in setOf("movie", "tv")) return@mapNotNull null
            val title = item[if (mediaType == "movie") "title" else "name"]
                ?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val date = item[if (mediaType == "movie") "release_date" else "first_air_date"]
                ?.jsonPrimitive?.contentOrNull
                ?.take(4)
                .orEmpty()
            TmdbSearchResult(
                id = item["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                mediaType = mediaType,
                title = title,
                subtitle = date,
                posterPath = item["poster_path"]?.jsonPrimitive?.contentOrNull,
                backdropPath = item["backdrop_path"]?.jsonPrimitive?.contentOrNull,
                overview = item["overview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                releaseDate = item[if (mediaType == "movie") "release_date" else "first_air_date"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    suspend fun titleDetails(mediaType: String, id: Long): TmdbTitleDetails {
        require(mediaType in setOf("movie", "tv")) { "Unsupported TMDB media type: $mediaType" }
        val creditsKey = if (mediaType == "tv") "aggregate_credits" else "credits"
        val root = getJson("https://api.themoviedb.org/3/$mediaType/$id", "$creditsKey,similar,videos,alternative_titles")
        val titleKey = if (mediaType == "movie") "title" else "name"
        val originalTitleKey = if (mediaType == "movie") "original_title" else "original_name"
        val dateKey = if (mediaType == "movie") "release_date" else "first_air_date"
        require(root["id"]?.jsonPrimitive?.longOrNull == id &&
            !root[titleKey]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) { "TMDB returned invalid title details" }
        val runtime = if (mediaType == "movie") {
            root["runtime"]?.jsonPrimitive?.longOrNull
        } else {
            root["episode_run_time"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.longOrNull
        }
        val seasons = root["seasons"]?.jsonArray.orEmpty().mapNotNull { raw ->
            val season = raw.jsonObject
            season["season_number"]?.jsonPrimitive?.longOrNull?.let { number ->
                TmdbSeasonSummary(
                    id = season["id"]?.jsonPrimitive?.longOrNull,
                    seasonNumber = number,
                    name = season["name"]?.jsonPrimitive?.contentOrNull,
                    airDate = season["air_date"]?.jsonPrimitive?.contentOrNull,
                    posterPath = season["poster_path"]?.jsonPrimitive?.contentOrNull,
                    episodeCount = season["episode_count"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
        }
        return TmdbTitleDetails(
            id = root["id"]?.jsonPrimitive?.longOrNull ?: id,
            mediaType = mediaType,
            title = root[titleKey]?.jsonPrimitive?.contentOrNull.orEmpty(),
            originalTitle = root[originalTitleKey]?.jsonPrimitive?.contentOrNull,
            overview = root["overview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            releaseDate = root[dateKey]?.jsonPrimitive?.contentOrNull,
            posterPath = root["poster_path"]?.jsonPrimitive?.contentOrNull,
            backdropPath = root["backdrop_path"]?.jsonPrimitive?.contentOrNull,
            runtimeMinutes = runtime,
            alternativeTitles = root["alternative_titles"]?.jsonObject
                ?.get(if (mediaType == "movie") "titles" else "results")?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.contentOrNull }.filter(String::isNotBlank).distinct(),
            genres = root["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull },
            seasons = seasons,
            score = root["vote_average"]?.jsonPrimitive?.doubleOrNull,
            status = root["status"]?.jsonPrimitive?.contentOrNull,
            networks = root["networks"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull },
            cast = root[creditsKey]?.jsonObject?.get("cast")?.jsonArray.orEmpty().mapNotNull {
                val person = it.jsonObject
                val name = person["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val roles = person["roles"]?.jsonArray.orEmpty().mapNotNull { role ->
                    role.jsonObject["character"]?.jsonPrimitive?.contentOrNull
                }.filter(String::isNotBlank).distinct().joinToString(" / ")
                TmdbCastMember(name, roles.ifBlank { person["character"]?.jsonPrimitive?.contentOrNull.orEmpty() },
                    person["profile_path"]?.jsonPrimitive?.contentOrNull)
            },
            similar = root["similar"]?.jsonObject?.get("results")?.jsonArray.orEmpty().mapNotNull {
                val item = it.jsonObject
                val itemId = item["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                TmdbSearchResult(itemId, mediaType, item[titleKey]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    item[dateKey]?.jsonPrimitive?.contentOrNull.orEmpty().take(4),
                    item["poster_path"]?.jsonPrimitive?.contentOrNull, item["backdrop_path"]?.jsonPrimitive?.contentOrNull,
                    item["overview"]?.jsonPrimitive?.contentOrNull.orEmpty())
            },
            trailer = selectYoutubeTrailer(root["videos"]?.jsonObject?.get("results") as? JsonArray),
        )
    }

    suspend fun seasonDetails(showTmdbId: Long, seasonNumber: Long): TmdbSeasonDetails {
        val root = getJson("https://api.themoviedb.org/3/tv/$showTmdbId/season/$seasonNumber")
        require(root["season_number"]?.jsonPrimitive?.longOrNull == seasonNumber) { "TMDB returned a different season" }
        return TmdbSeasonDetails(
            seasonNumber = root["season_number"]?.jsonPrimitive?.longOrNull ?: seasonNumber,
            name = root["name"]?.jsonPrimitive?.contentOrNull,
            episodes = root["episodes"]?.jsonArray.orEmpty().mapNotNull { raw ->
                val episode = raw.jsonObject
                episode["episode_number"]?.jsonPrimitive?.longOrNull?.let { number ->
                    TmdbEpisode(
                        id = episode["id"]?.jsonPrimitive?.longOrNull,
                        episodeNumber = number,
                        name = episode["name"]?.jsonPrimitive?.contentOrNull,
                        overview = episode["overview"]?.jsonPrimitive?.contentOrNull,
                        airDate = episode["air_date"]?.jsonPrimitive?.contentOrNull,
                        stillPath = episode["still_path"]?.jsonPrimitive?.contentOrNull,
                        runtimeMinutes = episode["runtime"]?.jsonPrimitive?.longOrNull,
                    )
                }
            },
        )
    }

    private suspend fun getJson(url: String, append: String? = null) = httpClient.get(url) {
        parameter("api_key", apiKey)
        if (append != null) parameter("append_to_response", append)
    }.let { response ->
        if (!response.status.isSuccess()) error("TMDB is temporarily unavailable (${response.status.value}).")
        Json.parseToJsonElement(response.body<String>()).jsonObject
    }
}

expect fun createTmdbHttpClient(): HttpClient
