package com.moneybook.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessRulesTest {
    private val rules = AccessRules(Cidr.DEFAULTS, setOf("moneybook.lan"))

    private fun check(
        peer: String?, host: String? = "100.101.102.103:8095", method: String = "GET", path: String = "/",
        origin: String? = null, sfs: String? = null,
    ) = rules.check(peer, host, method, path, origin, sfs)

    @Test
    fun `trusted peers are served`() {
        for (p in listOf("100.64.0.1", "100.127.255.254", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:100.100.1.1")) {
            assertNull(check(p), p)
        }
    }

    @Test
    fun `other peers are refused`() {
        for (p in listOf("192.168.0.5", "100.63.255.255", "100.128.0.0", "10.0.0.1", "::ffff:192.168.1.5", "fe80::1", "", null, "localhost", "not-an-ip", "999.1.1.1")) {
            assertEquals("forbidden_network", check(p), p.toString())
        }
    }

    @Test
    fun `health skips every rule`() = assertNull(check("192.168.0.5", host = "evil.example", path = "/health"))

    @Test
    fun `host rule`() {
        for (h in listOf("100.101.102.103", "100.101.102.103:8095", "localhost", "LOCALHOST:8080", "[::1]:8080", "moneybook.lan:8095", "MoneyBook.LAN")) {
            assertNull(check("127.0.0.1", host = h), h)
        }
        for (h in listOf("evil.example", "evil.example:8095", null, "", "100.101.102.103.evil.example")) {
            assertEquals("forbidden_host", check("127.0.0.1", host = h), h.toString())
        }
    }

    @Test
    fun `csrf rule on admin posts`() {
        fun post(origin: String?, sfs: String? = null, path: String = "/admin/backup", method: String = "POST") =
            check("127.0.0.1", method = method, path = path, origin = origin, sfs = sfs)
        assertNull(post("http://100.101.102.103:8095"))
        assertNull(post("HTTP://100.101.102.103:8095"))
        assertEquals("forbidden_origin", post("http://evil.example"))
        assertEquals("forbidden_origin", post("https://100.101.102.103:8095"))
        assertEquals("forbidden_origin", post("http://100.101.102.103"))
        assertNull(post(null, sfs = "same-origin"))
        assertEquals("forbidden_origin", post(null, sfs = "cross-site"))
        assertEquals("forbidden_origin", post(null))
        assertNull(post(null, path = "/admin/status", method = "GET"))
        assertNull(post(null, path = "/v1/sync"))
        // routing ignores empty segments: a doubled slash must not skip the rule
        assertEquals("forbidden_origin", post("http://evil.example", path = "//admin/key/regenerate"))
        assertEquals("forbidden_origin", post(null, path = "//admin//backup"))
        // any unsafe method, not only POST; a foreign Origin is refused even on /v1/
        assertEquals("forbidden_origin", post("http://evil.example", path = "/admin/backup", method = "DELETE"))
        assertEquals("forbidden_origin", post("http://evil.example", path = "/v1/sync"))
    }

    @Test
    fun `only an exact GET health skips the rules`() {
        assertEquals("forbidden_network", check("192.168.0.5", path = "//health/../admin/status"))
        assertEquals("forbidden_network", check("192.168.0.5", path = "/health", method = "POST"))
        assertNull(check("192.168.0.5", host = "evil.example", path = "//health"))
    }

    @Test
    fun `cidr parsing and matching`() {
        val c = Cidr.parse("100.64.0.0/10")
        assertTrue(c.contains(ipOrNull("100.100.1.1")!!))
        assertFalse(c.contains(ipOrNull("100.128.0.1")!!))
        assertFalse(c.contains(ipOrNull("::1")!!))
        assertTrue(Cidr.parse("0.0.0.0/0").contains(ipOrNull("8.8.8.8")!!))
        assertFalse(Cidr.parse("0.0.0.0/0").contains(ipOrNull("::1")!!))
        assertTrue(Cidr.parse("::/0").contains(ipOrNull("fe80::1")!!))
        for (bad in listOf("bad", "1.2.3.4/33", "::1/129", "1.2.3.4")) {
            assertFailsWith<IllegalArgumentException>(bad) { Cidr.parse(bad) }
        }
    }
}
