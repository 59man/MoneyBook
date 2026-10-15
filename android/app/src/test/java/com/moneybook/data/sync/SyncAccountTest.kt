package com.moneybook.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.Seeder
import com.moneybook.data.db.MemoEntity
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.data.DefaultData
import com.moneybook.testutil.Fixtures
import com.moneybook.shared.sync.SyncRequest
import com.moneybook.shared.sync.SyncResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncAccountTest {
    private val server = FakeSyncServer().apply {
        addKey("anna")
        addKey("ben")
    }
    private val devices = mutableListOf<SyncDevice>()
    private val cash = DefaultData.accountId(AccountGroupType.CASH)

    private fun device(id: String) = SyncDevice(server, id).also { devices += it }

    @After fun tearDown() = devices.forEach { it.close() }

    private fun SyncDevice.account(api: SyncApi = this.api) =
        SyncAccount(device, settings, db, engine, api, Seeder(db, time), "Test phone")

    private fun entry(id: String, at: Long = 1_000, note: String = "") =
        Fixtures.txn(id, TxnType.EXPENSE, "2026-01-10T12:00:00.000", cash, 100, note = note).copy(updatedAt = at)

    private suspend fun SyncDevice.add(vararg rows: com.moneybook.data.db.TxnEntity) = db.txnDao().upsertAll(rows.toList())
    private suspend fun SyncDevice.ids() = db.txnDao().allRows().filter { !it.deleted }.map { it.id }.sorted()
    private fun serverTxnIds(user: String) = server.rows(user).keys.filter { it.first == "txn" }.map { it.second }.sorted()

    /** Device "dev-a" has put two entries into anna's account. */
    private suspend fun annaWithData(): SyncDevice {
        val a = device("dev-a")
        a.seed()
        a.login("anna")
        a.add(entry("a1", note = "from a"), entry("a2"))
        assertEquals(SyncOutcome.Success, a.sync())
        return a
    }

    @Test fun normalizeUrlCases() {
        assertEquals("https://host.ts.net", SyncAccount.normalizeUrl("host.ts.net/"))
        assertEquals("http://10.0.2.2:8095", SyncAccount.normalizeUrl("http://10.0.2.2:8095//"))
        assertEquals("https://host.ts.net", SyncAccount.normalizeUrl("  host.ts.net  "))
        assertEquals("", SyncAccount.normalizeUrl("  "))
    }

    @Test fun loginToEmptyServerMergesWithoutAsking() = runBlocking {
        val c = device("dev-c")
        c.seed()
        c.add(entry("c1"))
        val acc = c.account()
        assertEquals(LoginResult.Synced, acc.login(" host.ts.net/ ", "anna"))
        val s = c.device.current()
        assertTrue(s.signedIn)
        assertEquals("https://host.ts.net", s.serverUrl)
        assertTrue(s.lastSyncAt > 0)
        assertEquals(listOf("c1"), serverTxnIds("anna"))
        assertFalse(acc.pending.value)
    }

    @Test fun deviceWithoutEntriesMergesWithoutAskingEvenIfServerHasData() = runBlocking {
        annaWithData()
        val c = device("dev-c")
        c.seed()
        assertEquals(LoginResult.Synced, c.account().login("h", "anna"))
        assertEquals(listOf("a1", "a2"), c.ids())
    }

    @Test fun deviceWithEntriesAndServerDataNeedsChoice() = runBlocking {
        annaWithData()
        val before = server.rows("anna")
        val c = device("dev-c")
        c.seed()
        c.add(entry("c1"))
        val acc = c.account()
        assertEquals(LoginResult.NeedsChoice, acc.login("h", "anna"))
        assertTrue(acc.pending.value)
        assertEquals(before, server.rows("anna"))
        assertEquals(listOf("c1"), c.ids()) // nothing pulled either
        assertTrue(server.requests.filter { it.first == "dev-c" }.all { it.second.changes.isEmpty() }) // only the probe
    }

    @Test fun sameServerAndUserAgainMergesWithoutAsking() = runBlocking {
        annaWithData()
        val c = device("dev-c")
        c.seed()
        c.add(entry("c1"))
        val acc = c.account()
        assertEquals(LoginResult.NeedsChoice, acc.login("h", "anna"))
        acc.merge()
        acc.logout()
        c.add(entry("c2"))
        assertEquals(LoginResult.Synced, acc.login("h/", "anna")) // same account after a logout or a server restore
        assertEquals(listOf("a1", "a2", "c1", "c2"), serverTxnIds("anna"))
        acc.logout()
        assertEquals(LoginResult.NeedsChoice, acc.login("other-host", "anna")) // another server still asks
    }

    @Test fun cancelledLoginIsNotTakenForTheSameAccountNextTime() = runBlocking {
        annaWithData()
        val c = device("dev-c")
        c.seed()
        c.add(entry("c1"))
        val acc = c.account()
        assertEquals(LoginResult.NeedsChoice, acc.login("h", "anna"))
        acc.cancelLogin()
        assertEquals(LoginResult.NeedsChoice, acc.login("h", "anna"))
    }

    @Test fun logoutWaitsForARunningSyncAndLeavesNoErrorBehind() = runBlocking {
        val c = device("dev-c")
        c.seed()
        val acc = c.account()
        acc.login("h", "anna")
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        server.beforeReply = {
            server.beforeReply = null
            started.complete(Unit)
            gate.await()
            throw SyncError.Offline(null)
        }
        val run = async(Dispatchers.Default) { c.sync() }
        started.await()
        val out = async(Dispatchers.Default) { acc.logout() }
        delay(200)
        assertFalse(out.isCompleted)
        assertTrue(c.device.current().signedIn)
        gate.complete(Unit)
        run.await()
        out.await()
        assertFalse(c.device.current().signedIn)
        assertEquals("", c.device.current().lastSyncError)
    }

    @Test fun mergeJoinsBothSets() = runBlocking {
        val a = annaWithData()
        val c = device("dev-c")
        c.seed()
        c.add(entry("c1"))
        val acc = c.account()
        acc.login("h", "anna")
        assertEquals(SyncOutcome.Success, acc.merge())
        assertFalse(acc.pending.value)
        assertEquals(listOf("a1", "a2", "c1"), c.ids())
        assertEquals(listOf("a1", "a2", "c1"), serverTxnIds("anna"))
        assertEquals(SyncOutcome.Success, a.sync())
        assertEquals(listOf("a1", "a2", "c1"), a.ids())
    }

    @Test fun replaceWipesLocalThenPullsExactlyTheAccount() = runBlocking {
        val a = annaWithData()
        val c = device("dev-c")
        c.seed()
        c.add(entry("c1"))
        c.db.memoDao().upsert(MemoEntity("m1", "2026-01-10", text = "mine", updatedAt = 1_000))
        c.settings.replace(c.settings.current().copy(monthStartDay = 9))
        c.photos.dir.mkdirs()
        c.photos.file("00000000000000000000000000000000.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val acc = c.account()
        assertEquals(LoginResult.NeedsChoice, acc.login("h", "anna"))

        assertEquals(SyncOutcome.Success, acc.replace())
        assertFalse(acc.pending.value)
        assertEquals(listOf("a1", "a2"), c.ids())
        assertTrue(c.db.memoDao().allRows().isEmpty())
        assertEquals(1, c.settings.current().monthStartDay) // defaults again
        assertEquals(0L, c.settings.sync.value.updatedAt)
        assertFalse(c.photos.file("00000000000000000000000000000000.jpg").exists())
        assertEquals(a.db.accountDao().allGroupRows().map { it.id }.sorted(), c.db.accountDao().allGroupRows().map { it.id }.sorted())
        assertEquals(listOf("a1", "a2"), serverTxnIds("anna")) // c1 never reached the server
        assertTrue(c.device.current().signedIn)
    }

    @Test fun replaceOnAnAccountWithoutGroupsSeedsDefaults() = runBlocking {
        // the account has entries but nobody ever pushed groups
        server.inject("anna", com.moneybook.shared.sync.SyncChange("memo", "m9", 5_000, false, "dev-z",
            kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive("m9"))
                put("date", kotlinx.serialization.json.JsonPrimitive("2026-01-01"))
                put("title", kotlinx.serialization.json.JsonPrimitive(""))
                put("text", kotlinx.serialization.json.JsonPrimitive("x"))
                put("pinned", kotlinx.serialization.json.JsonPrimitive(false))
                put("updatedAt", kotlinx.serialization.json.JsonPrimitive(5_000))
                put("deleted", kotlinx.serialization.json.JsonPrimitive(false))
            }))
        val c = device("dev-c")
        c.seed()
        c.add(entry("c1"))
        val acc = c.account()
        assertEquals(LoginResult.NeedsChoice, acc.login("h", "anna"))
        acc.replace()
        assertTrue(c.ids().isEmpty())
        assertTrue(c.db.accountDao().groups().isNotEmpty())
        assertNotNull(c.db.memoDao().allRows().firstOrNull { it.id == "m9" })
    }

    @Test fun cancelLogsOutAndKeepsLocalData() = runBlocking {
        annaWithData()
        val c = device("dev-c")
        c.seed()
        c.add(entry("c1"))
        val acc = c.account()
        acc.login("h", "anna")
        acc.cancelLogin()
        assertFalse(acc.pending.value)
        assertFalse(c.device.current().signedIn)
        assertEquals(listOf("c1"), c.ids())
        assertEquals(listOf("a1", "a2"), serverTxnIds("anna"))
    }

    @Test fun wrongKeyFailsAndStoresNothing() = runBlocking {
        val c = device("dev-c")
        val acc = c.account()
        val r = acc.login("h", "nope")
        assertEquals(SyncError.Unauthorized, (r as LoginResult.Failed).error)
        val s = c.device.current()
        assertFalse(s.signedIn)
        assertEquals("", s.serverUrl)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun offlineLoginFailsAndStoresNothing() = runBlocking {
        server.offline = true
        val c = device("dev-c")
        val r = c.account().login("h", "anna")
        assertTrue((r as LoginResult.Failed).error is SyncError.Offline)
        assertFalse(c.device.current().signedIn)
    }

    @Test fun probeFailureAfterLoginLeavesTheDeviceSignedOut() = runBlocking {
        val c = device("dev-c")
        val broken = object : SyncApi by c.api {
            override suspend fun sync(request: SyncRequest): SyncResponse = throw SyncError.Offline(null)
        }
        val engine = SyncEngine(c.db, c.settings, c.device, broken, c.photos, c.repeats, c.time)
        val r = SyncAccount(c.device, c.settings, c.db, engine, c.api, Seeder(c.db, c.time), "t").login("h", "anna")
        assertTrue((r as LoginResult.Failed).error is SyncError.Offline)
        assertFalse(c.device.current().signedIn)
        assertFalse(c.account().pending.value)
    }

    @Test fun pendingIsHeldFromTheMomentTheTokenIsStored() = runBlocking {
        val c = device("dev-c")
        lateinit var acc: SyncAccount
        var duringProbe: Boolean? = null
        val spy = object : SyncApi by c.api {
            override suspend fun sync(request: SyncRequest): SyncResponse {
                if (duringProbe == null) duringProbe = acc.pending.value // triggers must not start a run before the choice
                return c.api.sync(request)
            }
        }
        val engine = SyncEngine(c.db, c.settings, c.device, spy, c.photos, c.repeats, c.time)
        acc = SyncAccount(c.device, c.settings, c.db, engine, c.api, Seeder(c.db, c.time), "t")
        assertEquals(LoginResult.Synced, acc.login("h", "anna"))
        assertEquals(true, duringProbe)
        assertFalse(acc.pending.value)
    }

    @Test fun reloginResetsCursorEpochAndMarksEverythingDirty() = runBlocking {
        val c = device("dev-c")
        c.seed()
        c.add(entry("c1"))
        val acc = c.account()
        assertEquals(LoginResult.Synced, acc.login("h", "anna"))
        assertTrue(c.device.current().syncCursor > 0)
        assertEquals("e1", c.device.current().syncEpoch)
        assertTrue(c.db.txnDao().dirtyRows().isEmpty())

        assertEquals(LoginResult.NeedsChoice, acc.login("h2", "anna")) // the account has data now and so has c
        val s = c.device.current()
        assertEquals(0L, s.syncCursor)
        assertEquals("", s.syncEpoch)
        assertEquals(0L, s.lastSyncAt)
        assertEquals(listOf("c1"), c.db.txnDao().dirtyRows().map { it.id })
    }

    @Test fun loginAsAnotherUserRepushesEverythingToTheNewAccount() = runBlocking {
        val c = device("dev-c")
        c.seed()
        c.add(entry("c1"))
        val acc = c.account()
        acc.login("h", "anna")
        acc.logout()
        assertEquals(LoginResult.Synced, acc.login("h", "ben")) // ben's account is empty: no question
        assertEquals(listOf("c1"), serverTxnIds("ben")) // the data belongs to the device
        assertEquals(listOf("c1"), serverTxnIds("anna"))
    }

    @Test fun logoutKeepsUrlAndLocalData() = runBlocking {
        val c = device("dev-c")
        c.seed()
        c.add(entry("c1"))
        val acc = c.account()
        acc.login("h.ts.net", "anna")
        c.device.update { it.copy(lastSyncError = "offline") }
        acc.logout()
        val s = c.device.current()
        assertFalse(s.signedIn)
        assertEquals("https://h.ts.net", s.serverUrl)
        assertEquals(0L, s.syncCursor)
        assertEquals("", s.syncEpoch)
        assertEquals("", s.lastSyncError)
        assertEquals(listOf("c1"), c.ids())
        assertNull(c.api.user)
    }

    @Test fun logoutSurvivesOfflineServer() = runBlocking {
        val c = device("dev-c")
        val acc = c.account()
        acc.login("h", "anna")
        val offline = object : SyncApi by c.api {
            override suspend fun logout() = throw SyncError.Offline(null)
        }
        c.account(offline).logout()
        assertFalse(c.device.current().signedIn)
    }

    @Test fun pendingStaysTrueUntilChosen() = runBlocking {
        annaWithData()
        val c = device("dev-c")
        c.seed()
        c.add(entry("c1"))
        val acc = c.account()
        assertFalse(acc.pending.value)
        acc.login("h", "anna")
        assertTrue(acc.pending.value)
        assertTrue(acc.pending.value) // nothing else clears it
        acc.merge()
        assertFalse(acc.pending.value)
    }
}
