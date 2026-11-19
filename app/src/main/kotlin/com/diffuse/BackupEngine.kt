package com.diffuse

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import com.diffuse.backup.BackupContent
import com.diffuse.backup.BackupProgress
import com.diffuse.backup.BackupRunner
import com.diffuse.backup.BackupSources
import com.diffuse.backup.BackupStage
import com.diffuse.backup.BackupSummary
import com.diffuse.backup.RunSummaryText
import com.diffuse.backup.provider.from
import com.diffuse.backup.store.LastRun
import com.diffuse.backup.store.LastRunStore
import com.diffuse.drive.DriveClient
import com.diffuse.drive.DriveFileSink
import com.diffuse.drive.DriveFolderTree
import com.diffuse.drive.DriveInventory
import com.diffuse.drive.DriveMediaSink
import com.diffuse.drive.OkHttpHttp
import com.diffuse.drive.auth.AccessTokenProvider
import com.diffuse.drive.auth.AuthRevokedException
import com.diffuse.drive.auth.DeviceAuthClient
import com.diffuse.drive.auth.TokenResponse
import com.diffuse.drive.store.EncryptedDriveCredentialStore
import com.diffuse.drive.store.LegacyManifest
import com.diffuse.drive.store.RecentUploads
import com.diffuse.util.causeOfType
import com.diffuse.work.BackupSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.time.YearMonth

/** Live progress of the run in progress. */
data class RunProgress(
    val stage: BackupStage? = null,
    val photoDone: Int = 0,
    val photoTotal: Int = 0,
    val videoDone: Int = 0,
    val videoTotal: Int = 0,
)

/** How a call to [BackupEngine.runBackup] ended, other than by throwing. */
sealed interface RunOutcome {
    /**
     * The run finished. [summaryText] is what was recorded as the last run.
     * [skippedForPermission] lists enabled categories left out because their permission
     * isn't granted (the user needs to be told).
     */
    data class Completed(
        val summary: BackupSummary,
        val summaryText: String,
        val skippedForPermission: BackupContent = BackupContent.NONE,
    ) : RunOutcome {
        val failedItems: Int get() = summary.failedItems
    }

    /** Another run was already in progress, so this one did nothing. */
    data object AlreadyRunning : RunOutcome

    /** Drive isn't connected (or the build has no Drive credentials, or the user signed out mid-run). */
    data object NotConnected : RunOutcome

    /** Every category is turned off in Settings. */
    data object NothingSelected : RunOutcome

    /** Categories are selected, but none has its read permission granted. */
    data object NeedsPermission : RunOutcome
}

/**
 * The whole backup stack in one place, and one per process ([get]). Both entry points (the
 * "Back up now" button and the schedule) run through [com.diffuse.work.BackupWorker], which
 * calls [runBackup] here, so a manual run and an automatic run do *exactly* the same thing.
 *
 * Being a singleton with a [Mutex] means two runs can never overlap: overlapping runs used to
 * upload the same photos twice and race to create the `Diffuse` folder. It also means the UI
 * observes one source of truth ([progress], [lastRun], [connected], [accountEmail]) whether
 * a run was started by a tap or by the schedule.
 *
 * READ-ONLY: reads the providers (media streams in "r" mode) and writes only to our own files
 * and to Drive; nothing mutates a content provider.
 */
class BackupEngine private constructor(context: Context) {

    private val app = context.applicationContext
    private val workDir = File(app.filesDir, "backup")

    private val http = OkHttpHttp()
    private val credentialStore = EncryptedDriveCredentialStore(app)
    val auth = DeviceAuthClient(http, BuildConfig.DRIVE_CLIENT_ID, BuildConfig.DRIVE_CLIENT_SECRET)
    private val tokenProvider = AccessTokenProvider(auth, credentialStore)
    private val driveClient = DriveClient(http, tokenProvider)
    private val lastRunStore = LastRunStore(File(app.filesDir, "last-run.properties"))
    private val recentUploads = RecentUploads(File(app.filesDir, "recent-uploads.tsv"))
    private val legacyManifest = LegacyManifest(File(app.filesDir, "upload-manifest.properties"))
    private val settings = BackupSettings(app)

    /**
     * Which phone this is, so two phones backing up to one Drive keep separate documents.
     * A hash of ANDROID_ID, which survives reinstalling Diffuse but not a factory reset, so a
     * reinstall keeps updating the same monthly files.
     */
    private val deviceId: String by lazy {
        val androidId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"
        MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
    }

