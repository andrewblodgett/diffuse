package com.diffuse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diffuse.work.BackupFrequency
import com.diffuse.work.BackupPrefs
import com.diffuse.work.BackupScheduler
import com.diffuse.work.BackupSettings
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/**
 * Scheduling settings: how often the background backup runs and under what conditions. Every
 * change is persisted and immediately reconciled with WorkManager via [BackupScheduler.sync].
 * Defaults (daily, Wi-Fi + charging) come from [BackupPrefs].
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val colors = LightThemeTokens.colors
    val context = LocalContext.current
    val settings = remember { BackupSettings(context) }
    var prefs by remember { mutableStateOf(settings.prefsValue) }

    fun update(new: BackupPrefs) {
        prefs = new
        settings.prefsValue = new
        BackupScheduler.sync(context, new)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LightText(text = "Settings", variant = LightTextVariant.Heading)

            LightText(text = "Automatic backup", variant = LightTextVariant.Detail, lighten = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupFrequency.entries.forEach { freq ->
                    Chip(
                        text = freq.label,
                        selected = prefs.frequency == freq,
                        onClick = { update(prefs.copy(frequency = freq)) },
                    )
                }
            }

            val schedulingOn = prefs.frequency != BackupFrequency.Off
            if (schedulingOn) {
                ToggleRow(
                    label = "Only on Wi-Fi",
                    on = prefs.wifiOnly,
                    onToggle = { update(prefs.copy(wifiOnly = it)) },
                )
                ToggleRow(
                    label = "Only while charging",
                    on = prefs.chargingOnly,
                    onToggle = { update(prefs.copy(chargingOnly = it)) },
                )
            }

            LightText(
                text = when (prefs.frequency) {
                    BackupFrequency.Off -> "Background backup is off. Use “Back up now” to run manually."
                    else -> "Backs up ${prefs.frequency.label.lowercase()}" +
                        (if (prefs.wifiOnly) ", on Wi-Fi" else "") +
                        (if (prefs.chargingOnly) ", while charging" else "") + "."
                },
                variant = LightTextVariant.Fine,
                lighten = true,
            )

            LightText(
                text = "Back",
                variant = LightTextVariant.Detail,
                modifier = Modifier.lightClickable(onClick = onBack),
            )
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LightThemeTokens.colors
    // A bordered pill; the selected one is marked with a leading dot and full-strength text,
    // unselected ones are lightened — legible without relying on a text-color override.
    Box(
        modifier = Modifier
            .border(1.dp, colors.content)
            .lightClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = if (selected) "• $text" else text,
            variant = LightTextVariant.Detail,
            align = TextAlign.Center,
            lighten = !selected,
        )
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = { onToggle(!on) })
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LightText(text = label, variant = LightTextVariant.Detail)
        LightText(text = if (on) "On" else "Off", variant = LightTextVariant.Detail, lighten = !on)
    }
}
