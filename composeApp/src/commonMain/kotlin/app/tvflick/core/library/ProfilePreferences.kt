package app.tvflick.core.library

enum class AppLanguage(val label: String) {
    English("English"), Ukrainian("Українська"), Russian("Русский"),
}

data class ProfilePreferences(
    val lightTheme: Boolean = false,
    val language: AppLanguage = AppLanguage.English,
    val releaseAlerts: Boolean = false,
)

enum class LibraryResetScope { Titles, ShowViews, MovieViews }

interface ProfilePreferencesRepository {
    suspend fun profilePreferences(): ProfilePreferences
    suspend fun saveProfilePreferences(preferences: ProfilePreferences)
    suspend fun resetLibrary(scopes: Set<LibraryResetScope>)
    suspend fun releaseAlertHistory(): Map<String, String>
    suspend fun saveReleaseAlertHistory(history: Map<String, String>)
}
