package com.moneybook.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException

/** [detail] goes to the client, so it must never hold input values. */
class ApiException(val status: HttpStatusCode, val code: String, val detail: String? = null) : RuntimeException(code)

fun errorBody(code: String, message: String? = null) =
    if (message == null) mapOf("error" to code) else mapOf("error" to code, "message" to message)

fun Application.installErrorPages() {
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, errorBody(e.code, e.detail)) }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, errorBody("bad_request")) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, errorBody("bad_request")) }
        exception<IllegalArgumentException> { call, _ -> call.respond(HttpStatusCode.BadRequest, errorBody("bad_request")) }
        exception<PayloadTooLargeException> { call, _ -> call.respond(HttpStatusCode.PayloadTooLarge, errorBody("too_large")) }
        exception<Throwable> { call, e ->
            // class and SQLState only: the message of a database error can quote the payload
            val state = (e as? java.sql.SQLException)?.sqlState?.let { " (SQLState $it)" }.orEmpty()
            call.application.log.error("unhandled exception ${e::class.java.name}$state")
            call.respond(HttpStatusCode.InternalServerError, errorBody("internal"))
        }
        status(HttpStatusCode.TooManyRequests) { call, _ -> call.respond(HttpStatusCode.TooManyRequests, errorBody("rate_limited")) }
        status(HttpStatusCode.NotFound) { call, _ -> call.respond(HttpStatusCode.NotFound, errorBody("not_found")) }
    }
}
