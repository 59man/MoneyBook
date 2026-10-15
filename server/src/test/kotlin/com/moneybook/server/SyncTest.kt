package com.moneybook.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.moneybook.server.testutil.PostgresTestBase
import com.moneybook.shared.dto.AttachmentDto
import com.moneybook.shared.dto.SettingsDto
import com.moneybook.shared.dto.TxnDto
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.sync.LoginRequest
import com.moneybook.shared.sync.SyncChange
import com.moneybook.shared.sync.SyncRequest
import com.moneybook.shared.sync.SyncResponse
import com.moneybook.shared.sync.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** Synthetic sync fixtures shared by the sync tests. */
object SyncFixtures {
    fun txn(id: String, updatedAt: Long, note: String = "n", deleted: Boolean = false): JsonObject =
        Json.encodeToJsonElement(
            TxnDto(
                id = id, type = TxnType.EXPENSE, dateTime = "2026-01-02T10:00:00.000", accountId = "default-cash",
                categoryId = "default-food", amount = 1250, currency = "EUR", amountMain = 1250, note = note,
                updatedAt = updatedAt, deleted = deleted,
            ),
        ).jsonObject

    fun change(id: String, updatedAt: Long, note: String = "n", deleted: Boolean = false) =
        SyncChange("txn", id, updatedAt, deleted, payload = txn(id, updatedAt, note, deleted))

    /** A bearer token for [deviceId] of [userId], with the device row that a login would create. */
    fun token(userId: UUID, deviceId: String): String {
        PostgresTestBase.ds.inTransaction { Users.upsertDevice(it, userId, deviceId, "Phone") }
        return JwtService(PostgresTestBase.SECRET).issue(userId, deviceId)
    }

    suspend fun HttpClient.syncRaw(token: String?, req: SyncRequest): HttpResponse = post("/v1/sync") {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(req)
    }

    suspend fun HttpClient.sync(token: String, cursor: Long = 0, changes: List<SyncChange> = emptyList()): SyncResponse =
        syncRaw(token, SyncRequest(cursor, changes)).let {
            assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText())
            it.body()
        }
}

