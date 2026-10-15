package com.moneybook.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneybook.server.testutil.PostgresTestBase
import com.moneybook.shared.sync.LoginRequest
import com.moneybook.shared.sync.RefreshRequest
import com.moneybook.shared.sync.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.http.contentType
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class AuthTest : PostgresTestBase() {
    private fun owner(): UUID = ds.inTransaction { Users.ownerId(it) }

    private suspend fun HttpClient.login(key: String = API_KEY, device: String = "dev-1", name: String = "Phone") =
        post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(key, device, name))
        }

    private suspend fun HttpClient.refresh(token: String) = post("/v1/auth/refresh") {
        contentType(ContentType.Application.Json)
        setBody(RefreshRequest(token))
    }

    private suspend fun HttpClient.logout(access: String, refreshToken: String) = post("/v1/auth/logout") {
        header(HttpHeaders.Authorization, "Bearer $access")
        contentType(ContentType.Application.Json)
        setBody(RefreshRequest(refreshToken))
    }

    private suspend fun HttpClient.tokens(device: String = "dev-1"): TokenResponse =
        login(device = device).also { assertEquals(HttpStatusCode.OK, it.status) }.body()

    private fun errorCode(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    private fun count(sql: String, vararg args: Any): Int = ds.inTransaction { c ->
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { it.next(); it.getInt(1) }
        }
    }

    private val whoami: io.ktor.server.application.Application.() -> Unit = {
        routing {
            authenticate(AUTH_PROVIDER) {
                get("/test/whoami") {
                    val (u, d) = call.principalIds()
                    call.respondText("$u|$d")
                }
            }
        }
    }

    // ---- owner and key

    @Test
    fun `startup creates exactly one owner and keeps an existing user as the owner`() {
        var id: UUID? = null
        withApp { client ->
            assertEquals(1, count("select count(*) from app_user where username = 'owner' and password_hash is null"))
            id = owner()
            assertEquals(id.toString(), JWT.decode(client.tokens().accessToken).subject)
        }
        withApp { } // a restart changes nothing
        assertEquals(1, count("select count(*) from app_user"))
        assertEquals(id, owner())
        // an existing deployment: its user stays the owner
        ds.inTransaction { it.createStatement().use { st -> st.execute("truncate sync_record, refresh_token, device, app_user") } }
        val old = insertUser("59man")
        withApp { client ->
            assertEquals(1, count("select count(*) from app_user"))
            assertEquals(old.toString(), JWT.decode(client.tokens().accessToken).subject)
        }
    }

    @Test
    fun `changing the key deletes every refresh token once and the old key stops working`() {
        val a = "key-a-0123456789-key-a-0123456789-key-a-01"
        val b = "key-b-0123456789-key-b-0123456789-key-b-01"
        var token = ""
        withApp(apiKey = a) { client ->
            token = client.login(a).body<TokenResponse>().refreshToken
            assertEquals(1, count("select count(*) from refresh_token"))
        }
        withApp(apiKey = a) { client -> // same key: sessions survive
            assertEquals(HttpStatusCode.OK, client.refresh(token).status)
            assertEquals(2, count("select count(*) from refresh_token"))
        }
        withApp(apiKey = b) { client ->
            assertEquals(0, count("select count(*) from refresh_token"))
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(token).status)
            assertEquals(HttpStatusCode.Unauthorized, client.login(a).status)
            assertEquals(HttpStatusCode.OK, client.login(b).status)
            assertEquals(sha256(b).joinToString("") { "%02x".format(it) }, ds.inTransaction { c -> c.createStatement().use { it.executeQuery("select key_hash from server_meta").use { rs -> rs.next(); rs.getString(1) } } })
        }
    }

    // ---- login

    @Test
    fun `login returns a token pair, a 15 minute JWT and creates the device`() = withApp { client ->
        val id = owner()
        val t0 = Instant.now()
        val r = client.login(API_KEY, "dev-1", "Pixel")
        assertEquals(HttpStatusCode.OK, r.status)
        val t: TokenResponse = r.body()
        assertEquals(900L, t.expiresIn)
        val jwt = JWT.decode(t.accessToken)
        assertEquals("moneybook", jwt.issuer)
        assertEquals(id.toString(), jwt.subject)
        assertEquals("dev-1", jwt.getClaim("did").asString())
        val life = Duration.between(t0, jwt.expiresAtAsInstant).seconds
        assertTrue(life in 890..905, "life $life")
        assertTrue(t.refreshToken.length >= 43 && Regex("[A-Za-z0-9_-]+").matches(t.refreshToken), t.refreshToken)
        assertEquals(1, count("select count(*) from device where user_id = ? and device_id = 'dev-1' and name = 'Pixel'", id))
        // same device again updates the name instead of duplicating
        client.login(API_KEY, "dev-1", "Pixel 2")
        assertEquals(1, count("select count(*) from device where user_id = ?", id))
        assertEquals(1, count("select count(*) from device where name = 'Pixel 2'"))
    }

    @Test
    fun `a wrong, empty or near-miss key is a 401 invalid_key and creates nothing`() = withApp { client ->
        for (key in listOf("wrong", "", API_KEY.dropLast(1), API_KEY + "x", API_KEY.uppercase())) {
            val r = client.login(key)
            assertEquals(HttpStatusCode.Unauthorized, r.status, key)
            assertEquals("""{"error":"invalid_key"}""", r.bodyAsText())
        }
        assertEquals(0, count("select count(*) from device"))
        assertEquals(0, count("select count(*) from refresh_token"))
    }

    @Test
    fun `bad login bodies are 400`() = withApp { client ->
        suspend fun post(body: String) = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.BadRequest, post("{}").status)
        assertEquals(HttpStatusCode.BadRequest, post("not json").status)
        assertEquals(HttpStatusCode.BadRequest, client.login(device = "").status)
        assertEquals(HttpStatusCode.BadRequest, client.login(API_KEY, device = "d".repeat(101)).status)
        assertEquals(HttpStatusCode.BadRequest, client.login(API_KEY, name = "").status)
        assertEquals(HttpStatusCode.BadRequest, client.login(API_KEY, name = "n".repeat(101)).status)
        assertEquals(HttpStatusCode.OK, client.login(API_KEY, device = "d".repeat(100), name = "n".repeat(100)).status)
    }

    @Test
    fun `a NUL in the device id or device name is a 400, not a 500`() = withApp { client ->
        assertEquals(HttpStatusCode.BadRequest, client.login(API_KEY, device = "d\u00001").status)
        assertEquals(HttpStatusCode.BadRequest, client.login(API_KEY, name = "n\u00001").status)
        assertEquals(0, count("select count(*) from device"))
    }

    @Test
    fun `an unhandled error logs its class but never its message`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.addAppender(appender)
        try {
            withApp(extra = {
                routing {
                    get("/test/boom") { throw java.sql.SQLException("payload SECRET-NOTE here", "22P05") }
                    get("/test/boom2") { throw IllegalStateException("payload SECRET-NOTE here") }
                }
            }) { client ->
                assertEquals(HttpStatusCode.InternalServerError, client.get("/test/boom").status)
                assertEquals(HttpStatusCode.InternalServerError, client.get("/test/boom2").status)
            }
        } finally {
            root.detachAppender(appender)
        }
        val lines = appender.list.map {
            it.formattedMessage + (it.throwableProxy?.let { t -> t.message + t.stackTraceElementProxyArray.joinToString() } ?: "")
        }
        assertTrue(lines.any { "java.sql.SQLException" in it && "22P05" in it }, "no class/state logged: $lines")
        assertTrue(lines.any { "java.lang.IllegalStateException" in it }, "no class logged: $lines")
        for (l in lines) assertFalse("SECRET-NOTE" in l, "message leaked in: $l")
    }

    private fun oversize(n: Int = 70 * 1024) = """{"refreshToken":"${"x".repeat(n)}"}"""

    @Test
    fun `an auth body over 64 KiB is 413 with a content length and chunked`() = withApp { client ->
        val sized = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(oversize()) }
        assertEquals(HttpStatusCode.PayloadTooLarge, sized.status)
        assertEquals("too_large", errorCode(sized.bodyAsText()))
        val chunked = client.post("/v1/auth/refresh") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override val contentLength: Long? = null
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    oversize().toByteArray().asList().chunked(16 * 1024).forEach { channel.writeFully(it.toByteArray()) }
                }
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        assertEquals("too_large", errorCode(chunked.bodyAsText()))
        val login = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(oversize()) }
        assertEquals(HttpStatusCode.PayloadTooLarge, login.status)
    }

    // ---- access token

    @Test
    fun `protected route rejects missing, garbage, foreign, expired and incomplete tokens`() = withApp(extra = whoami) { client ->
        val id = UUID.randomUUID()
        suspend fun check(bearer: String?): HttpResponse {
            val r = client.get("/test/whoami") { if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer") }
            assertEquals(HttpStatusCode.Unauthorized, r.status, "token: $bearer")
            assertEquals("""{"error":"unauthorized"}""", r.bodyAsText())
            assertTrue(r.headers[HttpHeaders.WWWAuthenticate]!!.startsWith("Bearer"))
            return r
        }
        check(null)
        check("garbage")
        check(JwtService("other-secret-0123456789-other-secret-0123456789").issue(id, "d"))
        check(JwtService(SECRET, ttl = Duration.ofMinutes(-1)).issue(id, "d"))
        val base = JWT.create().withIssuer("moneybook").withSubject(id.toString()).withExpiresAt(Instant.now().plusSeconds(60))
        check(base.sign(Algorithm.HMAC256(SECRET))) // no did
        check(JWT.create().withIssuer("moneybook").withClaim("did", "d").withExpiresAt(Instant.now().plusSeconds(60)).sign(Algorithm.HMAC256(SECRET))) // no sub
        check(JWT.create().withIssuer("moneybook").withSubject("not-a-uuid").withClaim("did", "d").withExpiresAt(Instant.now().plusSeconds(60)).sign(Algorithm.HMAC256(SECRET)))
        check(JWT.create().withIssuer("evil").withSubject(id.toString()).withClaim("did", "d").withExpiresAt(Instant.now().plusSeconds(60)).sign(Algorithm.HMAC256(SECRET)))
        check(JWT.create().withSubject(id.toString()).withClaim("did", "d").sign(Algorithm.none())) // alg none
        val ok = client.get("/test/whoami") { header(HttpHeaders.Authorization, "Bearer " + JwtService(SECRET).issue(id, "dev-9")) }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("$id|dev-9", ok.bodyAsText())
    }

    @Test
    fun `a login token works on a protected route with the ids from the token`() = withApp(extra = whoami) { client ->
        val id = owner()
        val t = client.tokens(device = "dev-7")
        assertEquals("$id|dev-7", client.get("/test/whoami") { header(HttpHeaders.Authorization, "Bearer ${t.accessToken}") }.bodyAsText())
    }

    // ---- refresh

    @Test
    fun `refresh rotates, the old token dies and the new one works`() = withApp { client ->
        val t1 = client.tokens()
        val r = client.refresh(t1.refreshToken)
        assertEquals(HttpStatusCode.OK, r.status)
        val t2: TokenResponse = r.body()
        assertNotEquals(t1.refreshToken, t2.refreshToken)
        assertEquals(900L, t2.expiresIn)
        assertEquals(JWT.decode(t1.accessToken).subject, JWT.decode(t2.accessToken).subject)
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(t1.refreshToken).status)
        // t1's reuse revoked t2 as well (theft response), so only a new login recovers
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(t2.refreshToken).status)
    }

    @Test
    fun `a chain of refreshes keeps working`() = withApp { client ->
        var t = client.tokens()
        repeat(3) {
            val r = client.refresh(t.refreshToken)
            assertEquals(HttpStatusCode.OK, r.status)
            t = r.body()
        }
    }

    @Test
    fun `expired, unknown and malformed refresh tokens are the same 401`() = withApp { client ->
        val id = owner()
        val t = client.tokens()
        ds.inTransaction { c ->
            c.prepareStatement("update refresh_token set expires_at = now() - interval '1 second' where user_id = ?").use {
                it.setObject(1, id); it.executeUpdate()
            }
        }
        val expired = client.refresh(t.refreshToken)
        val unknown = client.refresh("A".repeat(43))
        assertEquals(HttpStatusCode.Unauthorized, expired.status)
        assertEquals("""{"error":"invalid_token"}""", expired.bodyAsText())
        assertEquals(unknown.status, expired.status)
        assertEquals(expired.bodyAsText(), unknown.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.refresh("").status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody("{}") }.status)
    }

    @Test
    fun `reusing a rotated token revokes that device's live tokens but not another device's`() = withApp { client ->
        val d1 = client.tokens(device = "dev-1")
        val d2 = client.tokens(device = "dev-2")
        val d1b: TokenResponse = client.refresh(d1.refreshToken).body()
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(d1.refreshToken).status) // reuse
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(d1b.refreshToken).status) // winner revoked too
        assertEquals(HttpStatusCode.OK, client.refresh(d2.refreshToken).status) // other device untouched
    }

    @Test
    fun `reusing a revoked or expired token does not trigger the theft response`() = withApp { client ->
        val a = client.tokens(device = "dev-1")
        val b = client.tokens(device = "dev-1") // second login on the same device: both live
        ds.inTransaction { c ->
            c.prepareStatement("update refresh_token set expires_at = now() - interval '1 second' where token_hash = ?").use {
                it.setBytes(1, sha256(a.refreshToken)); it.executeUpdate()
            }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(a.refreshToken).status)
        assertEquals(HttpStatusCode.OK, client.refresh(b.refreshToken).status) // b was not revoked by a's failure
    }

    @Test
    fun `two concurrent refreshes with one token give at most one 200`() = withApp { client ->
        repeat(3) {
            val t = client.tokens()
            val statuses = coroutineScope {
                (1..2).map { async { client.refresh(t.refreshToken).status } }.awaitAll()
            }
            assertTrue(statuses.count { it == HttpStatusCode.OK } <= 1, "$statuses")
            assertTrue(statuses.all { it == HttpStatusCode.OK || it == HttpStatusCode.Unauthorized }, "$statuses")
        }
    }

    // ---- logout

    @Test
    fun `logout revokes the token, answers 204 and is repeatable`() = withApp { client ->
        val t = client.tokens()
        assertEquals(HttpStatusCode.NoContent, client.logout(t.accessToken, t.refreshToken).status)
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(t.refreshToken).status)
        assertEquals(HttpStatusCode.NoContent, client.logout(t.accessToken, t.refreshToken).status)
        assertEquals(HttpStatusCode.NoContent, client.logout(t.accessToken, "unknown").status)
    }

    @Test
    fun `logout needs a bearer token`() = withApp { client ->
        val t = client.tokens()
        val r = client.post("/v1/auth/logout") { contentType(ContentType.Application.Json); setBody(RefreshRequest(t.refreshToken)) }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals(HttpStatusCode.OK, client.refresh(t.refreshToken).status)
    }

    @Test
    fun `logging out with another device's refresh token changes nothing`() = withApp { client ->
        val d1 = client.tokens(device = "dev-1")
        val d2 = client.tokens(device = "dev-2")
        assertEquals(HttpStatusCode.NoContent, client.logout(d1.accessToken, d2.refreshToken).status)
        assertEquals(HttpStatusCode.OK, client.refresh(d2.refreshToken).status)
    }

    // ---- storage

    @Test
    fun `only the sha256 of a refresh token is stored`() = withApp { client ->
        val id = owner()
        val t = client.tokens()
        ds.inTransaction { c ->
            c.prepareStatement("select token_hash, user_id, device_id, expires_at > now() + interval '89 days' from refresh_token").use { st ->
                st.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertContentEquals(sha256(t.refreshToken), rs.getBytes(1))
                    assertEquals(id, rs.getObject(2) as UUID)
                    assertEquals("dev-1", rs.getString(3))
                    assertTrue(rs.getBoolean(4))
                    assertFalse(rs.next())
                }
            }
            c.prepareStatement("select count(*) from refresh_token where position(?::bytea in token_hash) > 0 and length(token_hash) <> 32").use {
                it.setBytes(1, t.refreshToken.toByteArray()); it.executeQuery().use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
            }
        }
    }

    @Test
    fun `revokeLive kills every live token of one user and device only`() = withApp { client ->
        val id = owner()
        val d1 = client.tokens(device = "dev-1")
        val d2 = client.tokens(device = "dev-2")
        ds.inTransaction { Users.revokeLiveTokens(it, id, "dev-1") }
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(d1.refreshToken).status)
        assertEquals(HttpStatusCode.OK, client.refresh(d2.refreshToken).status)
    }

    // ---- rate limit

    @Test
    fun `the 11th login in a minute is 429 and refresh is not limited`() = withApp { client ->
        suspend fun bad() = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("{}") }
        repeat(10) { assertEquals(HttpStatusCode.BadRequest, bad().status, "request ${it + 1}") }
        val r = bad()
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
        assertEquals("""{"error":"rate_limited"}""", r.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.refresh("A".repeat(43)).status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    // ---- logs

    @Test
    fun `the log holds neither the key nor the issued tokens`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.addAppender(appender)
        val secrets = mutableListOf(API_KEY, "wrong-key-xyz")
        try {
            withApp(extra = whoami) { client ->
                val t: TokenResponse = client.login().body()
                secrets += listOf(t.accessToken, t.refreshToken)
                client.get("/test/whoami") { header(HttpHeaders.Authorization, "Bearer ${t.accessToken}") }
                val t2: TokenResponse = client.refresh(t.refreshToken).body()
                secrets += listOf(t2.accessToken, t2.refreshToken)
                client.refresh(t.refreshToken) // reuse path
                client.logout(t2.accessToken, t2.refreshToken)
                client.login("wrong-key-xyz")
            }
        } finally {
            root.detachAppender(appender)
        }
        val lines = appender.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        assertTrue(lines.any { "/v1/auth/login" in it }, "login not logged: $lines")
        for (l in lines) for (s in secrets) assertFalse(s in l, "secret leaked in: $l")
    }
}
