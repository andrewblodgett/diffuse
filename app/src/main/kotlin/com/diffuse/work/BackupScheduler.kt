package com.diffuse.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Every backup runs as WorkManager work:
 *  - the schedule: one unique periodic [BackupWorker] mirroring the user's [BackupPrefs],
 *    kept in sync by [sync] (call it on launch and after every settings edit);
 *  - "Back up now": one unique, expedited one-time [BackupWorker] ([runNow]).
 *
 * The two can be queued at once; [com.diffuse.BackupEngine]'s lock makes sure they never
 * actually run at the same time.
 *
 * READ-ONLY: only schedules work; the worker itself is read-only via the engine.
 */
object BackupScheduler {

    /** Unique name of the periodic scheduled backup. */
    private const val SCHEDULED_WORK = "diffuse-scheduled-backup"

    /** Unique name of the "Back up now" work, for observing its state. */
    const val MANUAL_WORK = "diffuse-manual-backup"

    /**
     * Start a backup now. It waits for any network connection (the Wi-Fi and charging
     * preferences only apply to the schedule, since the user asked for this one). If a manual
     * backup is already queued or running, this does nothing.
     */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(BackupWorker.inputData(manual = true))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** Drop a queued "Back up now" (e.g. on sign-out). A run already in progress is stopped. */
    fun cancelManual(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(MANUAL_WORK)
    }

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
            wm.cancelUniqueWork(SCHEDULED_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(intervalHours(prefs.frequency), TimeUnit.HOURS)
            .setInitialDelay(initialDelayMillis(prefs, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .setConstraints(constraints(prefs))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .setInputData(BackupWorker.inputData(manual = false))
            .build()
        val policy = if (reschedule) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.UPDATE
        wm.enqueueUniquePeriodicWork(SCHEDULED_WORK, policy, request)
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