class SyncTest : PostgresTestBase() {
    private val now = 2_000_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(HttpClient) -> Unit) =
        withApp(clock = clock, block = block)

    private fun user(name: String = "alice"): UUID = insertUser(name)

    private data class Row(val payload: JsonObject, val updatedAt: Long, val deleted: Boolean, val deviceId: String, val version: Long)

    private fun stored(userId: UUID, id: String, entity: String = "txn"): Row? = ds.inTransaction { c ->
        c.prepareStatement(
            "select payload::text, updated_at, deleted, device_id, version from sync_record where user_id = ? and entity = ? and id = ?",
        ).use { st ->
            st.setObject(1, userId); st.setString(2, entity); st.setString(3, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else Row(Json.parseToJsonElement(rs.getString(1)).jsonObject, rs.getLong(2), rs.getBoolean(3), rs.getString(4), rs.getLong(5))
            }
        }
    }

    private fun rowCount(): Int = ds.inTransaction { c ->
        c.createStatement().use { it.executeQuery("select count(*) from sync_record").use { rs -> rs.next(); rs.getInt(1) } }
    }

    private fun errorJson(r: String) = Json.parseToJsonElement(r).jsonObject

    // --- push and pull ---

    @Test
    fun `a change pushed by one device is pulled by another with the writer's id`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        val b = SyncFixtures.token(u, "dev-b")
        val pushed = SyncFixtures.change("t1", 1000)
        with(SyncFixtures) {
            val own = client.sync(a, 0, listOf(pushed))
            assertEquals(1, own.changes.size)
            assertEquals(pushed.copy(deviceId = "dev-a"), own.changes.single())
            assertFalse(own.hasMore)
            assertEquals(stored(u, "t1")!!.version, own.newCursor)

            val pulled = client.sync(b, 0)
            assertEquals(listOf(pushed.copy(deviceId = "dev-a")), pulled.changes)
            assertEquals(own.newCursor, pulled.newCursor)
            // nothing new after the cursor
            val again = client.sync(b, pulled.newCursor)
            assertEquals(emptyList(), again.changes)
            assertEquals(pulled.newCursor, again.newCursor)
        }
    }

    @Test
    fun `newer wins and an older change is rejected with the winner returned`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        val b = SyncFixtures.token(u, "dev-b")
        with(SyncFixtures) {
            client.sync(a, 0, listOf(change("t1", 1000, "first")))
            val newer = client.sync(b, 0, listOf(change("t1", 2000, "second")))
            assertEquals("second", newer.changes.single().payload["note"]!!.jsonPrimitive.content)
            val before = stored(u, "t1")!!
            assertEquals(2000, before.updatedAt)
            assertEquals("dev-b", before.deviceId)

            val cursor = newer.newCursor
            val older = client.sync(a, cursor, listOf(change("t1", 1500, "stale")))
            // the winner comes back even though its version is not after the cursor
            assertEquals(listOf(change("t1", 2000, "second").copy(deviceId = "dev-b")), older.changes)
            assertEquals(cursor, older.newCursor)
            assertEquals(before, stored(u, "t1"))
            // the rejected push left no visible version behind
            assertEquals(emptyList(), client.sync(b, cursor).changes)
        }
    }

    @Test
    fun `equal updatedAt is won by the device id that is greater by code point`() {
        // An ICU column collation orders "a" before "B"; the query's collate "C" must still put "B" first.
        ds.inTransaction { c ->
            c.createStatement().use { it.execute("alter table sync_record alter column device_id type text collate \"en-x-icu\"") }
        }
        try {
            app { client ->
                val u = user()
                val lower = SyncFixtures.token(u, "a")
                val upper = SyncFixtures.token(u, "B")
                with(SyncFixtures) {
                    client.sync(upper, 0, listOf(change("t1", 1000, "from B")))
                    client.sync(lower, 0, listOf(change("t1", 1000, "from a")))
                    assertEquals("a", stored(u, "t1")!!.deviceId)

                    client.sync(lower, 0, listOf(change("t2", 1000, "from a")))
                    val r = client.sync(upper, 0, listOf(change("t2", 1000, "from B")))
                    assertEquals("a", stored(u, "t2")!!.deviceId)
                    assertEquals("from a", r.changes.single { it.id == "t2" }.payload["note"]!!.jsonPrimitive.content)
                }
            }
        } finally {
            ds.inTransaction { c ->
                c.createStatement().use { it.execute("alter table sync_record alter column device_id type text collate \"default\"") }
            }
        }
    }

    @Test
    fun `same device and equal updatedAt is a no-op even with another payload`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        with(SyncFixtures) {
            client.sync(a, 0, listOf(change("t1", 1000, "first")))
            val before = stored(u, "t1")!!
            client.sync(a, 0, listOf(change("t1", 1000, "different")))
            assertEquals(before, stored(u, "t1"))
        }
    }

    @Test
    fun `a duplicate push is a no-op that returns the server row`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        with(SyncFixtures) {
            val first = client.sync(a, 0, listOf(change("t1", 1000)))
            val before = stored(u, "t1")!!
            val second = client.sync(a, first.newCursor, listOf(change("t1", 1000)))
            assertEquals(before, stored(u, "t1"))
            assertEquals(listOf(change("t1", 1000).copy(deviceId = "dev-a")), second.changes)
            assertEquals(first.newCursor, second.newCursor)
        }
    }

    @Test
    fun `a rejected winner already in the pull page appears once`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        val b = SyncFixtures.token(u, "dev-b")
        with(SyncFixtures) {
            client.sync(a, 0, listOf(change("t1", 2000)))
            val r = client.sync(b, 0, listOf(change("t1", 1000)))
            assertEquals(listOf(change("t1", 2000).copy(deviceId = "dev-a")), r.changes)
        }
    }

    @Test
    fun `a rejected winner is read after every change of the request`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        with(SyncFixtures) {
            val first = client.sync(a, 0, listOf(change("t1", 2000, "stored")))
            // the first change loses to the stored row, the second replaces that row in the same request
            val r = client.sync(a, first.newCursor, listOf(change("t1", 1000, "old"), change("t1", 3000, "new")))
            assertEquals(listOf(change("t1", 3000, "new").copy(deviceId = "dev-a")), r.changes)
        }
    }

    @Test
    fun `a rejected winner beyond the page does not move the cursor`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        val b = SyncFixtures.token(u, "dev-b")
        with(SyncFixtures) {
            client.sync(a, 0, (0 until 1000).map { change("p$it", 1000) })
            client.sync(a, 0, listOf(change("late", 5000)))
            val lateVersion = stored(u, "late")!!.version
            // b has pulled nothing yet; its push of "late" loses
            val r = client.sync(b, 0, listOf(change("late", 1000)))
            assertTrue(r.hasMore)
            assertEquals(1001, r.changes.size)
            assertEquals("late", r.changes.last().id)
            assertTrue(r.newCursor < lateVersion)
            assertEquals(stored(u, "p999")!!.version, r.newCursor)
        }
    }

    // --- tombstones and envelope ---

    @Test
    fun `tombstones are stored and returned, a newer undelete wins and an older one loses`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        with(SyncFixtures) {
            client.sync(a, 0, listOf(change("t1", 1000)))
            val del = client.sync(a, 0, listOf(change("t1", 2000, deleted = true)))
            assertTrue(del.changes.single().deleted)
            assertTrue(stored(u, "t1")!!.deleted)

            client.sync(a, 0, listOf(change("t1", 1500)))
            assertTrue(stored(u, "t1")!!.deleted)

            val undo = client.sync(a, 0, listOf(change("t1", 3000)))
            assertFalse(undo.changes.single().deleted)
            assertFalse(stored(u, "t1")!!.deleted)
        }
    }

    @Test
    fun `the envelope overwrites updatedAt and deleted in the payload and unknown keys are kept`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        val payload = JsonObject(
            SyncFixtures.txn("t1", 5) + mapOf("deleted" to JsonPrimitive(false), "futureField" to JsonPrimitive("kept")),
        )
        with(SyncFixtures) {
            val r = client.sync(a, 0, listOf(SyncChange("txn", "t1", 1000, true, payload = payload)))
            val row = stored(u, "t1")!!
            assertEquals(1000, row.payload["updatedAt"]!!.jsonPrimitive.content.toLong())
            assertEquals("true", row.payload["deleted"]!!.jsonPrimitive.content)
            assertEquals("kept", row.payload["futureField"]!!.jsonPrimitive.content)
            assertEquals(row.payload, r.changes.single().payload)
        }
    }

    @Test
    fun `the envelope adds no keys the payload lacks`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        with(SyncFixtures) {
            // DTOs encoded with the default Json leave out deleted = false
            client.sync(a, 0, listOf(change("t1", 1000)))
            assertNull(stored(u, "t1")!!.payload["deleted"])
        }
    }

    @Test
    fun `the settings row syncs`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        val b = SyncFixtures.token(u, "dev-b")
        val settings = Json.encodeToJsonElement(SettingsDto(mainCurrency = "CZK", carryOver = true)).jsonObject
        with(SyncFixtures) {
            client.sync(a, 0, listOf(SyncChange("settings", "app", 1000, false, payload = settings)))
            val r = client.sync(b, 0)
            assertEquals(listOf(SyncChange("settings", "app", 1000, false, "dev-a", settings)), r.changes)
            assertEquals(settings, stored(u, "app", "settings")!!.payload)
        }
    }

    // --- clamp ---

    @Test
    fun `an updatedAt more than 5 minutes ahead is clamped to now`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        val hour = Duration.ofHours(1).toMillis()
        val fourMin = Duration.ofMinutes(4).toMillis()
        val fiveMin = Duration.ofMinutes(5).toMillis()
        with(SyncFixtures) {
            val r = client.sync(
                a, 0,
                listOf(change("far", now + hour), change("near", now + fourMin), change("edge", now + fiveMin), change("over", now + fiveMin + 1)),
            )
            assertEquals(now, stored(u, "far")!!.updatedAt)
            assertEquals(now, stored(u, "far")!!.payload["updatedAt"]!!.jsonPrimitive.content.toLong())
            assertEquals(now + fourMin, stored(u, "near")!!.updatedAt)
            assertEquals(now + fiveMin, stored(u, "edge")!!.updatedAt)
            assertEquals(now, stored(u, "over")!!.updatedAt)
            assertEquals(now, r.changes.single { it.id == "far" }.updatedAt)
        }
    }

    // --- paging ---

    @Test
    fun `2500 rows page as 1000, 1000 and 500 with no gap or repeat`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        val b = SyncFixtures.token(u, "dev-b")
        val ids = (0 until 2500).map { "r%04d".format(it) }
        with(SyncFixtures) {
            ids.chunked(1000).forEach { chunk -> client.sync(a, Long.MAX_VALUE, chunk.map { change(it, 1000) }) }

            var cursor = 0L
            val seen = mutableListOf<String>()
            val sizes = mutableListOf<Int>()
            val more = mutableListOf<Boolean>()
            do {
                val r = client.sync(b, cursor)
                seen += r.changes.map { it.id }
                sizes += r.changes.size
                more += r.hasMore
                cursor = r.newCursor
            } while (r.hasMore)
            assertEquals(listOf(1000, 1000, 500), sizes)
            assertEquals(listOf(true, true, false), more)
            assertEquals(ids, seen) // pushed in this order, so version order is id order
            val maxVersion = ds.inTransaction { c ->
                c.createStatement().use { it.executeQuery("select max(version) from sync_record").use { rs -> rs.next(); rs.getLong(1) } }
            }
            assertEquals(maxVersion, cursor)

            val future = client.sync(b, maxVersion + 1000)
            assertEquals(emptyList(), future.changes)
            assertEquals(maxVersion + 1000, future.newCursor)
            assertFalse(future.hasMore)
        }
    }

    // --- validation ---

    private suspend fun HttpClient.expectBadRequest(token: String, req: SyncRequest, messagePart: String) {
        val r = with(SyncFixtures) { syncRaw(token, req) }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = errorJson(r.bodyAsText())
        assertEquals("bad_request", body["error"]!!.jsonPrimitive.content)
        val message = body["message"]?.jsonPrimitive?.content ?: ""
        assertTrue(messagePart in message, "message '$message' should contain '$messagePart'")
        assertFalse("secret-value" in message, message)
    }

    @Test
    fun `invalid requests are 400 and apply nothing`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        val good = SyncFixtures.change("good", 1000, "secret-value")
        val attachment = Json.encodeToJsonElement(AttachmentDto("p1", "t1", "secret-value.png", 0, 1000)).jsonObject
        val bigNote = "x".repeat(70 * 1024)
        with(client) {
            expectBadRequest(a, SyncRequest(0, listOf(good, good.copy(entity = "nope"))), "change 1: unknown entity")
            expectBadRequest(a, SyncRequest(0, listOf(good, good.copy(id = "secret-value"))), "change 1: id does not match")
            expectBadRequest(a, SyncRequest(0, listOf(good, SyncChange("attachment", "p1", 1000, false, payload = attachment))), "change 1: bad attachment")
            expectBadRequest(
                a, SyncRequest(0, listOf(good, good.copy(payload = buildJsonObject { put("id", "good"); put("note", "secret-value") }))),
                "change 1: payload is not a valid txn",
            )
            expectBadRequest(a, SyncRequest(0, List(1001) { SyncFixtures.change("c$it", 1000) }), "1000")
            expectBadRequest(a, SyncRequest(0, listOf(good, SyncFixtures.change("big", 1000, bigNote))), "change 1: payload")
            expectBadRequest(a, SyncRequest(-1, listOf(good)), "cursor")
        }
        assertEquals(0, rowCount())
        with(SyncFixtures) { assertEquals(emptyList(), client.sync(a, 0).changes) }
    }

    @Test
    fun `a body without a charset is read as UTF-8`() = app { client ->
        val u = user()
        val a = SyncFixtures.token(u, "dev-a")
        val body = Json.encodeToString(SyncRequest.serializer(), SyncRequest(0, listOf(SyncFixtures.change("t1", 1000, "Jídlo – čaj"))))
        val r = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer $a")
            setBody(io.ktor.http.content.ByteArrayContent(body.toByteArray(), ContentType.Application.Json.withoutParameters()))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("Jídlo – čaj", stored(u, "t1")!!.payload["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a malformed body is 400`() = app { client ->
        val a = SyncFixtures.token(user(), "dev-a")
        val r = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer $a")
            contentType(ContentType.Application.Json)
            setBody("""{"cursor":"x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals(0, rowCount())
    }

    private val hugeBody: ByteArray by lazy {
        val pad = "x".repeat(16 * 1024 * 1024 + 10)
        """{"cursor":0,"changes":[],"pad":"$pad"}""".toByteArray()
    }

    @Test
    fun `a body over 16 MiB is 413 with or without a content length`() = app { client ->
        val a = SyncFixtures.token(user(), "dev-a")
        val sized = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer $a")
            contentType(ContentType.Application.Json)
            setBody(hugeBody)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, sized.status)
        assertEquals("too_large", errorJson(sized.bodyAsText())["error"]!!.jsonPrimitive.content)

        val chunked = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer $a")
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override val contentLength: Long? = null
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    hugeBody.asList().chunked(1 shl 20).forEach { channel.writeFully(it.toByteArray()) }
                }
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        assertEquals("too_large", errorJson(chunked.bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    // --- epoch ---

    @Test
    fun `the epoch is present and stable across calls and changes when server_meta is updated`() = app { client ->
        val a = SyncFixtures.token(user(), "dev-a")
        with(SyncFixtures) {
            val first = client.sync(a).epoch
            UUID.fromString(first)
            assertEquals(first, client.sync(a, 0, listOf(change("t1", 1000))).epoch)
            assertEquals(first, client.sync(a).epoch)
            ds.inTransaction { c -> c.createStatement().use { it.execute("update server_meta set epoch = gen_random_uuid()") } }
            val second = client.sync(a).epoch
            UUID.fromString(second)
            assertNotEquals(first, second)
        }
    }

    // --- NUL characters ---

    private fun nulMessage(r: String): String = errorJson(r)["message"]!!.jsonPrimitive.content

    @Test
    fun `a NUL in a payload string is a 400 naming the change and nothing in the batch is applied`() = app { client ->
        val a = SyncFixtures.token(user(), "dev-a")
        with(SyncFixtures) {
            val r = client.syncRaw(a, SyncRequest(0, listOf(change("t1", 1000), change("t2", 1000, note = "bad\u0000value"))))
            assertEquals(HttpStatusCode.BadRequest, r.status)
            val body = r.bodyAsText()
            assertTrue(nulMessage(body).startsWith("change 1:"), body)
            assertFalse("bad\\u0000value" in body || "badvalue" in body)
            assertEquals(0, rowCount())
        }
    }

    @Test
    fun `a NUL in a change id is a 400`() = app { client ->
        val a = SyncFixtures.token(user(), "dev-a")
        with(SyncFixtures) {
            val r = client.syncRaw(a, SyncRequest(0, listOf(change("t\u00001", 1000))))
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(nulMessage(r.bodyAsText()).startsWith("change 0:"))
            assertEquals(0, rowCount())
        }
    }

    // --- isolation and auth ---

    @Test
    fun `users never see or overwrite each other's rows`() = app { client ->
        val alice = user("alice")
        val bob = user("bob")
        val a = SyncFixtures.token(alice, "dev-a")
        val b = SyncFixtures.token(bob, "dev-b")
        with(SyncFixtures) {
            client.sync(a, 0, listOf(change("t1", 1000, "alice's")))
            assertEquals(emptyList(), client.sync(b, 0).changes)

            // a newer write of the same (entity, id) by bob, with alice's id smuggled into the body and payload
            val smuggled = change("t1", 9000, "bob's").let { c ->
                c.copy(payload = JsonObject(c.payload + ("userId" to JsonPrimitive(alice.toString()))))
            }
            val body = buildJsonObject {
                put("cursor", 0)
                put("userId", alice.toString())
                put("deviceId", "dev-a")
                put("changes", buildJsonArray { add(Json.encodeToJsonElement(smuggled.copy(deviceId = "dev-a"))) })
            }
            val r = client.post("/v1/sync") {
                header(HttpHeaders.Authorization, "Bearer $b")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            assertEquals(HttpStatusCode.OK, r.status)
            val bobs = r.body<SyncResponse>().changes.single()
            assertEquals("dev-b", bobs.deviceId)
            assertEquals("bob's", bobs.payload["note"]!!.jsonPrimitive.content)

            assertEquals("alice's", stored(alice, "t1")!!.payload["note"]!!.jsonPrimitive.content)
            assertEquals("dev-a", stored(alice, "t1")!!.deviceId)
            assertEquals("dev-b", stored(bob, "t1")!!.deviceId)
            val alicePull = client.sync(a, 0).changes
            assertEquals(listOf(change("t1", 1000, "alice's").copy(deviceId = "dev-a")), alicePull)
        }
    }

    @Test
    fun `no token, an expired token and a deleted user are 401`() = app { client ->
        val u = user()
        with(SyncFixtures) {
            assertEquals(HttpStatusCode.Unauthorized, client.syncRaw(null, SyncRequest(0)).status)
            val expired = JwtService(SECRET, ttl = Duration.ofMinutes(-1)).issue(u, "dev-a")
            assertEquals(HttpStatusCode.Unauthorized, client.syncRaw(expired, SyncRequest(0)).status)

            val ghost = UUID.randomUUID()
            val ghostToken = JwtService(SECRET).issue(ghost, "dev-a")
            val r = client.syncRaw(ghostToken, SyncRequest(0, listOf(change("t1", 1000))))
            assertEquals(HttpStatusCode.Unauthorized, r.status)
            assertEquals("unauthorized", errorJson(r.bodyAsText())["error"]!!.jsonPrimitive.content)
        }
        assertEquals(0, rowCount())
    }

    @Test
    fun `a sync moves device last_seen forward`() = app { client ->
        user()
        val login: TokenResponse = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(API_KEY, "dev-a", "Phone"))
        }.body()
        ds.inTransaction { c -> c.createStatement().use { it.execute("update device set last_seen = now() - interval '1 day'") } }
        fun lastSeen(): Instant = ds.inTransaction { c ->
            c.createStatement().use { it.executeQuery("select last_seen from device").use { rs -> rs.next(); rs.getTimestamp(1).toInstant() } }
        }
        val before = lastSeen()
        with(SyncFixtures) { client.sync(login.accessToken, 0) }
        assertTrue(lastSeen() > before.plus(Duration.ofHours(23)))
    }

    @Test
    fun `a synced payload stays out of the log`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.addAppender(appender)
        try {
            app { client ->
                val a = SyncFixtures.token(user(), "dev-a")
                with(SyncFixtures) { client.sync(a, 0, listOf(change("t1", 1000, "zz-distinctive-note-77"))) }
                // a rejected request too
                client.post("/v1/sync") {
                    header(HttpHeaders.Authorization, "Bearer $a")
                    contentType(ContentType.Application.Json)
                    setBody("""{"cursor":0,"changes":[{"entity":"zz-distinctive-note-77"}]}""")
                }
            }
        } finally {
            root.detachAppender(appender)
        }
        val lines = appender.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        assertNotNull(lines.firstOrNull { "/v1/sync" in it }, "sync not logged: $lines")
        for (l in lines) assertFalse("zz-distinctive-note-77" in l, l)
    }
}
