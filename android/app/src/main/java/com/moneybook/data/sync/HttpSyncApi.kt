package com.moneybook.data.sync

import com.moneybook.BuildConfig
import com.moneybook.data.DeviceSettingsRepository
import com.moneybook.shared.sync.LoginRequest
import com.moneybook.shared.sync.RefreshRequest
import com.moneybook.shared.sync.SyncRequest
import com.moneybook.shared.sync.SyncResponse
import com.moneybook.shared.sync.TokenResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.HttpRetryException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException

/** [SyncApi] over HttpURLConnection. Refreshes the access token once on a 401 (under a mutex, Ruling 9). */
class HttpSyncApi(
    private val device: DeviceSettingsRepository,
    /** Fails closed by default; [com.moneybook.AppContainer] passes the real check. */
    private val vpn: VpnCheck = VpnCheck { false },
) : SyncApi {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val refreshLock = Mutex()

    @Volatile private var access = ""

    /** A non-accepted HTTP answer; internal only, mapped to [SyncError] at the edge. */
    private class Http(val status: Int, val code: String) : Exception("http $status")

    override suspend fun login(serverUrl: String, request: LoginRequest): TokenResponse = mapped {
        val tokens: TokenResponse = try {
            exchange(base(serverUrl), "POST", "/v1/auth/login", null, jsonBody(json.encodeToString(request))) { c, _ -> decode(c) }
        } catch (e: Http) {
            if (e.status == 401) throw SyncError.Unauthorized
            throw e
        }
        access = tokens.accessToken
        tokens
    }

    override suspend fun logout() {
        try {
            mapped {
                authed { token ->
                    val refresh = device.current().refreshToken
                    exchange(base(), "POST", "/v1/auth/logout", token, jsonBody(json.encodeToString(RefreshRequest(refresh)))) { _, _ -> }
                }
            }
        } catch (_: SyncError) {
            // best effort: the server forgets the device when the refresh token expires
        }
        access = ""
    }

    override suspend fun sync(request: SyncRequest): SyncResponse = mapped {
        authed { token ->
            exchange(base(), "POST", "/v1/sync", token, jsonBody(json.encodeToString(request))) { c, _ -> decode<SyncResponse>(c) }
        }
    }

    override suspend fun attachmentExists(fileName: String): Boolean = mapped {
        authed { token -> exchange(base(), "HEAD", "/v1/attachments/$fileName", token, null, setOf(200, 404)) { _, status -> status == 200 } }
    }

    override suspend fun putAttachment(fileName: String, file: File) = mapped {
        authed { token ->
            exchange(base(), "PUT", "/v1/attachments/$fileName", token, Body("image/jpeg", file.length()) { out -> file.inputStream().use { it.copyTo(out) } }, setOf(201, 204)) { _, _ -> }
        }
    }

    override suspend fun getAttachment(fileName: String, into: File): Boolean = mapped {
        authed { token ->
            exchange(base(), "GET", "/v1/attachments/$fileName", token, null, setOf(200, 404)) { c, status ->
                if (status == 404) {
                    false
                } else {
                    try {
                        c.inputStream.use { input -> into.outputStream().use { input.copyTo(it) } }
                    } catch (e: IOException) {
                        into.delete()
                        throw e
                    }
                    true
                }
            }
        }
    }

    // ---- plumbing ----

    private suspend fun base(url: String? = null): String {
        val result = (url ?: device.current().serverUrl).trim().trimEnd('/')
        if (!ServerUrl.isAllowed(result, BuildConfig.DEBUG)) throw SyncError.InsecureUrl
        if (ServerUrl.needsVpn(result) && !vpn.routesThroughTailnet(URI(result).host.orEmpty())) throw SyncError.VpnOff
        return result
    }

    private class Body(val type: String, val length: Long, val write: (java.io.OutputStream) -> Unit)

    private fun jsonBody(text: String): Body {
        val bytes = text.toByteArray()
        return Body("application/json", bytes.size.toLong()) { it.write(bytes) }
    }

    private inline fun <reified T> decode(c: HttpURLConnection): T = json.decodeFromString(c.inputStream.use { it.readBytes() }.decodeToString())

    /** Runs [block] on IO and maps what it throws to [SyncError]. */
    private suspend fun <T> mapped(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: SyncError) {
            throw e
        } catch (e: Http) {
            throw if (e.status >= 500) SyncError.Server(e.status) else SyncError.Rejected(e.status, e.code)
        } catch (e: ConnectException) {
            throw SyncError.Offline(e)
        } catch (e: SocketTimeoutException) {
            throw SyncError.Offline(e)
        } catch (e: UnknownHostException) {
            throw SyncError.Offline(e)
        } catch (e: NoRouteToHostException) {
            throw SyncError.Offline(e)
        } catch (e: IOException) {
            throw SyncError.Other(e)
        } catch (e: SerializationException) {
            throw SyncError.Other(e)
        } catch (e: IllegalArgumentException) { // malformed server url
            throw SyncError.Other(e)
        }
    }

    /** Runs [call] with the access token; on a 401 refreshes once and retries once. */
    private suspend fun <T> authed(call: suspend (token: String) -> T): T {
        var token = access
        if (token.isEmpty()) token = refresh(token)
        try {
            return call(token)
        } catch (e: Http) {
            if (e.status != 401) throw e
        }
        token = refresh(token)
        try {
            return call(token)
        } catch (e: Http) {
            if (e.status != 401) throw e
            signOut()
            throw SyncError.Unauthorized
        }
    }

    /** Replaces [stale] with a fresh access token, unless another caller already did. */
    private suspend fun refresh(stale: String): String = refreshLock.withLock {
        if (access.isNotEmpty() && access != stale) return access
        val stored = device.current().refreshToken
        if (stored.isEmpty()) throw SyncError.Unauthorized
        val tokens: TokenResponse = try {
            exchange(base(), "POST", "/v1/auth/refresh", null, jsonBody(json.encodeToString(RefreshRequest(stored)))) { c, _ -> decode(c) }
        } catch (e: Http) {
            if (e.status == 401) {
                signOut()
                throw SyncError.Unauthorized
            }
            throw e
        }
        // saved before anyone uses the new access token: the old refresh token is spent
        device.update { it.copy(refreshToken = tokens.refreshToken) }
        access = tokens.accessToken
        access
    }

    private suspend fun signOut() {
        access = ""
        device.update { it.copy(refreshToken = "", lastSyncError = "session_expired") }
    }

    /** One request. Statuses in [ok] (default any 2xx) go to [onOk]; anything else throws [Http]. */
    private fun <T> exchange(
        base: String, method: String, path: String, bearer: String?, body: Body?,
        ok: Set<Int>? = null, onOk: (HttpURLConnection, Int) -> T,
    ): T {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("Accept", "application/json")
            if (!bearer.isNullOrEmpty()) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", body.type)
                c.setFixedLengthStreamingMode(body.length)
            }
            val status = try {
                body?.let { b -> c.outputStream.use(b.write) }
                c.responseCode
            } catch (e: HttpRetryException) {
                e.responseCode() // a 401 to a streamed body
            }
            if (if (ok == null) status in 200..299 else status in ok) return onOk(c, status)
            throw Http(status, errorCode(c))
        } finally {
            c.disconnect()
        }
    }

    private fun errorCode(c: HttpURLConnection): String = try {
        val text = c.errorStream?.use { it.readNBytes(64 * 1024) }?.decodeToString().orEmpty()
        json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content.orEmpty()
    } catch (_: Exception) {
        ""
    }
}
