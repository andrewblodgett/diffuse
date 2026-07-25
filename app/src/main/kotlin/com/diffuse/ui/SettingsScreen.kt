package com.diffuse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
import java.util.Calendar

/** One consistent body-text size across settings, matching the home screen. */
private val Body = LightTextVariant.Paragraph

/**
 * Settings: what to back up (photos / videos / messages & calls), how often the background backup
 * runs and when (hour of day, plus day of week for weekly), the network/charging constraints, and
 * a sign-out. Every change is persisted and — when it affects the schedule — reconciled with
 * WorkManager via [BackupScheduler.sync]. Defaults come from [BackupPrefs].
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onSignOut: () -> Unit) {
    val colors = LightThemeTokens.colors
    val context = LocalContext.current
    val settings = remember { BackupSettings(context) }
    var prefs by remember { mutableStateOf(settings.prefsValue) }

    fun update(new: BackupPrefs) {
        val old = prefs
        prefs = new
        settings.prefsValue = new
        // Only touch WorkManager when scheduling actually changed. A time/frequency change resets
        // the schedule window (REPLACE); a constraint-only change keeps the next-run time (UPDATE);
        // a content-only change needs no rescheduling at all.
        val timeChanged = new.frequency != old.frequency ||
            new.hourOfDay != old.hourOfDay || new.dayOfWeek != old.dayOfWeek
        val constraintsChanged = new.wifiOnly != old.wifiOnly || new.chargingOnly != old.chargingOnly
        if (timeChanged || constraintsChanged) {
            BackupScheduler.sync(context, new, reschedule = timeChanged)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Header: a back chevron (replaces the old bottom "Back" link) + title.
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chevron(
                    pointsLeft = true,
                    modifier = Modifier
                        .lightClickable(onClickLabel = "Back", onClick = onBack)
                        .padding(8.dp)
                        .size(24.dp),
                )
                LightText(text = "Settings", variant = LightTextVariant.Heading)
            }

            // --- What to back up -----------------------------------------------------
            SectionLabel("What to back up")
            ToggleRow("Photos", prefs.backupPictures) { update(prefs.copy(backupPictures = it)) }
            ToggleRow("Videos", prefs.backupVideos) { update(prefs.copy(backupVideos = it)) }
            ToggleRow("Messages & calls", prefs.backupMessages) { update(prefs.copy(backupMessages = it)) }

            // --- Automatic backup ----------------------------------------------------
            SectionLabel("Automatic backup")
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
                LabeledRow("Time") {
                    Stepper(
                        label = formatHour(prefs.hourOfDay),
                        onPrev = { update(prefs.copy(hourOfDay = (prefs.hourOfDay + 23) % 24)) },
                        onNext = { update(prefs.copy(hourOfDay = (prefs.hourOfDay + 1) % 24)) },
                    )
                }
                if (prefs.frequency == BackupFrequency.Weekly) {
                    LabeledRow("Day") {
                        Stepper(
                            label = dayName(prefs.dayOfWeek),
                            onPrev = { update(prefs.copy(dayOfWeek = prevDow(prefs.dayOfWeek))) },
                            onNext = { update(prefs.copy(dayOfWeek = nextDow(prefs.dayOfWeek))) },
                        )
                    }
                }
                ToggleRow("Only on Wi-Fi", prefs.wifiOnly) { update(prefs.copy(wifiOnly = it)) }
                ToggleRow("Only while charging", prefs.chargingOnly) { update(prefs.copy(chargingOnly = it)) }
            }

            LightText(text = scheduleSummary(prefs), variant = Body)

            Spacer(Modifier.height(8.dp))
            SignOutButton(onSignOut)
        }
    }
}

/** Human summary of the current schedule for the footer line. */
private fun scheduleSummary(prefs: BackupPrefs): String {
    val conditions = (if (prefs.wifiOnly) ", on Wi-Fi" else "") +
        (if (prefs.chargingOnly) ", while charging" else "")
    return when (prefs.frequency) {
        BackupFrequency.Off -> "Background backup is off. Use “Back up now” to run manually."
        BackupFrequency.Daily -> "Backs up daily at ${formatHour(prefs.hourOfDay)}$conditions."
        BackupFrequency.Weekly ->
            "Backs up every ${dayName(prefs.dayOfWeek)} at ${formatHour(prefs.hourOfDay)}$conditions."
    }
}

