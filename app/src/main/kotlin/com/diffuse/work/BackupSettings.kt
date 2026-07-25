package com.diffuse.work

import android.content.Context
import com.diffuse.backup.BackupContent
import java.util.Calendar

/** How often the scheduled backup runs. [Off] disables the background job entirely. */
enum class BackupFrequency(val label: String) {
    Off("Off"),
    Daily("Daily"),
    Weekly("Weekly"),
}

/**
 * User's scheduling + content choices. Defaults match the Phase-4 decision: on, daily at 2am,
 * Wi-Fi + charging, and everything backed up (photos, videos, messages & calls).
 *
 * - [hourOfDay] is 0..23, the local hour the scheduled run targets.
 * - [dayOfWeek] uses [java.util.Calendar] constants (1=Sunday … 7=Saturday) and only matters
 *   when [frequency] is [BackupFrequency.Weekly].
 */
data class BackupPrefs(
    val frequency: BackupFrequency = BackupFrequency.Daily,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = true,
    val hourOfDay: Int = 2,
    val dayOfWeek: Int = Calendar.MONDAY,
    val backupPictures: Boolean = true,
    val backupVideos: Boolean = true,
    val backupMessages: Boolean = true,
) {
    /** The content-selection view of these prefs, handed to the backup engine/runner. */
    val content: BackupContent
        get() = BackupContent(pictures = backupPictures, videos = backupVideos, messages = backupMessages)
}

/**
 * Persists [BackupPrefs] in `SharedPreferences` (matching the app's Properties/EncryptedSharedPrefs
 * convention — no DataStore dependency). The default value of every getter encodes the
 * first-install policy, so a fresh install already schedules a daily Wi-Fi+charging backup once
 * [BackupScheduler.sync] runs.
 *
 * READ-ONLY: app-private preferences only; no content-provider access.
 */
class BackupSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("backup-settings", Context.MODE_PRIVATE)

    var prefsValue: BackupPrefs
        get() = BackupPrefs(
            frequency = runCatching { BackupFrequency.valueOf(prefs.getString(KEY_FREQ, null) ?: DEFAULT.frequency.name) }
                .getOrDefault(DEFAULT.frequency),
            wifiOnly = prefs.getBoolean(KEY_WIFI, DEFAULT.wifiOnly),
            chargingOnly = prefs.getBoolean(KEY_CHARGING, DEFAULT.chargingOnly),
            hourOfDay = prefs.getInt(KEY_HOUR, DEFAULT.hourOfDay).coerceIn(0, 23),
            dayOfWeek = prefs.getInt(KEY_DOW, DEFAULT.dayOfWeek).coerceIn(1, 7),
            backupPictures = prefs.getBoolean(KEY_PICTURES, DEFAULT.backupPictures),
            backupVideos = prefs.getBoolean(KEY_VIDEOS, DEFAULT.backupVideos),
            backupMessages = prefs.getBoolean(KEY_MESSAGES, DEFAULT.backupMessages),
        )
        set(value) {
            prefs.edit()
                .putString(KEY_FREQ, value.frequency.name)
                .putBoolean(KEY_WIFI, value.wifiOnly)
                .putBoolean(KEY_CHARGING, value.chargingOnly)
                .putInt(KEY_HOUR, value.hourOfDay)
                .putInt(KEY_DOW, value.dayOfWeek)
                .putBoolean(KEY_PICTURES, value.backupPictures)
                .putBoolean(KEY_VIDEOS, value.backupVideos)
                .putBoolean(KEY_MESSAGES, value.backupMessages)
                .apply()
        }

    private companion object {
        val DEFAULT = BackupPrefs()
        const val KEY_FREQ = "frequency"
        const val KEY_WIFI = "wifi_only"
        const val KEY_CHARGING = "charging_only"
        const val KEY_HOUR = "hour_of_day"
        const val KEY_DOW = "day_of_week"
        const val KEY_PICTURES = "backup_pictures"
        const val KEY_VIDEOS = "backup_videos"
        const val KEY_MESSAGES = "backup_messages"
    }
}
