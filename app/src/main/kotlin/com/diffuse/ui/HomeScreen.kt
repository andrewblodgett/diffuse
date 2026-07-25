package com.diffuse.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diffuse.backup.store.LastRun
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/** Runtime read permissions the backup needs. Requested on demand before a backup run. */
private val REQUIRED_PERMISSIONS = buildList {
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.READ_MEDIA_IMAGES)
    add(Manifest.permission.READ_MEDIA_VIDEO)
    add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/**
 * Home screen: **Connect Drive** (QR device-flow sign-in) and **Back up now** (extract → stream
 * media to Drive → upload index), plus live progress, last-run status, and a link to Settings.
 * Driven by [BackupController]; styling reuses the vendored Light theme.
 */
@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onToggleTheme: () -> Unit) {
    val colors = LightThemeTokens.colors
    val context = LocalContext.current
    val controller = remember { BackupController(context) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // Notifications are optional; the data reads are what a backup actually needs.
        val dataGranted = grants.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }.values.all { it }
        if (dataGranted) {
            controller.backupNow(scope)
        } else {
            val activity = context as? Activity
            val canAskAgain = activity != null && REQUIRED_PERMISSIONS.any {
                it != Manifest.permission.POST_NOTIFICATIONS &&
                    activity.shouldShowRequestPermissionRationale(it)
            }
            controller.onPermissionsDenied()
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
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LightText(text = "Diffuse", variant = LightTextVariant.Heading, align = TextAlign.Center)
            LightText(
                text = "One-way backup for the Light Phone III",
                variant = LightTextVariant.Detail,
                align = TextAlign.Center,
                lighten = true,
            )

            if (!controller.connected) {
                LightButton(text = "Connect Drive") { controller.connect(scope) }
            } else {
                LightButton(text = "Back up now") { permissionLauncher.launch(REQUIRED_PERMISSIONS) }
            }

            controller.qr?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Sign-in QR code",
                    modifier = Modifier.size(220.dp),
                )
                controller.verificationUrl?.let {
                    LightText(text = it, variant = LightTextVariant.Fine, align = TextAlign.Center, lighten = true)
                }
                controller.userCode?.let {
                    LightText(text = "Code: $it", variant = LightTextVariant.Detail, align = TextAlign.Center)
                }
            }

            // --- Live progress during a run --------------------------------------
            if (controller.phase == Phase.BackingUp) {
                controller.stageText?.let {
                    LightText(text = it, variant = LightTextVariant.Detail, align = TextAlign.Center)
                }
                if (controller.mediaTotal > 0) {
                    LightText(
                        text = "${controller.mediaDone} / ${controller.mediaTotal} photos & videos",
                        variant = LightTextVariant.Fine,
                        align = TextAlign.Center,
                        lighten = true,
                    )
                    ProgressBar(controller.mediaDone.toFloat() / controller.mediaTotal)
                }
            }

            controller.message?.let {
                LightText(text = it, variant = LightTextVariant.Fine, align = TextAlign.Center, lighten = true)
            }

            // --- Last run + settings, when idle ----------------------------------
            if (controller.phase != Phase.BackingUp && controller.phase != Phase.Connecting) {
                controller.lastRun?.let { LastRunLine(it) }
                if (controller.connected) {
                    LightText(
                        text = "Settings",
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        modifier = Modifier.lightClickable(onClick = onOpenSettings),
                    )
                }
            }

            LightText(
                text = "Tap to switch theme",
                variant = LightTextVariant.Fine,
                align = TextAlign.Center,
                lighten = true,
                modifier = Modifier.lightClickable(onClick = onToggleTheme),
            )
        }
    }
}

@Composable
private fun LastRunLine(run: LastRun) {
    val when_ = relativeTime(run.timestampMs)
    val text = if (run.success) "Last backed up $when_ — ${run.summary}"
    else "Last backup ($when_) failed: ${run.summary}"
    LightText(text = text, variant = LightTextVariant.Fine, align = TextAlign.Center, lighten = true)
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

/** A bordered, tappable label styled with the Light theme. */
@Composable
private fun LightButton(text: String, onClick: () -> Unit) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = Modifier
            .border(1.dp, colors.content)
            .lightClickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = text, variant = LightTextVariant.Detail, align = TextAlign.Center)
    }
}
