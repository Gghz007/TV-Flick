package app.tvflick.core.backup

import androidx.compose.runtime.Composable

interface BackupFileAccess {
    suspend fun exportFile(bytes: ByteArray, suggestedName: String, mimeType: String = "application/octet-stream"): Boolean
    suspend fun importFile(): ByteArray?
    suspend fun saveRecovery(bytes: ByteArray)
    suspend fun loadImageArchive(): ByteArray?
    suspend fun saveImageArchive(bytes: ByteArray)
}

@Composable
expect fun rememberBackupFileAccess(): BackupFileAccess
