package com.moneybook.server

import com.moneybook.server.SyncFixtures.token
import com.moneybook.server.testutil.PostgresTestBase
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AttachmentsTest : PostgresTestBase() {
    private fun nameOf(data: ByteArray) = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) } + ".jpg"
    private val cap = 16 * 1024 * 1024

    private fun user(n: String = "alice"): UUID = insertUser(n)
    private fun dir(userId: UUID) = File(dataDir, "attachments/$userId")
    private fun bytes(n: Int) = Random.nextBytes(n)

    private suspend fun HttpClient.putFile(token: String?, n: String, body: ByteArray): HttpResponse = put("/v1/attachments/$n") {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        setBody(body)
    }

    private suspend fun HttpClient.putChunked(token: String, n: String, body: ByteArray): HttpResponse = put("/v1/attachments/$n") {
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Application.OctetStream
            override val contentLength: Long? = null
            override suspend fun writeTo(channel: ByteWriteChannel) {
                body.asList().chunked(1 shl 20).forEach { channel.writeFully(it.toByteArray()) }
            }
        })
    }

    private suspend fun HttpClient.getFile(token: String?, n: String) = get("/v1/attachments/$n") {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun HttpClient.headFile(token: String?, n: String) = head("/v1/attachments/$n") {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun errorCode(r: String) = Json.parseToJsonElement(r).jsonObject["error"]!!.jsonPrimitive.content

    @Test
    fun `put get head round trip, re-put is a no-op`() = withApp { client ->
        val uid = user()
        val t = token(uid, "dev")
        val data = bytes(5000)
        val name = nameOf(data)

        assertEquals(HttpStatusCode.NotFound, client.headFile(t, name).status)
        val missing = client.getFile(t, name)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("not_found", errorCode(missing.bodyAsText()))

        assertEquals(HttpStatusCode.Created, client.putFile(t, name, data).status)
        val got = client.getFile(t, name)
        assertEquals(HttpStatusCode.OK, got.status)
        assertEquals(ContentType.Application.OctetStream, ContentType.parse(got.headers[HttpHeaders.ContentType]!!).withoutParameters())
        assertContentEquals(data, got.bodyAsBytes())
        val head = client.headFile(t, name)
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals("5000", head.headers[HttpHeaders.ContentLength])

        assertEquals(HttpStatusCode.NoContent, client.putFile(t, name, bytes(10)).status)
        assertContentEquals(data, client.getFile(t, name).bodyAsBytes())
    }

    @Test
    fun `stored where the spec says, owner readable, no temp files left`() = withApp { client ->
        val uid = user()
        val data = bytes(100)
        val name = nameOf(data)
        assertEquals(HttpStatusCode.Created, client.putFile(token(uid, "dev"), name, data).status)
        val f = File(dir(uid), name)
        assertContentEquals(data, f.readBytes())
        assertTrue(PosixFilePermission.OWNER_READ in Files.getPosixFilePermissions(f.toPath()))
        assertEquals(listOf(name), dir(uid).list()!!.toList())
    }

    @Test
    fun `bad names are 400 on put get and head`() = withApp { client ->
        val uid = user()
        val t = token(uid, "dev")
        val good = "0123456789abcdef0123456789abcdef.jpg"
        val bad = listOf(
            "0123456789ABCDEF0123456789ABCDEF.jpg",
            good.removeSuffix(".jpg").dropLast(1) + ".jpg",
            good.removeSuffix(".jpg") + "0.jpg",
            "0123456789abcdef0123456789abcdef.png",
            "..%2f..%2fetc%2fpasswd",
            "%2e%2e%2f",
            "%2e%2e",
            "..",
            "$good%0A",
            ".tmp-1",
        )
        for (n in bad) {
            assertEquals(HttpStatusCode.BadRequest, client.putFile(t, n, bytes(3)).status, "put $n")
            val g = client.getFile(t, n)
            assertEquals(HttpStatusCode.BadRequest, g.status, "get $n")
            assertEquals("bad_request", errorCode(g.bodyAsText()))
            assertEquals(HttpStatusCode.BadRequest, client.headFile(t, n).status, "head $n")
        }
        assertFalse(dir(uid).walkTopDown().any { it.isFile })
        assertFalse(File(dataDir, "attachments").walkTopDown().any { it.name == "passwd" || it.name == "etc" })
    }

    @Test
    fun `exactly 16 MiB is accepted, one byte more is 413 for content-length and chunked, nothing left behind`() = withApp { client ->
        val uid = user()
        val t = token(uid, "dev")

        val tooBig = bytes(cap + 1)
        val name = nameOf(tooBig)
        val declared = client.putFile(t, name, tooBig)
        assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)
        assertEquals("too_large", errorCode(declared.bodyAsText()))
        assertEquals(emptyList(), dir(uid).list()?.toList() ?: emptyList())

        val chunked = client.putChunked(t, name, tooBig)
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        assertEquals("too_large", errorCode(chunked.bodyAsText()))
        assertEquals(emptyList(), dir(uid).list()?.toList() ?: emptyList())
        assertEquals(HttpStatusCode.NotFound, client.getFile(t, name).status)

        val exact = bytes(cap)
        val exactName = nameOf(exact)
        assertEquals(HttpStatusCode.Created, client.putChunked(t, exactName, exact).status)
        assertEquals(cap.toLong(), File(dir(uid), exactName).length())
        assertEquals(listOf(exactName), dir(uid).list()!!.toList())
    }

    @Test
    fun `another user gets 404, and their put makes their own file`() = withApp { client ->
        val a = user("alice")
        val b = user("bob")
        val ta = token(a, "dev")
        val tb = token(b, "dev")
        val data = bytes(300)
        val name = nameOf(data)
        assertEquals(HttpStatusCode.Created, client.putFile(ta, name, data).status)

        assertEquals(HttpStatusCode.NotFound, client.getFile(tb, name).status)
        assertEquals(HttpStatusCode.NotFound, client.headFile(tb, name).status)
        assertEquals(HttpStatusCode.Created, client.putFile(tb, name, data).status)
        assertContentEquals(data, File(dir(a), name).readBytes())
        assertContentEquals(data, File(dir(b), name).readBytes())
    }

    @Test
    fun `no token is 401`() = withApp { client ->
        val name = nameOf(bytes(3))
        assertEquals(HttpStatusCode.Unauthorized, client.putFile(null, name, bytes(3)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.getFile(null, name).status)
        assertEquals(HttpStatusCode.Unauthorized, client.headFile(null, name).status)
    }

    @Test
    fun `two simultaneous puts of one name leave one valid file`() = withApp { client ->
        val uid = user()
        val t = token(uid, "dev")
        val data = bytes(2 * 1024 * 1024)
        val name = nameOf(data)
        val statuses = coroutineScope { (1..6).map { async { client.putFile(t, name, data).status } }.awaitAll() }
        assertTrue(statuses.all { it == HttpStatusCode.Created || it == HttpStatusCode.NoContent }, statuses.toString())
        assertTrue(HttpStatusCode.Created in statuses)
        assertContentEquals(data, File(dir(uid), name).readBytes())
        assertEquals(listOf(name), dir(uid).list()!!.toList())
    }

    @Test
    fun `bytes that do not match the name are 400 bad_hash and leave no file`() = withApp { client ->
        val uid = user()
        val t = token(uid, "dev")
        val data = bytes(1000)
        val wrong = nameOf(bytes(1000))
        for (put in listOf(client.putFile(t, wrong, data), client.putChunked(t, wrong, data))) {
            assertEquals(HttpStatusCode.BadRequest, put.status)
            assertEquals("bad_hash", errorCode(put.bodyAsText()))
        }
        assertEquals(emptyList(), dir(uid).list()?.toList() ?: emptyList())
        assertEquals(HttpStatusCode.NotFound, client.getFile(t, wrong).status)
    }
}
