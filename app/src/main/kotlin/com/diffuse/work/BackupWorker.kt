package com.diffuse.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.diffuse.BackupEngine
import com.diffuse.RunOutcome
import com.diffuse.describe
import com.diffuse.drive.DriveHttpException
import com.diffuse.drive.auth.AuthRevokedException
import com.diffuse.notify.BackupNotifier
import com.diffuse.util.causeOfType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The only thing that ever runs a backup, for both the "Back up now" button and the
 * schedule (see [BackupScheduler]). Running everything through WorkManager means a backup
 * survives leaving the app, screen rotation, and process death, and is retried with backoff
 * after a network failure.
 *
 * Runs as a foreground data-sync job so a long media upload survives Doze, mirroring the
 * engine's live progress into the ongoing notification.
 *
 * Outcomes are sorted by what fixes them:
 *  - transient trouble (network, Drive hiccups) → retried quietly with backoff; the user is
 *    only notified if every retry fails;
 *  - Drive access revoked → the app disconnects and asks the user to reconnect;
 *  - Drive full → the user is told, without pointless retries;
 *  - missing permissions → a notification asking the user to open the app.
 *
 * READ-ONLY end to end, with the same guarantee as [BackupEngine].
 */
class BackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val engine = BackupEngine.get(applicationContext)
    private val notifier = BackupNotifier(applicationContext)
    private val isManual = inputData.getBoolean(KEY_MANUAL, false)

    override suspend fun doWork(): Result = coroutineScope {
        promoteToForeground()
        val progressUpdates = launch {
            engine.progress.filterNotNull()
                .map { it.describe() }
                // Re-post only when the stage or whole percent changes: skipping already-uploaded
                // photos runs through hundreds of items a second, faster than Android accepts updates.
                .distinctUntilChangedBy { it.label to it.percent }
                .collect { notifier.notifyProgress(it.text, it.done, it.total) }
        }
        try {
            handle(engine.runBackup())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Look through wrappers: a run aborted after several uploads hit "Drive is full"
            // should be reported as exactly that.
            when {
                e.causeOfType<AuthRevokedException>() != null -> {
                    notifier.notifyReconnectNeeded()
                    Result.failure()
                }
                e.causeOfType<DriveHttpException>()?.isStorageFull == true -> {
                    notifier.notifyFailure("Your Google Drive is full. Free up space and Diffuse will continue.")
                    Result.failure()
                }
                else -> retryOrGiveUp(e)
            }
        } finally {
            progressUpdates.cancel()
            notifier.clearProgress()
        }
    }

    private fun handle(outcome: RunOutcome): Result = when (outcome) {
        is RunOutcome.Completed -> {
            // Part of what the user asked to back up is being left out every run: tell them.
            if (!outcome.skippedForPermission.isEmpty) notifier.notifyPermissionsNeeded()
            if (outcome.failedItems > 0) {
                // A few items failing is usually a passing hiccup; try those again soon rather
                // than waiting for the next scheduled run (everything that worked is skipped
                // then). Only bother the user if they keep failing.
                if (runAttemptCount < MAX_RETRIES) {
                    Result.retry()
                } else {
                    notifier.notifyIncomplete(outcome.summaryText)
                    Result.success()
                }
            } else {
                if (isManual) notifier.notifyComplete(outcome.summaryText)
                Result.success()
            }
        }
        RunOutcome.NeedsPermission -> {
            notifier.notifyPermissionsNeeded()
            Result.success()
        }
        // Nothing to do, or another run is already doing it.
        RunOutcome.AlreadyRunning, RunOutcome.NotConnected, RunOutcome.NothingSelected -> Result.success()
    }

    private fun retryOrGiveUp(e: Exception): Result {
        Log.w(TAG, "backup attempt ${runAttemptCount + 1} failed", e)
        if (runAttemptCount < MAX_RETRIES) return Result.retry()
        notifier.notifyFailure(e.message ?: "Unknown error")
        return Result.failure()
    }

    /**
     * Become a foreground service so the system lets a long upload finish. If the system
     * refuses (it can, for jobs started in the background), carry on as a normal job: it may
     * be stopped after ~10 minutes, but because every upload is skipped once done, the retry
     * continues where this attempt stopped.
     */
    private suspend fun promoteToForeground() {
        try {
            setForeground(foregroundInfo("Starting…"))
        } catch (e: Exception) {
            Log.w(TAG, "could not run in the foreground; continuing as a regular job", e)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Starting…")

    private fun foregroundInfo(text: String) = ForegroundInfo(
        BackupNotifier.ONGOING_ID,
        notifier.progress(text),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    companion object {
        const val KEY_MANUAL = "manual"
        private const val TAG = "DiffuseWorker"

        /** Attempts after the first before a failure is reported to the user. */
        private const val MAX_RETRIES = 4

        fun inputData(manual: Boolean) = workDataOf(KEY_MANUAL to manual)
    }
}
