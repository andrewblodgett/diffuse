package com.diffuse.ui

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.diffuse.BackupEngine
import com.diffuse.backup.store.LastRun
import com.diffuse.drive.QrEncoder
import com.diffuse.drive.auth.PollResult
import com.diffuse.work.BackupScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the QR sign-in is. */
enum class SignInPhase { Idle, Preparing, WaitingForApproval, Failed }

/** Why a requested "Back up now" hasn't started yet. */
enum class QueuedRun { WaitingForNetwork, RetryingSoon }

/**
 * UI state for every screen. A ViewModel, so the sign-in poll and screen state survive
 * rotation. Backups themselves never run here: [backUpNow] hands the work to WorkManager,
 * and the screens observe [BackupEngine]'s state, so a run keeps going (and stays visible)
 * no matter what happens to the activity.
 */
class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = BackupEngine.get(app)

    val connected: StateFlow<Boolean> = engine.connected
    val accountEmail: StateFlow<String?> = engine.accountEmail
    val lastRun: StateFlow<LastRun?> = engine.lastRun
    val progress = engine.progress
    val credentialsConfigured: Boolean get() = engine.credentialsConfigured

    /** Non-null while a "Back up now" is queued but not running. */
    val queuedRun: StateFlow<QueuedRun?> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(BackupScheduler.MANUAL_WORK)
        .map { infos ->
            val queued = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
            when {
                queued == null -> null
                queued.runAttemptCount > 0 -> QueuedRun.RetryingSoon
                else -> QueuedRun.WaitingForNetwork
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A note for the home screen (e.g. why a backup couldn't start), or null. */
    var homeMessage by mutableStateOf<String?>(null)
        private set

    // --- QR sign-in state ---------------------------------------------------------------------
    var signInPhase by mutableStateOf(SignInPhase.Idle)
        private set
    var qr by mutableStateOf<ImageBitmap?>(null)
        private set
    var userCode by mutableStateOf<String?>(null)
        private set
    var verificationUrl by mutableStateOf<String?>(null)
        private set
    var signInMessage by mutableStateOf<String?>(null)
        private set

    private var signInJob: Job? = null

    init {
        engine.refreshAccountEmailIfMissing()
    }

    /**
     * Begin the QR device-flow sign-in and poll until the user approves or it fails. The
     * poll keeps running if the user steps back from the Connect screen; calling this again
     * while it runs just leaves the current code showing.
     */
    fun connect() {
        if (!credentialsConfigured) {
            failSignIn("This build has no Drive credentials. See docs/drive-setup.md.")
            return
        }
        if (signInJob?.isActive == true) return
        signInJob = viewModelScope.launch {
            try {
                signInPhase = SignInPhase.Preparing
                signInMessage = null
                val code = withContext(Dispatchers.IO) { engine.auth.requestCode() }
                qr = withContext(Dispatchers.Default) { QrEncoder.encode(code.qrTarget).toImageBitmap() }
                userCode = code.userCode
                verificationUrl = code.userUrl
                signInPhase = SignInPhase.WaitingForApproval
                pollForApproval(code.deviceCode, code.interval, code.expiresInSec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sign-in failed", e)
                failSignIn("Couldn't reach Google. Check your connection and try again.")
            }
        }
    }

    private suspend fun pollForApproval(deviceCode: String, intervalSec: Int, expiresInSec: Int) {
        var interval = intervalSec.coerceAtLeast(5)
        val deadline = System.currentTimeMillis() + expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val result = try {
                withContext(Dispatchers.IO) { engine.auth.poll(deviceCode) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "poll failed; will try again", e)
                continue // a dropped poll doesn't end the sign-in; the code is still valid
            }
            when (result) {
                is PollResult.Authorized -> {
                    if (!engine.onSignedIn(result.tokens)) {
                        return failSignIn(
                            "Google didn't grant lasting access. Remove Diffuse at " +
                                "myaccount.google.com/permissions, then connect again.",
                        )
                    }
                    resetSignIn()
                    homeMessage = "Connected to Google Drive."
                    return
                }
                PollResult.Pending -> Unit
                PollResult.SlowDown -> interval += 5
                PollResult.Denied -> return failSignIn("Access was denied on the sign-in page.")
                PollResult.Expired -> return failSignIn("The code expired. Tap Try again.")
                is PollResult.Error -> return failSignIn("Sign-in failed: ${result.message}")
            }
        }
        failSignIn("The code expired. Tap Try again.")
    }

    /** Queue a backup now; it starts as soon as there's a network connection. */
    fun backUpNow() {
        homeMessage = null
        BackupScheduler.runNow(getApplication())
    }

    fun showHomeMessage(message: String) {
        homeMessage = message
    }

    /** Forget the Drive connection and return the app to its pre-sign-in state. */
    fun signOut() {
        BackupScheduler.cancelManual(getApplication())
        engine.signOut()
        homeMessage = null
        resetSignIn()
    }

    private fun failSignIn(message: String) {
        qr = null
        userCode = null
        verificationUrl = null
        signInPhase = SignInPhase.Failed
        signInMessage = message
    }

    private fun resetSignIn() {
        qr = null
        userCode = null
        verificationUrl = null
        signInPhase = SignInPhase.Idle
        signInMessage = null
    }

    private companion object {
        const val TAG = "DiffuseUi"
    }
}
