package app.tvflick.core.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

@Composable
actual fun rememberBackupFileAccess(): BackupFileAccess {
    val context = LocalContext.current
    val picker = remember { BackupPicker() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
        picker.pending?.let { if (it.isActive) it.resume(uri) }; picker.pending = null
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picker.pending?.let { if (it.isActive) it.resume(uri) }; picker.pending = null
    }
    return remember(context, export, import) {
        AndroidBackupFileAccess(context, picker, { name, mime -> export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name)) },
            { import.launch(arrayOf("*/*")) })
    }
}

private class BackupPicker { var pending: CancellableContinuation<Uri?>? = null }

private class AndroidBackupFileAccess(
    private val context: Context,
    private val picker: BackupPicker,
    private val export: (String, String) -> Unit,
    private val import: () -> Unit,
) : BackupFileAccess {
    override suspend fun loadImageArchive(): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "backup-artwork.json")
        if (!file.exists()) null else {
            require(file.length() <= MAX_BACKUP_BYTES)
            file.readBytes().also { require(it.size <= MAX_BACKUP_BYTES) }
        }
    }
    override suspend fun saveImageArchive(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = AtomicFile(File(context.filesDir, "backup-artwork.json"))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private suspend fun choose(launch: () -> Unit): Uri? = suspendCancellableCoroutine { continuation ->
        check(picker.pending == null)
        picker.pending = continuation
        continuation.invokeOnCancellation { if (picker.pending === continuation) picker.pending = null }
        try { launch() } catch (error: Exception) { picker.pending = null; continuation.cancel(error) }
    }
    override suspend fun exportFile(bytes: ByteArray, suggestedName: String, mimeType: String): Boolean {
        val uri = choose { export(suggestedName, mimeType) } ?: return false
        withContext(Dispatchers.IO) {
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
        }
        return true
    }
    override suspend fun importFile(): ByteArray? {
        val uri = choose(import) ?: return null
        return withContext(Dispatchers.IO) {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BACKUP_BYTES) { "Backup is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }
    override suspend fun saveRecovery(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = AtomicFile(File(context.filesDir, "backup-recovery.tvbackup"))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
