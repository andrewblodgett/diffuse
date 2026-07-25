package com.diffuse.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.diffuse.BuildConfig
import com.diffuse.backup.BackupRunner
import com.diffuse.backup.store.FilePropertiesTokenStore
import com.diffuse.drive.DriveClient
import com.diffuse.drive.DriveUploader
import com.diffuse.drive.OkHttpHttp
import com.diffuse.drive.QrEncoder
import com.diffuse.drive.auth.AccessTokenProvider
import com.diffuse.drive.auth.DeviceAuthClient
import com.diffuse.drive.auth.PollResult
import com.diffuse.drive.store.EncryptedDriveCredentialStore
import com.diffuse.drive.store.UploadManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Coarse screen state for the home UI. */
enum class Phase { Idle, Connecting, BackingUp, Done, Error }

/**
 * Assembles the Phase-3 Drive stack and drives the two user actions — QR sign-in and
 * "back up now" — exposing Compose state for [HomeScreen]. A plain remembered holder (not
 * an Android ViewModel) keeps the minimal wiring dependency-light; a background
 * WorkManager job and richer state handling are Phase 4.
 *
 * READ-ONLY: the whole stack only reads on-device data (via [BackupRunner]) and writes to
 * our own files / Drive; nothing here mutates a content provider.
 */
class BackupController(context: Context) {

    private val app = context.applicationContext
    private val filesDir get() = app.filesDir
    private val outputDir = File(filesDir, "backup")

    private val http = OkHttpHttp()
    private val store = EncryptedDriveCredentialStore(app)
    private val auth = DeviceAuthClient(http, BuildConfig.DRIVE_CLIENT_ID, BuildConfig.DRIVE_CLIENT_SECRET)
    private val tokenProvider = AccessTokenProvider(auth, store)
    private val driveClient = DriveClient(http, tokenProvider)
    private val uploader = DriveUploader(driveClient, UploadManifest(File(filesDir, "upload-manifest.properties")))
    private val runner = BackupRunner(
        app.contentResolver,
        outputDir,
        FilePropertiesTokenStore(File(filesDir, "backup-tokens.properties")),
    )

    var connected by mutableStateOf(store.isConnected)
        private set
    var phase by mutableStateOf(Phase.Idle)
        private set
    var qr by mutableStateOf<ImageBitmap?>(null)
        private set
    var userCode by mutableStateOf<String?>(null)
        private set
    var verificationUrl by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    val credentialsConfigured: Boolean get() = BuildConfig.DRIVE_CLIENT_ID.isNotBlank()

    /** Begin the QR device-flow sign-in and poll until the user approves or it fails. */
    fun connect(scope: CoroutineScope) {
        if (!credentialsConfigured) {
            phase = Phase.Error
            message = "No Drive credentials in this build — see docs/drive-setup.md."
            return
        }
        if (phase == Phase.Connecting) return
        scope.launch(Dispatchers.IO) {
            try {
                phase = Phase.Connecting
                message = "Scan the code with another device, then tap Allow."
                val code = auth.requestCode()
                qr = QrEncoder.encode(code.qrTarget).toImageBitmap()
                userCode = code.userCode
                verificationUrl = code.userUrl
                var interval = code.interval.coerceAtLeast(5)
                val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval * 1000L)
                    when (val r = auth.poll(code.deviceCode)) {
                        is PollResult.Authorized -> {
                            tokenProvider.onSignedIn(r.tokens)
                            connected = true
                            resetSignIn()
                            phase = Phase.Idle
                            message = "Connected to Google Drive."
                            return@launch
                        }
                        PollResult.Pending -> Unit
                        PollResult.SlowDown -> interval += 5
                        PollResult.Denied -> return@launch fail("Access denied on the sign-in page.")
                        PollResult.Expired -> return@launch fail("The code expired. Tap Connect to try again.")
                        is PollResult.Error -> return@launch fail("Sign-in failed: ${r.message}")
                    }
                }
                fail("The code expired. Tap Connect to try again.")
            } catch (e: Exception) {
                Log.e(TAG, "connect exception", e)
                fail("Sign-in error: ${e.message}")
            }
        }
    }

    /** Run a full extraction+archive, then mirror the archive to Drive. */
    fun backupNow(scope: CoroutineScope) {
        if (phase == Phase.BackingUp) return
        scope.launch(Dispatchers.IO) {
            try {
                phase = Phase.BackingUp
                message = "Backing up…"
                Log.i(TAG, "backup: extracting…")
                val s = runner.run(incremental = true)
                Log.i(TAG, "backup: extracted sms=${s.smsCount} mms=${s.mmsCount} calls=${s.callCount} " +
                    "img=${s.imageCount} vid=${s.videoCount}; uploading from ${s.outputDir}")
                val u = uploader.upload(outputDir)
                Log.i(TAG, "backup: upload done uploaded=${u.uploaded} skipped=${u.skipped} " +
                    "bytes=${u.bytesUploaded} folder=${u.rootFolderId}")
                phase = Phase.Done
                message = "Backed up ${s.smsCount} SMS, ${s.mmsCount} MMS, ${s.callCount} calls, " +
                    "${s.imageCount + s.videoCount} media. Uploaded ${u.uploaded}, skipped ${u.skipped}."
            } catch (e: Exception) {
                Log.e(TAG, "backup exception", e)
                fail("Backup failed: ${e.message}")
            }
        }
    }

    /** Called by the UI when the user declines the required read permissions. */
    fun onPermissionsDenied() {
        phase = Phase.Error
        message = "Read permissions are required to back up your data."
    }

    private fun fail(msg: String) {
        Log.w(TAG, "fail: $msg")
        resetSignIn()
        phase = Phase.Error
        message = msg
    }

    private fun resetSignIn() {
        qr = null
        userCode = null
        verificationUrl = null
    }

    private companion object {
        const val TAG = "DiffuseAuth"
    }
}
