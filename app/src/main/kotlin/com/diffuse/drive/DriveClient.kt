package com.diffuse.drive

import com.diffuse.drive.auth.AccessTokens
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.net.URLEncoder

/** The Drive operations Diffuse needs; implemented by [DriveClient], fakeable in tests. */
interface DriveApi {
    fun ensureFolder(name: String, parentId: String? = null): String
    /** Upload a local file, size-routing simple vs resumable by its length. */
    fun upload(name: String, parentId: String, mimeType: String, file: File): String
    /**
     * Upload [length] bytes streamed from [open] (a re-openable read-only source such as a
     * content-provider stream), size-routing on [length]. Lets a photo/video go straight
     * provider→Drive with no local copy.
     */
    fun upload(name: String, parentId: String, mimeType: String, length: Long, open: () -> InputStream): String

    /**
     * The signed-in account's email address (Drive `about.user.emailAddress`), or null if it
     * can't be determined. Lets the UI show *which* Google account is connected.
     */
    fun accountEmail(): String? = null
}

/**
 * Minimal Google Drive v3 REST client — just what a one-way backup needs: find-or-create
 * a folder, and upload a file (simple multipart for small files, resumable for large).
 * Everything the app writes lives under the `drive.file` scope, so Drive only ever shows
 * this client files Diffuse itself created.
 *
 * Pure logic over the [Http] seam + [AccessTokens]; unit-tested with fakes, no network.
 * Every call attaches a bearer token and, on a 401, refreshes once and retries.
 */
