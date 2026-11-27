package app.tvflick

import app.tvflick.plugin.api.*
import app.tvflick.ui.chooseVideoFile
import app.tvflick.ui.downloadMp4
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.FileDialog
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.test.*

class DesktopDownloadTest {
    @Test fun `Windows opens a native save dialog with an editable suggested name`() = runBlocking {
        if (GraphicsEnvironment.isHeadless() || !System.getProperty("os.name").startsWith("Windows")) return@runBlocking
        val caption = "TV Flick save dialog probe"
        val shown = AtomicReference<Pair<Int, String?>>()
        val observer = Timer(50) {
            Window.getWindows().filterIsInstance<FileDialog>().firstOrNull { it.title == caption && it.isShowing }?.let {
                shown.set(it.mode to it.file)
                it.dispose()
            }
        }
        SwingUtilities.invokeAndWait { observer.start() }
        try {
            assertNull(withTimeout(8_000) { chooseVideoFile("Film: part 2", caption, "Replace?") })
            assertEquals(FileDialog.SAVE to "Film_ part 2.mp4", shown.get())
        } finally {
            SwingUtilities.invokeAndWait { observer.stop() }
        }
    }

    @Test fun `failed video response preserves existing file and removes partial download`() = runBlocking {
        val directory = Files.createTempDirectory("tvflick-download-test").toFile()
        try {
            val file = directory.resolve("video.mp4").apply { writeText("previous file") }
            val http = HttpClient(MockEngine { respond("access denied", HttpStatusCode.Forbidden) })
            assertFailsWith<IllegalStateException> {
                downloadMp4(ResolvedVideoSource("https://source.test/file.mp4", StreamKind.Mp4, sourceId = "site"), file, http)
            }
            assertEquals("previous file", file.readText())
            assertEquals(listOf("video.mp4"), directory.listFiles()!!.map { it.name })
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun `CDN redirect strips private headers and streams complete response to selected destination`() = runBlocking {
        val directory = Files.createTempDirectory("tvflick-download-test").toFile()
        val data = ByteArray(150_000) { (it % 127).toByte() }
        try {
            val file = directory.resolve("video.mp4")
            val http = HttpClient(MockEngine { request ->
                if (request.url.host == "source.test") {
                    assertEquals("session=private", request.headers[HttpHeaders.Cookie])
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.test/final.mp4"))
                } else {
                    assertNull(request.headers[HttpHeaders.Cookie])
                    assertNull(request.headers[HttpHeaders.Authorization])
                    respond(data, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("video/mp4"), HttpHeaders.ContentLength to listOf(data.size.toString())))
                }
            })
            downloadMp4(ResolvedVideoSource("https://source.test/file.mp4", StreamKind.Mp4,
                headers = mapOf("Cookie" to "session=private", "Authorization" to "test"), sourceId = "site"), file, http)
            assertContentEquals(data, file.readBytes())
            assertEquals(1, directory.listFiles()!!.size)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
