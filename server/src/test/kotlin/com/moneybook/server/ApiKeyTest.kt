package com.moneybook.server

import com.moneybook.server.testutil.PostgresTestBase
import com.moneybook.shared.sync.LoginRequest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiKeyTest : PostgresTestBase() {
    private val a = "key-a-0123456789-key-a-0123456789-key-a-01"
    private val b = "key-b-0123456789-key-b-0123456789-key-b-01"

    private fun prepare(env: String?) = prepareAuth(config(env), ds)
    private fun stored(): String? = ds.inTransaction { Users.storedKey(it) }
    private fun tokens(): Int = ds.inTransaction { c ->
        c.createStatement().use { it.executeQuery("select count(*) from refresh_token").use { rs -> rs.next(); rs.getInt(1) } }
    }
    private fun addToken() { ds.inTransaction { c -> Users.issueRefreshToken(c, Users.ownerId(c), "dev") } }

    @Test
    fun `env key is stored and wins, then the stored one stays when env is unset`() {
        prepare(a)
        assertEquals(a, stored())
        prepare(null)
        assertEquals(a, stored())
        assertEquals(a, ds.inTransaction { Users.resolveKey(it, null) })
    }

    @Test
    fun `nothing set generates a 64 hex key once and keeps the sessions`() {
        prepare(null)
        val k = stored()!!
        assertTrue(Regex("[0-9a-f]{64}").matches(k), k)
        addToken()
        prepare(null)
        assertEquals(k, stored())
        assertEquals(1, tokens())
    }

    @Test
    fun `a replaced env key deletes the refresh tokens and stays after env is unset`() {
        prepare(a)
        addToken()
        prepare(b)
        assertEquals(b, stored())
        assertEquals(0, tokens())
        prepare(null)
        assertEquals(b, stored())
    }

    @Test
    fun `login uses the effective key and never matches a missing one`() {
        prepare(a)
        withApp(apiKey = b) { client ->
            fun login(key: String) = suspend { client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(key, "d", "Phone")) }.status }
            assertEquals(HttpStatusCode.OK, login(b)())
            assertEquals(HttpStatusCode.Unauthorized, login(a)())
            assertEquals(HttpStatusCode.Unauthorized, login("")())
            ds.inTransaction { c -> c.createStatement().use { it.executeUpdate("update server_meta set api_key = null") } }
            assertEquals(HttpStatusCode.Unauthorized, login("")())
            assertEquals(HttpStatusCode.Unauthorized, login(b)())
        }
    }
}
