package com.diffuse.drive

import org.junit.Assert.assertTrue
import org.junit.Test

class QrEncoderTest {
    @Test fun encodes_a_url_into_a_non_empty_square_matrix() {
        val m = QrEncoder.encode("https://www.google.com/device?user_code=WDJB-MJHT")
        assertTrue("matrix should be a positive square", m.size > 0)
        var dark = 0
        for (y in 0 until m.size) for (x in 0 until m.size) if (m.isDark(x, y)) dark++
        // A real QR has a substantial number of dark modules (finder patterns etc.).
        assertTrue("expected dark modules, got $dark", dark > 20)
    }
}
