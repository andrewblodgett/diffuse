package com.diffuse.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.diffuse.MainActivity
import com.diffuse.R

/**
 * Posts Diffuse's notifications: the ongoing progress notification (the worker's foreground
 * notification) and one result slot, reused so problems replace each other instead of
 * piling up. Without the notification permission, posting is silently skipped by Android.
 *
 * READ-ONLY: notifications are output only; nothing here reads or writes a content provider.
 */
class BackupNotifier(context: Context) {

    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)

    init {
        // IMPORTANCE_LOW keeps progress quiet (no sound).
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Backups", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Diffuse backup progress and results"
            },
        )
    }

    /** An ongoing progress notification for the foreground worker. [total] 0 → indeterminate. */
    fun progress(text: String, done: Int = 0, total: Int = 0): Notification =
        base("Backing up", text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total <= 0)
            .build()

    fun notifyProgress(text: String, done: Int = 0, total: Int = 0) =
        nm.notify(ONGOING_ID, progress(text, done, total))

    fun notifyComplete(summary: String) = result("Backup complete", summary)

    fun notifyIncomplete(summary: String) = result("Backup finished with problems", summary)

    fun notifyFailure(message: String) = result("Backup failed", message)

    fun notifyPermissionsNeeded() =
        result("Backup needs permission", "Open Diffuse and allow access to back up your data.")

    fun notifyReconnectNeeded() =
        result("Reconnect Google Drive", "Diffuse lost access to your Drive. Open Diffuse and tap Connect Drive.")

    /** Clear the ongoing progress notification (call when a run ends). */
    fun clearProgress() = nm.cancel(ONGOING_ID)

    private fun result(title: String, text: String) =
        nm.notify(RESULT_ID, base(title, text).setAutoCancel(true).build())

    private fun base(title: String, text: String): Notification.Builder =
        Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())

    private fun openAppIntent() = PendingIntent.getActivity(
        app,
        0,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "diffuse.backups"
        /** Stable id of the ongoing foreground/progress notification. */
        const val ONGOING_ID = 1001
        private const val RESULT_ID = 1002
    }
}
