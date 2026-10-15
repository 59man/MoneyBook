package com.moneybook.data.sync

import com.moneybook.shared.sync.LoginRequest
import com.moneybook.shared.sync.SYNC_MAX_CHANGES
import com.moneybook.shared.sync.SYNC_MAX_PAYLOAD_BYTES
import com.moneybook.shared.sync.SyncChange
import com.moneybook.shared.sync.SyncEntity
import com.moneybook.shared.sync.SyncRequest
import com.moneybook.shared.sync.SyncResponse
import com.moneybook.shared.sync.TokenResponse
import com.moneybook.shared.sync.overwriteEnvelope
import java.io.File
import java.security.MessageDigest

/**
 * The M3 server's sync rules in memory (server/src/main/kotlin/com/moneybook/server/Sync.kt): validation, the clamp,
 * LWW on `(updatedAt, deviceId)`, a global version counter, pages with `hasMore`, rejected winners that are not in the
 * page, the epoch, per-user stores and attachments.
 */
class FakeSyncServer(var pageSize: Int = SYNC_MAX_CHANGES, var epoch: String = "e1", var clock: () -> Long = System::currentTimeMillis) {
    class Rec(val change: SyncChange, val version: Long)

    /** A copy of the whole server, as `restore.sh` would bring back from a dump. */
    class Snapshot internal constructor(
        internal val stores: Map<String, Map<Pair<String, String>, Rec>>,
        internal val files: Map<String, Map<String, ByteArray>>,
        internal val version: Long,
    )

    private val keys = mutableSetOf<String>() // each API key has its own data, so tests can simulate two servers
    private val stores = mutableMapOf<String, MutableMap<Pair<String, String>, Rec>>()
    private val files = mutableMapOf<String, MutableMap<String, ByteArray>>()
    private var version = 0L

    /** Every handle throws [SyncError.Offline]. */
    var offline = false

    /** Attachment GETs answer 500 (a non-network error the engine skips). */
    var failGets = false

    /** The n-th sync call (1-based, counted over all devices) throws [SyncError.Offline] before the server sees it. */
    var failSyncCall = 0

    /** Called inside sync() after the pushes are stored, before the page is read: tests edit a device "during the push". */
    var beforeReply: (suspend () -> Unit)? = null

    /** Called inside a HEAD: tests attach a photo "during the upload". */
    var beforeHead: (suspend () -> Unit)? = null

    val requests = mutableListOf<Pair<String, SyncRequest>>() // device id, request
    val responses = mutableListOf<Pair<String, SyncResponse>>()
    var heads = 0
    var puts = 0

    fun addKey(name: String) {
        keys += name
        stores[name] = mutableMapOf()
        files[name] = mutableMapOf()
    }

    fun api(deviceId: String) = FakeApi(deviceId)

    /** The user's current rows, by entity and id. */
    fun rows(user: String): Map<Pair<String, String>, SyncChange> = stores.getValue(user).mapValues { it.value.change }

    fun file(user: String, name: String): ByteArray? = files.getValue(user)[name]

    /** Stores bytes under any name, skipping the MD5 check (a corrupt server file). */
    fun plantFile(user: String, name: String, bytes: ByteArray) {
        files.getValue(user)[name] = bytes
    }

    /** Stores a change as written by [deviceId] without validating it (a row from a newer app). */
    fun inject(user: String, change: SyncChange) {
        stores.getValue(user)[change.entity to change.id] = Rec(change, ++version)
    }

    fun snapshot() = Snapshot(stores.mapValues { it.value.toMap() }, files.mapValues { it.value.toMap() }, version)

    /** restore.sh: the dump's rows, files and sequence come back, with a new epoch. */
    fun restore(s: Snapshot, newEpoch: String) {
        stores.clear()
        s.stores.forEach { (u, m) -> stores[u] = m.toMutableMap() }
        files.clear()
        s.files.forEach { (u, m) -> files[u] = m.toMutableMap() }
        version = s.version
        epoch = newEpoch
    }

    suspend fun sync(user: String, device: String, req: SyncRequest): SyncResponse {
        requests += device to req
        if (failSyncCall == requests.size) throw SyncError.Offline(null)
        // validate(): any failure rejects the whole request before anything is stored
        if (req.cursor < 0 || req.changes.size > SYNC_MAX_CHANGES) throw SyncError.Rejected(400, "bad_request")
        for (c in req.changes) {
            if (c.payload.toString().encodeToByteArray().size > SYNC_MAX_PAYLOAD_BYTES) throw SyncError.Rejected(400, "bad_request")
            try {
                SyncEntity.validate(c)
            } catch (e: IllegalArgumentException) {
                throw SyncError.Rejected(400, "bad_request")
            }
        }
        val store = stores.getValue(user)
        val now = clock()
        val rejected = LinkedHashSet<Pair<String, String>>()
        for (c in req.changes) {
            val at = if (c.updatedAt > now + 5 * 60_000) now else c.updatedAt
            val key = c.entity to c.id
            val cur = store[key]?.change
            if (cur == null || compareValuesBy(cur, SyncChange(c.entity, c.id, at, c.deleted, device, c.payload), { it.updatedAt }, { it.deviceId }) < 0) {
                store[key] = Rec(c.copy(updatedAt = at, deviceId = device, payload = overwriteEnvelope(c.payload, at, c.deleted)), ++version)
            } else {
                rejected += key
            }
        }
        beforeReply?.invoke()
        val pulled = store.values.filter { it.version > req.cursor }.sortedBy { it.version }
        val page = pulled.take(pageSize)
        val inPage = page.map { it.change.entity to it.change.id }.toSet()
        val winners = (rejected - inPage).map { store.getValue(it) }.sortedBy { it.version }
        return SyncResponse((page + winners).map { it.change }, page.lastOrNull()?.version ?: req.cursor, pulled.size > page.size, epoch)
            .also { responses += device to it }
    }

    /** One device's view of the server: login binds the user; [revoked] answers every call with 401. */
    inner class FakeApi(val deviceId: String) : SyncApi {
        var user: String? = null
        var revoked = false

        private fun who(): String {
            if (offline) throw SyncError.Offline(null)
            if (revoked) throw SyncError.Unauthorized
            return user ?: throw SyncError.Unauthorized
        }

        override suspend fun login(serverUrl: String, request: LoginRequest): TokenResponse {
            if (offline) throw SyncError.Offline(null)
            if (request.apiKey !in keys) throw SyncError.Unauthorized
            user = request.apiKey
            return TokenResponse("access-$deviceId", "refresh-$deviceId", 900)
        }

        override suspend fun logout() {
            user = null
        }

        override suspend fun sync(request: SyncRequest): SyncResponse = this@FakeSyncServer.sync(who(), deviceId, request)

        override suspend fun attachmentExists(fileName: String): Boolean {
            val u = who()
            heads++
            beforeHead?.invoke()
            return fileName in files.getValue(u)
        }

        override suspend fun putAttachment(fileName: String, file: File) {
            val u = who()
            puts++
            val bytes = file.readBytes()
            val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
            if ("$md5.jpg" != fileName) throw SyncError.Rejected(400, "bad_hash")
            files.getValue(u)[fileName] = bytes
        }

        override suspend fun getAttachment(fileName: String, into: File): Boolean {
            val u = who()
            if (failGets) throw SyncError.Server(500)
            val bytes = files.getValue(u)[fileName] ?: return false
            into.writeBytes(bytes)
            return true
        }
    }
}
