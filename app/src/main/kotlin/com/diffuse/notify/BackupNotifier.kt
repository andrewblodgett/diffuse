package com.diffuse.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.diffuse.MainActivity
import com.diffuse.R

/**
 * Posts Diffuse's backup notifications: an ongoing progress notification (used as the scheduled
 * worker's foreground notification so a multi-minute upload survives Doze) and terminal
 * completion / failure / permissions-needed notes.
 *
 * READ-ONLY: notifications are output only; nothing here reads or writes a content provider.
 */
class BackupNotifier(context: Context) {

    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)

    init {
        // minSdk 33 → channels always exist; IMPORTANCE_LOW keeps progress quiet (no sound).
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
            .setProgress(total, done, total <= 0)
            .build()

    fun notifyProgress(text: String, done: Int = 0, total: Int = 0) =
        nm.notify(ONGOING_ID, progress(text, done, total))

    fun notifyComplete(summary: String) =
        nm.notify(RESULT_ID, base("Backup complete", summary).setAutoCancel(true).build())

    fun notifyFailure(message: String) =
        nm.notify(RESULT_ID, base("Backup failed", message).setAutoCancel(true).build())

    fun notifyPermissionsNeeded() = nm.notify(
        RESULT_ID,
        base("Backup needs permission", "Open Diffuse and grant read access to back up.")
            .setAutoCancel(true).build(),
    )

    /** Clear the ongoing progress notification (call when a foreground run ends). */
    fun clearProgress() = nm.cancel(ONGOING_ID)

    private fun base(title: String, text: String): Notification.Builder =
        Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())

    private fun openAppIntent() = android.app.PendingIntent.getActivity(
        app,
        0,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "diffuse.backups"
        /** Stable id of the ongoing foreground/progress notification. */
        const val ONGOING_ID = 1001
        private const val RESULT_ID = 1002
    }
}
