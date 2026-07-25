package com.diffuse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/**
 * Phase 1 home screen. Its job is to prove the vendored Light theme renders on
 * the LP3 — Akkurat resolves from the system fonts, typography scales to the
 * screen, and the dark/light palette flips on tap. Backup functionality lands
 * in later phases.
 */
@Composable
fun HomeScreen(onToggleTheme: () -> Unit) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LightText(
                text = "Diffuse",
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
            )
            LightText(
                text = "One-way backup for the Light Phone III",
                variant = LightTextVariant.Detail,
                align = TextAlign.Center,
                lighten = true,
            )
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
