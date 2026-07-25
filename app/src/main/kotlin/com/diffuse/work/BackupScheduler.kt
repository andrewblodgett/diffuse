package com.diffuse.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
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

    /**
     * Reconcile the scheduled job with [prefs]. Idempotent — safe to call every launch.
     *
     * @param reschedule when true (a settings edit that moved the frequency/time), REPLACE the
     *   job so the new [initialDelayMillis] takes effect and the next run lands on the chosen hour.
     *   When false (an ordinary launch), UPDATE in place so we don't keep pushing the next run out
     *   every time the app is opened.
     */
    fun sync(context: Context, prefs: BackupPrefs, reschedule: Boolean = false) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (prefs.frequency == BackupFrequency.Off) {
            wm.cancelUniqueWork(BackupWorker.UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(intervalHours(prefs.frequency), TimeUnit.HOURS)
            .setInitialDelay(initialDelayMillis(prefs, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .setConstraints(constraints(prefs))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        val policy = if (reschedule) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.UPDATE
        wm.enqueueUniquePeriodicWork(BackupWorker.UNIQUE_NAME, policy, request)
    }

    /** Repeat interval in hours for a frequency. Pure — unit-tested. */
    fun intervalHours(frequency: BackupFrequency): Long = when (frequency) {
        BackupFrequency.Daily -> 24
        BackupFrequency.Weekly -> 24 * 7
        BackupFrequency.Off -> 24 // unused (Off cancels), but keep total.
    }

    /**
     * Milliseconds from [nowMillis] until the next run should fire — the next local
     * [BackupPrefs.hourOfDay] (Daily) or the next [BackupPrefs.dayOfWeek] at that hour (Weekly).
     * Used as the periodic job's initial delay so the first run lands on the chosen time; WorkManager
     * then repeats it roughly a period later. Pure (takes the clock as a parameter) — unit-tested.
     */
    fun initialDelayMillis(prefs: BackupPrefs, nowMillis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, prefs.hourOfDay.coerceIn(0, 23))
        }
        if (prefs.frequency == BackupFrequency.Weekly) {
            cal.set(Calendar.DAY_OF_WEEK, prefs.dayOfWeek.coerceIn(1, 7))
            // set(DAY_OF_WEEK) may land earlier in the current week; step forward until it's ahead.
            while (cal.timeInMillis <= nowMillis) cal.add(Calendar.DAY_OF_YEAR, 7)
        } else if (cal.timeInMillis <= nowMillis) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis - nowMillis
    }

    private fun constraints(prefs: BackupPrefs): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(prefs.chargingOnly)
            .build()
}