    private val runLock = Mutex()
    /** Serialises credential writes, so a sign-out's background clear can't land after a new sign-in. */
    private val credentialLock = Mutex()
    /** The pass in progress, so [signOut] can stop it. */
    @Volatile private var activePass: Job? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connected = MutableStateFlow(credentialStore.isConnected)
    private val _accountEmail = MutableStateFlow(credentialStore.accountEmail)
    private val _lastRun = MutableStateFlow(lastRunStore.get())
    private val _progress = MutableStateFlow<RunProgress?>(null)

    /** True once a Drive refresh token is stored (the QR sign-in completed). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** The connected account's email, once known. */
    val accountEmail: StateFlow<String?> = _accountEmail.asStateFlow()

    /** The most recent run's outcome, persisted across launches. */
    val lastRun: StateFlow<LastRun?> = _lastRun.asStateFlow()

    /** Progress of the run in progress, or null when no run is active. */
    val progress: StateFlow<RunProgress?> = _progress.asStateFlow()

    /** False when the build has no Drive client id (see docs/drive-setup.md). */
    val credentialsConfigured: Boolean get() = BuildConfig.DRIVE_CLIENT_ID.isNotBlank()

    // --- Connection -------------------------------------------------------------------------

    /**
     * Store the tokens from a completed QR sign-in and look up the account's email. Returns
     * false, storing nothing, if Google sent no refresh token: without one the connection
     * would quietly stop working when the first access token expires an hour later.
     */
    suspend fun onSignedIn(tokens: TokenResponse): Boolean {
        val connected = credentialLock.withLock {
            withContext(Dispatchers.IO) {
                recentUploads.clear() // it describes whichever account was connected before
                tokenProvider.onSignedIn(tokens)
                credentialStore.isConnected.also { ok -> if (!ok) credentialStore.clear() }
            }
        }
        _connected.value = connected
        if (connected) background.launch { refreshAccountEmail() }
        return connected
    }

    /** Fetch the account email if it isn't known yet (e.g. connected before this was shown). */
    fun refreshAccountEmailIfMissing() {
        if (_connected.value && _accountEmail.value == null) background.launch { refreshAccountEmail() }
    }

    private fun refreshAccountEmail() {
        driveClient.accountEmail()?.let {
            credentialStore.accountEmail = it
            _accountEmail.value = it
        }
    }

    /**
     * Forget the Drive connection, stop any backup in progress (quietly: it isn't a failure),
     * and ask Google to revoke the token so it stops working everywhere (best-effort; signing
     * out works offline too). The user re-scans the QR to reconnect. Safe to call from the
     * main thread: the state flips at once and the disk and network work happens in the
     * background.
     */
    fun signOut() {
        _connected.value = false
        _accountEmail.value = null
        activePass?.cancel()
        background.launch {
            val token = credentialStore.refreshToken
            forgetCredentials()
            if (token != null) auth.revoke(token)
        }
    }

    private suspend fun forgetCredentials() {
        _connected.value = false
        _accountEmail.value = null
        credentialLock.withLock {
            withContext(Dispatchers.IO) {
                credentialStore.clear()
                recentUploads.clear()
            }
        }
    }

    // --- Running ----------------------------------------------------------------------------

    /**
     * Why [runBackup] would end at once without backing anything up, or null if it would run.
     * Cheap and side-effect free, so the worker can check before showing a notification.
     */
    fun reasonNotToRun(): RunOutcome? {
        if (!credentialsConfigured || !_connected.value) return RunOutcome.NotConnected
        val selected = settings.prefsValue.content
        if (selected.isEmpty) return RunOutcome.NothingSelected
        if ((selected intersect BackupPermissions.permitted(app)).isEmpty) return RunOutcome.NeedsPermission
        if (runLock.isLocked) return RunOutcome.AlreadyRunning
        return null
    }

