package com.moneybook.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.BackupRepository
import com.moneybook.data.DataStoreSettingsRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.SettingsSync
import com.moneybook.data.StyleMode
import com.moneybook.data.db.AttachmentEntity
import com.moneybook.data.db.RepeatRuleEntity
import com.moneybook.data.db.TxnEntity
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.Fixtures
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private val server = FakeSyncServer().apply {
        addKey("anna")
        addKey("ben")
    }
    private val devices = mutableListOf<SyncDevice>()
    private val cash = DefaultData.accountId(AccountGroupType.CASH)

    /** Ids order as "dev-a" < "dev-b" < "dev-c". */
    private fun device(id: String) = SyncDevice(server, id).also { devices += it }

    @After fun tearDown() = devices.forEach { it.close() }

    private fun entry(id: String, at: Long, note: String = "") =
        Fixtures.txn(id, TxnType.EXPENSE, "2026-01-10T12:00:00.000", cash, 100, note = note).copy(updatedAt = at)

    private suspend fun SyncDevice.ok() = assertEquals(SyncOutcome.Success, sync())
    private suspend fun SyncDevice.row(id: String): TxnEntity? = db.txnDao().get(id)
    private suspend fun SyncDevice.add(vararg rows: TxnEntity) = db.txnDao().upsertAll(rows.toList())
    private suspend fun SyncDevice.edit(id: String, at: Long, note: String) = db.txnDao().upsert(row(id)!!.copy(note = note, updatedAt = at))
    private fun serverNote(user: String, id: String) = server.rows(user).getValue("txn" to id).payload["note"]!!.jsonPrimitive.content
    private fun requestsOf(id: String) = server.requests.filter { it.first == id }.map { it.second }

    private fun photo(text: String): Pair<String, ByteArray> {
        val bytes = text.toByteArray()
        return MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) } + ".jpg" to bytes
    }

    private suspend fun SyncDevice.addPhoto(txnId: String, name: String, bytes: ByteArray?) {
        if (bytes != null) photos.dir.mkdirs().also { photos.file(name).writeBytes(bytes) }
        db.attachmentDao().upsertAll(listOf(AttachmentEntity(AttachmentEntity.idFor(txnId, name), txnId, name, 0, updatedAt = 1_000)))
    }

    @Test fun editAndDeleteOnAReachBAfterSync() = runBlocking {
        val a = device("dev-a")
        val b = device("dev-b")
        a.login("anna")
        b.login("anna")
        a.add(entry("t1", 1_000, "lunch"), entry("t2", 1_000))
        a.ok()
        b.ok()
        assertEquals("lunch", b.row("t1")!!.note)

        a.edit("t1", 2_000, "dinner")
        a.db.txnDao().upsert(a.row("t2")!!.copy(deleted = true, updatedAt = 2_000))
        a.settings.replace(a.settings.current().copy(monthStartDay = 7))
        a.ok()
        b.ok()
        assertEquals(a.db.txnDao().allRows().sortedBy { it.id }, b.db.txnDao().allRows().sortedBy { it.id })
        assertEquals("dinner", b.row("t1")!!.note)
        assertTrue(b.row("t2")!!.deleted)
        assertTrue(b.db.txnDao().dirtyRows().isEmpty() && a.db.txnDao().dirtyRows().isEmpty())
        assertEquals(7, b.settings.current().monthStartDay)
        assertEquals(a.settings.sync.value.copy(dirty = false), b.settings.sync.value)
        assertFalse(a.settings.sync.value.dirty)
    }

    @Test fun offlineEditsOfOneRowLaterUpdatedAtWinsOnBoth() = runBlocking {
        val a = device("dev-a")
        val b = device("dev-b")
        a.login("anna")
        b.login("anna")
        a.add(entry("t1", 1_000))
        a.ok()
        b.ok()

        // the later edit syncs last
        a.edit("t1", 2_000, "a")
        b.edit("t1", 3_000, "b")
        a.ok()
        b.ok()
        a.ok()
        assertEquals(listOf("b", "b", "b"), listOf(a.row("t1")!!.note, b.row("t1")!!.note, serverNote("anna", "t1")))

        // the later edit syncs first: the older push is rejected
        a.edit("t1", 5_000, "a2")
        b.edit("t1", 4_000, "b2")
        a.ok()
        b.ok()
        a.ok()
        assertEquals(listOf("a2", "a2", "a2"), listOf(a.row("t1")!!.note, b.row("t1")!!.note, serverNote("anna", "t1")))
        assertFalse(a.row("t1")!!.dirty || b.row("t1")!!.dirty)
    }

    @Test fun tieGoesToTheGreaterDeviceId() = runBlocking {
        val a = device("dev-a")
        val b = device("dev-b")
        a.login("anna")
        b.login("anna")
        a.add(entry("t1", 1_000))
        a.ok()
        b.ok()

        a.edit("t1", 5_000, "a")
        b.edit("t1", 5_000, "b")
        b.ok()
        a.ok() // rejected: (5000, dev-a) < (5000, dev-b)
        b.ok()
        assertEquals(listOf("b", "b", "b"), listOf(a.row("t1")!!.note, b.row("t1")!!.note, serverNote("anna", "t1")))

        a.edit("t1", 6_000, "a2")
        b.edit("t1", 6_000, "b2")
        a.ok()
        b.ok() // accepted over dev-a's
        a.ok()
        assertEquals(listOf("b2", "b2", "b2"), listOf(a.row("t1")!!.note, b.row("t1")!!.note, serverNote("anna", "t1")))
    }

    @Test fun rejectedPushReturnsTheWinnerAndOverwritesTheLocalRow() = runBlocking {
        val a = device("dev-a")
        val b = device("dev-b")
        a.login("anna")
        b.login("anna")
        a.add(entry("t1", 1_000), entry("t2", 1_000), entry("t3", 1_000))
        a.ok()
        a.edit("t1", 5_000, "a")
        a.ok() // t1 now has the newest version
        b.add(entry("t1", 4_000, "b")) // offline and older
        server.pageSize = 1
        b.ok()

        // the first page holds t2 only, so the winner t1 travels outside the page
        val first = server.responses.first { it.first == "dev-b" }.second
        assertEquals(listOf("t2", "t1"), first.changes.map { it.id })
        assertEquals("a", b.row("t1")!!.note)
        assertEquals(5_000, b.row("t1")!!.updatedAt)
        assertFalse(b.row("t1")!!.dirty)
        assertEquals("a", serverNote("anna", "t1"))
        assertEquals(setOf("t1", "t2", "t3"), b.db.txnDao().allRows().map { it.id }.toSet())
    }

    @Test fun freshDeviceKeepsAccountsRenamedDefaultCategory() = runBlocking {
        val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)
        val a = device("dev-a")
        a.seed()
        a.login("anna")
        a.db.categoryDao().upsert(a.db.categoryDao().get(food)!!.copy(name = "Groceries", updatedAt = 5_000))
        a.ok()

        val b = device("dev-b") // greater id: its seeds would win a tie
        b.seed()
        b.login("anna")
        b.ok()
        a.ok()
        assertEquals("Groceries", b.db.categoryDao().get(food)!!.name)
        assertEquals("Groceries", a.db.categoryDao().get(food)!!.name)
        assertFalse(b.db.categoryDao().get(food)!!.dirty)
    }

    @Test fun restoreOnAPropagatesIncludingTombstoneForRowBackupLacks() = runBlocking {
        val a = device("dev-a")
        val b = device("dev-b")
        a.seed()
        a.login("anna")
        a.add(entry("t1", 1_000), entry("t2", 1_000))
        val backup = BackupRepository(a.db, a.settings, a.time, "test", a.photos)
        val file = ByteArrayOutputStream().also { backup.backup(it) }.toByteArray()
        a.add(entry("t3", 1_000))
        a.ok()
        b.login("anna")
        b.ok()
        assertFalse(b.row("t3")!!.deleted)

        backup.restore(ByteArrayInputStream(file))
        a.ok()
        b.ok()
        assertTrue(b.row("t3")!!.deleted)
        assertEquals(setOf("t1", "t2"), b.db.txnDao().allRows().filter { !it.deleted }.map { it.id }.toSet())
        assertTrue(a.db.txnDao().dirtyRows().isEmpty())
    }

    @Test fun editDuringPushStaysDirtyAndGoesNextRun() = runBlocking {
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000, "first"))
        server.beforeReply = {
            server.beforeReply = null
            a.edit("t1", 2_000, "second")
        }
        a.ok()
        // the echo of "first" did not clean the newer edit, so the run's next round pushed it
        val pushes = requestsOf("dev-a").map { r -> r.changes.map { it.updatedAt } }
        assertEquals(listOf(listOf(1_000L), listOf(2_000L)), pushes)
        assertEquals("second", serverNote("anna", "t1"))
        assertEquals("second", a.row("t1")!!.note)
        assertFalse(a.row("t1")!!.dirty)
    }

    @Test fun lostResponseIsRepushedHarmlessly() = runBlocking {
        val a = device("dev-a")
        val b = device("dev-b")
        a.login("anna")
        a.add(entry("t1", 1_000))
        server.beforeReply = {
            server.beforeReply = null
            throw SyncError.Offline(null) // the server stored the push, the reply never came
        }
        assertTrue(a.sync() is SyncOutcome.Failure)
        assertTrue(a.row("t1")!!.dirty)
        a.ok() // the same change again: rejected as equal, the winner (itself) comes back
        assertFalse(a.row("t1")!!.dirty)
        assertEquals(1_000, a.row("t1")!!.updatedAt)
        b.login("anna")
        b.ok()
        assertEquals(1, b.db.txnDao().allRows().size)
    }

    @Test fun epochChangeRepushesEveryRow() = runBlocking {
        val a = device("dev-a")
        val b = device("dev-b")
        a.login("anna")
        b.login("anna")
        a.add(entry("t1", 1_000))
        a.ok()
        b.ok()
        val dump = server.snapshot()
        a.add(entry("t2", 1_000))
        a.ok()
        b.add(entry("t3", 1_000))
        b.ok()
        a.ok()
        val cursorBefore = a.device.current().syncCursor

        server.restore(dump, "e2") // the server forgot t2 and t3 and its versions went back
        a.ok()
        assertEquals(setOf("t1", "t2", "t3"), server.rows("anna").keys.map { it.second }.toSet())
        val after = requestsOf("dev-a").takeLast(2)
        assertEquals(cursorBefore, after[0].cursor) // the revealing request, discarded
        assertEquals(0, after[1].cursor) // the restarted run
        assertEquals(setOf("t1", "t2", "t3"), after[1].changes.map { it.id }.toSet())
        assertEquals("e2", a.device.current().syncEpoch)
        assertTrue(a.db.txnDao().dirtyRows().isEmpty())

        b.ok()
        assertEquals("e2", b.device.current().syncEpoch)
        assertEquals(setOf("t1", "t2", "t3"), b.db.txnDao().allRows().map { it.id }.toSet())
    }

    @Test fun epochFirstSightDoesNotReset() = runBlocking {
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000))
        a.ok()
        assertEquals("e1", a.device.current().syncEpoch)
        a.device.update { it.copy(syncEpoch = "") }
        val cursor = a.device.current().syncCursor
        val before = server.requests.size
        a.ok()
        assertEquals(before + 1, server.requests.size)
        assertEquals(cursor, server.requests.last().second.cursor)
        assertTrue(server.requests.last().second.changes.isEmpty())
        assertEquals("e1", a.device.current().syncEpoch)
    }

    @Test fun pagingWithPageSizeTwoLosesNothing() = runBlocking {
        server.pageSize = 2
        val a = device("dev-a")
        a.seed()
        a.login("anna")
        a.add(*(1..7).map { entry("t$it", 1_000L + it) }.toTypedArray())
        a.ok()
        val b = device("dev-b")
        b.login("anna")
        b.ok()
        assertEquals(a.db.txnDao().allRows().sortedBy { it.id }, b.db.txnDao().allRows().sortedBy { it.id })
        assertEquals(a.db.categoryDao().allRows().map { it.id }.toSet(), b.db.categoryDao().allRows().map { it.id }.toSet())
        assertEquals(a.db.accountDao().allAccountRows().map { it.id }.toSet(), b.db.accountDao().allAccountRows().map { it.id }.toSet())
        assertTrue(requestsOf("dev-b").size > 5)
        assertEquals(a.device.current().syncCursor, b.device.current().syncCursor)
    }

    @Test fun userIsolation() = runBlocking {
        val a = device("dev-a")
        val c = device("dev-c")
        a.login("anna")
        c.login("ben")
        a.add(entry("t1", 1_000))
        a.ok()
        c.add(entry("t9", 1_000))
        c.ok()
        a.ok()
        assertNull(c.row("t1"))
        assertNull(a.row("t9"))
        assertEquals(setOf("t9"), server.rows("ben").keys.map { it.second }.toSet())
    }

    @Test fun photoUploadedOnceThenBDownloadsIt() = runBlocking {
        val (name, bytes) = photo("receipt")
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000))
        a.addPhoto("t1", name, bytes)
        a.ok()
        assertEquals(1 to 1, server.heads to server.puts)
        a.ok()
        assertEquals(1 to 1, server.heads to server.puts)

        val b = device("dev-b")
        b.login("anna")
        b.ok()
        assertArrayEquals(bytes, b.photos.file(name).readBytes())
        assertEquals(listOf(name), b.photos.dir.list()!!.toList())
    }

    @Test fun photoAttachedDuringUploadStillGoesWithItsFile() = runBlocking {
        val (first, firstBytes) = photo("one")
        val (second, secondBytes) = photo("two")
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000))
        a.addPhoto("t1", first, firstBytes)
        server.beforeHead = {
            server.beforeHead = null
            a.addPhoto("t1", second, secondBytes)
        }
        a.ok()
        assertArrayEquals(firstBytes, server.file("anna", first))
        assertArrayEquals(secondBytes, server.file("anna", second))
        assertTrue(a.db.attachmentDao().allRows().none { it.dirty })
    }

    @Test fun missingDownloadIsRetriedNextRun() = runBlocking {
        val (name, bytes) = photo("receipt")
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000))
        a.addPhoto("t1", name, bytes)
        a.ok()
        val b = device("dev-b")
        b.login("anna")
        server.failGets = true
        b.ok()
        assertFalse(b.photos.file(name).exists())
        server.failGets = false
        b.ok()
        assertArrayEquals(bytes, b.photos.file(name).readBytes())
    }

    @Test fun missingLocalPhotoFileIsSkippedButRowSyncs() = runBlocking {
        val (name, _) = photo("gone")
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000))
        a.addPhoto("t1", name, null)
        a.ok()
        assertEquals(0 to 0, server.heads to server.puts)
        val b = device("dev-b")
        b.login("anna")
        b.ok() // the GET answers 404: skipped
        assertEquals(name, b.db.attachmentDao().forTxn("t1").single().fileName)
        assertFalse(b.photos.file(name).exists())
    }

    @Test fun corruptDownloadIsDropped() = runBlocking {
        val (name, bytes) = photo("receipt")
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000))
        a.addPhoto("t1", name, bytes)
        a.ok()
        server.plantFile("anna", name, "not the receipt".toByteArray())
        val b = device("dev-b")
        b.login("anna")
        b.ok()
        assertTrue(b.photos.dir.list().isNullOrEmpty()) // no file and no leftover part
    }

    @Test fun unauthorizedSignsOutAndKeepsData() = runBlocking {
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000))
        a.ok()
        a.add(entry("t2", 1_000))
        a.api.revoked = true
        val out = a.sync()
        assertSame(SyncError.Unauthorized, (out as SyncOutcome.Failure).error)
        val d = a.device.current()
        assertEquals("" to "session_expired", d.refreshToken to d.lastSyncError)
        assertFalse(a.row("t1")!!.dirty)
        assertTrue(a.row("t2")!!.dirty)
        a.api.revoked = false // the server would answer now, but a signed-out engine does not call it
        assertSame(SyncError.Unauthorized, (a.sync() as SyncOutcome.Failure).error)
        assertEquals(1, requestsOf("dev-a").size)
    }

    @Test fun offlineKeepsStateAndSetsError() = runBlocking {
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000))
        a.ok()
        val before = a.device.current()
        a.add(entry("t2", 1_000))
        server.offline = true
        assertTrue((a.sync() as SyncOutcome.Failure).error is SyncError.Offline)
        val d = a.device.current()
        assertEquals(before.copy(lastSyncError = "offline"), d)
        assertTrue(a.row("t2")!!.dirty)
        server.offline = false
        a.ok()
        assertEquals("", a.device.current().lastSyncError)
        assertFalse(a.row("t2")!!.dirty)
    }

    @Test fun failureMidwayResumesFromSavedCursor() = runBlocking {
        server.pageSize = 2
        val a = device("dev-a")
        a.login("anna")
        a.add(*(1..5).map { entry("t$it", 1_000L + it) }.toTypedArray())
        a.ok()
        val b = device("dev-b")
        b.login("anna")
        server.failSyncCall = server.requests.size + 2 // b's second page
        assertTrue((b.sync() as SyncOutcome.Failure).error is SyncError.Offline)
        val saved = b.device.current().syncCursor
        assertTrue(saved > 0)
        assertEquals(2, b.db.txnDao().allRows().size)
        b.ok()
        assertEquals(saved, requestsOf("dev-b")[2].cursor)
        assertEquals((1..5).map { "t$it" }.toSet(), b.db.txnDao().allRows().map { it.id }.toSet())
    }

    @Test fun clockAheadPushDoesNotRepushForever() = runBlocking {
        val a = device("dev-a")
        a.login("anna")
        val ahead = System.currentTimeMillis() + 3_600_000
        a.add(entry("t1", ahead))
        a.ok()
        assertTrue(server.rows("anna").getValue("txn" to "t1").updatedAt < ahead)
        assertFalse(a.row("t1")!!.dirty)
        a.ok()
        assertTrue(server.requests.last().second.changes.isEmpty())
    }

    @Test fun incompatibleIncomingRowFailsTheRunAndWritesNothing() = runBlocking {
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000), entry("t2", 1_000))
        a.ok()
        val good = server.rows("anna").getValue("txn" to "t1")
        val newer = JsonObject(good.payload + mapOf("id" to JsonPrimitive("t9"), "type" to JsonPrimitive("TELEPORT")))
        server.inject("anna", good.copy(id = "t9", payload = newer))

        val b = device("dev-b")
        b.login("anna")
        val error = (b.sync() as SyncOutcome.Failure).error as SyncError.Rejected
        assertEquals(400 to "incompatible", error.status to error.error)
        assertTrue(b.db.txnDao().allRows().isEmpty())
        assertEquals(0, b.device.current().syncCursor)
        assertEquals("rejected", b.device.current().lastSyncError)
    }

    @Test fun secondCallerWaitsAndRunsItsOwnPass() = runBlocking {
        val a = device("dev-a")
        a.login("anna")
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        server.beforeReply = {
            server.beforeReply = null
            started.complete(Unit)
            gate.await()
            throw SyncError.Server(503)
        }
        val first = async(Dispatchers.Default) { a.sync() }
        started.await()
        assertTrue(a.engine.running.value)
        val second = async(start = CoroutineStart.UNDISPATCHED) { a.sync() }
        gate.complete(Unit)
        assertEquals(SyncOutcome.Failure(SyncError.Server(503)).javaClass, first.await().javaClass)
        assertEquals(SyncOutcome.Success, second.await())
        assertEquals(2, server.requests.size)
        assertFalse(a.engine.running.value)
    }

    @Test fun editMadeDuringARunIsPushedByTheNextCall() = runBlocking {
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000))
        var at = 10_000L
        server.beforeReply = { a.edit("t1", ++at, "edit $at") } // every round, so the last edit is left for the next call
        a.ok()
        assertTrue(a.row("t1")!!.dirty)
        server.beforeReply = null
        a.ok()
        assertFalse(a.row("t1")!!.dirty)
        assertEquals("edit $at", serverNote("anna", "t1"))
    }

    @Test fun rejectedPhotoIsSkippedAndTheRestStillSync() = runBlocking {
        val (good, goodBytes) = photo("receipt")
        val bad = "0".repeat(32) + ".jpg" // its bytes don't hash to the name: the fake server answers 400 bad_hash
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000))
        a.addPhoto("t1", bad, "not matching".toByteArray())
        a.addPhoto("t1", good, goodBytes)
        a.ok()
        assertNull(server.file("anna", bad))
        assertArrayEquals(goodBytes, server.file("anna", good))
        assertEquals(2, server.rows("anna").keys.count { it.first == "attachment" })
        assertTrue(a.db.attachmentDao().forTxn("t1").isNotEmpty())
    }

    private suspend fun SyncDevice.addRule() = db.repeatRuleDao().upsert(
        RepeatRuleEntity(
            id = "r1", frequency = RepeatFrequency.MONTHLY, startDate = "2025-12-01", lastDate = "2025-12-01",
            type = TxnType.EXPENSE, accountId = cash, amount = 100, currency = "CZK", time = "12:00", updatedAt = 1_000,
        ),
    )

    @Test fun catchUpOnAStaleDeviceDoesNotOverwriteAnEditMadeElsewhere() = runBlocking {
        val a = device("dev-a")
        val b = SyncDevice(server, "dev-b", Clock.offset(SYNC_TEST_CLOCK, Duration.ofHours(1))).also { devices += it } // B's clock is later
        for (d in listOf(a, b)) { d.seed(); d.login("anna") }
        a.addRule()
        a.ok() // pushes the rule, then A adds the occurrence locally
        b.ok() // pulls the rule and adds its own copy of the occurrence, not pushed yet
        val id = "rep-r1-2026-01-01"
        assertEquals(100, b.row(id)!!.amount)
        a.db.txnDao().upsert(a.row(id)!!.copy(amount = 999, updatedAt = SYNC_TEST_CLOCK.millis() + 1_000))
        a.ok()
        b.ok()
        a.ok()
        assertEquals(999, b.row(id)!!.amount)
        assertEquals(999, a.row(id)!!.amount)
        assertEquals(JsonPrimitive(999), server.rows("anna").getValue("txn" to id).payload["amount"])
    }

    @Test fun catchUpOnAStaleDeviceDoesNotReviveADeletedRule() = runBlocking {
        val a = device("dev-a")
        val b = SyncDevice(server, "dev-b", Clock.offset(SYNC_TEST_CLOCK, Duration.ofHours(1))).also { devices += it } // B's clock is later
        for (d in listOf(a, b)) { d.seed(); d.login("anna") }
        a.addRule()
        a.ok()
        b.ok() // B has the rule and bumped its lastDate locally
        a.repeats.delete("r1")
        a.ok()
        b.ok()
        assertTrue(b.db.repeatRuleDao().get("r1")!!.deleted)
        assertEquals(JsonPrimitive(true), server.rows("anna").getValue("repeat_rule" to "r1").payload["deleted"])
    }

    @Test fun repeatsAreCaughtUpAfterAPull() = runBlocking {
        val a = device("dev-a")
        a.seed()
        a.login("anna")
        a.db.repeatRuleDao().upsert(
            RepeatRuleEntity(
                id = "r1", frequency = RepeatFrequency.MONTHLY, startDate = "2025-12-01", lastDate = "2025-12-01",
                type = TxnType.EXPENSE, accountId = cash, amount = 100, currency = "CZK", time = "12:00", updatedAt = 1_000,
            ),
        )
        a.ok()
        val b = device("dev-b")
        b.seed()
        b.login("anna")
        b.ok()
        assertEquals("r1", b.row("rep-r1-2026-01-01")!!.repeatRuleId)
    }

    @Test fun loopStopsAfterThreeIterations() = runBlocking {
        val a = device("dev-a")
        a.login("anna")
        a.add(entry("t1", 1_000))
        var at = 10_000L
        server.beforeReply = { a.edit("t1", ++at, "edit $at") }
        a.ok()
        assertEquals(3, server.requests.size)
        assertTrue(a.row("t1")!!.dirty)
    }

    @Test fun lastSyncAtAndErrorAreSaved() = runBlocking {
        val a = device("dev-a")
        a.login("anna")
        server.offline = true
        a.sync()
        assertEquals(0L to "offline", a.device.current().let { it.lastSyncAt to it.lastSyncError })
        server.offline = false
        a.ok()
        assertEquals(a.time.millis() to "", a.device.current().let { it.lastSyncAt to it.lastSyncError })
    }

    @Test fun hasDirtyFlowTracksTablesAndSettings() = runBlocking {
        val a = device("dev-a")
        a.login("anna")
        assertFalse(a.engine.hasDirty.first())
        a.add(entry("t1", 1_000))
        assertTrue(a.engine.hasDirty.first())
        a.ok()
        assertFalse(a.engine.hasDirty.first())
        a.settings.replace(a.settings.current().copy(monthStartDay = 3))
        assertTrue(a.engine.hasDirty.first())
        a.ok()
        assertFalse(a.engine.hasDirty.first())
        a.db.accountDao().upsertGroups(listOf(Fixtures.group()))
        assertTrue(a.engine.hasDirty.first())
    }

    @Test fun wipeLocalHardDeletesEverythingAndResetsSettings() = runBlocking {
        val (name, bytes) = photo("receipt")
        val a = device("dev-a")
        a.seed()
        a.login("anna")
        a.add(entry("t1", 1_000))
        a.addPhoto("t1", name, bytes)
        a.settings.replace(a.settings.current().copy(monthStartDay = 3, style = StyleMode.DARK))
        a.ok()

        a.engine.wipeLocal()
        val db = a.db
        assertEquals(
            listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            listOf(
                db.accountDao().allGroupRows().size, db.accountDao().allAccountRows().size, db.categoryDao().allRows().size,
                db.txnDao().allRows().size, db.memoDao().allRows().size, db.bookmarkDao().allRows().size,
                db.repeatRuleDao().allRows().size, db.currencyDao().allRows().size, db.budgetDao().allRows().size,
                db.attachmentDao().allRows().size,
            ),
        )
        assertTrue(a.photos.dir.list().isNullOrEmpty())
        assertEquals(SettingsSync(0, false), a.settings.sync.value)
        assertEquals(DataStoreSettingsRepository.defaultCurrency(), a.settings.current().mainCurrency)
        assertEquals(1, a.settings.current().monthStartDay)
        assertEquals(StyleMode.DARK, a.settings.current().style)
        assertEquals(0, a.device.current().syncCursor)
    }

    @Test fun settingsLastWriterWins() = runBlocking {
        val a = device("dev-a")
        val b = device("dev-b")
        a.login("anna")
        b.login("anna")
        a.settings.state.value = a.settings.current().copy(monthStartDay = 5, style = StyleMode.DARK)
        a.settings.sync.value = SettingsSync(2_000, true)
        b.settings.state.value = b.settings.current().copy(monthStartDay = 9)
        b.settings.sync.value = SettingsSync(3_000, true)
        a.ok()
        b.ok()
        a.ok()
        assertEquals(9 to 9, a.settings.current().monthStartDay to b.settings.current().monthStartDay)
        assertEquals(SettingsSync(3_000, false), a.settings.sync.value)
        assertEquals(SettingsSync(3_000, false), b.settings.sync.value)
        assertEquals(StyleMode.DARK, a.settings.current().style) // device-only
    }
}
