package app.tvflick.core.tmdb

enum class CatalogCategory(val label: String, val mediaType: String, private val route: String) {
    RecommendedShows("Recommended Shows", "tv", "tv/%s/recommendations"),
    RecommendedMovies("Recommended Movies", "movie", "movie/%s/recommendations"),
    TrendingShows("Trending Shows", "tv", "trending/tv/week"),
    TrendingMovies("Trending Movies", "movie", "trending/movie/week"),
    PopularShows("Popular Shows", "tv", "tv/popular"),
    PopularMovies("Popular Movies", "movie", "movie/popular"),
    TopRatedShows("Top Rated Shows", "tv", "tv/top_rated"),
    TopRatedMovies("Top Rated Movies", "movie", "movie/top_rated"),
    AiringToday("Airing Today", "tv", "tv/airing_today"),
    AiringThisWeek("Airing This Week", "tv", "tv/on_the_air"),
    NowPlaying("Now Playing", "movie", "movie/now_playing"),
    UpcomingMovies("Upcoming Movies", "movie", "movie/upcoming");

    val needsSeed: Boolean get() = this == RecommendedShows || this == RecommendedMovies

    fun path(seedId: Long? = null): String {
        require(!needsSeed || (seedId != null && seedId > 0)) { "Recommendations require a library title" }
        return if (needsSeed) route.replace("%s", seedId.toString()) else route
    }
}

data class TmdbCatalogPage(val items: List<TmdbSearchResult>, val page: Int, val totalPages: Int)
