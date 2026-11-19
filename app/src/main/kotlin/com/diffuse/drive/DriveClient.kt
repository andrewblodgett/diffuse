package com.diffuse.drive

import com.diffuse.drive.auth.AccessTokens
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.net.URLEncoder

/** Bytes to upload: exactly [length] bytes from a source [open] can re-open at will. */
class UploadContent(val length: Long, val open: () -> InputStream) {
    companion object {
        fun of(file: File) = UploadContent(file.length()) { file.inputStream() }
    }
}

/** One file Diffuse created on Drive, as seen in a listing. */
data class DriveFile(
    val id: String,
    val name: String,
    /** Private key/value tags Diffuse attached at upload (Drive `appProperties`). */
    val appProperties: Map<String, String> = emptyMap(),
    /** Size in bytes, when Drive reports one. */
    val size: Long? = null,
)

/** The Drive operations Diffuse needs; implemented by [DriveClient], fakeable in tests. */
interface DriveApi {
    /** Id of folder [name] under [parentId] (Drive root when null), creating it if absent. */
    fun ensureFolder(name: String, parentId: String? = null): String

    /**
     * Every non-trashed, non-folder file visible to this app, oldest first. Under the
     * `drive.file` scope that is exactly the files Diffuse itself created.
     */
    fun listFiles(): List<DriveFile>

    /** Create file [name] in [parentId] with [content] and [appProperties]; returns its id. */
    fun create(
        name: String,
        parentId: String,
        mimeType: String,
        appProperties: Map<String, String>,
        content: UploadContent,
    ): String

    /** Replace the content of existing file [fileId] (same id, name and folder) and its [appProperties]. */
    fun updateContent(
        fileId: String,
        mimeType: String,
        appProperties: Map<String, String>,
        content: UploadContent,
    )

    /** Set [appProperties] on existing file [fileId] without touching its content. */
    fun setAppProperties(fileId: String, appProperties: Map<String, String>)

    /** The signed-in account's email address, or null if it can't be determined. */
    fun accountEmail(): String? = null
}

/**
 * Minimal Google Drive v3 REST client: find-or-create folders, list our files, and upload.
 * Everything lives under the `drive.file` scope, so Drive only ever shows this client the
 * files Diffuse itself created.
 *
 * Built to keep going on a flaky phone connection:
 *  - Every request attaches a bearer token and, on a 401, refreshes it once and retries.
 *  - Transient failures (network errors, 408/429/5xx, rate limits) are retried with
 *    exponential backoff, up to [maxAttempts] tries.
 *  - Every upload is a **resumable** session. If the connection drops part-way through a
 *    video, the client asks Drive how many bytes it committed and continues from there
 *    instead of starting over, and a retry can never create a second copy of the file.
 *
 * Pure logic over the [Http] seam + [AccessTokens]; unit-tested with fakes, no network.
 */
