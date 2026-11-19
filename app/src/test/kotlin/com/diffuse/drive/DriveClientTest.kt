package com.diffuse.drive

import com.diffuse.drive.auth.AccessTokens
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.net.URLDecoder

class DriveClientTest {

    private class FakeTokens(var token: String = "tok", val refreshed: String = "tok2") : AccessTokens {
        var refreshCount = 0
        override fun validAccessToken() = token
        override fun forceRefresh(): String { refreshCount++; return refreshed }
    }

    private val sleeps = mutableListOf<Long>()
    private fun client(http: Http, tokens: AccessTokens = FakeTokens()) =
        DriveClient(http, tokens, maxAttempts = 4, sleep = { sleeps += it })

    private fun decoded(req: FakeHttp.Req) = URLDecoder.decode(req.url, "UTF-8")
    private fun bodyText(req: FakeHttp.Req) = String((req.body as Http.Body.Bytes).bytes)

    private val tenBytes = UploadContent(10) { ByteArrayInputStream(ByteArray(10) { it.toByte() }) }

    // --- Folders -------------------------------------------------------------------------

    @Test fun ensureFolder_reuses_the_oldest_existing_folder() {
        val http = FakeHttp { HttpResp(200, """{"files":[{"id":"F1"},{"id":"F2"}]}""") }
        assertEquals("F1", client(http).ensureFolder("Diffuse"))
        assertEquals(1, http.requests.size) // no create
        assertTrue("orderBy=createdTime" in http.requests.single().url)
    }

    @Test fun ensureFolder_creates_when_absent() {
        val http = FakeHttp { req ->
            if (req.method == "GET") HttpResp(200, """{"files":[]}""") else HttpResp(200, """{"id":"NEW"}""")
        }
        assertEquals("NEW", client(http).ensureFolder("Camera", parentId = "P"))
        assertEquals("POST", http.requests[1].method)
        assertTrue(bodyText(http.requests[1]).contains("\"parents\":[\"P\"]"))
    }

    @Test fun ensureFolder_escapes_quotes_and_backslashes_in_the_query() {
        val http = FakeHttp { HttpResp(200, """{"files":[{"id":"F"}]}""") }
        client(http).ensureFolder("""Bob's \ pics""")
        assertTrue(decoded(http.requests.single()).contains("""name='Bob\'s \\ pics'"""))
    }

    // --- Listing -------------------------------------------------------------------------

    @Test fun listFiles_pages_through_results_and_reads_app_properties() {
        val http = FakeHttp { req ->
            val url = decoded(req)
            assertTrue("folders must be excluded", "mimeType!=" in url)
            if ("pageToken" !in url) {
                HttpResp(200, """{"nextPageToken":"p2","files":[{"id":"A","name":"a.jpg","appProperties":{"diffuseKey":"media:images:1"}},{"id":"B","name":"b"}]}""")
            } else {
                HttpResp(200, """{"files":[{"id":"C","name":"c"}]}""")
            }
        }
        val files = client(http).listFiles()
        assertEquals(listOf("A", "B", "C"), files.map { it.id })
        assertEquals("media:images:1", files[0].appProperties["diffuseKey"])
        assertTrue(files[1].appProperties.isEmpty())
    }

    @Test fun transient_errors_are_retried_with_backoff() {
        var calls = 0
        val http = FakeHttp {
            calls++
            when (calls) {
                1 -> throw IOException("connection reset")
                2 -> HttpResp(503, """{"error":{"message":"backend"}}""")
                else -> HttpResp(200, """{"files":[]}""")
            }
        }
        assertTrue(client(http).listFiles().isEmpty())
        assertEquals(3, calls)
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }

    @Test fun permanent_errors_are_not_retried_and_storage_full_is_recognised() {
        val http = FakeHttp {
            HttpResp(403, """{"error":{"message":"The user's Drive storage quota has been exceeded.","errors":[{"reason":"storageQuotaExceeded"}]}}""")
        }
        val e = assertThrows(DriveHttpException::class.java) { client(http).listFiles() }
        assertTrue(e.isStorageFull)
        assertFalse(e.isTransient)
        assertEquals(1, http.requests.size)
    }

    @Test fun rate_limit_403_is_treated_as_transient() {
        var calls = 0
        val http = FakeHttp {
            calls++
            if (calls == 1) HttpResp(403, """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}""")
            else HttpResp(200, """{"files":[]}""")
        }
        client(http).listFiles()
        assertEquals(2, calls)
    }

    @Test fun gives_up_after_max_attempts() {
        val http = FakeHttp { HttpResp(500, "") }
        assertThrows(DriveHttpException::class.java) { client(http).listFiles() }
        assertEquals(4, http.requests.size)
    }

    // --- Uploads -------------------------------------------------------------------------

    /** A Drive fake for the resumable protocol: [onPut] answers each PUT to the session. */
    private fun uploadServer(onPut: (FakeHttp.Req) -> HttpResp) = FakeHttp { req ->
        if ("uploadType=resumable" in req.url) HttpResp(200, "", mapOf("Location" to SESSION))
        else onPut(req)
    }

    @Test fun create_starts_a_tagged_session_then_sends_all_bytes() {
        val http = uploadServer { HttpResp(200, """{"id":"FILE"}""") }
        val id = client(http).create("a.jpg", "P", "image/jpeg", mapOf("diffuseKey" to "k"), tenBytes)

        assertEquals("FILE", id)
        val init = http.requests[0]
        assertEquals("POST", init.method)
        assertEquals("10", init.headers["X-Upload-Content-Length"])
        assertEquals("image/jpeg", init.headers["X-Upload-Content-Type"])
        val meta = bodyText(init)
        assertTrue(meta.contains("\"name\":\"a.jpg\""))
        assertTrue(meta.contains("\"appProperties\":{\"diffuseKey\":\"k\"}"))

        val put = http.requests[1]
        assertEquals("PUT", put.method)
        assertEquals(SESSION, put.url)
        assertEquals("bytes 0-9/10", put.headers["Content-Range"])
        val body = put.body as Http.Body.Stream
        assertEquals(0L, body.offset)
        assertEquals(10L, body.length)
    }