    /**
     * Run one backup: list what's on Drive, then export and upload call log, messages, photos
     * and videos in that order (see [BackupRunner]), and record the result as [lastRun].
     *
     * Categories turned off in Settings are skipped, and so are categories whose permission
     * isn't granted (noted in the summary). Returns [RunOutcome.AlreadyRunning] at once if
     * another run holds the lock.
     *
     * @throws Exception caused by [AuthRevokedException] (possibly wrapped) after
     *   disconnecting, if Google no longer accepts the stored sign-in; the user has to reconnect.
     * @throws Exception for any other failure, after recording it as the last run. Network
     *   problems are worth retrying later; the next run picks up where this one stopped.
     */
    suspend fun runBackup(): RunOutcome {
        reasonNotToRun()?.let { return it }
        val selected = settings.prefsValue.content
        val included = selected intersect BackupPermissions.permitted(app)
        if (included.isEmpty) return RunOutcome.NeedsPermission // permissions changed just now
        if (!runLock.tryLock()) return RunOutcome.AlreadyRunning

        _progress.value = RunProgress()
        try {
            val summary = runPassUnlessSignedOut(included) ?: return RunOutcome.NotConnected
            val skipped = selected - included
            val text = RunSummaryText.describe(summary, included, skippedForPermission = skipped)
            record(LastRun(System.currentTimeMillis(), success = true, summary = text, failedItems = summary.failedItems))
            // Every media item on the phone has now been matched to Drive, so the 0.1.x manifest
            // has nothing left to contribute.
            if (included.pictures && included.videos && summary.failedItems == 0) legacyManifest.retire()
            return RunOutcome.Completed(summary, text, skipped)
        } catch (e: CancellationException) {
            throw e // stopped by the system (e.g. Wi-Fi lost); WorkManager runs it again later
        } catch (e: Exception) {
            // Signed out mid-run: the errors are just the missing credentials, not a failure.
            if (!_connected.value) return RunOutcome.NotConnected
            val revoked = e.causeOfType<AuthRevokedException>()
            if (revoked != null) forgetCredentials()
            val message = (revoked ?: e).message ?: e.javaClass.simpleName
            record(LastRun(System.currentTimeMillis(), success = false, summary = message))
            throw e
        } finally {
            _progress.value = null
            workDir.deleteRecursively()
            runLock.unlock()
        }
    }

    /**
     * [runPass], in a job [signOut] can cancel. Returns null if it was stopped that way; a
     * cancellation of the run itself (WorkManager stopping the worker) still propagates.
     */
    private suspend fun runPassUnlessSignedOut(content: BackupContent): BackupSummary? = try {
        coroutineScope {
            val pass = async { runPass(content) }
            activePass = pass
            pass.await()
        }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    } finally {
        activePass = null
    }

    private suspend fun runPass(content: BackupContent): BackupSummary {
        workDir.deleteRecursively() // leftovers from a run that was killed mid-way
        val inventory = runInterruptible(Dispatchers.IO) {
            DriveInventory.load(driveClient, recentUploads, legacyManifest.mediaPaths())
        }
        val rootId = runInterruptible(Dispatchers.IO) { driveClient.ensureFolder(ROOT_FOLDER) }
        val folders = DriveFolderTree(driveClient, rootId)
        val readOriginal = BackupPermissions.canReadPhotoLocation(app)
        val mediaSink = DriveMediaSink(
            openStream = { uri -> openMedia(uri, readOriginal) },
            lengthOf = { uri -> mediaLength(uri, readOriginal) },
            drive = driveClient,
            folders = folders,
            inventory = inventory,
        )
        val runner = BackupRunner(
            sources = BackupSources.from(app.contentResolver),
            workDir = workDir,
            month = YearMonth.now(),
            mediaSink = mediaSink,
            fileSink = DriveFileSink(driveClient, folders, inventory, deviceId),
            progress = progressListener,
            content = content,
        )
        return runner.run()
    }

    /** Feeds [progress] from the runner's callbacks. */
    private val progressListener = object : BackupProgress {
        override fun onStage(stage: BackupStage) = _progress.update { it?.copy(stage = stage) }
        override fun onPhotoProgress(done: Int, total: Int) =
            _progress.update { it?.copy(photoDone = done, photoTotal = total) }
        override fun onVideoProgress(done: Int, total: Int) =
            _progress.update { it?.copy(videoDone = done, videoTotal = total) }
    }

    /**
     * The URI to read a media item's bytes from. Android strips GPS location out of photos
     * and videos for apps that don't ask for the original, even when they hold
     * ACCESS_MEDIA_LOCATION, so when that permission is granted we ask for the original.
     */
    private fun mediaUri(uri: String, readOriginal: Boolean): Uri {
        val parsed = Uri.parse(uri)
        return if (readOriginal) MediaStore.setRequireOriginal(parsed) else parsed
    }

    private fun openMedia(uri: String, readOriginal: Boolean): InputStream =
        app.contentResolver.openInputStream(mediaUri(uri, readOriginal))
            ?: throw FileNotFoundException("cannot open $uri")

    /** Real byte length from the file descriptor (MediaStore's `_size` is unreliable on the LP3). */
    private fun mediaLength(uri: String, readOriginal: Boolean): Long =
        app.contentResolver.openFileDescriptor(mediaUri(uri, readOriginal), "r")?.use { it.statSize } ?: -1L

    private fun record(run: LastRun) {
        lastRunStore.put(run)
        _lastRun.value = run
    }

    companion object {
        private const val ROOT_FOLDER = "Diffuse"

        // Holds only the application context, which lives as long as the process: not a leak.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: BackupEngine? = null

        /** The process-wide engine. */
        fun get(context: Context): BackupEngine =
            instance ?: synchronized(this) {
                instance ?: BackupEngine(context.applicationContext).also { instance = it }
            }
    }
}