private fun formatHour(hour: Int): String {
    val h = hour.coerceIn(0, 23)
    val display = when (val m = h % 12) { 0 -> 12; else -> m }
    val meridiem = if (h < 12) "AM" else "PM"
    return "$display:00 $meridiem"
}

private fun dayName(dow: Int): String = when (dow) {
    Calendar.SUNDAY -> "Sunday"
    Calendar.MONDAY -> "Monday"
    Calendar.TUESDAY -> "Tuesday"
    Calendar.WEDNESDAY -> "Wednesday"
    Calendar.THURSDAY -> "Thursday"
    Calendar.FRIDAY -> "Friday"
    else -> "Saturday"
}

// Calendar days run 1 (Sunday) … 7 (Saturday); wrap around either end.
private fun nextDow(dow: Int): Int = if (dow >= Calendar.SATURDAY) Calendar.SUNDAY else dow + 1
private fun prevDow(dow: Int): Int = if (dow <= Calendar.SUNDAY) Calendar.SATURDAY else dow - 1

@Composable
private fun SectionLabel(text: String) {
    LightText(text = text, variant = Body)
}

/** A label on the left and arbitrary trailing control on the right, like [ToggleRow]. */
@Composable
private fun LabeledRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = label, variant = Body)
        trailing()
    }
}

/** ‹ value › — two chevrons flanking a fixed-width label, for hour/day selection. */
@Composable
private fun Stepper(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(
            pointsLeft = true,
            modifier = Modifier.lightClickable(onClickLabel = "Previous", onClick = onPrev)
                .padding(8.dp).size(16.dp),
        )
        Box(modifier = Modifier.width(112.dp), contentAlignment = Alignment.Center) {
            LightText(text = label, variant = Body, align = TextAlign.Center)
        }
        Chevron(
            pointsLeft = false,
            modifier = Modifier.lightClickable(onClickLabel = "Next", onClick = onNext)
                .padding(8.dp).size(16.dp),
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LightThemeTokens.colors
    // A bordered pill. The selected one is filled (white box, black text) so the choice is obvious
    // at a glance; unselected chips stay white-on-black like the rest of the screen.
    Box(
        modifier = Modifier
            .border(1.dp, colors.content)
            .background(if (selected) colors.content else colors.background)
            .lightClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = text,
            variant = Body,
            align = TextAlign.Center,
            color = if (selected) colors.background else colors.content,
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = label, variant = Body)
        LightText(text = if (on) "On" else "Off", variant = Body)
    }
}

/** A bordered "Sign out" that requires a confirming second tap. */
@Composable
private fun SignOutButton(onSignOut: () -> Unit) {
    val colors = LightThemeTokens.colors
    var confirming by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.content)
            .lightClickable(onClick = { if (confirming) onSignOut() else confirming = true })
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = if (confirming) "Tap again to sign out" else "Sign out",
            variant = Body,
            align = TextAlign.Center,
        )
    }
}

/** A simple chevron (‹ or ›) drawn in the theme's content color. */
@Composable
private fun Chevron(pointsLeft: Boolean, modifier: Modifier = Modifier) {
    val color = LightThemeTokens.colors.content
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.14f
        val tipX = if (pointsLeft) size.width * 0.28f else size.width * 0.72f
        val armX = if (pointsLeft) size.width * 0.66f else size.width * 0.34f
        val topY = size.height * 0.18f
        val midY = size.height * 0.5f
        val botY = size.height * 0.82f
        drawLine(color, Offset(armX, topY), Offset(tipX, midY), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(tipX, midY), Offset(armX, botY), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
