package com.diffuse.drive.auth

/**
 * Persists the Google OAuth credentials Diffuse holds after a successful QR sign-in:
 * the long-lived refresh token plus a short-lived cached access token and its expiry.
 *
 * Pure interface so [AccessTokenProvider] and the upload layer are unit-testable with
 * a fake; the real Android implementation ([com.diffuse.drive.store.EncryptedDriveCredentialStore])
 * encrypts at rest. Storing our own OAuth tokens is not a content-provider mutation,
 * so it has no bearing on the read-only invariant.
 */
interface DriveCredentialStore {
    /** The refresh token from the last successful sign-in, or null if not connected. */
    var refreshToken: String?

    /** The most recently obtained access token, or null. */
    var accessToken: String?

    /** Epoch-millis at which [accessToken] expires (0 if none). */
    var accessTokenExpiryMs: Long

    /** True once a refresh token is stored — i.e. the user has connected Drive. */
    val isConnected: Boolean get() = refreshToken != null

    /** Persist a full credential set atomically after sign-in or refresh. */
    fun save(refreshToken: String?, accessToken: String?, expiryMs: Long)

    /** Forget all credentials (disconnect Drive). */
    fun clear()
}
