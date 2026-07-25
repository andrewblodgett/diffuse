package com.diffuse.work

import android.content.Context

/** How often the scheduled backup runs. [Off] disables the background job entirely. */
enum class BackupFrequency(val label: String) {
    Off("Off"),
    Daily("Daily"),
    Weekly("Weekly"),
}

/** User's scheduling choices. Defaults match the Phase-4 decision: on, daily, Wi-Fi + charging. */
data class BackupPrefs(
    val frequency: BackupFrequency = BackupFrequency.Daily,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = true,
)

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
        )
        set(value) {
            prefs.edit()
                .putString(KEY_FREQ, value.frequency.name)
                .putBoolean(KEY_WIFI, value.wifiOnly)
                .putBoolean(KEY_CHARGING, value.chargingOnly)
                .apply()
        }

    private companion object {
        val DEFAULT = BackupPrefs()
        const val KEY_FREQ = "frequency"
        const val KEY_WIFI = "wifi_only"
        const val KEY_CHARGING = "charging_only"
    }
}
