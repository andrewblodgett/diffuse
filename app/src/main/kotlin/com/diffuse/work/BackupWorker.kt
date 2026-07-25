package com.diffuse.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.diffuse.BackupEngine
import com.diffuse.backup.BackupProgress
import com.diffuse.backup.BackupStage
import com.diffuse.notify.BackupNotifier

/**
 * Runs the scheduled backup headless via the shared [BackupEngine] — the exact same
 * extract→stream→upload path as the manual "Back up now" button. Runs as a foreground
 * data-sync job so a multi-minute media upload survives Doze, showing progress in the ongoing
 * notification.
 *
 * It can't do anything interactive: if the required read permissions aren't granted, or Drive
 * isn't connected yet, it posts a "needs attention" note and finishes cleanly (the user must
 * open the app once). READ-ONLY end to end — same guarantee as the engine.
 */
class BackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val engine = BackupEngine(applicationContext)
    private val notifier = BackupNotifier(applicationContext)

    override suspend fun doWork(): Result {
        if (!hasReadPermissions()) {
            notifier.notifyPermissionsNeeded()
            return Result.success() // nothing we can fix headless; try again next schedule
        }
        if (!engine.isConnected || !engine.credentialsConfigured) {
            return Result.success() // not signed in to Drive yet; can't do it in the background
        }

        setForeground(foregroundInfo("Starting…"))
        val progress = object : BackupProgress {
            private var lastNotified = -1
            override fun onStage(stage: BackupStage) = notifier.notifyProgress(label(stage))
            override fun onMediaProgress(done: Int, total: Int) {
                // Throttle: update at most ~every 1% (or the final item) to avoid notification spam.
                val pct = if (total > 0) done * 100 / total else 0
                if (pct != lastNotified || done == total) {
                    lastNotified = pct
                    notifier.notifyProgress("Backing up photos & videos", done, total)
                }
            }
        }

        return try {
            val r = engine.runBackup(progress)
            notifier.clearProgress()
            notifier.notifyComplete(r.summary)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "scheduled backup failed", e)
            engine.recordFailure(e.message ?: "unknown error")
            notifier.clearProgress()
            notifier.notifyFailure(e.message ?: "unknown error")
            Result.retry() // transient (network) failures back off and retry
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val n = notifier.progress(text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(BackupNotifier.ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(BackupNotifier.ONGOING_ID, n)
        }
    }

    private fun hasReadPermissions(): Boolean = READ_PERMISSIONS.all {
        applicationContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun label(stage: BackupStage): String = when (stage) {
        BackupStage.Messages -> "Backing up messages…"
        BackupStage.Calls -> "Backing up calls…"
        BackupStage.Media -> "Backing up photos & videos…"
        BackupStage.UploadingIndex -> "Finishing up…"
        BackupStage.Complete -> "Done"
    }

    companion object {
        const val UNIQUE_NAME = "diffuse-scheduled-backup"
        const val TAG = "DiffuseWorker"
        private val READ_PERMISSIONS = arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
    }
}
