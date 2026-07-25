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
 *
 * Defaults are **inverted** — dark modules are drawn white and the quiet zone black — so the
 * code reads as white-on-black to match the app's palette (and blends into the black page, which
 * gives it an effectively infinite quiet zone). Modern phone cameras / Google Lens decode inverted
 * QR fine; if a picky scanner ever fails, pass the standard `dark`/`light` to flip it back.
 */
fun QrMatrix.toImageBitmap(dark: Int = 0xFFFFFFFF.toInt(), light: Int = 0xFF000000.toInt()): ImageBitmap {
    val bmp = createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(size * size) { i -> if (isDark(i % size, i / size)) dark else light }
    bmp.setPixels(pixels, 0, size, 0, 0, size, size)
    return bmp.asImageBitmap()
}
