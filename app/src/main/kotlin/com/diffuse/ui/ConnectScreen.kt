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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/**
 * Full-screen Drive sign-in: a large white-on-black QR plus the verification link and the code to
 * type, so the user can connect either way without anything crowding the screen. Reads live state
 * from [vm] (the sign-in is started by the caller before navigating here) and returns home
 * automatically the moment the connection lands.
 *
 * @param onBack leave this screen (also the auto-return target once connected).
 * @param onRetry restart the device-flow after an error/expiry (it runs in the ViewModel, so it
 *   survives navigation and rotation).
 */
@Composable
fun ConnectScreen(vm: BackupViewModel, onBack: () -> Unit, onRetry: () -> Unit) {
    val colors = LightThemeTokens.colors
    val connected by vm.connected.collectAsStateWithLifecycle()

    // The instant the poll reports success, connected flips → go home.
    LaunchedEffect(connected) { if (connected) onBack() }

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

            val qr = vm.qr
            when {
                vm.signInPhase == SignInPhase.Failed -> {
                    LightText(
                        text = vm.signInMessage ?: "Sign-in failed.",
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

                    vm.verificationUrl?.let {
                        LightText(text = it, variant = Body, align = TextAlign.Center)
                    }
                    vm.userCode?.let {
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
                        text = "Preparing sign-in…",
                        variant = Body,
                        align = TextAlign.Center,
                    )
                }
            }
        }
    }
}
