package com.diffuse.drive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InterruptedIOException

/**
 * A Drive request came back with an error status. [reason] is Drive's machine-readable
 * reason (e.g. `storageQuotaExceeded`), when the response included one.
 */
class DriveHttpException(
    val code: Int,
    val reason: String?,
    message: String,
) : Exception(message) {

    /** Worth retrying after a pause: throttling and server-side hiccups. */
    val isTransient: Boolean
        get() = code == 408 || code == 429 || code >= 500 || reason in RATE_LIMIT_REASONS

    /** The user's Drive is full; nothing will succeed until they free space. */
    val isStorageFull: Boolean get() = reason == "storageQuotaExceeded"

    companion object {
        private val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")
        private val json = Json { ignoreUnknownKeys = true }

        /** Build from a failed response, pulling Drive's reason and message out of its JSON body. */
        fun from(action: String, resp: HttpResp): DriveHttpException {
            val error = runCatching { json.decodeFromString<ErrorBody>(resp.body).error }.getOrNull()
            val reason = error?.errors?.firstOrNull()?.reason
            val detail = error?.message ?: resp.body.take(200)
            return DriveHttpException(resp.code, reason, "$action failed (${resp.code}): $detail")
        }
    }

    @Serializable
    private data class ErrorBody(val error: ErrorDetail? = null)

    @Serializable
    private data class ErrorDetail(val message: String? = null, val errors: List<Reason> = emptyList())

    @Serializable
    private data class Reason(val reason: String? = null)
}

/**
 * True when [e] is worth retrying: a transient Drive status, or a network failure. A thread
 * interrupt (the backup was cancelled) surfaces as an InterruptedIOException too, but that
 * must stop the run, not retry it.
 */
internal fun isTransient(e: Throwable): Boolean = when (e) {
    is DriveHttpException -> e.isTransient
    is InterruptedIOException -> !Thread.currentThread().isInterrupted
    is IOException -> true
    else -> false
}
