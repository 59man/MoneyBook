package com.moneybook.server

import com.moneybook.shared.sync.SYNC_MAX_BODY_BYTES
import com.moneybook.shared.sync.SYNC_MAX_CHANGES
import com.moneybook.shared.sync.SYNC_MAX_PAYLOAD_BYTES
import com.moneybook.shared.sync.SyncChange
import com.moneybook.shared.sync.SyncEntity
import com.moneybook.shared.sync.SyncRequest
import com.moneybook.shared.sync.SyncResponse
import com.moneybook.shared.sync.overwriteEnvelope
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private const val CLAMP_MS = 5 * 60 * 1000L
private val requestJson = Json { ignoreUnknownKeys = true }

fun Application.installSync(ds: DataSource, clock: Clock) {
    routing {
        authenticate(AUTH_PROVIDER) {
            route("/v1/sync") {
                // counts the bytes read, so a chunked body without Content-Length is cut off too
                install(RequestBodyLimit) { bodyLimit { SYNC_MAX_BODY_BYTES.toLong() } }
                post {
                    val (userId, deviceId) = call.principalIds()
                    // Decoded here: ContentNegotiation would turn the body limit's 413 into a 400. JSON is UTF-8 whatever
                    // the Content-Type says.
                    val req = requestJson.decodeFromString<SyncRequest>(call.receive<ByteArray>().decodeToString())
                    validate(req)
                    val res = withContext(Dispatchers.IO) {
                        ds.inTransaction { sync(it, userId, deviceId, req, clock.millis()) }
                    }
                    call.respond(res)
                }
            }
        }
    }
}

/** Throws a 400 whose message names the change index and the rule, never a value from the request. */
private fun validate(req: SyncRequest) {
    fun bad(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, "bad_request", message)
    if (req.cursor < 0) bad("cursor must be >= 0")
    if (req.changes.size > SYNC_MAX_CHANGES) bad("more than $SYNC_MAX_CHANGES changes")
    req.changes.forEachIndexed { i, change ->
        if (change.payload.toString().encodeToByteArray().size > SYNC_MAX_PAYLOAD_BYTES) {
            bad("change $i: payload over $SYNC_MAX_PAYLOAD_BYTES bytes")
        }
        try {
            SyncEntity.validate(change)
        } catch (e: IllegalArgumentException) {
            bad("change $i: ${e.message}")
        }
    }
}

private class Versioned(val version: Long, val change: SyncChange)

private const val UPSERT = """
insert into sync_record (user_id, entity, id, payload, updated_at, deleted, device_id, version)
values (?, ?, ?, ?::jsonb, ?, ?, ?, nextval('sync_version'))
on conflict (user_id, entity, id) do update
  set payload = excluded.payload, updated_at = excluded.updated_at, deleted = excluded.deleted,
      device_id = excluded.device_id, version = excluded.version
  where (sync_record.updated_at, sync_record.device_id collate "C") < (excluded.updated_at, excluded.device_id collate "C")
returning version
"""

private const val COLUMNS = "entity, id, payload::text, updated_at, deleted, device_id, version"

private fun sync(c: Connection, userId: UUID, deviceId: String, req: SyncRequest, now: Long): SyncResponse {
    // Serializes this user's syncs: versions commit in increasing order, so a pull can never skip a row that
    // commits later with a lower version. Each later statement (read committed) sees the previous holder's commit.
    c.prepareStatement("select pg_advisory_xact_lock(?)").use {
        it.setLong(1, userId.mostSignificantBits xor userId.leastSignificantBits)
        it.executeQuery().close()
    }
    // access tokens are not checked against the database, so a deleted user's token still verifies
    val userExists = c.prepareStatement("select 1 from app_user where id = ?").use {
        it.setObject(1, userId)
        it.executeQuery().use(ResultSet::next)
    }
    if (!userExists) throw ApiException(HttpStatusCode.Unauthorized, "unauthorized")

    val rejected = LinkedHashSet<Pair<String, String>>()
    c.prepareStatement(UPSERT).use { st ->
        for (change in req.changes) {
            val updatedAt = if (change.updatedAt > now + CLAMP_MS) now else change.updatedAt
            st.setObject(1, userId)
            st.setString(2, change.entity)
            st.setString(3, change.id)
            st.setString(4, overwriteEnvelope(change.payload, updatedAt, change.deleted).toString())
            st.setLong(5, updatedAt)
            st.setBoolean(6, change.deleted)
            st.setString(7, deviceId)
            st.executeQuery().use { if (!it.next()) rejected += change.entity to change.id }
        }
    }

    val pulled = c.prepareStatement(
        "select $COLUMNS from sync_record where user_id = ? and version > ? order by version limit ${SYNC_MAX_CHANGES + 1}",
    ).use {
        it.setObject(1, userId)
        it.setLong(2, req.cursor)
        readRows(it.executeQuery())
    }
    val page = pulled.take(SYNC_MAX_CHANGES)
    // Read after every change of the request, so a winner equals its copy in the page when both exist.
    val missing = rejected - page.map { it.change.entity to it.change.id }.toSet()
    val winners = if (missing.isEmpty()) emptyList() else c.prepareStatement(
        "select $COLUMNS from sync_record where user_id = ? and (entity, id) in (select * from unnest(?::text[], ?::text[])) order by version",
    ).use {
        it.setObject(1, userId)
        it.setArray(2, c.createArrayOf("text", missing.map { k -> k.first }.toTypedArray()))
        it.setArray(3, c.createArrayOf("text", missing.map { k -> k.second }.toTypedArray()))
        readRows(it.executeQuery())
    }
    Users.touchDevice(c, userId, deviceId)
    val epoch = c.createStatement().use { st -> st.executeQuery("select epoch::text from server_meta").use { it.next(); it.getString(1) } }
    return SyncResponse(
        changes = (page + winners).map { it.change },
        // from the page only: a winner past the page's end must not skip the rows before it
        newCursor = page.lastOrNull()?.version ?: req.cursor,
        hasMore = pulled.size > SYNC_MAX_CHANGES,
        epoch = epoch,
    )
}

private fun readRows(rs: ResultSet): List<Versioned> = rs.use {
    buildList {
        while (rs.next()) {
            val change = SyncChange(
                entity = rs.getString(1),
                id = rs.getString(2),
                updatedAt = rs.getLong(4),
                deleted = rs.getBoolean(5),
                deviceId = rs.getString(6),
                payload = Json.parseToJsonElement(rs.getString(3)).jsonObject,
            )
            add(Versioned(rs.getLong(7), change))
        }
    }
}
