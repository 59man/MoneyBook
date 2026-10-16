package com.moneybook.data.sync

import androidx.room.withTransaction
import com.moneybook.data.DataStoreSettingsRepository
import com.moneybook.data.DeviceSettingsRepository
import com.moneybook.data.PhotoStore
import com.moneybook.data.RepeatRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.StyleMode
import com.moneybook.data.TimeSource
import com.moneybook.data.backup.toAppSettings
import com.moneybook.data.backup.toDto
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.dto.SettingsDto
import com.moneybook.shared.sync.SETTINGS_ID
import com.moneybook.shared.sync.SYNC_MAX_CHANGES
import com.moneybook.shared.sync.SYNC_MAX_PAYLOAD_BYTES
import com.moneybook.shared.sync.SyncChange
import com.moneybook.shared.sync.SyncEntity
import com.moneybook.shared.sync.SyncRequest
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface SyncOutcome {
    data object Success : SyncOutcome
    data class Failure(val error: SyncError) : SyncOutcome
}

/** Rounds of push and pull in one run while edits keep arriving during it; later edits wait for the next run. */
private const val MAX_ROUNDS = 3

/** Payload bytes per request: half the server's 16 MiB body cap, leaving room for the envelopes. */
private const val MAX_BATCH_BYTES = 8 * 1024 * 1024

private val SETTINGS = SyncEntity.SETTINGS.wire
private val ATTACHMENT = SyncEntity.ATTACHMENT.wire

/** The server's epoch changed (a restore): the run is restarted from cursor 0 with every row dirty. */
private class EpochChanged : Exception("epoch changed")

/**
 * One sync run (spec "Sync engine"): upload photos, push every dirty row and the settings, pull until no more,
 * apply each response in one transaction, download missing photos, catch up repeats. Last write wins on
 * `(updatedAt, deviceId)`, as on the server. Runs never overlap (a process-wide mutex).
 */
