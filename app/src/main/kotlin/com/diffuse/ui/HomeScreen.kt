package com.diffuse.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diffuse.BackupPermissions
import com.diffuse.backup.store.LastRun
import com.diffuse.describe
import com.diffuse.work.BackupSettings
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlin.math.cos
import kotlin.math.sin

/**
 * Home screen: **Connect Drive** (QR device-flow sign-in) and **Back up now**, plus live
 * progress (whether the run was started here or by the schedule), last-run status, and a
 * gear that opens Settings. State comes from [BackupViewModel]; styling reuses the vendored
 * Light theme.
 */
@Composable
fun HomeScreen(vm: BackupViewModel, onOpenSettings: () -> Unit, onConnect: () -> Unit) {
    val colors = LightThemeTokens.colors
    val context = LocalContext.current
    val connected by vm.connected.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val queuedRun by vm.queuedRun.collectAsStateWithLifecycle()
    val lastRun by vm.lastRun.collectAsStateWithLifecycle()
    val busy = progress != null || queuedRun != null

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // Back up whatever is allowed; the run's summary names anything skipped.
        val selected = BackupSettings(context).prefsValue.content
        if (!(selected intersect BackupPermissions.permitted(context)).isEmpty) {
            vm.backUpNow()
            return@rememberLauncherForActivityResult
        }
        vm.showHomeMessage("Diffuse needs permission to read your data to back it up.")
        val activity = context as? Activity
        val canAskAgain = activity != null &&
            // Only the required permissions count: an optional one (notifications, location)
            // still being askable mustn't keep the user from reaching App Settings.
            BackupPermissions.requiredFor(selected).any { activity.shouldShowRequestPermissionRationale(it) }
        if (!canAskAgain) {
            // Permanently denied → the only way to grant is App Settings.
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun onBackUpNow() {
        val selected = BackupSettings(context).prefsValue.content
        if (selected.isEmpty) {
            vm.showHomeMessage("Nothing is selected. Turn on photos, videos or messages in Settings.")
        } else {
            permissionLauncher.launch(BackupPermissions.toRequest(selected))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            LightText(text = "Diffuse", variant = LightTextVariant.Heading, align = TextAlign.Center)
            LightText(
                text = "One-way backup for the Light Phone III",
                variant = Body,
                align = TextAlign.Center,
            )

            when {
                !connected -> LightButton(text = "Connect Drive", onClick = onConnect)
                !busy -> LightButton(text = "Back up now", onClick = ::onBackUpNow)
            }

            // --- Live state of a run ---------------------------------------------------------
            val running = progress
            when {
                running != null -> {
                    val line = running.describe()
                    LightText(text = line.text, variant = Body, align = TextAlign.Center)
                    if (line.total > 0) ProgressBar(line.done.toFloat() / line.total)
                }
                queuedRun == QueuedRun.WaitingForNetwork ->
                    LightText(text = "Waiting for a network connection…", variant = Body, align = TextAlign.Center)
                queuedRun == QueuedRun.RetryingSoon ->
                    LightText(text = "Backup interrupted. Retrying shortly…", variant = Body, align = TextAlign.Center)
            }

            vm.homeMessage?.let {
                LightText(text = it, variant = Body, align = TextAlign.Center)
            }

            if (!busy) lastRun?.let { LastRunLine(it) }
        }

        // Settings gear, anchored to the bottom, once Drive is connected.
        if (connected) {
            GearIcon(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .lightClickable(onClickLabel = "Settings", onClick = onOpenSettings)
                    .padding(12.dp)
                    .size(32.dp),
            )
        }
    }
}

@Composable
private fun LastRunLine(run: LastRun) {
    val ago = relativeTime(run.timestampMs)
    val text = when {
        !run.success -> "Last backup ($ago) failed: ${run.summary}"
        run.failedItems > 0 -> "Last backed up $ago, with problems: ${run.summary}"
        else -> "Last backed up $ago: ${run.summary}"
    }
    LightText(text = text, variant = Body, align = TextAlign.Center)
}

/** A minimal determinate progress bar drawn with theme colors (no Material dependency). */
@Composable
private fun ProgressBar(fraction: Float) {
    val colors = LightThemeTokens.colors
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .width(220.dp)
            .height(4.dp)
            .border(1.dp, colors.content),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(f)
                .background(colors.content),
        )
    }
}

/** Coarse "N minutes/hours/days ago" for the last-run line. */
private fun relativeTime(epochMs: Long): String {
    val secs = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        secs < 60 -> "just now"
        secs < 3600 -> "${secs / 60}m ago"
        secs < 86_400 -> "${secs / 3600}h ago"
        else -> "${secs / 86_400}d ago"
    }
}

/** A simple gear glyph drawn in the theme's content color — the entry point to Settings. */
@Composable
private fun GearIcon(modifier: Modifier = Modifier) {
    val color = LightThemeTokens.colors.content
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke = size.minDimension * 0.10f
        val ring = size.minDimension * 0.30f
        val toothInner = ring + stroke * 0.3f
        val toothOuter = size.minDimension * 0.48f
        val teeth = 8
        for (i in 0 until teeth) {
            val ang = (Math.PI * 2 / teeth * i).toFloat()
            val dx = cos(ang)
            val dy = sin(ang)
            drawLine(
                color = color,
                start = Offset(cx + dx * toothInner, cy + dy * toothInner),
                end = Offset(cx + dx * toothOuter, cy + dy * toothOuter),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(color = color, radius = ring, center = Offset(cx, cy), style = Stroke(width = stroke))
        drawCircle(color = color, radius = ring * 0.34f, center = Offset(cx, cy), style = Stroke(width = stroke))
    }
}
