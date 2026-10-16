package com.moneybook.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.Clock
import javax.sql.DataSource
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main() {
    val config = try {
        Config.fromEnv()
    } catch (e: IllegalStateException) {
        System.err.println("moneybook-server: ${e.message}")
        exitProcess(1)
    }
    val ds = createDataSource(config)
    migrate(ds)
    prepareAuth(config, ds)
    // listens inside the container's network namespace; access is limited by the source-address rules (AccessRules)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(config, ds) }.start(wait = true)
}

fun Application.module(
    config: Config,
    ds: DataSource,
    clock: Clock = Clock.systemUTC(),
    peer: (ApplicationCall) -> String = { it.request.local.remoteAddress },
) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    installAccessRules(AccessRules(config.trustedNetworks, config.allowedHosts), peer)
    installErrorPages()
    installAuth(config, ds)
    installSync(ds, clock)
    installAttachments(config)
    installAdmin(config, ds)
    install(CallLogging) {
        level = Level.INFO
        // method, path and status only: no query string, headers or bodies
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} ${call.response.status()}" }
    }
    routing {
        get("/health") {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    ds.inTransaction { c -> c.createStatement().use { it.execute("select 1") } }
                }
            }.isSuccess
            if (ok) call.respond(mapOf("status" to "ok"))
            else call.respond(HttpStatusCode.ServiceUnavailable, errorBody("unavailable"))
        }
    }
}
