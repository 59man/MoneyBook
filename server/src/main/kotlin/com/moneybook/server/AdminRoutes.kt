package com.moneybook.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.sql.Connection
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable data class DeviceStatus(val deviceId: String, val name: String, val lastSeen: Long)
@Serializable data class Counts(val txn: Int, val accounts: Int, val categories: Int, val photos: Int)
@Serializable data class StatusDto(
    val keyFromEnv: Boolean,
    val apiKey: String,
    val devices: List<DeviceStatus>,
    val counts: Counts,
    val lastSyncAt: Long?,
    val backup: BackupStatus,
)

fun status(c: Connection, config: Config, backups: BackupService): StatusDto {
    val owner = Users.ownerId(c)
    val devices = c.prepareStatement(
        "select device_id, name, (extract(epoch from last_seen) * 1000)::bigint from device where user_id = ? order by last_seen desc, device_id",
    ).use { st ->
        st.setObject(1, owner)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(DeviceStatus(rs.getString(1), rs.getString(2), rs.getLong(3))) } }
    }
    val counts = c.prepareStatement(
        "select entity, count(*) from sync_record where user_id = ? and not deleted and entity in ('txn','account','category','attachment') group by entity",
    ).use { st ->
        st.setObject(1, owner)
        st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) } }
    }
    return StatusDto(
        keyFromEnv = config.apiKey != null,
        apiKey = Users.storedKey(c).orEmpty(),
        devices = devices,
        counts = Counts(counts["txn"] ?: 0, counts["account"] ?: 0, counts["category"] ?: 0, counts["attachment"] ?: 0),
        lastSyncAt = devices.maxOfOrNull { it.lastSeen },
        backup = backups.status(),
    )
}

private suspend fun <T> io(block: () -> T) = withContext(Dispatchers.IO) { block() }

private fun ApplicationCall.noStore() = response.header(HttpHeaders.CacheControl, "no-store")

private object PageResource

/** The status page at `/` and the JSON routes under `/admin`. The access rules (network, Host, Origin) run before all of it. */
fun Application.installAdmin(config: Config, ds: DataSource, backups: BackupService) {
    val html = PageResource.javaClass.getResource("/admin/index.html")!!.readText()
    routing {
        get("/") {
            call.response.header(
                "Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            )
            call.noStore()
            call.respondText(html, ContentType.Text.Html)
        }
        route("/admin") {
            get("/status") {
                call.noStore()
                call.respond(io { ds.inTransaction { status(it, config, backups) } })
            }
            post("/key/regenerate") {
                if (config.apiKey != null) throw ApiException(HttpStatusCode.Conflict, "key_from_env")
                call.noStore()
                call.respond(io { ds.inTransaction { c -> Users.regenerateKey(c); status(c, config, backups) } })
            }
            post("/backup") {
                if (!backups.launch(this@installAdmin)) throw ApiException(HttpStatusCode.Conflict, "backup_running")
                call.noStore()
                call.respond(HttpStatusCode.Accepted, mapOf("started" to true))
            }
            post("/devices/{deviceId}/remove") {
                val id = call.parameters["deviceId"]!!
                call.noStore()
                call.respond(
                    io {
                        ds.inTransaction { c ->
                            if (!Users.removeDevice(c, Users.ownerId(c), id)) throw ApiException(HttpStatusCode.NotFound, "not_found")
                            status(c, config, backups)
                        }
                    },
                )
            }
        }
    }
}
