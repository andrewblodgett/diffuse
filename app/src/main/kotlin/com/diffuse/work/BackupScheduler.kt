package com.diffuse.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Translates the user's [BackupPrefs] into a WorkManager schedule and keeps them in sync:
 * one unique periodic [BackupWorker], re-enqueued whenever settings change (or cancelled when
 * frequency is Off). Call [sync] on app launch (so a fresh install picks up the daily default)
 * and after every settings edit.
 *
 * READ-ONLY: only schedules work; the worker itself is read-only via [BackupEngine].
 */
object BackupScheduler {

    /** Reconcile the scheduled job with [prefs]. Idempotent — safe to call every launch. */
    fun sync(context: Context, prefs: BackupPrefs) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (prefs.frequency == BackupFrequency.Off) {
            wm.cancelUniqueWork(BackupWorker.UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(intervalHours(prefs.frequency), TimeUnit.HOURS)
            .setConstraints(constraints(prefs))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        // UPDATE keeps the existing schedule's next-run time when only constraints changed.
        wm.enqueueUniquePeriodicWork(BackupWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Repeat interval in hours for a frequency. Pure — unit-tested. */
    fun intervalHours(frequency: BackupFrequency): Long = when (frequency) {
        BackupFrequency.Daily -> 24
        BackupFrequency.Weekly -> 24 * 7
        BackupFrequency.Off -> 24 // unused (Off cancels), but keep total.
    }

    private fun constraints(prefs: BackupPrefs): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(prefs.chargingOnly)
            .build()
}
