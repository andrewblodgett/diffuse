package com.diffuse.drive.auth

import com.diffuse.drive.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OAuth 2.0 Device Authorization Grant (RFC 8628) against Google's endpoints — the
 * flow designed for limited-input devices like the Light Phone III. The app asks
 * Google for a code, shows the user a QR of [DeviceCode.verificationUriComplete] to
 * scan on any other device, and polls until the user taps *Allow*. Sign-in happens
 * once; the returned refresh token drives silent re-auth thereafter.
 *
 * Pure logic over the [Http] seam — unit-tested with a fake, no network. The
 * `clientId`/`clientSecret` identify the *app* (not the user); for a sideloaded app
 * they are not true secrets. See docs/drive-setup.md.
 *
 * Endpoints/params are per Google's "OAuth 2.0 for TV and Limited-Input Device
 * Applications" documentation.
 */
class DeviceAuthClient(
    private val http: Http,
    private val clientId: String,
    private val clientSecret: String,
    private val deviceCodeUrl: String = DEVICE_CODE_URL,
    private val tokenUrl: String = TOKEN_URL,
) {
    /** Step 1: request a device+user code for [scope]. */
    fun requestCode(scope: String = DriveScopes.DRIVE_FILE): DeviceCode {
        val resp = http.request(
            method = "POST",
            url = deviceCodeUrl,
            body = Http.Body.Form(mapOf("client_id" to clientId, "scope" to scope)),
        )
        require(resp.isSuccess) { "device/code failed: ${resp.code} ${resp.body}" }
        return json.decodeFromString<DeviceCode>(resp.body)
    }

    /**
     * Step 2 (call repeatedly on [DeviceCode.interval]): poll for the user's decision.
     * Returns [PollResult.Authorized] with tokens on success, or a non-terminal
     * ([Pending]/[SlowDown]) or terminal ([Denied]/[Expired]) status otherwise.
     */
    fun poll(deviceCode: String): PollResult {
        val resp = http.request(
            method = "POST",
            url = tokenUrl,
            body = Http.Body.Form(
                mapOf(
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "device_code" to deviceCode,
                    "grant_type" to GRANT_DEVICE_CODE,
                ),
            ),
        )
        if (resp.isSuccess) return PollResult.Authorized(json.decodeFromString<TokenResponse>(resp.body))
        val err = errorOf(resp.body)
        return when (err) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "access_denied" -> PollResult.Denied
            "expired_token" -> PollResult.Expired
            else -> PollResult.Error(resp.code, err ?: resp.body)
        }
    }

    /** Exchange a stored refresh token for a fresh access token (silent re-auth). */
    fun refresh(refreshToken: String): TokenResponse {
        val resp = http.request(
            method = "POST",
            url = tokenUrl,
            body = Http.Body.Form(
                mapOf(
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token",
                ),
            ),
        )
        require(resp.isSuccess) { "token refresh failed: ${resp.code} ${resp.body}" }
        return json.decodeFromString<TokenResponse>(resp.body)
    }

    private fun errorOf(body: String): String? =
        runCatching { json.decodeFromString<ErrorResponse>(body).error }.getOrNull()

    private companion object {
        const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val GRANT_DEVICE_CODE = "urn:ietf:params:oauth:grant-type:device_code"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Response to the device/code request. */
@Serializable
data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String = "",
    // Google returns verification_url; the RFC name is verification_uri. Accept both.
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("verification_uri_complete") val verificationUriComplete: String = "",
    // Google's underscore-named variant of the "complete" URL (rarely present).
    @SerialName("verification_url_complete") val verificationUrlComplete: String = "",
    @SerialName("expires_in") val expiresInSec: Int = 1800,
    val interval: Int = 5,
) {
    /** URL to show the user (prefer the RFC `verification_uri`, fall back to Google's). */
    val userUrl: String get() = verificationUri.ifBlank { verificationUrl }

    /**
     * The QR target: a URL that already embeds the user code, so scanning it lands the
     * user on a pre-filled consent page instead of making them type the code.
     *
     * Google's `device/code` response omits any "complete" field and returns only the bare
     * `verification_url`, so we synthesize the complete URL ourselves by appending
     * `?user_code=…` (the widely-used convention; if a server ignores the param the QR is
     * simply the plain page, no worse than before). A server-provided complete URI wins.
     */
    val qrTarget: String get() {
        val provided = verificationUriComplete.ifBlank { verificationUrlComplete }
        if (provided.isNotBlank()) return provided
        val base = userUrl
        if (base.isBlank() || userCode.isBlank()) return base
        val sep = if ('?' in base) "&" else "?"
        return "$base${sep}user_code=$userCode"
    }
}

/** Successful token response (device-code exchange or refresh). */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresInSec: Int = 3600,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String = "Bearer",
    val scope: String = "",
)

@Serializable
private data class ErrorResponse(val error: String? = null)

/** Outcome of a single [DeviceAuthClient.poll]. */
sealed interface PollResult {
    data class Authorized(val tokens: TokenResponse) : PollResult
    /** User hasn't decided yet — keep polling at the current interval. */
    data object Pending : PollResult
    /** Polling too fast — increase the interval by 5s and keep polling. */
    data object SlowDown : PollResult
    /** User refused — terminal. */
    data object Denied : PollResult
    /** The code expired before the user acted — request a new code. */
    data object Expired : PollResult
    /** Any other error — terminal. */
    data class Error(val code: Int, val message: String) : PollResult
}
