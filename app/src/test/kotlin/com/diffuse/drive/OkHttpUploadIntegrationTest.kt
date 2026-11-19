package com.diffuse.drive

import com.diffuse.drive.auth.AccessTokens
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The resumable-upload protocol against the *real* [OkHttpHttp], on a local server. The fakes
 * in [DriveClientTest] can't show how OkHttp itself treats Drive's `308 Resume Incomplete`
 * (it must hand it back, not chase it like a redirect) or a connection cut mid-body.
 */
class OkHttpUploadIntegrationTest {

    private val server = MockWebServer().apply { start() }
    private val bytes = ByteArray(10) { it.toByte() }

    private val client = DriveClient(
        http = OkHttpHttp(),
        tokens = object : AccessTokens {
            override fun validAccessToken() = "tok"
            override fun forceRefresh() = "tok"
        },
        sleep = {},
        apiBase = server.url("/drive/v3").toString(),
        uploadBase = server.url("/upload/drive/v3").toString(),
    )

    @After fun stop() = server.shutdown()

    private fun startSession() = MockResponse().setHeader("Location", server.url("/session").toString())

    @Test fun a_308_is_returned_to_the_client_and_the_rest_is_sent() {
        server.enqueue(startSession())
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-3"))
        server.enqueue(MockResponse().setBody("""{"id":"FILE"}"""))

        val id = client.create("v.mp4", "P", "video/mp4", emptyMap(), UploadContent(10) { ByteArrayInputStream(bytes) })

        assertEquals("FILE", id)
        server.takeRequest() // session start
        val first = server.takeRequest()
        assertEquals("bytes 0-9/10", first.getHeader("Content-Range"))
        val second = server.takeRequest()
        assertEquals("bytes 4-9/10", second.getHeader("Content-Range"))
        assertEquals(bytes.copyOfRange(4, 10).toList(), second.body.readByteArray().toList())
    }

    @Test fun a_connection_cut_mid_upload_resumes_on_the_same_session() {
        server.enqueue(startSession())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-1")) // status query
        server.enqueue(MockResponse().setBody("""{"id":"FILE"}"""))

        val id = client.create("v.mp4", "P", "video/mp4", emptyMap(), UploadContent(10) { ByteArrayInputStream(bytes) })

        assertEquals("FILE", id)
        assertEquals("/upload/drive/v3/files?uploadType=resumable&fields=id", server.takeRequest().path)
        server.takeRequest() // the cut-off PUT
        assertEquals("bytes */10", server.takeRequest().getHeader("Content-Range"))
        val resumed = server.takeRequest()
        assertEquals("bytes 2-9/10", resumed.getHeader("Content-Range"))
        assertEquals(bytes.copyOfRange(2, 10).toList(), resumed.body.readByteArray().toList())
        assertEquals(4, server.requestCount)
    }
}
