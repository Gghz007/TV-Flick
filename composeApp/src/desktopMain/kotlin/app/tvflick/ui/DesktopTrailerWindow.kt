package app.tvflick.ui

import androidx.compose.runtime.*
import app.tvflick.core.tmdb.YoutubeTrailer
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Window
import java.io.File

internal val LocalTrailerOwner = staticCompositionLocalOf<Window?> { null }

@Composable
internal actual fun PlatformTrailerWindow(trailer: YoutubeTrailer, notices: UserNotices, onDismiss: () -> Unit) {
    val owner = LocalTrailerOwner.current
    val dismiss by rememberUpdatedState(onDismiss)
    var unavailable by remember(trailer) { mutableStateOf(false) }
    LaunchedEffect(trailer, owner) {
        var process: Process? = null
        try {
            if (!Platform.isWindows()) { unavailable = true; return@LaunchedEffect }
            val root = System.getProperty("compose.application.resources.dir")
            val host = System.getProperty("tvflick.trailer.host")?.let(::File)
                ?: root?.let { File(it, "trailer/TVFlickTrailer.exe") }
            if (host?.isFile != true) { unavailable = true; return@LaunchedEffect }
            val ownerHandle = owner?.takeIf { it.isDisplayable }?.let { Pointer.nativeValue(Native.getWindowPointer(it)) } ?: 0L
            val exitCode = withContext(Dispatchers.IO) {
                val child = ProcessBuilder(host.absolutePath, ProcessHandle.current().pid().toString(), ownerHandle.toString())
                    .directory(host.parentFile)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                process = child
                child.outputStream.use { it.write(trailer.playerHtml().encodeToByteArray()) }
                while (child.isAlive) delay(120)
                child.exitValue()
            }
            if (exitCode == 0) dismiss() else unavailable = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable = true }
        finally { process?.takeIf { it.isAlive }?.destroy() }
    }
    if (unavailable) TrailerWindow(trailer, notices, onDismiss, unavailable = true)
}
