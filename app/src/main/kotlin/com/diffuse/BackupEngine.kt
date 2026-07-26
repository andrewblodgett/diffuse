package com.diffuse

import android.content.Context
import android.net.Uri
import com.diffuse.backup.BackupProgress
import com.diffuse.backup.BackupRunner
import com.diffuse.work.BackupSettings
import com.diffuse.backup.store.LastRun
import com.diffuse.backup.store.LastRunStore
import com.diffuse.drive.BackupReconciler
import com.diffuse.drive.DriveClient
import com.diffuse.drive.DriveFileSink
import com.diffuse.drive.DriveFolderTree
import com.diffuse.drive.DriveMediaSink
import com.diffuse.drive.OkHttpHttp
import com.diffuse.drive.auth.AccessTokenProvider
import com.diffuse.drive.auth.DeviceAuthClient
import com.diffuse.drive.store.EncryptedDriveCredentialStore
import com.diffuse.drive.store.UploadManifest
import java.io.File
import java.io.IOException

/**
 * The whole backup stack in one place — the shared engine behind both the interactive
 * [com.diffuse.ui.BackupController] (the "Back up now" button) and the scheduled
 * [com.diffuse.work.BackupWorker], so a manual run and an automatic run do *exactly* the same
 * thing. It owns the Drive plumbing (auth, client, manifest) and the last-run record, and
 * exposes [runBackup] to execute one full extract→stream→upload pass.
 *
 * READ-ONLY: reads the providers (via [BackupRunner], opening media streams in "r" mode) and
 * writes only to our own files and to Drive; nothing mutates a content provider.
 */
class BackupEngine(context: Context) {

    private val app = context.applicationContext
    private val outputDir = File(app.filesDir, "backup")

    private val http = OkHttpHttp()
    private val credentialStore = EncryptedDriveCredentialStore(app)
    val auth = DeviceAuthClient(http, BuildConfig.DRIVE_CLIENT_ID, BuildConfig.DRIVE_CLIENT_SECRET)
    val tokenProvider = AccessTokenProvider(auth, credentialStore)
    private val driveClient = DriveClient(http, tokenProvider)
    // One manifest shared by streamed media (`media/…` keys) and the XML docs (`*.xml` keys).
    private val manifest = UploadManifest(File(app.filesDir, "upload-manifest.properties"))
    private val lastRunStore = LastRunStore(File(app.filesDir, "last-run.properties"))
    private val settings = BackupSettings(app)
    // If Drive no longer has something the manifest thinks is already backed up (e.g. the user
    // emptied the Diffuse folder), this catches it before extraction starts. See its doc.
    private val reconciler = BackupReconciler(driveClient, manifest)

    /** True once a Drive refresh token is stored (the QR sign-in completed at least once). */
    val isConnected: Boolean get() = credentialStore.isConnected

    /** Forget the stored Drive credentials — the user must re-scan the QR to reconnect. */
    fun signOut() = credentialStore.clear()

    /** The connected account's email as last cached, or null if unknown / not connected. */
    val accountEmail: String? get() = credentialStore.accountEmail

    /**
     * Fetch the connected account's email from Drive and cache it for display; returns the fresh
     * value (or the cached one if the network call fails). Requires a valid token, so only call
     * while connected. Blocking — run off the main thread.
     */
    fun refreshAccountEmail(): String? =
        driveClient.accountEmail()?.also { credentialStore.accountEmail = it } ?: credentialStore.accountEmail

    /** False when the build has no Drive client id (see docs/drive-setup.md). */
    val credentialsConfigured: Boolean get() = BuildConfig.DRIVE_CLIENT_ID.isNotBlank()

    fun lastRun(): LastRun? = lastRunStore.get()

    /** Outcome of one [runBackup] pass. */
    data class RunResult(
        val summary: String,
        val smsCount: Int,
        val mmsCount: Int,
        val callCount: Int,
        val mediaStreamed: Int,
        val mediaSkipped: Int,
    )

    /**
     * Execute one backup: resolve the Drive root, then extract and upload call log, messages,
     * photos, and videos in that fixed order (see [BackupRunner]) — each media original streams
     * straight provider→Drive with no local copy (skipped if already in the manifest), and each
     * XML doc is written in full and uploaded (or overwritten in place) immediately after, then
     * persist a success [LastRun]. Reports through
     * [progress]. Throws on failure (callers should surface it and call [recordFailure]); a
     * single unreadable item is skipped inside the runner and never aborts the pass.
     */
    suspend fun runBackup(progress: BackupProgress = BackupProgress.NONE): RunResult {
        reconciler.reconcile()
        val rootId = driveClient.ensureFolder(ROOT_FOLDER)
        val folders = DriveFolderTree(driveClient, rootId)
        val mediaSink = DriveMediaSink(
            openStream = { uri ->
                app.contentResolver.openInputStream(Uri.parse(uri))
                    ?: throw IOException("cannot open $uri")
            },
            // Real byte length from the file descriptor — robust to MediaStore _size=0.
            lengthOf = { uri ->
                app.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { it.statSize } ?: -1L
            },
            drive = driveClient,
            folders = folders,
            manifest = manifest,
        )
        val fileSink = DriveFileSink(driveClient, folders, manifest)
        val runner = BackupRunner(
            app.contentResolver, outputDir, mediaSink, fileSink, progress,
            content = settings.prefsValue.content,
        )
        val s = runner.run()
        // Every doc was already uploaded by fileSink as the runner produced it; the local copies
        // are safely on Drive now, so there's no reason to keep them piling up run after run.
        outputDir.listFiles()?.forEach { it.deleteRecursively() }

        val summary = "${s.smsCount} SMS, ${s.mmsCount} MMS, ${s.callCount} calls; " +
            "media streamed ${mediaSink.uploaded}, skipped ${mediaSink.skipped}"
        lastRunStore.put(LastRun(System.currentTimeMillis(), success = true, summary = summary))
        return RunResult(summary, s.smsCount, s.mmsCount, s.callCount, mediaSink.uploaded, mediaSink.skipped)
    }

    /** Persist a failed [LastRun] so the home screen shows the failure after the process ends. */
    fun recordFailure(message: String) {
        lastRunStore.put(LastRun(System.currentTimeMillis(), success = false, summary = message))
    }

    private companion object {
        const val ROOT_FOLDER = "Diffuse"
    }
}
