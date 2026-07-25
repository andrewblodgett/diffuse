package com.diffuse.drive

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tiny HTTP seam so the OAuth device flow ([auth.DeviceAuthClient]) and the Drive
 * REST layer ([DriveClient]) can be unit-tested off-device with a fake, the same way
 * Phase 2 put a `Row` abstraction over `android.database.Cursor`. The real
 * implementation ([OkHttpHttp]) is the only place the network is touched.
 *
 * READ-ONLY: this is a network client; it never touches an on-device content
 * provider or the media store, so it has no bearing on the read-only invariant.
 */
interface Http {
    /**
     * Perform [method] against [url] with [headers] and an optional [body], returning
     * the status code and the (UTF-8, fully-buffered) response body. File bodies are
     * streamed from disk so large videos are never held in memory.
     */
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: Body? = null,
    ): HttpResp

    /** A request body. File-backed variants stream from disk rather than buffering. */
    sealed interface Body {
        /** Raw in-memory bytes (small JSON payloads). */
        data class Bytes(val bytes: ByteArray, val contentType: String) : Body
        /** application/x-www-form-urlencoded fields (OAuth token calls). */
        data class Form(val fields: Map<String, String>) : Body
        /** A file streamed as the whole body (Drive resumable-upload PUT). */
        data class FileBody(val file: File, val contentType: String) : Body
        /**
         * Drive simple-upload `multipart/related`: a JSON [metadataJson] part followed
         * by [file] streamed as the media part with [mediaContentType].
         */
        data class RelatedFile(
            val metadataJson: String,
            val file: File,
            val mediaContentType: String,
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
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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
            is Http.Body.FileBody -> body.file.asRequestBody(body.contentType.toMediaType())
            is Http.Body.RelatedFile -> MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(body.metadataJson.toRequestBody(JSON.toMediaType()))
                .addPart(body.file.asRequestBody(body.mediaContentType.toMediaType()))
                .build()
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
        const val JSON = "application/json; charset=UTF-8"
    }
}
