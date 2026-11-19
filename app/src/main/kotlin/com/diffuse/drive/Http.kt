package com.diffuse.drive

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.EOFException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Tiny HTTP seam so the OAuth device flow ([auth.DeviceAuthClient]) and the Drive REST
 * layer ([DriveClient]) can be unit-tested off-device with a fake. The real implementation
 * ([OkHttpHttp]) is the only place the network is touched.
 *
 * READ-ONLY: this is a network client; it never touches an on-device content provider.
 */
interface Http {
    /**
     * Perform [method] against [url] with [headers] and an optional [body], returning the
     * status code and the (UTF-8, fully-buffered) response body. Throws an IOException when
     * no response arrives at all (no network, timeout, connection reset).
     */
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: Body? = null,
    ): HttpResp

    /** A request body. */
    sealed interface Body {
        /** Raw in-memory bytes (small JSON payloads, empty status-check bodies). */
        class Bytes(val bytes: ByteArray, val contentType: String) : Body

        /** application/x-www-form-urlencoded fields (OAuth token calls). */
        data class Form(val fields: Map<String, String>) : Body

        /**
         * Exactly [length] bytes starting [offset] bytes into the stream that [open] yields.
         * [open] must return a fresh stream on every call (OkHttp may write the body more
         * than once, and a resumed upload starts part-way through). Nothing is buffered in
         * memory, so a multi-GB video streams provider→Drive through a small copy buffer.
         */
        class Stream(
            val open: () -> InputStream,
            val offset: Long,
            val length: Long,
            val contentType: String,
        ) : Body
    }
}

/** Buffered HTTP response. [body] is the response text (may be empty). */
data class HttpResp(val code: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    val isSuccess: Boolean get() = code in 200..299
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** Real [Http] backed by OkHttp. */
class OkHttpHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build(),
) : Http {
    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: Http.Body?,
    ): HttpResp {
        val reqBody: RequestBody? = when (body) {
            null -> null
            is Http.Body.Bytes -> body.bytes.toRequestBody(body.contentType.toMediaType())
            is Http.Body.Form -> body.fields.entries
                .joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
                .toRequestBody(FORM.toMediaType())
            is Http.Body.Stream ->
                StreamRequestBody(body.open, body.offset, body.length, body.contentType.toMediaType())
        }
        val builder = Request.Builder().url(url).method(method, reqBody)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            val h = resp.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
            return HttpResp(resp.code, resp.body?.string().orEmpty(), h)
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val FORM = "application/x-www-form-urlencoded"
    }
}

/**
 * Writes exactly [length] bytes from [offset] of a re-openable source. Copying a fixed
 * count (rather than "until end of stream") means a file that grows while it uploads
 * can't break the declared Content-Length; a file that shrank fails with EOFException.
 *
 * One-shot: if the connection drops mid-body, OkHttp must not quietly replay it from byte 0
 * (its retry-on-connection-failure would, re-sending a whole video). The failure surfaces to
 * [DriveClient] instead, which asks Drive how much arrived and resumes from there.
 */
internal class StreamRequestBody(
    private val open: () -> InputStream,
    private val offset: Long,
    private val length: Long,
    private val mediaType: MediaType,
) : RequestBody() {
    override fun contentType(): MediaType = mediaType
    override fun contentLength(): Long = length
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) {
        open().use { input ->
            input.skipExactly(offset)
            sink.write(input.source(), length)
        }
    }

    private fun InputStream.skipExactly(n: Long) {
        var left = n
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) {
                left -= skipped
            } else if (read() == -1) {
                throw EOFException("stream ended $left bytes before the resume offset")
            } else {
                left--
            }
        }
    }
}
