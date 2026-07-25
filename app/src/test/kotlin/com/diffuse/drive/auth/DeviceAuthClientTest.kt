package com.diffuse.drive.auth

import com.diffuse.drive.FakeHttp
import com.diffuse.drive.HttpResp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuthClientTest {

    private fun client(handler: (FakeHttp.Req) -> HttpResp) =
        DeviceAuthClient(FakeHttp(handler), clientId = "cid", clientSecret = "secret")

    @Test fun requestCode_parses_and_prefers_complete_uri_for_qr() {
        val c = client {
            HttpResp(
                200,
                """
                {"device_code":"DC","user_code":"WDJB-MJHT",
                 "verification_url":"https://www.google.com/device",
                 "verification_uri_complete":"https://www.google.com/device?user_code=WDJB-MJHT",
                 "expires_in":1800,"interval":5}
                """.trimIndent(),
            )
        }
        val code = c.requestCode()
        assertEquals("DC", code.deviceCode)
        assertEquals("WDJB-MJHT", code.userCode)
        assertEquals("https://www.google.com/device", code.userUrl)
        // QR target embeds the code so scanning skips manual entry.
        assertEquals("https://www.google.com/device?user_code=WDJB-MJHT", code.qrTarget)
    }

    @Test fun requestCode_synthesizes_complete_url_when_google_omits_it() {
        // Google returns only the bare verification_url; we embed the code so the QR pre-fills.
        val c = client {
            HttpResp(200, """{"device_code":"DC","user_code":"NSZ-TMD-KMLV","verification_url":"https://www.google.com/device"}""")
        }
        assertEquals("https://www.google.com/device?user_code=NSZ-TMD-KMLV", c.requestCode().qrTarget)
    }

    @Test fun poll_maps_pending_slowdown_denied_expired() {
        assertEquals(PollResult.Pending, pollWith(428, """{"error":"authorization_pending"}"""))
        assertEquals(PollResult.SlowDown, pollWith(403, """{"error":"slow_down"}"""))
        assertEquals(PollResult.Denied, pollWith(403, """{"error":"access_denied"}"""))
        assertEquals(PollResult.Expired, pollWith(400, """{"error":"expired_token"}"""))
    }

    private fun pollWith(code: Int, body: String): PollResult =
        client { HttpResp(code, body) }.poll("DC")

    @Test fun poll_success_returns_tokens() {
        val c = client {
            HttpResp(200, """{"access_token":"AT","expires_in":3599,"refresh_token":"RT","token_type":"Bearer"}""")
        }
        val r = c.poll("DC")
        assertTrue(r is PollResult.Authorized)
        val tokens = (r as PollResult.Authorized).tokens
        assertEquals("AT", tokens.accessToken)
        assertEquals("RT", tokens.refreshToken)
    }

    @Test fun refresh_posts_refresh_grant_and_parses_token() {
        val http = FakeHttp { HttpResp(200, """{"access_token":"AT2","expires_in":3599}""") }
        val c = DeviceAuthClient(http, "cid", "secret")
        val tokens = c.refresh("RT")
        assertEquals("AT2", tokens.accessToken)
        val form = http.requests.single().body as com.diffuse.drive.Http.Body.Form
        assertEquals("refresh_token", form.fields["grant_type"])
        assertEquals("RT", form.fields["refresh_token"])
    }
}
