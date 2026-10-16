package com.moneybook.ui.more

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.sync.FakeSyncServer
import com.moneybook.data.sync.SyncAccount
import com.moneybook.data.sync.SyncDevice
import com.moneybook.data.sync.SyncError
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeSyncServer().apply { addKey("anna") }
    private val phone = SyncDevice(server, "dev-c")
    private val account = SyncAccount(phone.device, phone.settings, phone.db, phone.engine, phone.api, Seeder(phone.db, phone.time), "Test phone")
    // after the rule has set Dispatchers.Main (field initializers run before it)
    private val vm by lazy { SyncViewModel(account, phone.engine, phone.device) }

    @After fun tearDown() = phone.close()

    private suspend fun until(check: (SyncUiState) -> Boolean) = withTimeout(10_000) { vm.state.first(check) }

    private suspend fun giveAnnaData() {
        val a = SyncDevice(server, "dev-a")
        a.seed()
        a.login("anna")
        a.db.txnDao().upsert(entry("a1"))
        a.sync()
        a.close()
    }

    private fun entry(id: String) =
        Fixtures.txn(id, TxnType.EXPENSE, "2026-01-10T12:00:00.000", DefaultData.accountId(AccountGroupType.CASH), 100).copy(updatedAt = 1_000)

    @Test fun loginErrorTexts() {
        assertEquals("Wrong API key", loginErrorText(SyncError.Unauthorized))
        assertEquals("Use https, or a Tailscale 100.x address", loginErrorText(SyncError.InsecureUrl))
        assertEquals("Can't reach the server", loginErrorText(SyncError.Offline(null)))
        assertEquals("Too many attempts. Try again in a minute.", loginErrorText(SyncError.Rejected(429, "rate_limited")))
        assertEquals("Server error", loginErrorText(SyncError.Server(500)))
        assertEquals("Couldn't log in", loginErrorText(SyncError.Rejected(400, "bad_request")))
        assertEquals("Couldn't log in", loginErrorText(SyncError.Other(null)))
    }

    @Test fun syncErrorTexts() {
        assertNull(syncErrorText(""))
        assertEquals("Offline", syncErrorText("offline"))
        assertEquals("Session expired. Log in again.", syncErrorText("session_expired"))
        assertEquals("Server error", syncErrorText("server"))
        assertEquals("Update the app to sync this data", syncErrorText("rejected"))
        assertEquals("Sync failed", syncErrorText("other"))
        assertEquals("Use https, or a Tailscale 100.x address", syncErrorText("insecure_url"))
    }

    @Test fun wrongKeyShowsTheErrorAndStaysSignedOut() = runBlocking {
        phone.seed()
        vm.login("host.ts.net", "wrong")
        val s = until { it.error != null && !it.busy }
        assertEquals("Wrong API key", s.error)
        assertFalse(s.signedIn)
        vm.login("host.ts.net", "anna") // a new attempt clears the old line
        assertTrue(until { it.signedIn }.error == null)
    }

    @Test fun insecureUrlShowsTheHint() = runBlocking {
        phone.seed()
        vm.login("http://192.168.0.103:8095", "anna")
        assertEquals("Use https, or a Tailscale 100.x address", until { it.error != null && !it.busy }.error)
    }

    @Test fun unreachableServerShowsOffline() = runBlocking {
        server.offline = true
        vm.login("host.ts.net", "anna")
        assertEquals("Can't reach the server", until { it.error != null && !it.busy }.error)
    }

    @Test fun successfulLoginSignsInAndRemembersTheServer() = runBlocking {
        phone.seed()
        vm.login(" host.ts.net/ ", " anna ")
        val s = until { it.signedIn }
        assertEquals("https://host.ts.net", s.serverUrl)
        assertTrue(s.lastSyncAt > 0)
        assertFalse(s.choice)
        vm.logout()
        val out = until { !it.signedIn && it.serverUrl.isNotEmpty() }
        assertEquals("https://host.ts.net", out.serverUrl) // kept for the next login's prefill
    }

    @Test fun anExpiredSessionShowsWhileSignedOut() = runBlocking {
        phone.device.update { it.copy(serverUrl = "https://h", lastSyncError = "session_expired") }
        assertEquals("Session expired. Log in again.", until { it.serverUrl.isNotEmpty() }.error)
    }

    @Test fun needsChoiceOpensTheDialogAndKeepsTheLoginFormBehindIt() = runBlocking {
        giveAnnaData()
        phone.seed()
        phone.db.txnDao().upsert(entry("c1"))
        vm.login("host.ts.net", "anna")
        val s = until { it.choice }
        assertFalse(s.signedIn)
        assertFalse(s.busy)
    }

    @Test fun mergeKeepsBothAndSignsIn() = runBlocking {
        giveAnnaData()
        phone.seed()
        phone.db.txnDao().upsert(entry("c1"))
        vm.login("host.ts.net", "anna")
        until { it.choice }
        vm.merge()
        val s = until { it.signedIn }
        assertFalse(s.choice)
        assertEquals(listOf("a1", "c1"), phone.db.txnDao().allRows().filter { !it.deleted }.map { it.id }.sorted())
    }

    @Test fun replaceDropsThisDevicesData() = runBlocking {
        giveAnnaData()
        phone.seed()
        phone.db.txnDao().upsert(entry("c1"))
        vm.login("host.ts.net", "anna")
        until { it.choice }
        vm.replace()
        until { it.signedIn }
        assertEquals(listOf("a1"), phone.db.txnDao().allRows().filter { !it.deleted }.map { it.id })
    }

    @Test fun cancelLogsOutAgainAndKeepsTheData() = runBlocking {
        giveAnnaData()
        phone.seed()
        phone.db.txnDao().upsert(entry("c1"))
        vm.login("host.ts.net", "anna")
        until { it.choice }
        vm.cancelLogin()
        val s = until { !it.choice && !it.signedIn && !account.pending.value }
        assertEquals(listOf("c1"), phone.db.txnDao().allRows().filter { !it.deleted }.map { it.id })
        assertFalse(phone.device.current().signedIn)
    }

    @Test fun syncNowWhileRunningStartsNoSecondRun() = runBlocking {
        phone.seed()
        server.addKey("ben")
        phone.login("anna")
        val gate = CompletableDeferred<Unit>()
        server.beforeReply = { gate.await() }
        vm.syncNow()
        until { it.syncing }
        val requests = server.requests.size
        vm.syncNow() // ignored: the first run is still going
        assertEquals(requests, server.requests.size)
        server.beforeReply = null
        gate.complete(Unit)
        val s = until { !it.syncing && it.lastSyncAt > 0 }
        assertTrue(s.signedIn)
    }

    @Test fun aFailedRunShowsItsErrorLineWhileSignedIn() = runBlocking {
        phone.seed()
        phone.login("anna")
        server.offline = true
        vm.syncNow()
        assertEquals("Offline", until { it.error != null && !it.syncing }.error)
    }
}
