package app.tvflick

import app.tvflick.core.backup.DesktopGoogleAuthorization
import app.tvflick.core.backup.CloudBackup
import app.tvflick.core.backup.GoogleBackupStore
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import java.io.File

/** Explicit Gradle task, never part of automated tests. Never opens the user's library. */
fun main(args: Array<String>) = runBlocking {
    val link = File(args.first())
    require(args.size in 1..2)
    val option = args.getOrNull(1)
    val roundtrip = option == "--roundtrip"
    val androidBackup = option?.takeIf { it.startsWith("--android-backup=") }?.removePrefix("--android-backup=")
    require(option == null || roundtrip || androidBackup != null)
    require(androidBackup == null || androidBackup.matches(Regex("TV-Flick-ANDROID-TEST-[0-9a-f-]{36}\\.tvbackup")))
    val auth = DesktopGoogleAuthorization(openBrowser = { uri ->
        link.parentFile.mkdirs()
        link.writeText(uri.toASCIIString())
        println("GOOGLE_CONSENT_READY")
    }, consentTimeoutMillis = 600_000)
    val store = GoogleBackupStore(auth)
    try {
        check(store.configured) { "OAuth configuration missing" }
        store.profile(interactive = !roundtrip && androidBackup == null)
        println("GOOGLE_PROFILE_OK")
        store.list()
        println("GOOGLE_DRIVE_LIST_OK")
        if (roundtrip) {
            // Exercise a real refresh, not just a still-valid cached access token.
            auth.invalidateToken(auth.token(false))
            store.profile(false)
            println("GOOGLE_REFRESH_OK")
            val sourceDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                TvFlickDatabase.Schema.create(sourceDriver)
                TvFlickDatabase.Schema.create(targetDriver)
                val source = SqlDelightLibraryRepository(TvFlickDatabase(sourceDriver))
                val target = SqlDelightLibraryRepository(TvFlickDatabase(targetDriver))
                source.save(LibraryContentDraft(MediaType.Movie, 1, "TV Flick synthetic backup verification"), true)
                val original = source.exportBackup()
                val name = "TV-Flick-TEST-${java.util.UUID.randomUUID()}.tvbackup"
                store.upload(original, name)
                val uploaded = store.list().single { it.name == name }
                try {
                    val downloaded = store.download(uploaded)
                    check(original.contentEquals(downloaded))
                    target.restoreBackup(downloaded)
                    fun tables(bytes: ByteArray) = Json.parseToJsonElement(bytes.decodeToString()).jsonObject["tables"]
                    check(tables(original) == tables(target.exportBackup()))
                    println("GOOGLE_BACKUP_ROUNDTRIP_OK")
                } finally {
                    // Delete only this run's UUID-labelled synthetic file, never an existing user backup.
                    deleteSyntheticBackup(auth, uploaded)
                }
            } finally { sourceDriver.close(); targetDriver.close() }
        }
        if (androidBackup != null) {
            val uploaded = store.list().single { it.name == androidBackup }
            val bytes = store.download(uploaded)
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                TvFlickDatabase.Schema.create(driver)
                val library = SqlDelightLibraryRepository(TvFlickDatabase(driver))
                library.restoreBackup(bytes)
                val restored = Json.parseToJsonElement(library.exportBackup().decodeToString()).jsonObject
                    .getValue("tables").jsonObject.getValue("content").jsonArray.single().jsonObject
                check(restored.getValue("title").jsonPrimitive.content == "TV Flick Android synthetic verification")
                println("GOOGLE_ANDROID_TO_WINDOWS_RESTORE_OK")
            } finally { driver.close() }
            deleteSyntheticBackup(auth, uploaded)
        }
        // Verify that the saved session can be used silently by a new authorization instance.
        val reopened = DesktopGoogleAuthorization()
        val silentStore = GoogleBackupStore(reopened)
        try {
            silentStore.profile(interactive = false)
            println("GOOGLE_SAVED_SESSION_OK")
        } finally { silentStore.close(); reopened.close() }
    } catch (error: Exception) {
        // Never log server responses, tokens, user profile or backup filenames.
        println("GOOGLE_VERIFICATION_FAILED: ${error.javaClass.simpleName}")
        throw IllegalStateException("Google live verification failed; no private response logged")
    } finally {
        link.delete()
        store.close()
        auth.close()
    }
}

private suspend fun deleteSyntheticBackup(auth: DesktopGoogleAuthorization, backup: CloudBackup) {
    require(backup.name.matches(Regex("TV-Flick-(ANDROID-)?TEST-[0-9a-f-]{36}\\.tvbackup")))
    require(backup.id.matches(Regex("[A-Za-z0-9_-]+")))
    HttpClient { followRedirects = false }.use { cleanup ->
        val response = cleanup.delete("https://www.googleapis.com/drive/v3/files/${backup.id}") {
            bearerAuth(auth.token(false))
        }
        check(response.status.isSuccess()) { "Synthetic backup cleanup failed" }
    }
    println("GOOGLE_TEST_BACKUP_REMOVED")
}
