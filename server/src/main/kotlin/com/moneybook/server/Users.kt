package com.moneybook.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import java.util.UUID

/** Blocking SQL helpers: call them inside `ds.inTransaction`. Every statement on per-user tables filters by user_id. */
object Users {
    const val REFRESH_DAYS = 90
    private val random = SecureRandom()

    /** The single owner: the oldest user (an older deployment keeps its user), or a new one named `owner`. */
    fun ownerId(c: Connection): UUID {
        fun find() = c.createStatement().use { st ->
            st.executeQuery("select id from app_user order by created_at, id limit 1").use { rs -> if (rs.next()) rs.getObject(1) as UUID else null }
        }
        find()?.let { return it }
        c.prepareStatement("insert into app_user (id, username) values (?, 'owner') on conflict (username) do nothing")
            .use { it.setObject(1, UUID.randomUUID()); it.executeUpdate() }
        return find()!!
    }

    /**
     * Remembers the key's hash. When a different one was stored (the key was changed), deletes every refresh token,
     * so each device has to log in with the new key. Access tokens expire on their own.
     */
    fun applyKey(c: Connection, keyHash: String) {
        val stored = c.createStatement().use { st ->
            st.executeQuery("select key_hash from server_meta").use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (stored == keyHash) return
        if (stored != null) c.createStatement().use { it.executeUpdate("delete from refresh_token") }
        c.prepareStatement("update server_meta set key_hash = ?").use { it.setString(1, keyHash); it.executeUpdate() }
    }

    fun upsertDevice(c: Connection, userId: UUID, deviceId: String, name: String) {
        c.prepareStatement(
            "insert into device (user_id, device_id, name) values (?, ?, ?) " +
                "on conflict (user_id, device_id) do update set name = excluded.name, last_seen = now()",
        ).use { it.setObject(1, userId); it.setString(2, deviceId); it.setString(3, name); it.executeUpdate() }
    }

    fun touchDevice(c: Connection, userId: UUID, deviceId: String) {
        c.prepareStatement("update device set last_seen = now() where user_id = ? and device_id = ?")
            .use { it.setObject(1, userId); it.setString(2, deviceId); it.executeUpdate() }
    }

    /** A new refresh token (32 random bytes, base64url); only its SHA-256 is stored. */
    fun issueRefreshToken(c: Connection, userId: UUID, deviceId: String): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
        c.prepareStatement(
            "insert into refresh_token (token_hash, user_id, device_id, expires_at) values (?, ?, ?, now() + make_interval(days => ?))",
        ).use {
            it.setBytes(1, sha256(token)); it.setObject(2, userId); it.setString(3, deviceId); it.setInt(4, REFRESH_DAYS)
            it.executeUpdate()
        }
        return token
    }

    /**
     * Atomically marks a live token used and returns its user and device, or null. When the token was already used
     * (reuse = possible theft) every live token of that user and device is revoked. The caller must commit before
     * answering, so the revocation survives the 401.
     */
    fun rotateRefreshToken(c: Connection, token: String): Pair<UUID, String>? {
        val hash = sha256(token)
        c.prepareStatement(
            "update refresh_token set used_at = now() where token_hash = ? and used_at is null and revoked_at is null " +
                "and expires_at > now() returning user_id, device_id",
        ).use {
            it.setBytes(1, hash)
            it.executeQuery().use { rs -> if (rs.next()) return (rs.getObject(1) as UUID) to rs.getString(2) }
        }
        c.prepareStatement("select user_id, device_id from refresh_token where token_hash = ? and used_at is not null").use {
            it.setBytes(1, hash)
            it.executeQuery().use { rs -> if (rs.next()) revokeLiveTokens(c, rs.getObject(1) as UUID, rs.getString(2)) }
        }
        return null
    }

    /** Revokes the token only when it belongs to this user and device; otherwise nothing happens. */
    fun revokeRefreshToken(c: Connection, userId: UUID, deviceId: String, token: String) {
        c.prepareStatement(
            "update refresh_token set revoked_at = now() where token_hash = ? and user_id = ? and device_id = ? and revoked_at is null",
        ).use { it.setBytes(1, sha256(token)); it.setObject(2, userId); it.setString(3, deviceId); it.executeUpdate() }
    }

    /** Ends every live session of one device (theft response). */
    fun revokeLiveTokens(c: Connection, userId: UUID, deviceId: String) {
        c.prepareStatement(
            "update refresh_token set revoked_at = now() where user_id = ? and device_id = ? and revoked_at is null and used_at is null",
        ).use { it.setObject(1, userId); it.setString(2, deviceId); it.executeUpdate() }
    }
}

internal fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
