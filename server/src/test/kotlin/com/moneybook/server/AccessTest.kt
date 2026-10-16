package com.moneybook.server

import com.moneybook.server.testutil.PostgresTestBase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.test.Test
import kotlin.test.assertEquals

class AccessTest : PostgresTestBase() {
    private val forbiddenNetwork = """{"error":"forbidden_network"}"""

    @Test
    fun `a LAN peer gets 403 everywhere except health`() = withApp(peer = { "192.168.0.7" }) { client ->
        for (path in listOf("/v1/anything", "/", "/admin/status")) {
            val r = client.get(path)
            assertEquals(HttpStatusCode.Forbidden, r.status, path)
            assertEquals(forbiddenNetwork, r.bodyAsText(), path)
        }
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/auth/login").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        // a spoofed forwarded header changes nothing
        val r = client.get("/v1/anything") { header("X-Forwarded-For", "127.0.0.1") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `a refused request never reaches a route`() {
        var hits = 0
        withApp(peer = { "192.168.0.7" }, extra = { routing { get("/test/hit") { hits++; call.respondText("hit") } } }) { client ->
            assertEquals(HttpStatusCode.Forbidden, client.get("/test/hit").status)
        }
        assertEquals(0, hits)
    }

    @Test
    fun `host rule`() = withApp { client ->
        assertEquals(HttpStatusCode.OK, client.get("/health") { header(HttpHeaders.Host, "evil.example") }.status)
        val r = client.get("/nope") { header(HttpHeaders.Host, "evil.example") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("""{"error":"forbidden_host"}""", r.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/nope").status)
    }

    @Test
    fun `admin posts need a same-origin Origin`() = withApp { client ->
        val evil = client.post("/admin/anything") { header(HttpHeaders.Origin, "http://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, evil.status)
        assertEquals("""{"error":"forbidden_origin"}""", evil.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, client.post("/admin/anything").status)
    }

    private suspend fun io.ktor.client.HttpClient.key() =
        Regex("\"apiKey\":\"([0-9a-f]+)\"").find(get("/admin/status").bodyAsText())!!.groupValues[1]

    @Test
    fun `an encoded admin path does not skip the origin rule`() = withApp(apiKey = null) { client ->
        val r = client.post("/%61dmin/backup")
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("""{"error":"forbidden_origin"}""", r.bodyAsText())
    }

    @Test
    fun `dot segments under v1 cannot reach the key route`() = withApp(apiKey = null) { client ->
        val before = client.key()
        for (path in listOf("/v1/%2e%2e/admin/key/regenerate", "/v1/../admin/key/regenerate")) {
            client.post(path) { header(HttpHeaders.Origin, "http://evil.example") }
            assertEquals(HttpStatusCode.NotFound, client.post(path).status, path)
            assertEquals(before, client.key(), path)
        }
    }

    @Test
    fun `a LAN peer cannot sync`() = withApp(peer = { "192.168.0.7" }) { client ->
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/sync").status)
    }
}
