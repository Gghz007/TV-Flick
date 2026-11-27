package app.tvflick.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.tvflick.plugin.api.ResolvedVideoSource
import app.tvflick.plugin.api.StreamKind
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
actual fun rememberMediaDownloader(): suspend (ResolvedVideoSource, String) -> Unit {
    val caption = appText("Save video")
    val overwrite = appText("Replace the existing file?")
    return remember(caption, overwrite) {
        { source, title ->
            val destination = chooseVideoFile(title, caption, overwrite)
            if (destination != null) when (source.kind) {
                StreamKind.Mp4 -> downloadMp4(source, destination)
                StreamKind.Hls -> downloadHls(source, destination)
            }
        }
    }
}

internal suspend fun chooseVideoFile(title: String, caption: String, overwrite: String): File? = suspendCancellableCoroutine { continuation ->
    SwingUtilities.invokeLater {
        if (!continuation.isActive) return@invokeLater
        val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val dialog = when (active) {
            is Dialog -> FileDialog(active, caption, FileDialog.SAVE)
            else -> FileDialog(active as? Frame ?: Frame.getFrames().firstOrNull { it.isShowing }, caption, FileDialog.SAVE)
        }
        try {
            continuation.invokeOnCancellation { SwingUtilities.invokeLater { dialog.dispose() } }
            dialog.file = "${videoFileStem(title)}.mp4"
            dialog.isVisible = true
            var selected = dialog.files.firstOrNull()
            if (continuation.isActive && selected?.exists() == true && JOptionPane.showConfirmDialog(dialog.owner,
                    overwrite, caption, JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) selected = null
            if (continuation.isActive) continuation.resume(selected)
        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        finally { dialog.dispose() }
    }
}

internal suspend fun downloadMp4(source: ResolvedVideoSource, destination: File, client: HttpClient = HttpClient()) {
    val http = client.config {
        followRedirects = false; expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 10_000; socketTimeoutMillis = 30_000 }
    }
    var partial: java.nio.file.Path? = null
    try {
        withContext(Dispatchers.IO) {
            partial = Files.createTempFile(destination.absoluteFile.parentFile.toPath(), ".tvflick-", ".part")
            var url = source.url
            var forwardedHeaders = source.headers
            var finished = false
            repeat(6) {
                if (finished) return@repeat
                http.prepareGet(url) { headers { forwardedHeaders.forEach { (key, value) -> append(key, value) } } }.execute { response ->
                    if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                        val next = java.net.URI(url).resolve(requireNotNull(response.headers[HttpHeaders.Location])).toASCIIString()
                        val oldUrl = Url(url); val newUrl = Url(next)
                        require(newUrl.protocol == URLProtocol.HTTP || newUrl.protocol == URLProtocol.HTTPS)
                        if (oldUrl.host != newUrl.host || oldUrl.port != newUrl.port || oldUrl.protocol != newUrl.protocol) {
                            forwardedHeaders = forwardedHeaders.filterKeys { !it.equals("Cookie", true) && !it.equals("Authorization", true) }
                        }
                        url = next
                    } else {
                        check(response.status == HttpStatusCode.OK)
                        val type = response.contentType()?.withoutParameters()?.toString().orEmpty()
                        check(type.isEmpty() || type.startsWith("video/") || type == "application/octet-stream")
                        val channel = response.bodyAsChannel()
                        var bytes = 0L
                        Files.newOutputStream(partial!!).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = channel.readAvailable(buffer)
                                if (read == -1) break
                                if (read > 0) { output.write(buffer, 0, read); bytes += read }
                            }
                        }
                        check(bytes > 0 && (response.contentLength() == null || bytes == response.contentLength()))
                        finished = true
                    }
                }
            }
            check(finished)
            Files.move(partial!!, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        http.close(); client.close()
        partial?.let { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { Files.deleteIfExists(it) } }
    }
}
