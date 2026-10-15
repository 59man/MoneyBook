package com.moneybook.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JwtTest {
    private val secret = "unit-secret-0123456789-unit-secret-0123456789"
    private val user = UUID.randomUUID()

    @Test
    fun `token carries issuer, subject, device and a 15 minute expiry`() {
        val now = Instant.parse("2026-10-12T10:00:00Z")
        val jwt = JwtService(secret, clock = Clock.fixed(now, ZoneOffset.UTC))
        val d = JWT.decode(jwt.issue(user, "dev-1"))
        assertEquals("moneybook", d.issuer)
        assertEquals(user.toString(), d.subject)
        assertEquals("dev-1", d.getClaim("did").asString())
        assertEquals("HS256", d.algorithm)
        assertEquals(now.plusSeconds(900), d.expiresAtAsInstant)
        assertEquals(900L, JwtService.EXPIRES_IN)
    }

    @Test
    fun `verifier accepts its own token and rejects other secrets and expired ones`() {
        val jwt = JwtService(secret)
        jwt.verifier.verify(jwt.issue(user, "d"))
        assertFailsWith<Exception> { JwtService("other-secret-0123456789-other-secret-0123456789").let { jwt.verifier.verify(it.issue(user, "d")) } }
        assertFailsWith<Exception> { jwt.verifier.verify(JwtService(secret, ttl = Duration.ofMinutes(-1)).issue(user, "d")) }
        val noneIssuer = JWT.create().withIssuer("evil").withSubject(user.toString()).withClaim("did", "d")
            .withExpiresAt(Instant.now().plusSeconds(60)).sign(Algorithm.HMAC256(secret))
        assertFailsWith<Exception> { jwt.verifier.verify(noneIssuer) }
    }
}
