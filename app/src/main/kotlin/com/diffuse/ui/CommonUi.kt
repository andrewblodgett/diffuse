package com.diffuse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/**
 * The single body-text size used everywhere except the big headings, so every screen reads at one
 * consistent, comfortably-large size. Text is always full-strength white on black — hierarchy comes
 * from layout, not from graying text out.
 */
internal val Body = LightTextVariant.Paragraph

/** A bordered, tappable label styled with the Light theme — the app's one button style. */
@Composable
internal fun LightButton(text: String, onClick: () -> Unit) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = Modifier
            .border(1.dp, colors.content)
            .lightClickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = text, variant = Body, align = TextAlign.Center)
    }
}

/** A simple chevron (‹ or ›) drawn in the theme's content color — for back arrows and steppers. */
@Composable
internal fun Chevron(pointsLeft: Boolean, modifier: Modifier = Modifier) {
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