class DriveClient(
    private val http: Http,
    private val tokens: AccessTokens,
    /** Files at or below this size use simple multipart upload; larger use resumable. */
    val resumableThresholdBytes: Long = 5L * 1024 * 1024,
    private val apiBase: String = API_BASE,
    private val uploadBase: String = UPLOAD_BASE,
) : DriveApi {
    val folderMimeType: String get() = FOLDER_MIME

    /**
     * Return the id of the folder named [name] under [parentId] (or the Drive root when
     * null), creating it if absent. Idempotent: safe to call every run. Under `drive.file`
     * the list query only sees folders this app created, so it reliably finds our own.
     */
    override fun ensureFolder(name: String, parentId: String?): String {
        existingFolder(name, parentId)?.let { return it }
        return createFolder(name, parentId)
    }

    private fun existingFolder(name: String, parentId: String?): String? {
        val q = buildString {
            append("mimeType='").append(FOLDER_MIME).append("'")
            append(" and name='").append(name.replace("'", "\\'")).append("'")
            append(" and trashed=false")
            if (parentId != null) append(" and '").append(parentId).append("' in parents")
        }
        val url = "$apiBase/files?q=${enc(q)}&fields=files(id,name)&spaces=drive"
        val resp = authed("GET", url)
        require(resp.isSuccess) { "list folder failed: ${resp.code} ${resp.body}" }
        return json.decodeFromString<FileList>(resp.body).files.firstOrNull()?.id
    }

    private fun createFolder(name: String, parentId: String?): String {
        val meta = FileMetadata(name = name, mimeType = FOLDER_MIME, parents = parentId?.let { listOf(it) })
        val resp = authed(
            "POST",
            "$apiBase/files?fields=id",
            body = Http.Body.Bytes(json.encodeToString(FileMetadata.serializer(), meta).toByteArray(), JSON_CT),
        )
        require(resp.isSuccess) { "create folder failed: ${resp.code} ${resp.body}" }
        return json.decodeFromString<DriveFile>(resp.body).id
    }

    /** Upload [file] as [name] under [parentId], picking simple vs resumable by size. */
    override fun upload(name: String, parentId: String, mimeType: String, file: File): String =
        if (file.length() <= resumableThresholdBytes) uploadSimple(name, parentId, mimeType, file)
        else uploadResumable(name, parentId, mimeType, file)

    /** Streaming counterpart of [upload]: size-routes on [length], never touching local disk. */
    override fun upload(name: String, parentId: String, mimeType: String, length: Long, open: () -> InputStream): String =
        if (length <= resumableThresholdBytes)
            uploadSimpleCore(name, parentId) { meta -> Http.Body.RelatedStream(meta, open, length, mimeType) }
        else
            uploadResumableCore(name, parentId, mimeType, length) { Http.Body.StreamBody(open, length, mimeType) }

    /** Simple `multipart/related` upload (metadata + bytes in one request). */
    fun uploadSimple(name: String, parentId: String, mimeType: String, file: File): String =
        uploadSimpleCore(name, parentId) { meta -> Http.Body.RelatedFile(meta, file, mimeType) }

    /**
     * Resumable upload: initiate a session, then PUT the file bytes. Robust for large
     * videos on a flaky connection — the single PUT can be re-driven against the session
     * URI. (Chunked upload with progress is a fast-follow.)
     */
    fun uploadResumable(name: String, parentId: String, mimeType: String, file: File): String =
        uploadResumableCore(name, parentId, mimeType, file.length()) { Http.Body.FileBody(file, mimeType) }

    /** Shared simple-upload body: [relatedBody] wraps the JSON metadata + media part (file or stream). */
    private inline fun uploadSimpleCore(name: String, parentId: String, relatedBody: (metadataJson: String) -> Http.Body): String {
        val meta = FileMetadata(name = name, parents = listOf(parentId))
        val resp = authed(
            "POST",
            "$uploadBase/files?uploadType=multipart&fields=id",
            body = relatedBody(json.encodeToString(FileMetadata.serializer(), meta)),
        )
        require(resp.isSuccess) { "simple upload failed: ${resp.code} ${resp.body}" }
        return json.decodeFromString<DriveFile>(resp.body).id
    }

    /** Shared resumable-upload flow: init a session with [length], then PUT [putBody]. */
    private inline fun uploadResumableCore(name: String, parentId: String, mimeType: String, length: Long, putBody: () -> Http.Body): String {
        val meta = FileMetadata(name = name, parents = listOf(parentId))
        val init = authed(
            "POST",
            "$uploadBase/files?uploadType=resumable&fields=id",
            headers = mapOf(
                "X-Upload-Content-Type" to mimeType,
                "X-Upload-Content-Length" to length.toString(),
            ),
            body = Http.Body.Bytes(json.encodeToString(FileMetadata.serializer(), meta).toByteArray(), JSON_CT),
        )
        require(init.isSuccess) { "resumable init failed: ${init.code} ${init.body}" }
        val session = init.header("Location")
            ?: error("resumable init returned no Location header")

        val put = authed("PUT", session, body = putBody())
        require(put.isSuccess) { "resumable PUT failed: ${put.code} ${put.body}" }
        return json.decodeFromString<DriveFile>(put.body).id
    }

    /**
     * Read the connected account's email from Drive's `about` resource. `drive.file` is enough to
     * call `about.get` for the authenticated user. Returns null (never throws) if the call fails,
     * so a missing email never breaks the UI.
     */
    override fun accountEmail(): String? = try {
        val resp = authed("GET", "$apiBase/about?fields=user")
        if (resp.isSuccess) json.decodeFromString<About>(resp.body).user?.emailAddress else null
    } catch (_: Exception) {
        null
    }

    /** Attach a bearer token; on 401 refresh once and retry. */
    private fun authed(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: Http.Body? = null,
    ): HttpResp {
        val first = http.request(method, url, withAuth(headers, tokens.validAccessToken()), body)
        if (first.code != 401) return first
        return http.request(method, url, withAuth(headers, tokens.forceRefresh()), body)
    }

    private fun withAuth(headers: Map<String, String>, token: String) =
        headers + ("Authorization" to "Bearer $token")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    @Serializable
    private data class FileMetadata(
        val name: String,
        val mimeType: String? = null,
        val parents: List<String>? = null,
    )

    @Serializable
    private data class DriveFile(val id: String, val name: String = "")

    @Serializable
    private data class FileList(val files: List<DriveFile> = emptyList())

    @Serializable
    private data class About(val user: AboutUser? = null)

    @Serializable
    private data class AboutUser(
        val emailAddress: String? = null,
        val displayName: String? = null,
    )

    private companion object {
        const val API_BASE = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val JSON_CT = "application/json; charset=UTF-8"
        val json = Json { ignoreUnknownKeys = true }
    }
}
