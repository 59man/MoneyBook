package com.moneybook.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigTest {
    private val secret = "s".repeat(32)
    private val password = "db-pass-xyz"
    private val env = mapOf(
        "MB_DB_URL" to "jdbc:postgresql://db/mb",
        "MB_DB_USER" to "mb",
        "MB_DB_PASSWORD" to password,
        "MB_JWT_SECRET" to secret,
    )
    private val withKey = env + ("MB_API_KEY" to "k".repeat(32))

    @Test
    fun `defaults for data dir and port`() {
        val c = Config.fromEnv(env)
        assertEquals("/data", c.dataDir)
        assertEquals(8080, c.port)
        assertEquals("jdbc:postgresql://db/mb", c.dbUrl)
        assertEquals("mb", c.dbUser)
    }

    @Test
    fun `data dir and port can be set`() {
        val c = Config.fromEnv(env + mapOf("MB_DATA" to "/x", "MB_PORT" to "9090"))
        assertEquals("/x", c.dataDir)
        assertEquals(9090, c.port)
    }

    @Test
    fun `each required variable is named when missing`() {
        for (name in env.keys) { // MB_API_KEY is optional now
            val e = assertFailsWith<IllegalStateException> { Config.fromEnv(env - name) }
            assertTrue(name in e.message!!, "message for $name: ${e.message}")
        }
    }

    @Test
    fun `bad port is refused`() {
        val e = assertFailsWith<IllegalStateException> { Config.fromEnv(env + ("MB_PORT" to "abc")) }
        assertTrue("MB_PORT" in e.message!!)
    }

    @Test
    fun `secret of 31 bytes is refused and 32 accepted`() {
        val e = assertFailsWith<IllegalStateException> { Config.fromEnv(env + ("MB_JWT_SECRET" to "s".repeat(31))) }
        assertTrue("MB_JWT_SECRET" in e.message!!)
        Config.fromEnv(env + ("MB_JWT_SECRET" to "s".repeat(32)))
    }

    @Test
    fun `secret length counts utf-8 bytes`() {
        // 16 two-byte characters are 32 bytes
        Config.fromEnv(env + ("MB_JWT_SECRET" to "é".repeat(16)))
        assertFailsWith<IllegalStateException> { Config.fromEnv(env + ("MB_JWT_SECRET" to "é".repeat(15))) }
    }

    @Test
    fun `api key of 31 characters is refused and 32 accepted`() {
        val e = assertFailsWith<IllegalStateException> { Config.fromEnv(env + ("MB_API_KEY" to "k".repeat(31))) }
        assertTrue("MB_API_KEY" in e.message!!)
        assertEquals("k".repeat(32), Config.fromEnv(env + ("MB_API_KEY" to "k".repeat(32))).apiKey)
    }

    @Test
    fun `api key may be missing or blank`() {
        assertNull(Config.fromEnv(env).apiKey)
        assertNull(Config.fromEnv(env + ("MB_API_KEY" to "  ")).apiKey)
    }

    @Test
    fun `trusted networks, allowed hosts and backup dir`() {
        val d = Config.fromEnv(env)
        assertEquals(Cidr.DEFAULTS.size, d.trustedNetworks.size)
        assertTrue(d.allowedHosts.isEmpty())
        assertEquals("/backups", d.backupDir)
        assertEquals(Cidr.DEFAULTS.size, Config.fromEnv(env + ("MB_TRUSTED_NETWORKS" to "")).trustedNetworks.size)
        val c = Config.fromEnv(env + mapOf("MB_TRUSTED_NETWORKS" to "10.0.0.0/8, 192.168.0.0/16", "MB_ALLOWED_HOSTS" to "A.lan, b.lan", "MB_BACKUP_DIR" to "/x"))
        assertEquals(2, c.trustedNetworks.size)
        assertTrue(c.trustedNetworks.any { it.contains(ipOrNull("192.168.1.1")!!) })
        assertFalse(c.trustedNetworks.any { it.contains(ipOrNull("100.100.1.1")!!) })
        assertEquals(setOf("a.lan", "b.lan"), c.allowedHosts)
        assertEquals("/x", c.backupDir)
        val e = assertFailsWith<IllegalStateException> { Config.fromEnv(env + ("MB_TRUSTED_NETWORKS" to "10.0.0.0/8,secret-entry")) }
        assertTrue("MB_TRUSTED_NETWORKS" in e.message!!)
        assertFalse("secret-entry" in e.message!!)
    }

    @Test
    fun `messages and toString never contain the secret or the password`() {
        val short = "short-secret-value"
        val e = assertFailsWith<IllegalStateException> { Config.fromEnv(env + ("MB_JWT_SECRET" to short)) }
        assertFalse(short in e.message!!)
        assertFalse(password in e.message!!)
        val text = Config.fromEnv(withKey).toString()
        assertFalse(secret in text)
        assertFalse("k".repeat(32) in text)
        assertFalse(password in text)
    }
}
