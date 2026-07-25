package com.diffuse.drive

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A square QR bitmap as a flat boolean grid; [isDark] tells whether a module is on. */
class QrMatrix(val size: Int, private val cells: BooleanArray) {
    fun isDark(x: Int, y: Int): Boolean = cells[y * size + x]
}

/**
 * Encodes text (the device-flow `verification_uri_complete`) into a QR [QrMatrix] using
 * ZXing's pure-Java writer, so it unit-tests off-device. The Android→Compose bridge that
 * paints this into an `ImageBitmap` lives in [com.diffuse.ui].
 */
object QrEncoder {
    fun encode(text: String, size: Int = 512): QrMatrix {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = bits.width
        val cells = BooleanArray(w * w) { i -> bits.get(i % w, i / w) }
        return QrMatrix(w, cells)
    }
}
