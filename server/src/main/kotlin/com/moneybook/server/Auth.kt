package com.moneybook.server

import com.moneybook.shared.sync.LoginRequest
import com.moneybook.shared.sync.RefreshRequest
import com.moneybook.shared.sync.TokenResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Name of the bearer provider: wrap a route in `authenticate(AUTH_PROVIDER)` and read the ids with [principalIds]. */
const val AUTH_PROVIDER = "auth"
private val LOGIN_LIMIT = RateLimitName("login")
private const val MAX_FIELD = 100
private const val AUTH_MAX_BODY_BYTES = 64L * 1024

/** The authenticated user and device. Routes take them only from here, never from the body, query or path. */
fun ApplicationCall.principalIds(): Pair<UUID, String> {
    val p = principal<JWTPrincipal>() ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized")
    return UUID.fromString(p.payload.subject) to p.payload.getClaim(JwtService.DEVICE_CLAIM).asString()
}

/** Startup, before serving: makes sure the owner exists and the effective key is stored; a changed key ends every session. */
fun prepareAuth(config: Config, ds: DataSource) {
    ds.inTransaction { c ->
        Users.ownerId(c)
        Users.resolveKey(c, config.apiKey)
    }
}

private fun parseUuid(s: String?) = runCatching { UUID.fromString(s) }.getOrNull()

fun Application.installAuth(config: Config, ds: DataSource) {
    val jwt = JwtService(config.jwtSecret)
    install(Authentication) {
        jwt(AUTH_PROVIDER) {
            verifier(jwt.verifier)
            validate { c ->
                val did = c.payload.getClaim(JwtService.DEVICE_CLAIM).asString()
                if (parseUuid(c.payload.subject) != null && !did.isNullOrEmpty()) JWTPrincipal(c.payload) else null
            }
            challenge { _, _ ->
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"moneybook\"")
                call.respond(HttpStatusCode.Unauthorized, errorBody("unauthorized"))
            }
        }
    }
    install(RateLimit) {
        register(LOGIN_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            // one bucket for all clients: a single-user home server, and a proxy would hide client addresses anyway
            requestKey { "global" }
        }
    }

    routing {
        route("/v1/auth") {
            // counts the bytes read, so a chunked body is cut off too; the typed receive below needs a body this small anyway
            install(RequestBodyLimit) { bodyLimit { AUTH_MAX_BODY_BYTES } }
            rateLimit(LOGIN_LIMIT) {
                post("/login") {
                    val req = call.receive<LoginRequest>()
                    // U+0000 cannot be stored in PostgreSQL text (device ids and names are written)
                    if (req.deviceId.length !in 1..MAX_FIELD || req.deviceName.length !in 1..MAX_FIELD ||
                        listOf(req.deviceId, req.deviceName).any { '\u0000' in it }
                    ) {
                        throw ApiException(HttpStatusCode.BadRequest, "bad_request")
                    }
                    val tokens = withContext(Dispatchers.IO) {
                        ds.inTransaction { c ->
                            // read on every login (a regenerated key applies at once); a missing key never matches.
                            // Fixed-length digests: constant time whatever the length of the given key
                            val key = Users.storedKey(c) ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_key")
                            if (!MessageDigest.isEqual(sha256(req.apiKey), sha256(key))) throw ApiException(HttpStatusCode.Unauthorized, "invalid_key")
                            val owner = Users.ownerId(c)
                            Users.upsertDevice(c, owner, req.deviceId, req.deviceName)
                            tokenPair(c, jwt, owner, req.deviceId)
                        }
                    }
                    call.respond(tokens)
                }
            }
            post("/refresh") {
                val req = call.receive<RefreshRequest>()
                val tokens = withContext(Dispatchers.IO) {
                    // commits even when it finds a reuse, so the revocation outlives the 401 below
                    ds.inTransaction { c ->
                        Users.rotateRefreshToken(c, req.refreshToken)?.let { (userId, deviceId) ->
                            Users.touchDevice(c, userId, deviceId)
                            tokenPair(c, jwt, userId, deviceId)
                        }
                    }
                } ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_token")
                call.respond(tokens)
            }
            authenticate(AUTH_PROVIDER) {
                post("/logout") {
                    val (userId, deviceId) = call.principalIds()
                    val req = call.receive<RefreshRequest>()
                    withContext(Dispatchers.IO) { ds.inTransaction { Users.revokeRefreshToken(it, userId, deviceId, req.refreshToken) } }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun tokenPair(c: java.sql.Connection, jwt: JwtService, userId: UUID, deviceId: String) =
    TokenResponse(jwt.issue(userId, deviceId), Users.issueRefreshToken(c, userId, deviceId), JwtService.EXPIRES_IN)
