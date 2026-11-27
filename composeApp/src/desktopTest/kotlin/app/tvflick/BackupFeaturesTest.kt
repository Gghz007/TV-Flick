package app.tvflick

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.backup.*
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.*
import app.tvflick.core.tmdb.*
import app.tvflick.ui.BackupController
import app.tvflick.ui.FlickState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import tv_flick.composeapp.generated.resources.Res
import kotlin.test.*

class BackupFeaturesTest {
    private class Store : AutoCloseable {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val repo: SqlDelightLibraryRepository
        init { TvFlickDatabase.Schema.create(driver); repo = SqlDelightLibraryRepository(TvFlickDatabase(driver)) }
        override fun close() = driver.close()
    }
    private class Auth(override val configured: Boolean = true) : GoogleAuthorization {
        override suspend fun token(interactive: Boolean) = "test-token"
        override suspend fun signOut() {}
    }
    private class Files : BackupFileAccess {
        var exported: ByteArray? = null
        var mime = ""
        var name = ""
        override suspend fun exportFile(bytes: ByteArray, suggestedName: String, mimeType: String): Boolean {
            exported = bytes; mime = mimeType; name = suggestedName; return true
        }
        override suspend fun importFile(): ByteArray? = null
        override suspend fun saveRecovery(bytes: ByteArray) {}
        override suspend fun loadImageArchive(): ByteArray? = null
        override suspend fun saveImageArchive(bytes: ByteArray) {}
    }
    private suspend fun await(controller: BackupController) { withTimeout(30_000) { while (controller.busy) delay(1) } }

