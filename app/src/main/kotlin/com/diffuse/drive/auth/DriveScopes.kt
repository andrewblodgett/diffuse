package com.diffuse.drive.auth

/**
 * OAuth scopes Diffuse requests. We use only [DRIVE_FILE] — the least-privilege
 * Drive scope, granting access **only to files the app itself creates**, never the
 * user's other Drive content.
 *
 * This is also the *only* Drive scope compatible with the device-authorization
 * (QR sign-in) flow: Google's limited-input device flow allows `drive.file` and
 * `drive.appdata` but **not** the broad `drive` scope. `drive.file` keeps backups
 * visible and restorable in the user's Drive UI; `drive.appdata` would hide them.
 */
object DriveScopes {
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
}
