package com.diffuse.drive

/**
 * In-memory [Http] for tests: records every request and answers via a [handler] lambda,
 * so auth/Drive logic is exercised with canned responses and no network — the same
 * fake-the-seam approach as `provider/FakeRow.kt`.
 */
class FakeHttp(private val handler: (Req) -> HttpResp) : Http {
    data class Req(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: Http.Body?,
    )

    val requests = mutableListOf<Req>()

    override fun request(method: String, url: String, headers: Map<String, String>, body: Http.Body?): HttpResp {
        val req = Req(method, url, headers, body)
        requests.add(req)
        return handler(req)
    }
}
