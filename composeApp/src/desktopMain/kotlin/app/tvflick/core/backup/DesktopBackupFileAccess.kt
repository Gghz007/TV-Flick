package app.tvflick.core.backup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
actual fun rememberBackupFileAccess(): BackupFileAccess = remember { DesktopBackupFileAccess() }

internal class DesktopBackupFileAccess : BackupFileAccess {
    private val artwork = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "TV Flick/backup-artwork.json")
    override suspend fun loadImageArchive(): ByteArray? = withContext(Dispatchers.IO) {
        if (!artwork.exists()) null else {
            require(artwork.length() <= MAX_BACKUP_BYTES)
            artwork.inputStream().use { it.readNBytes(MAX_BACKUP_BYTES + 1) }.also { require(it.size <= MAX_BACKUP_BYTES) }
        }
    }
    override suspend fun saveImageArchive(bytes: ByteArray) = withContext(Dispatchers.IO) { atomicWrite(artwork, bytes) }
    override suspend fun exportFile(bytes: ByteArray, suggestedName: String, mimeType: String): Boolean {
        val chosen = choose(FileDialog.SAVE, suggestedName) ?: return false
        // Write precisely the path confirmed by the native picker, including overwrite approval.
        withContext(Dispatchers.IO) { atomicWrite(chosen, bytes) }
        return true
    }

    override suspend fun importFile(): ByteArray? {
        val chosen = choose(FileDialog.LOAD, "*.*") ?: return null
        return withContext(Dispatchers.IO) {
            require(chosen.length() in 1..MAX_BACKUP_BYTES.toLong()) { "Invalid backup size" }
            chosen.inputStream().use { stream ->
                stream.readNBytes(MAX_BACKUP_BYTES + 1).also { require(it.size <= MAX_BACKUP_BYTES) }
            }
        }
    }

    override suspend fun saveRecovery(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val parent = System.getenv("LOCALAPPDATA")?.let(::File)
            ?: File(System.getProperty("user.home"), "AppData/Local")
        atomicWrite(File(parent, "TV Flick/backup-recovery.tvbackup"), bytes)
    }

    private suspend fun choose(mode: Int, suggested: String): File? = suspendCancellableCoroutine { continuation ->
        SwingUtilities.invokeLater {
            if (!continuation.isActive) return@invokeLater
            try {
                val dialog = FileDialog(null as Frame?, if (mode == FileDialog.SAVE) "Export TV Flick backup" else "Import TV Flick backup", mode)
                continuation.invokeOnCancellation { SwingUtilities.invokeLater { dialog.dispose() } }
                try {
                    dialog.file = suggested
                    dialog.isVisible = true
                    val result = dialog.file?.let { File(dialog.directory, it) }
                    if (continuation.isActive) continuation.resume(result)
                } finally { dialog.dispose() }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }
}

internal fun atomicWrite(target: File, bytes: ByteArray) {
    val parent = target.absoluteFile.parentFile
    require(parent.isDirectory || parent.mkdirs()) { "Backup folder is unavailable" }
    val temp = Files.createTempFile(parent.toPath(), ".tvflick-backup-", ".tmp")
    try {
        Files.write(temp, bytes)
        try { Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temp) }
}
