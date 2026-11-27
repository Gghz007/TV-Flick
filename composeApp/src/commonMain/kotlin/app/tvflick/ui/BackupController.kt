package app.tvflick.ui

import androidx.compose.runtime.*
import app.tvflick.core.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import tv_flick.composeapp.generated.resources.Res
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
@Stable
class BackupController(
    private val state: FlickState,
    private val files: BackupFileAccess,
    private val scope: CoroutineScope,
    private val cloud: GoogleBackupStore,
) {
    private val repository get() = requireNotNull(state.repository as? BackupRepository)
    private val operations = Mutex()
    private val preferenceUpdates = Mutex()
    private var operationBusy by mutableStateOf(false)
    private var waitingForGoogle by mutableStateOf(false)
    private var queuedPreferences by mutableIntStateOf(0)
    val busy get() = operationBusy || waitingForGoogle || queuedPreferences > 0
    val applyingBackup get() = operationBusy
    var message by mutableStateOf<String?>(null); private set
    var pendingRestore by mutableStateOf<BackupSummary?>(null); private set
    private var pendingBytes: ByteArray? = null
    var lastLocalBackup by mutableStateOf<Long?>(null); private set
    var lastCloudBackup by mutableStateOf<Long?>(null); private set
    var format by mutableStateOf(ExportFormat.Backup); private set
    var localImages by mutableStateOf(false); private set
    var cloudImages by mutableStateOf(false); private set
    var autoExport by mutableStateOf(false); private set
    var interval by mutableStateOf(BackupInterval.Daily); private set
    var account by mutableStateOf<GoogleProfile?>(null); private set
    var cloudBackups by mutableStateOf<List<CloudBackup>>(emptyList()); private set
    var selectedCloudBackup by mutableStateOf<CloudBackup?>(null); private set

    private suspend fun preferences() = preferenceUpdates.withLock {
        lastLocalBackup = repository.backupPreference("backup.lastLocal")?.toLongOrNull()
        lastCloudBackup = repository.backupPreference("backup.lastCloud")?.toLongOrNull()
        val savedFormat = repository.backupPreference("backup.format")
        format = ExportFormat.entries.firstOrNull { it.name == savedFormat } ?: ExportFormat.Backup
        localImages = repository.backupPreference("backup.localImages") == "true"
        cloudImages = repository.backupPreference("backup.cloudImages") == "true"
        autoExport = repository.backupPreference("backup.autoEnabled") == "true"
        val savedInterval = repository.backupPreference("backup.intervalDays")
        interval = BackupInterval.entries.firstOrNull { it.days.toString() == savedInterval } ?: BackupInterval.Daily
    }

    /** Once per app startup, never an OS task or a timer outside the running application. */
    fun startup() = operation {
        preferences()
        try {
            files.loadImageArchive()?.let { installBackupImages(decodeBackupImages(Json.parseToJsonElement(it.decodeToString()).jsonObject)) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Saved artwork is unavailable; images will load online" }
        if (cloud.configured) {
            try {
                val profile = withTimeoutOrNull(15_000) { cloud.profile(false) }
                if (profile != null) {
                    acceptAccount(profile)
                    if (autoBackupDue(autoExport, lastCloudBackup, interval, now())) uploadCloud()
                } else if (autoExport) message = "AutoExport could not connect. It will retry at the next app start."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (autoExport) message = "AutoExport needs a Google connection. Sign in again; it will retry at the next app start." }
        } else if (autoExport) message = "AutoExport is enabled. Set up Google and sign in to start automatic backups."
    }

    fun chooseFormat(value: ExportFormat) = changePreference { repository.saveBackupPreference("backup.format", value.name); format = value }
    fun chooseInterval(value: BackupInterval) = changePreference { repository.saveBackupPreference("backup.intervalDays", value.days.toString()); interval = value }
    fun toggleImages(isCloud: Boolean) = changePreference {
        if (isCloud) { repository.saveBackupPreference("backup.cloudImages", (!cloudImages).toString()); cloudImages = !cloudImages }
        else if (format != ExportFormat.Csv) { repository.saveBackupPreference("backup.localImages", (!localImages).toString()); localImages = !localImages }
    }
    fun toggleAutoExport() = changePreference {
        repository.saveBackupPreference("backup.autoEnabled", (!autoExport).toString()); autoExport = !autoExport
        if (autoExport && account == null)
            message = "AutoExport is enabled. Sign in to Google; backups run when the app starts."
    }

    fun signInOrOut() {
        if (operationBusy || waitingForGoogle) return
        if (account != null) {
            operation {
                cloud.signOut()
                preferenceUpdates.withLock {
                    repository.saveBackupPreference("backup.autoEnabled", "false")
                    autoExport = false; account = null; cloudBackups = emptyList(); selectedCloudBackup = null
                }
                message = "Signed out of Google"
            }
            return
        }
        waitingForGoogle = true
        message = null
        scope.launch {
            try {
                reportErrors {
                    check(cloud.configured) { "Google setup is required: install the Desktop OAuth JSON as TV Flick/google-oauth.json in LocalAppData." }
                    val profile = cloud.profile(true)
                    operations.withLock { acceptAccount(profile) }
                    refreshCloudList()
                    message = "Signed in to Google"
                }
            } finally { waitingForGoogle = false }
        }
    }

    private suspend fun acceptAccount(profile: GoogleProfile) = preferenceUpdates.withLock {
        require(profile.email.isNotBlank()) { "Google did not return an account email" }
        val previousAccount = repository.backupPreference("backup.cloudAccount")
        if (previousAccount != profile.email) {
            repository.saveBackupPreference("backup.cloudAccount", profile.email)
            repository.saveBackupPreference("backup.lastCloud", "")
            lastCloudBackup = null
            // Preserve the user's opt-in for the first sign-in, never transfer it to another account.
            if (!previousAccount.isNullOrBlank()) {
                repository.saveBackupPreference("backup.autoEnabled", "false")
                autoExport = false
            }
        }
        account = profile
    }

    fun listCloudBackups() = operation { check(account != null) { "Sign in to Google first" }; refreshCloudList() }
    private suspend fun refreshCloudList() {
        cloudBackups = cloud.list()
        selectedCloudBackup = cloudBackups.firstOrNull { it.id == selectedCloudBackup?.id } ?: cloudBackups.firstOrNull()
    }
    fun selectCloudBackup(value: CloudBackup) { if (!busy) selectedCloudBackup = value }

    fun exportLocal() = operation {
        val format = format
        val includeImages = localImages && format != ExportFormat.Csv
        val snapshot = repository.exportBackup()
        val images = if (includeImages) collectBackupImages(snapshot) else emptyMap()
        val bytes = when (format) {
            ExportFormat.Backup -> withBackupImages(snapshot, images)
            ExportFormat.Json -> readableJsonExport(withBackupImages(snapshot, images))
            ExportFormat.Csv -> csvLibraryExport(libraryExportRows(snapshot))
            ExportFormat.Pdf -> renderLibraryPdf(libraryExportRows(snapshot), Res.readBytes("font/inter_regular.ttf"), images)
        }
        if (format == ExportFormat.Backup || format == ExportFormat.Json)
            require(bytes.size <= MAX_BACKUP_BYTES) { "Backup exceeds 32 MB; export without Image" }
        if (files.exportFile(bytes, "TVFlick_Library_${fileDate(now())}.${format.extension}", format.mimeType)) {
            lastLocalBackup = now()
            repository.saveBackupPreference("backup.lastLocal", lastLocalBackup.toString())
            message = "${format.label} exported"
        }
    }

    fun exportCloud() = operation { check(account != null) { "Sign in to Google first" }; uploadCloud(); refreshCloudList() }
    private suspend fun uploadCloud() {
        val snapshot = repository.exportBackup()
        val images = if (cloudImages) collectBackupImages(snapshot) else emptyMap()
        cloud.upload(withBackupImages(snapshot, images), "TVFlick_Backup_${fileDate(now())}.tvbackup")
        // A failed upload never advances the automatic schedule.
        lastCloudBackup = now()
        repository.saveBackupPreference("backup.lastCloud", lastCloudBackup.toString())
        message = "Google backup exported"
    }

    fun importLocal() = operation { files.importFile()?.let { prepareRestore(it) } }
    fun importCloud() = operation {
        val file = selectedCloudBackup ?: error("Select a Google backup first")
        prepareRestore(cloud.download(file))
    }
    private suspend fun prepareRestore(bytes: ByteArray) {
        val summary = repository.inspectBackup(bytes)
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val images = decodeBackupImages(root)
        require(images.keys.all { it in backupImageUrls(bytes) }) { "Backup contains unrelated artwork" }
        pendingBytes = bytes; pendingRestore = summary
    }
    fun cancelRestore() { if (!operationBusy) { pendingRestore = null; pendingBytes = null } }

    fun confirmRestore() = operation {
        val bytes = pendingBytes ?: return@operation
        val images = decodeBackupImages(Json.parseToJsonElement(bytes.decodeToString()).jsonObject)
        state.withLibraryRestore {
            files.saveRecovery(repository.exportBackup())
            // Finish post-commit UI/preferences together if the caller is disposed during restore.
            withContext(NonCancellable) {
                repository.restoreBackup(bytes)
                pendingBytes = null; pendingRestore = null
                autoExport = false
                repository.saveBackupPreference("backup.cloudAccount", account?.email.orEmpty())
                repository.saveBackupPreference("backup.lastCloud", "")
                preferences()
                installBackupImages(images)
            }
        }
        message = "Backup restored. AutoExport is off until you enable it."
        try {
            files.saveImageArchive(buildJsonObject { put("images", encodeBackupImages(images)) }.toString().encodeToByteArray())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Library restored; offline artwork could not be saved on this device" }
    }

    private fun changePreference(block: suspend () -> Unit) {
        queuedPreferences++
        scope.launch {
            try {
                reportErrors {
                    preferenceUpdates.withLock {
                        if (!state.libraryRestoring && pendingRestore == null) block()
                    }
                }
            } finally { queuedPreferences-- }
        }
    }

    private fun operation(block: suspend () -> Unit) {
        if (operationBusy) return
        operationBusy = true; message = null
        scope.launch {
            try { operations.withLock { reportErrors(block) } }
            finally { operationBusy = false }
        }
    }

    private suspend fun reportErrors(block: suspend () -> Unit) {
        try { block() }
        catch (_: GoogleSignInCancelled) { message = "Вход отменён" }
        catch (e: GoogleSignInDenied) { message = e.message }
        catch (e: GoogleBrowserSignInIncomplete) { message = e.message }
        catch (_: GoogleSignInRequired) {
            account = null; cloudBackups = emptyList(); selectedCloudBackup = null
            message = "Войдите в Google снова"
            state.notices.show(UserProblem.GoogleSignInRequired)
        }
        catch (_: TimeoutCancellationException) { message = "Operation timed out. Try again."; state.notices.show(UserProblem.TimedOut) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            message = e.message?.take(180) ?: "Could not complete the operation. Try again."
            state.notices.show(if (!cloud.configured && message?.startsWith("Google setup") == true)
                UserProblem.GoogleSetupRequired else UserProblem.BackupFailed)
        }
    }
    private fun now() = Clock.System.now().toEpochMilliseconds()
    private fun fileDate(time: Long) = Instant.fromEpochMilliseconds(time).toString().take(19).replace(':', '-')
}

@Composable
fun rememberBackupController(state: FlickState): BackupController {
    val files = rememberBackupFileAccess()
    val auth = rememberGoogleAuthorization()
    val cloud = remember(auth) { GoogleBackupStore(auth) }
    val scope = rememberCoroutineScope()
    val controller = remember(state, files, cloud) { BackupController(state, files, scope, cloud) }
    LaunchedEffect(controller) { controller.startup() }
    DisposableEffect(cloud) { onDispose { cloud.close() } }
    DisposableEffect(auth) { onDispose { auth.close() } }
    return controller
}

@OptIn(ExperimentalTime::class)
internal fun backupDate(time: Long?): String = time?.let {
    val date = Instant.fromEpochMilliseconds(it).toString().take(10).split("-")
    "${date[2]}.${date[1]}.${date[0]}"
} ?: "..."

