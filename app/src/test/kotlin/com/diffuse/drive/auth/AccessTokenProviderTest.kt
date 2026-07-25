package com.diffuse.drive.auth

import com.diffuse.drive.FakeHttp
import com.diffuse.drive.HttpResp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccessTokenProviderTest {

    private class FakeCredStore : DriveCredentialStore {
        override var refreshToken: String? = null
        override var accessToken: String? = null
        override var accessTokenExpiryMs: Long = 0
        override fun save(refreshToken: String?, accessToken: String?, expiryMs: Long) {
            this.refreshToken = refreshToken; this.accessToken = accessToken; this.accessTokenExpiryMs = expiryMs
        }
        override fun clear() { refreshToken = null; accessToken = null; accessTokenExpiryMs = 0 }
    }

    private fun provider(store: DriveCredentialStore, now: Long, refreshBody: String): AccessTokenProvider {
        val auth = DeviceAuthClient(FakeHttp { HttpResp(200, refreshBody) }, "cid", "secret")
        return AccessTokenProvider(auth, store, now = { now })
    }

    @Test fun returns_cached_token_when_still_valid() {
        val store = FakeCredStore().apply { accessToken = "cached"; accessTokenExpiryMs = 10_000_000 }
        val p = provider(store, now = 1_000, refreshBody = "{}")
        assertEquals("cached", p.validAccessToken())
    }

    @Test fun refreshes_when_expired_and_keeps_existing_refresh_token() {
        val store = FakeCredStore().apply {
            refreshToken = "RT"; accessToken = "old"; accessTokenExpiryMs = 500
        }
        val p = provider(store, now = 1_000, refreshBody = """{"access_token":"AT_NEW","expires_in":3600}""")
        assertEquals("AT_NEW", p.validAccessToken())
        assertEquals("AT_NEW", store.accessToken)
        assertEquals("RT", store.refreshToken) // refresh response omitted it; we keep the old one
    }

    @Test fun forceRefresh_without_connection_throws() {
        val p = provider(FakeCredStore(), now = 0, refreshBody = "{}")
        assertThrows(AccessTokenProvider.NotConnectedException::class.java) { p.forceRefresh() }
    }

    @Test fun onSignedIn_persists_tokens() {
        val store = FakeCredStore()
        val p = provider(store, now = 1_000, refreshBody = "{}")
        p.onSignedIn(TokenResponse(accessToken = "AT", expiresInSec = 3600, refreshToken = "RT"))
        assertEquals("RT", store.refreshToken)
        assertEquals("AT", store.accessToken)
        assertEquals(1_000 + 3_600_000, store.accessTokenExpiryMs)
    }
}
