package com.moneybook.data.sync

import com.moneybook.data.DeviceSettings
import com.moneybook.shared.sync.LoginRequest
import com.moneybook.shared.sync.SyncRequest
import com.moneybook.testutil.FakeDeviceSettingsRepository
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class HttpSyncApiTest {
    @get:Rule val folder = TemporaryFolder()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val url = "http://127.0.0.1:${server.address.port}"
    private val seen = CopyOnWriteArrayList<String>() // "METHOD path bearer"
    private val bodies = CopyOnWriteArrayList<ByteArray>()

    @After fun stop() = server.stop(0)

    private fun on(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { ex ->
            seen += "${ex.requestMethod} ${ex.requestURI.path} ${ex.requestHeaders.getFirst("Authorization").orEmpty()}"
            bodies += ex.requestBody.readBytes()
            handler(ex)
            ex.close()
        }
    }

    private fun HttpExchange.reply(status: Int, text: String = "") {
        val b = text.toByteArray()
        sendResponseHeaders(status, if (b.isEmpty() && requestMethod == "HEAD") -1 else if (b.isEmpty()) -1 else b.size.toLong())
        if (b.isNotEmpty() && requestMethod != "HEAD") responseBody.write(b)
    }

    private val tokens = """{"accessToken":"A1","refreshToken":"R1","expiresIn":900}"""
    private val syncOk = """{"changes":[],"newCursor":7,"hasMore":false,"epoch":"e"}"""
    private val login = LoginRequest("k3y-abc", "dev", "Pixel")

    private fun device(refresh: String = "R0") = FakeDeviceSettingsRepository(DeviceSettings(serverUrl = url, refreshToken = refresh))

    @Test fun loginPostsJsonAndReturnsTokens() = runBlocking {
        on("/v1/auth/login") { it.reply(200, tokens) }
        val t = HttpSyncApi(device("")).login(url + "/", login)
        assertEquals("A1", t.accessToken)
        assertEquals("R1", t.refreshToken)
        assertTrue(String(bodies[0]).contains("\"apiKey\":\"k3y-abc\""))
        assertEquals("POST /v1/auth/login ", seen[0])
    }

    @Test fun loginWith429IsRejectedWithStatus429() = runBlocking {
        on("/v1/auth/login") { it.reply(429, """{"error":"rate_limited"}""") }
        val e = try { HttpSyncApi(device("")).login(url, login); null } catch (e: SyncError) { e }
        assertEquals(429, (e as SyncError.Rejected).status) // the Sync screen shows "too many attempts"
    }

    @Test fun loginWith401StoresNothing() = runBlocking {
        on("/v1/auth/login") { it.reply(401, """{"error":"invalid_key"}""") }
        val dev = device("")
        try {
            HttpSyncApi(dev).login(url, login)
            fail()
        } catch (e: SyncError) {
            assertEquals(SyncError.Unauthorized, e)
        }
        assertEquals(DeviceSettings(serverUrl = url), dev.current())
        assertEquals(1, seen.size) // no refresh attempt
    }

    @Test fun syncSendsBearerAndJsonBody() = runBlocking {
        on("/v1/auth/login") { it.reply(200, tokens) }
        on("/v1/sync") { it.reply(200, syncOk) }
        val api = HttpSyncApi(device())
        api.login(url, login)
        val r = api.sync(SyncRequest(cursor = 3))
        assertEquals(7L, r.newCursor)
        assertEquals("e", r.epoch)
        assertEquals("POST /v1/sync Bearer A1", seen.last())
        assertTrue(String(bodies.last()).contains("\"cursor\":3"))
    }

    @Test fun unauthorizedRefreshesOnceAndRetries() = runBlocking {
        on("/v1/auth/login") { it.reply(200, tokens) }
        on("/v1/auth/refresh") { it.reply(200, """{"accessToken":"A2","refreshToken":"R2","expiresIn":900}""") }
        val dev = device()
        var storedBeforeRetry = ""
        on("/v1/sync") {
            if (it.requestHeaders.getFirst("Authorization") == "Bearer A2") {
                storedBeforeRetry = dev.state.value.refreshToken
                it.reply(200, syncOk)
            } else {
                it.reply(401, """{"error":"unauthorized"}""")
            }
        }
        val api = HttpSyncApi(dev)
        api.login(url, login)
        assertEquals(7L, api.sync(SyncRequest(0)).newCursor)
        assertEquals("R2", storedBeforeRetry)
        assertEquals(listOf("POST /v1/auth/login ", "POST /v1/sync Bearer A1", "POST /v1/auth/refresh ", "POST /v1/sync Bearer A2"), seen.toList())
        assertTrue(String(bodies[2]).contains("R0")) // refreshed with the stored token
    }

    @Test fun rejectedRefreshClearsTokensAndThrowsUnauthorized() = runBlocking {
        on("/v1/auth/login") { it.reply(200, tokens) }
        on("/v1/auth/refresh") { it.reply(401, """{"error":"invalid_token"}""") }
        on("/v1/sync") { it.reply(401) }
        val dev = device()
        val api = HttpSyncApi(dev)
        api.login(url, login)
        try {
            api.sync(SyncRequest(0))
            fail()
        } catch (e: SyncError) {
            assertEquals(SyncError.Unauthorized, e)
            assertEquals("session_expired", e.code)
        }
        assertEquals("", dev.state.value.refreshToken)
        assertEquals("session_expired", dev.state.value.lastSyncError)
        assertFalse(dev.state.value.signedIn)
    }

    @Test fun secondUnauthorizedAfterRetryThrowsUnauthorized() = runBlocking {
        on("/v1/auth/login") { it.reply(200, tokens) }
        on("/v1/auth/refresh") { it.reply(200, """{"accessToken":"A2","refreshToken":"R2","expiresIn":900}""") }
        on("/v1/sync") { it.reply(401) }
        val dev = device()
        val api = HttpSyncApi(dev)
        api.login(url, login)
        try {
            api.sync(SyncRequest(0))
            fail()
        } catch (e: SyncError) {
            assertEquals(SyncError.Unauthorized, e)
        }
        assertEquals(2, seen.count { it.startsWith("POST /v1/sync") }) // original + one retry
        assertEquals(1, seen.count { it.contains("/refresh") })
        assertEquals("", dev.state.value.refreshToken)
    }

    @Test fun concurrentCallsRefreshOnlyOnce() = runBlocking {
        val refreshes = AtomicInteger()
        on("/v1/auth/login") { it.reply(200, tokens) }
        on("/v1/auth/refresh") {
            refreshes.incrementAndGet()
            Thread.sleep(150)
            it.reply(200, """{"accessToken":"A2","refreshToken":"R2","expiresIn":900}""")
        }
        on("/v1/sync") {
            if (it.requestHeaders.getFirst("Authorization") == "Bearer A2") it.reply(200, syncOk) else it.reply(401)
        }
        val api = HttpSyncApi(device())
        api.login(url, login)
        awaitAll(
            async(Dispatchers.IO) { api.sync(SyncRequest(0)) },
            async(Dispatchers.IO) { api.sync(SyncRequest(0)) },
        )
        assertEquals(1, refreshes.get())
    }

    @Test fun errorMapping() = runBlocking {
        on("/v1/auth/login") { it.reply(200, tokens) }
        var status = 400
        on("/v1/sync") { it.reply(status, """{"error":"x"}""") }
        val api = HttpSyncApi(device())
        api.login(url, login)
        suspend fun fail(s: Int): SyncError {
            status = s
            return try { api.sync(SyncRequest(0)); throw AssertionError("no error") } catch (e: SyncError) { e }
        }
        val r = fail(400) as SyncError.Rejected
        assertEquals(400, r.status)
        assertEquals("x", r.error)
        assertEquals("rejected", r.code)
        assertEquals(429, (fail(429) as SyncError.Rejected).status)
        assertEquals(500, (fail(500) as SyncError.Server).status)
        assertEquals("server", fail(503).code)

        server.stop(0)
        val closed = try { api.sync(SyncRequest(0)); null } catch (e: SyncError) { e }
        assertTrue(closed is SyncError.Offline)
        assertEquals("offline", closed!!.code)
        val unknown = HttpSyncApi(FakeDeviceSettingsRepository(DeviceSettings(serverUrl = "https://nonexistent.invalid", refreshToken = "R"))).run {
            try { login("https://nonexistent.invalid", login); null } catch (e: SyncError) { e }
        }
        assertTrue(unknown is SyncError.Offline)
    }

    @Test fun plainHttpOutsideTailscaleIsRefusedBeforeAnyConnection() = runBlocking {
        val login = LoginRequest("k3y-abc", "dev-1", "Phone")
        val lan = HttpSyncApi(FakeDeviceSettingsRepository(DeviceSettings(serverUrl = "http://192.168.0.5:8095", refreshToken = "R")))
        assertEquals("insecure_url", try { lan.login("http://example.com", login); "" } catch (e: SyncError) { e.code })
        assertEquals("insecure_url", try { lan.sync(SyncRequest(0)); "" } catch (e: SyncError) { e.code })
        assertEquals("insecure_url", try { lan.attachmentExists("a.jpg"); "" } catch (e: SyncError) { e.code })
        assertTrue(seen.isEmpty())
    }

    @Test fun errorMessagesCarryNoBodyOrSecrets() = runBlocking {
        on("/v1/auth/login") { it.reply(400, """{"error":"bad","detail":"secret-pw"}""") }
        try {
            HttpSyncApi(device("")).login(url, login)
            fail()
        } catch (e: SyncError) {
            assertFalse(e.message!!.contains("secret"))
            assertFalse(e.message!!.contains("k3y-abc"))
        }
    }

    @Test fun attachmentHeadPutGet() = runBlocking {
        on("/v1/auth/login") { it.reply(200, tokens) }
        val name = "0123456789abcdef0123456789abcdef.jpg"
        val stored = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
        server.createContext("/v1/attachments/$name") { ex ->
            val data = ex.requestBody.readBytes()
            when (ex.requestMethod) {
                "HEAD" -> ex.reply(if (stored.get() == null) 404 else 200)
                "PUT" -> { stored.set(data); ex.reply(201) }
                else -> stored.get()?.let { b -> ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.write(b) } ?: ex.reply(404)
            }
            ex.close()
        }
        val api = HttpSyncApi(device())
        api.login(url, login)
        val into = folder.newFile("dl.jpg")
        assertFalse(api.attachmentExists(name))
        assertFalse(api.getAttachment(name, into))
        val bytes = ByteArray(200_000) { (it * 31).toByte() }
        api.putAttachment(name, folder.newFile("up.jpg").also { it.writeBytes(bytes) })
        assertArrayEquals(bytes, stored.get())
        assertTrue(api.attachmentExists(name))
        assertTrue(api.getAttachment(name, into))
        assertArrayEquals(bytes, into.readBytes())
    }

    @Test fun logoutPostsTheStoredRefreshTokenAndSwallowsErrors() = runBlocking {
        on("/v1/auth/login") { it.reply(200, tokens) }
        on("/v1/auth/logout") { it.reply(204) }
        val api = HttpSyncApi(device("R9"))
        api.login(url, login)
        api.logout()
        assertEquals("POST /v1/auth/logout Bearer A1", seen.last())
        assertTrue(String(bodies.last()).contains("R9"))
        server.stop(0)
        api.logout() // offline: no throw
    }
}
