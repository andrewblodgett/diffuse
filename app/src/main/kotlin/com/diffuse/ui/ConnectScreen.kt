package com.diffuse.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/**
 * Full-screen Drive sign-in: a large white-on-black QR plus the verification link and the code to
 * type, so the user can connect either way without anything crowding the screen. Reads live state
 * from [controller] (started by the caller before navigating here) and returns home automatically
 * the moment the connection lands.
 *
 * @param onBack leave this screen (also the auto-return target once connected).
 * @param onRetry restart the device-flow after an error/expiry (launched on a screen-independent
 *   scope by the caller so it survives navigation).
 */
@Composable
fun ConnectScreen(controller: BackupController, onBack: () -> Unit, onRetry: () -> Unit) {
    val colors = LightThemeTokens.colors

    // The instant the poll reports success, controller.connected flips → go home.
    LaunchedEffect(controller.connected) { if (controller.connected) onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Chevron(
            pointsLeft = true,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
                .lightClickable(onClickLabel = "Back", onClick = onBack)
                .padding(8.dp)
                .size(24.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .padding(top = 96.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            LightText(text = "Connect Google Drive", variant = LightTextVariant.Heading, align = TextAlign.Center)

            val qr = controller.qr
            when {
                controller.phase == Phase.Error -> {
                    LightText(
                        text = controller.message ?: "Sign-in failed.",
                        variant = Body,
                        align = TextAlign.Center,
                    )
                    LightButton(text = "Try again", onClick = onRetry)
                }

                qr != null -> {
                    LightText(
                        text = "Scan this code, or open the link below and enter the code.",
                        variant = Body,
                        align = TextAlign.Center,
                    )
                    Image(bitmap = qr, contentDescription = "Sign-in QR code", modifier = Modifier.size(248.dp))

                    controller.verificationUrl?.let {
                        LightText(text = it, variant = Body, align = TextAlign.Center)
                    }
                    controller.userCode?.let {
                        Spacer(Modifier.height(4.dp))
                        LightText(text = "Enter this code", variant = Body, align = TextAlign.Center)
                        LightText(
                            text = it,
                            variant = LightTextVariant.Subheading,
                            align = TextAlign.Center,
                            monospace = true,
                        )
                    }
                }

                else -> {
                    LightText(
                        text = controller.message ?: "Preparing sign-in…",
                        variant = Body,
                        align = TextAlign.Center,
                    )
                }
            }
        }
    }
}