class DriveClient(
    private val http: Http,
    private val tokens: AccessTokens,
    private val maxAttempts: Int = 5,
    /** Sleeps between retries; injectable so tests run instantly. */
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val apiBase: String = API_BASE,
    private val uploadBase: String = UPLOAD_BASE,
) : DriveApi {

    override fun ensureFolder(name: String, parentId: String?): String = withRetry {
        existingFolder(name, parentId) ?: createFolder(name, parentId)
    }

    /** The oldest matching folder, so that if a duplicate ever got created we keep using the original. */
    private fun existingFolder(name: String, parentId: String?): String? {
        val q = buildString {
            append("mimeType='").append(FOLDER_MIME).append("'")
            append(" and name='").append(escapeQuery(name)).append("'")
            append(" and trashed=false")
            if (parentId != null) append(" and '").append(escapeQuery(parentId)).append("' in parents")
        }
        val url = "$apiBase/files?q=${enc(q)}&fields=files(id)&orderBy=createdTime&spaces=drive"
        val resp = authed("GET", url)
        if (!resp.isSuccess) throw DriveHttpException.from("Find folder '$name'", resp)
        return json.decodeFromString<FileList>(resp.body).files.firstOrNull()?.id
    }

    private fun createFolder(name: String, parentId: String?): String {
        val meta = Metadata(name = name, mimeType = FOLDER_MIME, parents = parentId?.let { listOf(it) })
        val resp = authed("POST", "$apiBase/files?fields=id", body = jsonBody(meta))
        if (!resp.isSuccess) throw DriveHttpException.from("Create folder '$name'", resp)
        return json.decodeFromString<FileRef>(resp.body).id
    }

    override fun listFiles(): List<DriveFile> {
        val q = "trashed=false and mimeType!='$FOLDER_MIME'"
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$apiBase/files?q=${enc(q)}&spaces=drive&pageSize=1000&orderBy=createdTime")
                append("&fields=${enc("nextPageToken,files(id,name,size,appProperties)")}")
                pageToken?.let { append("&pageToken=").append(enc(it)) }
            }
            val page = withRetry {
                val resp = authed("GET", url)
                if (!resp.isSuccess) throw DriveHttpException.from("List Drive files", resp)
                json.decodeFromString<FileListPage>(resp.body)
            }
            page.files.mapTo(files) { DriveFile(it.id, it.name, it.appProperties.orEmpty(), it.size?.toLongOrNull()) }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return files
    }

    override fun create(
        name: String,
        parentId: String,
        mimeType: String,
        appProperties: Map<String, String>,
        content: UploadContent,
    ): String {
        val meta = Metadata(name = name, parents = listOf(parentId), appProperties = appProperties)
        return resumableUpload("POST", "$uploadBase/files?uploadType=resumable&fields=id", meta, mimeType, content)
    }

    override fun updateContent(
        fileId: String,
        mimeType: String,
        appProperties: Map<String, String>,
        content: UploadContent,
    ) {
        val url = "$uploadBase/files/${enc(fileId)}?uploadType=resumable&fields=id"
        resumableUpload("PATCH", url, Metadata(appProperties = appProperties), mimeType, content)
    }

    override fun setAppProperties(fileId: String, appProperties: Map<String, String>) = withRetry {
        val resp = authed(
            "PATCH",
            "$apiBase/files/${enc(fileId)}?fields=id",
            body = jsonBody(Metadata(appProperties = appProperties)),
        )
        if (!resp.isSuccess) throw DriveHttpException.from("Tag file", resp)
    }

    /** Never throws, so a missing email can't break the UI. */
    override fun accountEmail(): String? = try {
        val resp = authed("GET", "$apiBase/about?fields=user")
        if (resp.isSuccess) json.decodeFromString<About>(resp.body).user?.emailAddress else null
    } catch (_: Exception) {
        null
    }

    // --- Resumable uploads --------------------------------------------------------------

    /**
     * Start a resumable session ([initMethod] on [initUrl] with [meta]) and send [content]
     * through it. If Drive forgets the session (404/410, e.g. it expired), start a new one.
     */
    private fun resumableUpload(
        initMethod: String,
        initUrl: String,
        meta: Metadata,
        mimeType: String,
        content: UploadContent,
    ): String {
        var sessionsStarted = 0
        while (true) {
            val session = withRetry { startSession(initMethod, initUrl, meta, mimeType, content.length) }
            sessionsStarted++
            try {
                return sendToSession(session, mimeType, content)
            } catch (e: DriveHttpException) {
                val sessionGone = e.code == 404 || e.code == 410
                if (!sessionGone || sessionsStarted >= MAX_SESSIONS) throw e
            }
        }
    }

    private fun startSession(method: String, url: String, meta: Metadata, mimeType: String, length: Long): String {
        val resp = authed(
            method,
            url,
            headers = mapOf("X-Upload-Content-Type" to mimeType, "X-Upload-Content-Length" to length.toString()),
            body = jsonBody(meta),
        )
        if (!resp.isSuccess) throw DriveHttpException.from("Start upload", resp)
        return resp.header("Location") ?: throw DriveHttpException(resp.code, null, "Start upload: no session URL")
    }

    /**
     * Send [content] to [session], resuming after failures. Drive answers a partial upload
     * with 308 and a `Range: bytes=0-N` header naming what it has committed. After a failed
     * request we ask for that explicitly (an empty PUT whose Content-Range names only the
     * total size) and continue from byte N+1.
     */
    private fun sendToSession(session: String, mimeType: String, content: UploadContent): String {
        val total = content.length
        var offset = 0L
        var askForProgressFirst = false
        var failures = 0
        while (true) {
            try {
                val resp = if (askForProgressFirst) {
                    authed("PUT", session, mapOf("Content-Range" to "bytes */$total"), Http.Body.Bytes(ByteArray(0), mimeType))
                } else {
                    val range = "bytes $offset-${total - 1}/$total"
                    authed("PUT", session, mapOf("Content-Range" to range), Http.Body.Stream(content.open, offset, total - offset, mimeType))
                }
                when {
                    resp.isSuccess -> return json.decodeFromString<FileRef>(resp.body).id
                    resp.code == RESUME_INCOMPLETE -> {
                        val committed = committedBytes(resp)
                        val madeProgress = askForProgressFirst || committed > offset
                        offset = committed
                        askForProgressFirst = false
                        if (!madeProgress) throw DriveHttpException(503, null, "Upload made no progress")
                    }
                    else -> throw DriveHttpException.from("Upload", resp)
                }
            } catch (e: Exception) {
                if (!isTransient(e) || ++failures >= maxAttempts) throw e
                sleep(backoffMs(failures))
                askForProgressFirst = true
            }
        }
    }

    /** Bytes Drive has committed, from a 308's `Range: bytes=0-N` header (none → 0). */
    private fun committedBytes(resp: HttpResp): Long =
        resp.header("Range")?.substringAfterLast('-')?.trim()?.toLongOrNull()?.plus(1) ?: 0L

    // --- Plumbing -------------------------------------------------------------------------

    /** Run [block], retrying transient failures with exponential backoff. */
    private fun <T> withRetry(block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (!isTransient(e) || attempt >= maxAttempts) throw e
                sleep(backoffMs(attempt))
                attempt++
            }
        }
    }

    /** 1s, 2s, 4s, 8s, ... capped at 30s. */
    private fun backoffMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(30_000L)

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

    private fun jsonBody(meta: Metadata) =
        Http.Body.Bytes(json.encodeToString(Metadata.serializer(), meta).toByteArray(), JSON_CT)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Drive query strings quote with '…' and escape with backslashes. */
    private fun escapeQuery(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    @Serializable
    private data class Metadata(
        val name: String? = null,
        val mimeType: String? = null,
        val parents: List<String>? = null,
        val appProperties: Map<String, String>? = null,
    )

    @Serializable
    private data class FileRef(val id: String)

    @Serializable
    private data class FileList(val files: List<FileRef> = emptyList())

    @Serializable
    private data class ListedFile(
        val id: String,
        val name: String = "",
        val appProperties: Map<String, String>? = null,
        /** Drive sends int64 values as JSON strings. */
        val size: String? = null,
    )

    @Serializable
    private data class FileListPage(
        val files: List<ListedFile> = emptyList(),
        val nextPageToken: String? = null,
    )

    @Serializable
    private data class About(val user: AboutUser? = null)

    @Serializable
    private data class AboutUser(val emailAddress: String? = null)

    private companion object {
        const val API_BASE = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val JSON_CT = "application/json; charset=UTF-8"
        const val RESUME_INCOMPLETE = 308
        const val MAX_SESSIONS = 3
        // Null fields are left out of request JSON (encodeDefaults is off and they default to null).
        val json = Json { ignoreUnknownKeys = true }
    }
}
