package com.diffuse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.diffuse.ui.HomeScreen
import com.diffuse.ui.SettingsScreen
import com.diffuse.work.BackupScheduler
import com.diffuse.work.BackupSettings
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Reconcile the scheduled backup with saved settings on every launch. On a fresh
        // install the defaults are daily / Wi-Fi + charging, so this enqueues the first job.
        BackupScheduler.sync(this, BackupSettings(this).prefsValue)

        setContent {
            // Fixed dark palette (white on black). Light/dark is a device-level setting on the
            // LP3, handled by the OS — the app doesn't offer its own toggle.
            var showSettings by remember { mutableStateOf(false) }
            LightTheme(colors = LightThemeColors.Dark) {
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    HomeScreen(onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
