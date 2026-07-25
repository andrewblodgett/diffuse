package com.diffuse

import android.content.Context
import android.net.Uri
import com.diffuse.backup.BackupProgress
import com.diffuse.backup.BackupRunner
import com.diffuse.backup.store.FilePropertiesTokenStore
import com.diffuse.backup.store.LastRun
import com.diffuse.backup.store.LastRunStore
import com.diffuse.drive.DriveClient
import com.diffuse.drive.DriveFolderTree
import com.diffuse.drive.DriveMediaSink
import com.diffuse.drive.DriveUploader
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
 * thing. It owns the Drive plumbing (auth, client, manifest), the incremental token store, and
 * the last-run record, and exposes [runBackup] to execute one full extract→stream→upload pass.
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
    // One manifest shared by streamed media (`media/…` keys) and the XML uploader (`*.xml` keys).
    private val manifest = UploadManifest(File(app.filesDir, "upload-manifest.properties"))
    private val uploader = DriveUploader(driveClient, manifest)
    private val tokenStore = FilePropertiesTokenStore(File(app.filesDir, "backup-tokens.properties"))
    private val lastRunStore = LastRunStore(File(app.filesDir, "last-run.properties"))

    /** True once a Drive refresh token is stored (the QR sign-in completed at least once). */
    val isConnected: Boolean get() = credentialStore.isConnected

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
     * Execute one incremental backup: resolve the Drive root, stream each new media original
     * straight provider→Drive (no local copy), write the small XML docs locally and upload them,
     * then persist a success [LastRun]. Reports through [progress]. Throws on failure (callers
     * should surface it and call [recordFailure]); a single unreadable media item is skipped
     * inside the runner and never aborts the pass.
     */
    suspend fun runBackup(progress: BackupProgress = BackupProgress.NONE): RunResult {
        val rootId = driveClient.ensureFolder(ROOT_FOLDER)
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
            folders = DriveFolderTree(driveClient, rootId),
            manifest = manifest,
        )
        val runner = BackupRunner(app.contentResolver, outputDir, tokenStore, mediaSink, progress)
        val s = runner.run(incremental = true)
        uploader.upload(outputDir)

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