    @Test fun a_dropped_upload_resumes_from_what_drive_committed() {
        var puts = 0
        val http = uploadServer { req ->
            puts++
            when (puts) {
                1 -> throw IOException("connection reset mid-upload")
                2 -> {
                    // The status query: empty body, total size only.
                    assertEquals("bytes */10", req.headers["Content-Range"])
                    HttpResp(308, "", mapOf("Range" to "bytes=0-3"))
                }
                else -> {
                    assertEquals("bytes 4-9/10", req.headers["Content-Range"])
                    val body = req.body as Http.Body.Stream
                    assertEquals(4L, body.offset)
                    assertEquals(6L, body.length)
                    HttpResp(200, """{"id":"FILE"}""")
                }
            }
        }
        assertEquals("FILE", client(http).create("v.mp4", "P", "video/mp4", emptyMap(), tenBytes))
        assertEquals(1, http.requests.count { "uploadType=resumable" in it.url }) // one session, not restarted
        assertEquals(listOf(1_000L), sleeps)
    }

    @Test fun a_partial_308_continues_without_waiting() {
        var puts = 0
        val http = uploadServer { req ->
            puts++
            if (puts == 1) HttpResp(308, "", mapOf("Range" to "bytes=0-5"))
            else {
                assertEquals("bytes 6-9/10", req.headers["Content-Range"])
                HttpResp(201, """{"id":"FILE"}""")
            }
        }
        assertEquals("FILE", client(http).create("a", "P", "x/y", emptyMap(), tenBytes))
        assertTrue(sleeps.isEmpty())
    }

    @Test fun an_expired_session_is_restarted() {
        var puts = 0
        val http = uploadServer {
            puts++
            if (puts == 1) HttpResp(404, """{"error":{"message":"session not found"}}""") else HttpResp(200, """{"id":"FILE"}""")
        }
        assertEquals("FILE", client(http).create("a", "P", "x/y", emptyMap(), tenBytes))
        assertEquals(2, http.requests.count { "uploadType=resumable" in it.url })
    }

    @Test fun updateContent_patches_the_existing_file_through_a_session() {
        val http = uploadServer { HttpResp(200, """{"id":"DOC"}""") }
        client(http).updateContent("DOC", "application/xml", mapOf("diffuseVersion" to "v2"), tenBytes)
        val init = http.requests[0]
        assertEquals("PATCH", init.method)
        assertTrue(init.url.contains("/upload/drive/v3/files/DOC?uploadType=resumable"))
        assertFalse("PATCH must not rename or move the file", bodyText(init).contains("\"name\""))
        assertTrue(bodyText(init).contains("\"diffuseVersion\":\"v2\""))
    }

    @Test fun setAppProperties_patches_metadata_only() {
        val http = FakeHttp { HttpResp(200, """{"id":"X"}""") }
        client(http).setAppProperties("X", mapOf("diffuseKey" to "k"))
        val req = http.requests.single()
        assertEquals("PATCH", req.method)
        assertTrue(req.url.contains("/drive/v3/files/X?"))
        assertEquals("""{"appProperties":{"diffuseKey":"k"}}""", bodyText(req))
    }

    // --- Auth & misc ---------------------------------------------------------------------

    @Test fun on_401_it_refreshes_and_retries_with_new_token() {
        val tokens = FakeTokens(token = "stale", refreshed = "fresh")
        var calls = 0
        val http = FakeHttp {
            calls++
            if (calls == 1) HttpResp(401, "") else HttpResp(200, """{"files":[{"id":"F1"}]}""")
        }
        assertEquals("F1", client(http, tokens).ensureFolder("Diffuse"))
        assertEquals(1, tokens.refreshCount)
        assertEquals("Bearer stale", http.requests[0].headers["Authorization"])
        assertEquals("Bearer fresh", http.requests[1].headers["Authorization"])
    }

    @Test fun accountEmail_reads_about_user_and_is_null_on_failure() {
        val ok = FakeHttp { HttpResp(200, """{"user":{"emailAddress":"a@b.com","displayName":"A B"}}""") }
        assertEquals("a@b.com", client(ok).accountEmail())
        assertEquals(null, client(FakeHttp { HttpResp(403, "{}") }).accountEmail())
    }

    // --- Streaming body ------------------------------------------------------------------

    @Test fun stream_body_writes_exactly_length_bytes_from_offset() {
        val source = ByteArray(10) { it.toByte() }
        val body = StreamRequestBody({ ByteArrayInputStream(source) }, offset = 3, length = 4, "x/y".toMediaType())
        val out = Buffer()
        body.writeTo(out)
        assertEquals(listOf<Byte>(3, 4, 5, 6), out.readByteArray().toList())
        assertEquals(4L, body.contentLength())
    }

    @Test fun stream_body_fails_if_the_file_shrank() {
        val body = StreamRequestBody({ ByteArrayInputStream(ByteArray(5)) }, offset = 2, length = 10, "x/y".toMediaType())
        assertThrows(EOFException::class.java) { body.writeTo(Buffer()) }
    }

    private companion object {
        const val SESSION = "https://upload/session/123"
    }
}
