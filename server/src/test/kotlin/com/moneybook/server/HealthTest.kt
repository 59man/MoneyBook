package com.moneybook.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.moneybook.server.testutil.PostgresTestBase
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.log
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class HealthTest : PostgresTestBase() {
    private fun errorCode(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content

    @Test
    fun `health is ok`() = withApp { client ->
        val r = client.get("/health")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("""{"status":"ok"}""", r.bodyAsText())
    }

    @Test
    fun `health is 503 when the pool is closed`() {
        val closed = createDataSource(testConfig).also { it.close() }
        withApp(dataSource = closed) { client ->
            val r = client.get("/health")
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            assertEquals("""{"error":"unavailable"}""", r.bodyAsText())
        }
    }

    @Test
    fun `unknown route is a JSON 404`() = withApp { client ->
        val r = client.get("/nope")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertEquals("""{"error":"not_found"}""", r.bodyAsText())
        assertEquals(ContentType.Application.Json, r.contentType()?.withoutParameters())
    }

    @Test
    fun `unexpected exception is a bare 500`() = withApp(extra = {
        routing { get("/test/boom") { error("boom-detail-xyz") } }
    }) { client ->
        val r = client.get("/test/boom")
        assertEquals(HttpStatusCode.InternalServerError, r.status)
        val body = r.bodyAsText()
        assertEquals("internal", errorCode(body))
        assertFalse("boom-detail-xyz" in body)
        assertFalse("Exception" in body)
        assertFalse("\tat " in body)
    }

    @Test
    fun `api exception, bad input and too large map to their codes`() = withApp(extra = {
        routing {
            get("/test/api") { throw ApiException(HttpStatusCode.Conflict, "taken") }
            get("/test/illegal") { throw IllegalArgumentException("bad") }
            get("/test/big") { throw io.ktor.server.plugins.PayloadTooLargeException(10) }
        }
    }) { client ->
        client.get("/test/api").let {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("taken", errorCode(it.bodyAsText()))
        }
        client.get("/test/illegal").let {
            assertEquals(HttpStatusCode.BadRequest, it.status)
            assertEquals("bad_request", errorCode(it.bodyAsText()))
        }
        client.get("/test/big").let {
            assertEquals(HttpStatusCode.PayloadTooLarge, it.status)
            assertEquals("too_large", errorCode(it.bodyAsText()))
        }
    }

    @Test
    fun `the log holds no authorization header, password or body`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.addAppender(appender)
        try {
            withApp(extra = {
                routing {
                    post("/test/echo") {
                        call.receiveText()
                        call.respondText("ok")
                    }
                    get("/test/boom") { error("whatever") }
                }
            }) { client ->
                client.post("/test/echo") {
                    header(HttpHeaders.Authorization, "Bearer secret-token-xyz")
                    contentType(ContentType.Application.Json)
                    setBody("""{"password":"hunter2"}""")
                }
                client.get("/test/boom") { header(HttpHeaders.Authorization, "Bearer secret-token-xyz") }
            }
        } finally {
            root.detachAppender(appender)
        }
        val lines = appender.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        assert(lines.any { "/test/echo" in it }) { "request not logged: $lines" }
        for (l in lines) {
            assertFalse("secret-token-xyz" in l, l)
            assertFalse("hunter2" in l, l)
        }
    }
}