class SyncEngine(
    private val db: MoneyBookDatabase,
    private val settings: SettingsRepository,
    private val device: DeviceSettingsRepository,
    private val api: SyncApi,
    private val photos: PhotoStore,
    private val repeats: RepeatRepository,
    private val time: TimeSource,
) {
    private val tables = syncTables(db).associateBy { it.entity.wire }
    private val mutex = Mutex()

    private val runningState = MutableStateFlow(false)

    val running: StateFlow<Boolean> = runningState.asStateFlow()

    /** Any table or the settings has something to push. */
    val hasDirty: Flow<Boolean> =
        combine(tables.values.map { it.dirtyCount } + settings.syncState.map { if (it.dirty) 1 else 0 }) { counts -> counts.any { it > 0 } }
            .distinctUntilChanged()

    /**
     * Never throws (cancellation excepted). A caller that arrives while a run is active waits for it and then runs its
     * own pass, so an edit made during the first run's tail is not left for the periodic run.
     */
    suspend fun sync(): SyncOutcome {
        return mutex.withLock {
            runningState.value = true
            try {
                withContext(Dispatchers.IO) { run() }
            } finally {
                runningState.value = false
            }
        }
    }

    /** Asks for the first page from cursor 0 without applying it: true when the account already has data. Throws [SyncError]. */
    suspend fun probeServer(): Boolean = api.sync(SyncRequest(0)).changes.isNotEmpty()

    /** Runs [block] with no sync run active (the same mutex): login and logout change tokens and cursor under it. [block] must not call [sync]. */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Every row and the settings (when ever changed here) go again: after a login, and on an epoch change. */
    suspend fun markAllDirty() = mutex.withLock { markAllDirtyUnlocked() }

    /** Replace: hard-deletes every row (the only hard delete), all photo files, and resets the settings and the cursor. */
    suspend fun wipeLocal() = mutex.withLock {
        db.withTransaction { tables.values.forEach { it.wipe() } }
        withContext(Dispatchers.IO) { photos.sweep(emptySet(), now = Long.MAX_VALUE) }
        settings.resetForReplace(DataStoreSettingsRepository.defaultCurrency())
        device.update { it.copy(syncCursor = 0) }
    }

    /** For use inside [exclusive] only. */
    internal suspend fun markAllDirtyUnlocked() {
        db.withTransaction { tables.values.forEach { it.markAllDirty() } }
        settings.markDirty()
    }

    private suspend fun run(): SyncOutcome {
        if (!device.current().signedIn) return SyncOutcome.Failure(SyncError.Unauthorized)
        val error = try {
            val me = device.deviceId()
            try {
                pass(me)
            } catch (_: EpochChanged) {
                pass(me) // once; a second change in the same run fails it as Other
            }
            downloadPhotos()
            repeats.catchUp(time.today())
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncError) {
            e
        } catch (e: Exception) {
            SyncError.Other(e)
        }
        if (error == null) {
            device.update { it.copy(lastSyncAt = time.millis(), lastSyncError = "") }
            return SyncOutcome.Success
        }
        // A failure keeps everything committed so far; the next run carries on from the saved cursor.
        device.update { if (error === SyncError.Unauthorized) it.copy(refreshToken = "", lastSyncError = error.code) else it.copy(lastSyncError = error.code) }
        return SyncOutcome.Failure(error)
    }

    private suspend fun pass(me: String) {
        for (round in 0 until MAX_ROUNDS) {
            val changes = tables.values.flatMap { it.dirty() } + listOfNotNull(dirtySettings())
            if (round > 0 && changes.isEmpty()) return
            val requests = batches(changes)
            uploadPhotos(changes)
            var more = false
            for (batch in requests) more = exchange(me, batch) // always one request, so an empty run still pulls
            while (more) more = exchange(me, emptyList())
        }
    }

    /** Requests of at most [SYNC_MAX_CHANGES] changes and [MAX_BATCH_BYTES]; at least one, possibly empty. */
    private fun batches(changes: List<SyncChange>): List<List<SyncChange>> {
        val out = mutableListOf(mutableListOf<SyncChange>())
        var bytes = 0
        for (c in changes) {
            // a bug shows here, not as a server 400 for the whole batch on every retry
            SyncEntity.validate(c)
            val size = c.payload.toString().encodeToByteArray().size
            if (size > SYNC_MAX_PAYLOAD_BYTES) throw SyncError.Rejected(400, "payload_too_large")
            if (out.last().size == SYNC_MAX_CHANGES || (out.last().isNotEmpty() && bytes + size > MAX_BATCH_BYTES)) {
                out += mutableListOf<SyncChange>()
                bytes = 0
            }
            out.last() += c
            bytes += size
        }
        return out
    }

    private suspend fun dirtySettings(): SyncChange? {
        // The stamp is read before the values: an edit in between leaves a newer stamp, so this push can't clean it.
        val state = settings.syncState.first()
        if (!state.dirty) return null
        val payload = SyncJson.encodeToJsonElement(SettingsDto.serializer(), settings.current().toDto()).jsonObject
        return SyncChange(SETTINGS, SETTINGS_ID, state.updatedAt, false, payload = payload)
    }

    /** One request: push [pushed], apply the response, clear what the server answered for, save the cursor. Returns hasMore. */
    private suspend fun exchange(me: String, pushed: List<SyncChange>): Boolean {
        val state = device.current()
        val res = api.sync(SyncRequest(state.syncCursor, pushed))
        if (state.syncEpoch.isNotEmpty() && res.epoch != state.syncEpoch) {
            // The page was computed for a cursor of the old data: discarded. Marked before the new epoch is stored,
            // so a crash in between repeats the reset instead of skipping it.
            markAllDirtyUnlocked()
            device.update { it.copy(syncCursor = 0, syncEpoch = res.epoch) }
            throw EpochChanged()
        }
        val writes: List<suspend () -> Unit>
        val incomingSettings: List<Pair<SyncChange, SettingsDto>>
        try {
            res.changes.forEach { SyncEntity.validate(it) }
            val (settingsChanges, rows) = res.changes.partition { it.entity == SETTINGS }
            writes = rows.groupBy { it.entity }.map { (entity, changes) -> tables.getValue(entity).prepare(changes, me) }
            incomingSettings = settingsChanges.map { it to SyncJson.decodeFromJsonElement(SettingsDto.serializer(), it.payload) }
        } catch (e: IllegalArgumentException) { // a row from a newer app: nothing of this page is written
            throw SyncError.Rejected(400, "incompatible")
        }
        db.withTransaction {
            writes.forEach { it() }
            // After applying, so a returned winner lands first; only the exact pushed stamp is cleaned, so an edit made
            // during the request stays dirty, and a clamped echo (older than the row) can't keep it dirty (Ruling 7).
            pushed.forEach { c -> if (c.entity != SETTINGS) tables.getValue(c.entity).clearDirty(c.id, c.updatedAt) }
        }
        for ((c, dto) in incomingSettings) {
            // ponytail: read-then-write is not atomic in DataStore; a settings edit landing between the two is overwritten.
            // Add a conditional applyRemote to SettingsRepository if that ever shows up.
            val local = settings.syncState.first()
            if (incomingWins(local.updatedAt, local.dirty, me, c)) settings.applyRemote(dto.toAppSettings(StyleMode.SYSTEM), c.updatedAt)
        }
        pushed.firstOrNull { it.entity == SETTINGS }?.let { settings.clearDirty(it.updatedAt) }
        device.update { it.copy(syncCursor = res.newCursor, syncEpoch = res.epoch) }
        return res.hasMore
    }

    /**
     * Before pushing: the server must hold a photo before a row naming it arrives. Taken from the rows about to be pushed,
     * not from the table, so a photo attached after the upload can't go out without its file. A rejected file is skipped;
     * Offline, VpnOff, Unauthorized and Server errors abort the run (Ruling 11).
     */
    private suspend fun uploadPhotos(changes: List<SyncChange>) {
        val names = changes.filter { it.entity == ATTACHMENT && !it.deleted }.map { it.payload.getValue("fileName").jsonPrimitive.content }
        for (name in names.distinct()) {
            val file = photos.file(name)
            if (!file.exists()) continue // a missing local file is skipped
            try {
                if (!api.attachmentExists(name)) api.putAttachment(name, file)
            } catch (_: SyncError.Rejected) {
                // the server will never take this file (too large, bad name): skip it, the row still pushes
            }
        }
    }

    /** Live photos this phone lacks. A 404, a bad MD5 or a non-network error is skipped and tried again next run. */
    private suspend fun downloadPhotos() {
        for (name in db.attachmentDao().liveFileNames()) {
            val target = photos.file(name)
            if (target.exists()) continue
            photos.dir.mkdirs()
            val part = File(photos.dir, "$name.part")
            try {
                if (api.getAttachment(name, part) && md5Name(part) == name) part.renameTo(target)
            } catch (e: Exception) {
                if (e is CancellationException || e is SyncError.Offline || e === SyncError.VpnOff || e === SyncError.Unauthorized) throw e
            } finally {
                part.delete()
            }
        }
    }

    private fun md5Name(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.forEachBlock { buffer, n -> md.update(buffer, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) } + ".jpg"
    }
}
