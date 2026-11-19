package com.diffuse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diffuse.ui.BackupViewModel
import com.diffuse.ui.ConnectScreen
import com.diffuse.ui.HomeScreen
import com.diffuse.ui.SettingsScreen
import com.diffuse.work.BackupScheduler
import com.diffuse.work.BackupSettings
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors

/** The three top-level screens. Flat nav is enough: there's no back stack to speak of. */
private enum class Screen { Home, Connect, Settings }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Reconcile the scheduled backup with saved settings on every launch. On a fresh
        // install the defaults are daily / Wi-Fi + charging, so this enqueues the first job.
        BackupScheduler.sync(this, BackupSettings(this).prefsValue)

        setContent {
            // Fixed dark palette (white on black). Light/dark is a device-level setting on the
            // LP3, handled by the OS; the app doesn't offer its own toggle.
            val vm: BackupViewModel = viewModel()
            // Saveable, so rotation or a theme change doesn't bounce the user back to Home.
            var screen by rememberSaveable { mutableStateOf(Screen.Home) }
            // System back from a sub-screen returns Home instead of closing the app.
            BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

            LightTheme(colors = LightThemeColors.Dark) {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        vm = vm,
                        onOpenSettings = { screen = Screen.Settings },
                        onConnect = {
                            vm.connect()
                            screen = Screen.Connect
                        },
                    )
                    Screen.Connect -> ConnectScreen(
                        vm = vm,
                        onBack = { screen = Screen.Home },
                        onRetry = { vm.connect() },
                    )
                    Screen.Settings -> SettingsScreen(
                        vm = vm,
                        onBack = { screen = Screen.Home },
                        onSignOut = {
                            vm.signOut()
                            screen = Screen.Home
                        },
                    )
                }
            }
        }
    }
}
