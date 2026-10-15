package com.moneybook.server

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Issues and verifies access tokens (HS256). [ttl] and [clock] are injectable so tests can mint an expired token. */
class JwtService(secret: String, private val ttl: Duration = Duration.ofSeconds(EXPIRES_IN), private val clock: Clock = Clock.systemUTC()) {
    private val algorithm = Algorithm.HMAC256(secret)

    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(ISSUER).build()

    fun issue(userId: UUID, deviceId: String): String = JWT.create()
        .withIssuer(ISSUER)
        .withSubject(userId.toString())
        .withClaim(DEVICE_CLAIM, deviceId)
        .withExpiresAt(clock.instant().plus(ttl))
        .sign(algorithm)

    companion object {
        const val ISSUER = "moneybook"
        const val DEVICE_CLAIM = "did"
        const val EXPIRES_IN = 900L // seconds
    }
}
