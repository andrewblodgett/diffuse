package com.thelightphone.sdk.ui

/**
 * Extracted verbatim from the upstream `LightIcon.kt` so the Light theme can be
 * vendored without the icon/resource machinery (which pulls in the full icon set
 * and the CameraX QR scanner). See ../../../../../../NOTICE.md.
 *
 * Matches LightOS theme behavior: a surface is either Dark or Light.
 */
enum class LightSurfaceScheme {
    Dark,
    Light,
}
