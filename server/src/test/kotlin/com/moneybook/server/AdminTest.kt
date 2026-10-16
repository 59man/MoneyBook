package com.moneybook.server

import com.moneybook.server.SyncFixtures.sync
import com.moneybook.server.testutil.PostgresTestBase
import com.moneybook.shared.sync.LoginRequest
import com.moneybook.shared.sync.RefreshRequest
import com.moneybook.shared.sync.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class AdminTest : PostgresTestBase() {
    private suspend fun HttpClient.post(path: String): HttpResponse = post(path) { header(HttpHeaders.Origin, "http://localhost") }

    private suspend fun HttpClient.login(deviceId: String, name: String, key: String = API_KEY): HttpResponse =
        post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(key, deviceId, name))
        }

    private suspend fun HttpClient.tokens(deviceId: String, name: String = "Phone"): TokenResponse =
        login(deviceId, name).also { assertEquals(HttpStatusCode.OK, it.status) }.body()

    private suspend fun HttpClient.status(): JsonObject {
        val r = get("/admin/status")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.headers[HttpHeaders.CacheControl]!!.contains("no-store"))
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    private fun JsonObject.devices(): List<JsonObject> = this["devices"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    private fun errorCode(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content

    private fun count(sql: String): Int = ds.inTransaction { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) } }
    }

    private fun storedKey(): String? = ds.inTransaction { Users.storedKey(it) }

    /** A sync_record row as a push would leave it; the status page only counts rows. */
    private fun record(entity: String, id: String, deleted: Boolean = false) = ds.inTransaction { c ->
        c.prepareStatement(
            "insert into sync_record (user_id, entity, id, payload, updated_at, deleted, device_id, version) " +
                "values (?, ?, ?, '{}'::jsonb, 1, ?, 'dev', nextval('sync_version'))",
        ).use { it.setObject(1, Users.ownerId(c)); it.setString(2, entity); it.setString(3, id); it.setBoolean(4, deleted); it.executeUpdate() }
    }

    @Test
    fun `status of an empty book`() = withApp { client ->
        val s = client.status()
        assertTrue(s["keyFromEnv"]!!.jsonPrimitive.boolean)
        assertEquals(API_KEY, s.str("apiKey"))
        assertEquals(0, s.devices().size)
        assertEquals(listOf(0, 0, 0, 0), listOf("txn", "accounts", "categories", "photos").map { s["counts"]!!.jsonObject[it]!!.jsonPrimitive.content.toInt() })
        assertEquals(JsonNull, s["lastSyncAt"])
    }

    @Test
    fun `only live rows are counted`() = withApp { client ->
        record("txn", "t1"); record("txn", "t2", deleted = true)
        record("account", "a1"); record("account", "a2")
        record("category", "c1")
        record("attachment", "p1"); record("attachment", "p2", deleted = true)
        record("memo", "m1")
        val c = client.status()["counts"]!!.jsonObject
        assertEquals(listOf(1, 2, 1, 1), listOf("txn", "accounts", "categories", "photos").map { c[it]!!.jsonPrimitive.content.toInt() })
    }

    @Test
    fun `devices come newest first and a sync moves lastSeen`() = withApp { client ->
        val a = client.tokens("dev-a", "Phone")
        client.tokens("dev-b", "Tablet")
        ds.inTransaction { c ->
            c.createStatement().use { it.execute("update device set last_seen = now() - interval '1 hour' where device_id = 'dev-a'") }
        }
        val s = client.status()
        assertEquals(listOf("dev-b", "dev-a"), s.devices().map { it.str("deviceId") })
        assertEquals("Tablet", s.devices()[0].str("name"))
        assertEquals(s.devices()[0]["lastSeen"]!!.jsonPrimitive.long, s["lastSyncAt"]!!.jsonPrimitive.long)
        val before = s.devices().single { it.str("deviceId") == "dev-a" }["lastSeen"]!!.jsonPrimitive.long

        client.sync(a.accessToken)
        val after = client.status().devices()
        assertEquals("dev-a", after[0].str("deviceId"))
        assertTrue(after[0]["lastSeen"]!!.jsonPrimitive.long > before)
    }

    @Test
    fun `removing a device ends its sessions and keeps the others`() = withApp { client ->
        val a = client.tokens("dev-a", "Phone")
        val b = client.tokens("dev-b", "Tablet")
        val r = client.post("/admin/devices/dev-a/remove")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(listOf("dev-b"), Json.parseToJsonElement(r.bodyAsText()).jsonObject.devices().map { it.str("deviceId") })
        assertEquals(0, count("select count(*) from refresh_token where device_id = 'dev-a'"))
        assertEquals(1, count("select count(*) from refresh_token where device_id = 'dev-b'"))

        val refresh = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(a.refreshToken)) }
        assertEquals(HttpStatusCode.Unauthorized, refresh.status)
        val ok = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(b.refreshToken)) }
        assertEquals(HttpStatusCode.OK, ok.status)

        assertEquals(HttpStatusCode.NotFound, client.post("/admin/devices/dev-a/remove").status)
        client.sync(a.accessToken) // still-valid access token: works, but the device row is not recreated
        assertEquals(0, count("select count(*) from device where device_id = 'dev-a'"))
    }

    @Test
    fun `an unknown device is not found`() = withApp { client ->
        val r = client.post("/admin/devices/nope/remove")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertEquals("not_found", errorCode(r.bodyAsText()))
    }

    @Test
    fun `regenerate with a stored key`() = withApp(apiKey = null) { client ->
        val old = client.status().let { assertFalse(it["keyFromEnv"]!!.jsonPrimitive.boolean); it.str("apiKey") }
        assertEquals(HttpStatusCode.OK, client.login("dev-x", "Phone", old).status)
        assertEquals(1, count("select count(*) from refresh_token"))

        val r = client.post("/admin/key/regenerate")
        assertEquals(HttpStatusCode.OK, r.status)
        val s = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        val new = s.str("apiKey")
        assertFalse(s["keyFromEnv"]!!.jsonPrimitive.boolean)
        assertTrue(Regex("[0-9a-f]{64}").matches(new))
        assertNotEquals(old, new)
        assertEquals(new, storedKey())

        val bad = client.login("dev-x", "Phone", old)
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals("invalid_key", errorCode(bad.bodyAsText()))
        assertEquals(0, count("select count(*) from refresh_token"))
        assertEquals(HttpStatusCode.OK, client.login("dev-x", "Phone", new).status)

        val again = Json.parseToJsonElement(client.post("/admin/key/regenerate").bodyAsText()).jsonObject.str("apiKey")
        assertNotEquals(new, again)
    }

    @Test
    fun `regenerate is refused while the key comes from the environment`() = withApp { client ->
        val r = client.post("/admin/key/regenerate")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals("key_from_env", errorCode(r.bodyAsText()))
        assertEquals(API_KEY, storedKey())
    }

    @Test
    fun `a foreign origin is refused and changes nothing`() = withApp(apiKey = null) { client ->
        val before = storedKey()
        val r = client.post("/admin/key/regenerate") { header(HttpHeaders.Origin, "http://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(before, storedKey())
    }

    @Test
    fun `the page is served with a content security policy`() = withApp { client ->
        val r = client.get("/")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))
        assertTrue(r.headers["Content-Security-Policy"] != null)
        val html = r.bodyAsText()
        assertTrue("<title>MoneyBook" in html)
        assertTrue("/admin/status" in html)
    }

    @Test
    fun `page builds dynamic content with textContent only`() = withApp { client ->
        val html = client.get("/").bodyAsText()
        assertTrue("textContent" in html)
        for (bad in listOf("innerHTML", "insertAdjacentHTML", "document.write", "outerHTML")) assertFalse(bad in html, bad)
    }

    @Test
    fun `the page is refused to an untrusted peer`() = withApp(peer = { "192.168.0.7" }) { client ->
        assertEquals(HttpStatusCode.Forbidden, client.get("/").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/admin/status").status)
    }
}
