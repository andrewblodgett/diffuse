package com.diffuse.drive.auth

/** The token surface [com.diffuse.drive.DriveClient] depends on (fakeable in tests). */
interface AccessTokens {
    /** A currently-valid access token (refreshed if needed). */
    fun validAccessToken(): String
    /** Force a refresh (e.g. after a 401) and return the new token. */
    fun forceRefresh(): String
}

/**
 * Yields a valid Google access token on demand, refreshing transparently. [DriveClient]
 * calls [validAccessToken] before each request and [forceRefresh] after a 401.
 *
 * Pure logic over [DeviceAuthClient] + [DriveCredentialStore]; unit-tested with fakes.
 */
class AccessTokenProvider(
    private val auth: DeviceAuthClient,
    private val store: DriveCredentialStore,
    /** Refresh a little early so a token doesn't expire mid-upload. */
    private val skewMs: Long = 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) : AccessTokens {
    class NotConnectedException : IllegalStateException("Drive is not connected — sign in first")

    /** Persist the tokens returned by a successful device-flow sign-in. */
    fun onSignedIn(tokens: TokenResponse) {
        store.save(
            refreshToken = tokens.refreshToken ?: store.refreshToken,
            accessToken = tokens.accessToken,
            expiryMs = now() + tokens.expiresInSec * 1000L,
        )
    }

    /** A currently-valid access token, refreshing via the refresh token if needed. */
    override fun validAccessToken(): String {
        val cached = store.accessToken
        if (cached != null && now() < store.accessTokenExpiryMs - skewMs) return cached
        return forceRefresh()
    }

    /** Force a refresh (e.g. after the server rejects the cached token with 401). */
    override fun forceRefresh(): String {
        val refresh = store.refreshToken ?: throw NotConnectedException()
        val tokens = auth.refresh(refresh)
        store.save(
            // Google usually omits refresh_token on refresh; keep the existing one.
            refreshToken = tokens.refreshToken ?: refresh,
            accessToken = tokens.accessToken,
            expiryMs = now() + tokens.expiresInSec * 1000L,
        )
        return tokens.accessToken
    }
}
