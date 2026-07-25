package com.diffuse.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.diffuse.drive.QrMatrix

/**
 * Paints a [QrMatrix] into a Compose [ImageBitmap] for display. Kept out of
 * [com.diffuse.drive.QrEncoder] so the encoder stays pure/unit-testable; this bridge is
 * the only Android-graphics part.
 */
fun QrMatrix.toImageBitmap(dark: Int = 0xFF000000.toInt(), light: Int = 0xFFFFFFFF.toInt()): ImageBitmap {
    val bmp = createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(size * size) { i -> if (isDark(i % size, i / size)) dark else light }
    bmp.setPixels(pixels, 0, size, 0, 0, size, size)
    return bmp.asImageBitmap()
}
