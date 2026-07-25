package com.diffuse.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/** Runtime read permissions the backup needs. Requested on demand before a backup run. */
private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.READ_SMS,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.ACCESS_MEDIA_LOCATION,
)

/**
 * Home screen. Phase 3 gives it two actions: **Connect Drive** (QR device-flow sign-in)
 * and **Back up now** (extract → archive → upload), driven by [BackupController]. Styling
 * reuses the vendored Light theme. Runtime-permission UX polish and a background job are
 * Phase 4.
 */
@Composable
fun HomeScreen(onToggleTheme: () -> Unit) {
    val colors = LightThemeTokens.colors
    val context = LocalContext.current
    val controller = remember { BackupController(context) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) controller.backupNow(scope) else controller.onPermissionsDenied()
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

            controller.message?.let {
                LightText(text = it, variant = LightTextVariant.Fine, align = TextAlign.Center, lighten = true)
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
