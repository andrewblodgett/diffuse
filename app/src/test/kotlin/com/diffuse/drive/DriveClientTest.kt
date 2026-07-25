package com.diffuse.drive

import com.diffuse.drive.auth.AccessTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DriveClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeTokens(var token: String = "tok", val refreshed: String = "tok2") : AccessTokens {
        var refreshCount = 0
        override fun validAccessToken() = token
        override fun forceRefresh(): String { refreshCount++; return refreshed }
    }

    @Test fun ensureFolder_reuses_existing() {
        val http = FakeHttp { HttpResp(200, """{"files":[{"id":"F1","name":"Diffuse"}]}""") }
        val client = DriveClient(http, FakeTokens())
        assertEquals("F1", client.ensureFolder("Diffuse"))
        // Only the list query — no create POST.
        assertEquals(1, http.requests.size)
        assertEquals("GET", http.requests.single().method)
    }

    @Test fun ensureFolder_creates_when_absent() {
        val http = FakeHttp { req ->
            if (req.method == "GET") HttpResp(200, """{"files":[]}""")
            else HttpResp(200, """{"id":"NEW"}""")
        }
        val client = DriveClient(http, FakeTokens())
        assertEquals("NEW", client.ensureFolder("Diffuse"))
        assertEquals(2, http.requests.size)
        assertEquals("POST", http.requests[1].method)
    }

    @Test fun upload_routes_small_to_simple_and_large_to_resumable() {
        val small = File(tmp.root, "s.txt").apply { writeText("hi") }
        val large = File(tmp.root, "l.bin").apply { writeBytes(ByteArray(50)) }
        val seen = mutableListOf<String>()
        val http = FakeHttp { req ->
            when {
                "uploadType=multipart" in req.url -> { seen += "simple"; HttpResp(200, """{"id":"S"}""") }
                "uploadType=resumable" in req.url -> {
                    seen += "resumable-init"
                    HttpResp(200, "", mapOf("Location" to "https://upload/session"))
                }
                req.url == "https://upload/session" -> { seen += "resumable-put"; HttpResp(200, """{"id":"L"}""") }
                else -> HttpResp(404, "")
            }
        }
        val client = DriveClient(http, FakeTokens(), resumableThresholdBytes = 10)
        assertEquals("S", client.upload("s.txt", "P", "text/plain", small))
        assertEquals("L", client.upload("l.bin", "P", "application/octet-stream", large))
        assertEquals(listOf("simple", "resumable-init", "resumable-put"), seen)
    }

    @Test fun simple_upload_sends_related_multipart() {
        val f = File(tmp.root, "s.txt").apply { writeText("hi") }
        val http = FakeHttp { HttpResp(200, """{"id":"S"}""") }
        DriveClient(http, FakeTokens()).uploadSimple("s.txt", "P", "text/plain", f)
        val body = http.requests.single().body
        assertTrue(body is Http.Body.RelatedFile)
        assertTrue("uploadType=multipart" in http.requests.single().url)
    }

    @Test fun stream_upload_routes_small_to_simple_and_large_to_resumable() {
        val seen = mutableListOf<String>()
        val http = FakeHttp { req ->
            when {
                "uploadType=multipart" in req.url -> { seen += "simple"; HttpResp(200, """{"id":"S"}""") }
                "uploadType=resumable" in req.url -> {
                    // The init advertises the streamed length up-front (no local file to size).
                    assertEquals("50", req.headers["X-Upload-Content-Length"])
                    seen += "resumable-init"
                    HttpResp(200, "", mapOf("Location" to "https://upload/session"))
                }
                req.url == "https://upload/session" -> { seen += "resumable-put"; HttpResp(200, """{"id":"L"}""") }
                else -> HttpResp(404, "")
            }
        }
        val client = DriveClient(http, FakeTokens(), resumableThresholdBytes = 10)
        val open = { java.io.ByteArrayInputStream(ByteArray(4)) as java.io.InputStream }
        assertEquals("S", client.upload("s.bin", "P", "application/octet-stream", 4L, open))
        assertEquals("L", client.upload("l.bin", "P", "application/octet-stream", 50L, open))
        assertEquals(listOf("simple", "resumable-init", "resumable-put"), seen)
    }

    @Test fun stream_simple_upload_sends_related_stream_body_with_length() {
        val http = FakeHttp { HttpResp(200, """{"id":"S"}""") }
        DriveClient(http, FakeTokens())
            .upload("s.bin", "P", "application/octet-stream", 2L) { java.io.ByteArrayInputStream(ByteArray(2)) }
        val body = http.requests.single().body
        assertTrue(body is Http.Body.RelatedStream)
        assertEquals(2L, (body as Http.Body.RelatedStream).length)
    }

    @Test fun accountEmail_reads_about_user_and_is_null_on_failure() {
        val ok = FakeHttp { HttpResp(200, """{"user":{"emailAddress":"a@b.com","displayName":"A B"}}""") }
        assertEquals("a@b.com", DriveClient(ok, FakeTokens()).accountEmail())
        assertTrue("about" in ok.requests.single().url)

        val bad = FakeHttp { HttpResp(403, """{"error":"forbidden"}""") }
        assertEquals(null, DriveClient(bad, FakeTokens()).accountEmail())
    }

    @Test fun listAllFileIds_pages_through_results() {
        var call = 0
        val http = FakeHttp { req ->
            call++
            val decoded = java.net.URLDecoder.decode(req.url, "UTF-8")
            assertTrue("folders must be excluded from the query", "mimeType!=" in decoded)
            if ("pageToken" !in decoded) {
                HttpResp(200, """{"nextPageToken":"p2","files":[{"id":"A"},{"id":"B"}]}""")
            } else {
                assertTrue("pageToken=p2" in decoded)
                HttpResp(200, """{"files":[{"id":"C"}]}""")
            }
        }
        val ids = DriveClient(http, FakeTokens()).listAllFileIds()
        assertEquals(setOf("A", "B", "C"), ids)
        assertEquals(2, call)
    }

    @Test fun listAllFileIds_empty_when_nothing_on_drive() {
        val http = FakeHttp { HttpResp(200, """{"files":[]}""") }
        assertTrue(DriveClient(http, FakeTokens()).listAllFileIds().isEmpty())
    }

    @Test fun on_401_it_refreshes_and_retries_with_new_token() {
        val tokens = FakeTokens(token = "stale", refreshed = "fresh")
        var calls = 0
        val http = FakeHttp { _ ->
            calls++
            // First list 401s (stale token); the retried list finds the folder.
            if (calls == 1) HttpResp(401, """{"error":"invalid"}""")
            else HttpResp(200, """{"files":[{"id":"F1","name":"Diffuse"}]}""")
        }
        val client = DriveClient(http, tokens)
        assertEquals("F1", client.ensureFolder("Diffuse"))
        assertEquals(1, tokens.refreshCount)
        assertEquals("Bearer stale", http.requests[0].headers["Authorization"])
        assertEquals("Bearer fresh", http.requests[1].headers["Authorization"])
    }
}