    @Test fun `browser sign in errors clear busy state and allow a retry`() = runBlocking {
        Store().use { store ->
            val denied = GoogleSignInDenied()
            val incomplete = GoogleBrowserSignInIncomplete()
            var issue: Exception? = denied
            val auth = object : GoogleAuthorization {
                override val configured = true
                override suspend fun token(interactive: Boolean): String {
                    check(interactive || issue == null)
                    issue?.let { throw it }
                    return "test-token"
                }
                override suspend fun signOut() {}
            }
            val cloud = GoogleBackupStore(auth, HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("/userinfo")) respond("""{"name":"Test","email":"test@example.com"}""")
                else respond("""{"files":[]}""")
            }))
            try {
                val controller = BackupController(FlickState(store.repo, null, this), Files(), this, cloud)
                controller.signInOrOut(); await(controller)
                assertEquals(denied.message, controller.message)
                assertNull(controller.account)
                issue = incomplete
                controller.signInOrOut(); await(controller)
                assertEquals(incomplete.message, controller.message)
                assertNull(controller.account)
                issue = null
                controller.signInOrOut(); await(controller)
                assertEquals("test@example.com", controller.account?.email)
            } finally { cloud.close() }
        }
    }

    @Test fun `backup format and local export work while the Google browser is open`() = runBlocking {
        Store().use { store ->
            val browserResult = CompletableDeferred<String>()
            val auth = object : GoogleAuthorization {
                override val configured = true
                override suspend fun token(interactive: Boolean) = browserResult.await()
                override suspend fun signOut() {}
            }
            val cloud = GoogleBackupStore(auth, HttpClient(MockEngine { error("Google has not finished") }))
            val files = Files()
            try {
                val controller = BackupController(FlickState(store.repo, null, this), files, this, cloud)
                controller.signInOrOut()
                controller.chooseFormat(ExportFormat.Csv)
                controller.chooseInterval(BackupInterval.Weekly)
                withTimeout(5_000) {
                    while (controller.format != ExportFormat.Csv || controller.interval != BackupInterval.Weekly) delay(1)
                }
                assertTrue(controller.busy)
                assertEquals("Csv", store.repo.backupPreference("backup.format"))
                controller.exportLocal()
                withTimeout(5_000) { while (files.exported == null) delay(1) }
                assertEquals(ExportFormat.Csv.mimeType, files.mime)
                browserResult.completeExceptionally(GoogleSignInDenied())
                await(controller)
                assertNull(controller.account)
            } finally {
                browserResult.cancel()
                cloud.close()
            }
        }
    }

    @Test fun `autoexport is opt in before Google setup and survives restart without network access`() = runBlocking {
        Store().use { store ->
            var requests = 0
            val cloud = GoogleBackupStore(Auth(false), HttpClient(MockEngine { requests++; error("No Google connection") }))
            try {
                fun controller() = BackupController(FlickState(store.repo, null, this), Files(), this, cloud)
                val first = controller()
                first.startup(); await(first)
                assertFalse(first.autoExport)
                first.toggleAutoExport(); await(first)
                assertTrue(first.autoExport)
                assertEquals("true", store.repo.backupPreference("backup.autoEnabled"))
                val restarted = controller()
                restarted.startup(); await(restarted)
                assertTrue(restarted.autoExport)
                assertNotNull(restarted.message)
                assertEquals(0, requests)
                restarted.toggleAutoExport(); await(restarted)
                assertFalse(restarted.autoExport)
                assertEquals("false", store.repo.backupPreference("backup.autoEnabled"))
            } finally { cloud.close() }
        }
    }

    @Test fun `first Google sign in preserves opt in but changing account disables uploads`() = runBlocking {
        Store().use { store ->
            var email = "first@example.com"
            var uploads = 0
            val cloud = GoogleBackupStore(Auth(), HttpClient(MockEngine { request ->
                when {
                    request.url.encodedPath.contains("userinfo") -> respond("{\"name\":\"Test\",\"email\":\"$email\"}", HttpStatusCode.OK)
                    request.url.encodedPath.contains("upload") -> { uploads++; respond("{}", HttpStatusCode.OK) }
                    else -> respond("{\"files\":[]}", HttpStatusCode.OK)
                }
            }))
            try {
                fun controller() = BackupController(FlickState(store.repo, null, this), Files(), this, cloud)
                val first = controller()
                first.toggleAutoExport(); await(first)
                first.signInOrOut(); await(first)
                assertTrue(first.autoExport)
                assertEquals(0, uploads) // Sign-in is not an application startup.
                val restarted = controller()
                restarted.startup(); await(restarted)
                assertEquals(1, uploads)
                email = "second@example.com"
                val changed = controller()
                changed.startup(); await(changed)
                assertFalse(changed.autoExport)
                assertEquals(1, uploads)
                assertEquals("false", store.repo.backupPreference("backup.autoEnabled"))
            } finally { cloud.close() }
        }
    }

    @Test fun `export formats retain Unicode titles episode identity and resume data`() = runBlocking {
        Store().use { store ->
            val show = store.repo.save(LibraryContentDraft(MediaType.Tv, 1, "Серіал, \"test\"", releaseYear = 2024, posterPath = "/poster.jpg"), true)
            store.repo.syncSeason(show.localId, TmdbSeasonSummary(10, 2, "Two", "2020-01-01", null),
                TmdbSeasonDetails(2, "Two", listOf(TmdbEpisode(21, 5, "Серія", null, "2020-01-01", null, 40))))
            val snapshot = store.repo.exportBackup()
            val rows = libraryExportRows(snapshot)
            assertEquals("episode", rows.last().kind)
            assertEquals("2", rows.last().season); assertEquals("5", rows.last().episode)
            val csv = csvLibraryExport(rows).decodeToString()
            assertTrue(csv.startsWith("\uFEFF")); assertContains(csv, "\"Серіал, \"\"test\"\"\"")
            assertContains(csv, "https://image.tmdb.org/t/p/w342/poster.jpg")
            assertEquals(store.repo.inspectBackup(snapshot).titles, store.repo.inspectBackup(readableJsonExport(snapshot)).titles)
            val pdf = renderLibraryPdf(rows, Res.readBytes("font/inter_regular.ttf"), emptyMap())
            Loader.loadPDF(pdf).use { document ->
                val text = PDFTextStripper().getText(document)
                assertContains(text, "Серіал"); assertContains(text, "S02E05"); assertContains(text, "Серія")
            }
            val safeCsv = csvLibraryExport(listOf(rows.first().copy(title = "=HYPERLINK(\"bad\")"))).decodeToString()
            assertContains(safeCsv, "\"'=HYPERLINK")
        }
    }

    @Test fun `local format selection controls file extension MIME and exported content`() = runBlocking {
        Store().use { store ->
            store.repo.save(LibraryContentDraft(MediaType.Movie, 1, "Movie"), true)
            val files = Files()
            val cloud = GoogleBackupStore(Auth(false))
            try {
                val controller = BackupController(FlickState(store.repo, null, this), files, this, cloud)
                controller.startup(); await(controller)
                for (format in ExportFormat.entries) {
                    controller.chooseFormat(format); await(controller)
                    controller.exportLocal(); await(controller)
                    assertTrue(files.name.endsWith(".${format.extension}"), controller.message)
                    assertEquals(format.mimeType, files.mime)
                    assertNotNull(files.exported)
                    when (format) {
                        ExportFormat.Backup, ExportFormat.Json -> assertEquals(1, store.repo.inspectBackup(files.exported!!).titles)
                        ExportFormat.Csv -> assertContains(files.exported!!.decodeToString(), "Movie")
                        ExportFormat.Pdf -> assertTrue(files.exported!!.decodeToString().startsWith("%PDF-"))
                    }
                    assertNotNull(controller.lastLocalBackup)
                }
            } finally { cloud.close() }
        }
    }

    @Test fun `automatic backups use last success and only run when enabled and due`() {
        val last = 1_000_000L
        BackupInterval.entries.forEach { interval ->
            val due = last + interval.days * 86_400_000L
            assertFalse(autoBackupDue(false, null, interval, due))
            assertTrue(autoBackupDue(true, null, interval, last))
            assertFalse(autoBackupDue(true, last, interval, due - 1))
            assertTrue(autoBackupDue(true, last, interval, due))
            assertFalse(autoBackupDue(true, last, interval, last - 1))
        }
    }

    @Test fun `artwork archives preserve bytes and reject non TMDB URLs and non image data`() {
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aDXcAAAAASUVORK5CYII=")
        val url = "https://image.tmdb.org/t/p/w342/poster.png"
        val root = buildJsonObject { put("images", encodeBackupImages(mapOf(url to png))) }
        assertContentEquals(png, decodeBackupImages(root).getValue(url))
        val wrongUrl = buildJsonObject { put("images", encodeBackupImages(mapOf("file:///private.txt" to png))) }
        assertFailsWith<IllegalArgumentException> { decodeBackupImages(wrongUrl) }
        val wrongData = buildJsonObject { put("images", encodeBackupImages(mapOf(url to "<script>no image</script>".encodeToByteArray()))) }
        assertFailsWith<IllegalArgumentException> { decodeBackupImages(wrongData) }
    }

    @Test fun `Drive upload uses multipart related and hidden app folder with original backup payload`() = runBlocking {
        val bytes = "backup payload".encodeToByteArray()
        val client = HttpClient(MockEngine { request ->
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            assertEquals("multipart", request.url.parameters["uploadType"])
            val body = request.body as OutgoingContent.ByteArrayContent
            assertEquals("multipart", body.contentType!!.contentType)
            assertEquals("related", body.contentType!!.contentSubtype)
            val text = body.bytes().decodeToString()
            assertContains(text, "\"parents\":[\"appDataFolder\"]")
            assertContains(text, "\"tvflickBackup\":\"1\"")
            assertContains(text, "\r\n\r\nbackup payload\r\n--")
            respond("{}", HttpStatusCode.OK)
        })
        val cloud = GoogleBackupStore(Auth(), client)
        try { cloud.upload(bytes, "Test.tvbackup") } finally { cloud.close() }
    }

    @Test fun `startup uploads once when due and does not advance schedule on failure`() = runBlocking {
        Store().use { store ->
            store.repo.saveBackupPreference("backup.cloudAccount", "test@example.com")
            store.repo.saveBackupPreference("backup.autoEnabled", "true")
            store.repo.saveBackupPreference("backup.lastCloud", "1")
            var uploads = 0
            var fail = true
            val client = HttpClient(MockEngine { request ->
                if (request.url.encodedPath.contains("userinfo")) respond("{\"name\":\"Test\",\"email\":\"test@example.com\"}", HttpStatusCode.OK)
                else { uploads++; respond("{}", if (fail) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK) }
            })
            val cloud = GoogleBackupStore(Auth(), client)
            try {
                fun controller() = BackupController(FlickState(store.repo, null, this), Files(), this, cloud)
                controller().also { it.startup(); await(it) }
                assertEquals(1, uploads)
                assertEquals("1", store.repo.backupPreference("backup.lastCloud"))
                fail = false
                controller().also { it.startup(); await(it) }
                assertEquals(2, uploads)
                assertTrue(store.repo.backupPreference("backup.lastCloud")!!.toLong() > 1)
                controller().also { it.startup(); await(it) }
                assertEquals(2, uploads)
            } finally { cloud.close() }
        }
    }
}
