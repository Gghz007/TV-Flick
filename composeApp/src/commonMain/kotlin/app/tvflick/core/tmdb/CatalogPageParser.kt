package app.tvflick.core.tmdb

import kotlinx.serialization.json.*

internal fun parseCatalogPage(body: String, mediaType: String): TmdbCatalogPage {
    val root = Json.parseToJsonElement(body).jsonObject
    val items = root["results"]?.jsonArray.orEmpty().mapNotNull { raw ->
        val item = raw.jsonObject
        val returnedType = item["media_type"]?.jsonPrimitive?.contentOrNull
        if (returnedType != null && returnedType != mediaType) return@mapNotNull null
        val title = item[if (mediaType == "movie") "title" else "name"]?.jsonPrimitive?.contentOrNull
            ?: return@mapNotNull null
        TmdbSearchResult(
            id = item["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
            mediaType = mediaType,
            title = title,
            subtitle = item[if (mediaType == "movie") "release_date" else "first_air_date"]
                ?.jsonPrimitive?.contentOrNull?.take(4).orEmpty(),
            posterPath = item["poster_path"]?.jsonPrimitive?.contentOrNull,
            backdropPath = item["backdrop_path"]?.jsonPrimitive?.contentOrNull,
            overview = item["overview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            releaseDate = item[if (mediaType == "movie") "release_date" else "first_air_date"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.length == 10 },
        )
    }
    return TmdbCatalogPage(items, root["page"]?.jsonPrimitive?.intOrNull ?: 1,
        root["total_pages"]?.jsonPrimitive?.intOrNull ?: 1)
}
