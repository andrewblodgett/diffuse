package com.diffuse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.diffuse.ui.BackupController
import com.diffuse.ui.ConnectScreen
import com.diffuse.ui.HomeScreen
import com.diffuse.ui.SettingsScreen
import com.diffuse.work.BackupScheduler
import com.diffuse.work.BackupSettings
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors

/** The three top-level screens. Flat nav is enough — there's no back stack to speak of. */
private enum class Screen { Home, Connect, Settings }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Reconcile the scheduled backup with saved settings on every launch. On a fresh
        // install the defaults are daily / Wi-Fi + charging, so this enqueues the first job.
        BackupScheduler.sync(this, BackupSettings(this).prefsValue)

        setContent {
            // Fixed dark palette (white on black). Light/dark is a device-level setting on the
            // LP3, handled by the OS — the app doesn't offer its own toggle.
            val context = LocalContext.current
            // One controller shared across screens so state (connection, account) stays consistent.
            val controller = remember { BackupController(context) }
            // A scope tied to the Activity, not any one screen, so the sign-in poll keeps running
            // when we navigate from Connect back to Home while waiting for approval.
            val scope = rememberCoroutineScope()
            var screen by remember { mutableStateOf(Screen.Home) }
            LightTheme(colors = LightThemeColors.Dark) {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        controller = controller,
                        onOpenSettings = { screen = Screen.Settings },
                        onConnect = {
                            controller.connect(scope)
                            screen = Screen.Connect
                        },
                    )
                    Screen.Connect -> ConnectScreen(
                        controller = controller,
                        onBack = { screen = Screen.Home },
                        onRetry = { controller.connect(scope) },
                    )
                    Screen.Settings -> SettingsScreen(
                        controller = controller,
                        onBack = { screen = Screen.Home },
                        onSignOut = {
                            controller.signOut()
                            screen = Screen.Home
                        },
                    )
                }
            }
        }
    }
}
