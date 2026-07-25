package com.diffuse.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.diffuse.BackupEngine
import com.diffuse.backup.BackupProgress
import com.diffuse.backup.BackupStage
import com.diffuse.backup.store.LastRun
import com.diffuse.drive.QrEncoder
import com.diffuse.drive.auth.PollResult
import com.diffuse.notify.BackupNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Coarse screen state for the home UI. */
enum class Phase { Idle, Connecting, BackingUp, Done, Error }

/**
 * Drives the home screen: QR sign-in and "back up now", plus live progress/status. The actual
 * backup work lives in [BackupEngine] (shared with the scheduled worker); this class is just the
 * UI-state layer — a plain remembered holder, not a ViewModel, to keep wiring dependency-light.
 *
 * READ-ONLY: all real work is [BackupEngine], which only reads on-device data and writes to our
 * own files / Drive; nothing here mutates a content provider.
 */
class BackupController(context: Context) {

    private val engine = BackupEngine(context)
    private val notifier = BackupNotifier(context)

    var connected by mutableStateOf(engine.isConnected)
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

    /** Live stage label during a run (e.g. "Backing up messages…"), else null. */
    var stageText by mutableStateOf<String?>(null)
        private set
    /** Media streamed so far this run / total, for a progress bar; total 0 = not started. */
    var mediaDone by mutableStateOf(0)
        private set
    var mediaTotal by mutableStateOf(0)
        private set
    /** Outcome of the previous run, loaded from disk so it survives process death. */
    var lastRun by mutableStateOf<LastRun?>(engine.lastRun())
        private set

    val credentialsConfigured: Boolean get() = engine.credentialsConfigured

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
                val code = engine.auth.requestCode()
                qr = QrEncoder.encode(code.qrTarget).toImageBitmap()
                userCode = code.userCode
                verificationUrl = code.userUrl
                var interval = code.interval.coerceAtLeast(5)
                val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval * 1000L)
                    when (val r = engine.auth.poll(code.deviceCode)) {
                        is PollResult.Authorized -> {
                            engine.tokenProvider.onSignedIn(r.tokens)
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

    /** Run a full incremental backup: extract, stream media to Drive, upload the index. */
    fun backupNow(scope: CoroutineScope) {
        if (phase == Phase.BackingUp) return
        scope.launch(Dispatchers.IO) {
            phase = Phase.BackingUp
            mediaDone = 0
            mediaTotal = 0
            stageText = "Starting…"
            message = null
            val progress = object : BackupProgress {
                override fun onStage(stage: BackupStage) { stageText = label(stage) }
                override fun onMediaProgress(done: Int, total: Int) { mediaDone = done; mediaTotal = total }
            }
            try {
                val r = engine.runBackup(progress)
                stageText = null
                phase = Phase.Done
                message = "Backed up ${r.summary}."
                lastRun = engine.lastRun()
                notifier.notifyComplete(r.summary)
            } catch (e: Exception) {
                Log.e(TAG, "backup exception", e)
                engine.recordFailure(e.message ?: "unknown error")
                lastRun = engine.lastRun()
                notifier.notifyFailure(e.message ?: "unknown error")
                stageText = null
                fail("Backup failed: ${e.message}")
            }
        }
    }

    /** Forget the Drive connection and return the home screen to its pre-sign-in state. */
    fun signOut() {
        engine.signOut()
        connected = false
        phase = Phase.Idle
        message = null
        resetSignIn()
    }

    /** Called by the UI when the user declines the required read permissions. */
    fun onPermissionsDenied() {
        phase = Phase.Error
        message = "Read permissions are required to back up your data."
    }

    private fun label(stage: BackupStage): String = when (stage) {
        BackupStage.Messages -> "Backing up messages…"
        BackupStage.Calls -> "Backing up calls…"
        BackupStage.Media -> "Backing up photos & videos…"
        BackupStage.UploadingIndex -> "Finishing up…"
        BackupStage.Complete -> "Done"
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
        const val TAG = "DiffuseBackup"
    }
}
