package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.DbTime
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.ArchiveState
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.TRANSFER
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountRepositoryTest {
    private lateinit var db: MoneyBookDatabase
    private lateinit var repo: AccountRepository
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-10T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val bank = DefaultData.accountId(AccountGroupType.ACCOUNTS)
    private val savings = DefaultData.groupId(AccountGroupType.SAVINGS)

    @Before fun setUp() {
        db = inMemoryDb()
        repo = AccountRepository(db, time)
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("e", EXPENSE, "2026-10-02T08:00:00.000", bank, 100),
                Fixtures.txn("t", TRANSFER, "2026-10-03T08:00:00.000", cash, 300, toAccountId = bank),
            ))
        }
    }

    @After fun tearDown() = db.close()

    private suspend fun entryIds() = TxnRepository(db, time).observeAll().first().map { it.id }.sorted()
    private suspend fun saveIn(groupId: String, id: String, transferExpense: Boolean = false) =
        repo.save(AccountEntity(id = id, groupId = groupId, name = id, currency = "CZK", initialBalance = 0, sortOrder = 0, updatedAt = 0, transferExpense = transferExpense))

    @Test fun `entries survive delete and purge`() = runTest {
        repo.delete(bank)
        assertEquals(listOf("e", "t"), entryIds())
        assertEquals(ArchiveState.DELETED, db.accountDao().get(bank)!!.state)
        repo.purge(bank)
        assertEquals(listOf("e", "t"), entryIds())
        val row = TxnRepository(db, time).observeAll().first().single { it.id == "e" }
        assertEquals("Accounts", row.accountName) // still shows the account's name
        assertFalse(db.accountDao().get(bank)!!.deleted) // a live row, so the entries keep a valid reference
    }

    @Test fun `delete moves the account out of the lists and restore brings it back un-hidden`() = runTest {
        repo.setHidden(bank, true)
        repo.delete(bank)
        assertFalse(bank in repo.accounts().map { it.id })
        assertFalse(bank in repo.observeBalances().first().map { it.id })
        assertEquals(listOf(bank), repo.observeDeletedAccounts().first().map { it.id })
        assertTrue(bank in repo.allAccounts().map { it.id })
        repo.restore(bank)
        assertTrue(bank in repo.accounts().map { it.id })
        assertFalse(db.accountDao().get(bank)!!.hidden)
        assertTrue(repo.observeDeletedAccounts().first().isEmpty())
    }

    @Test fun `purge drops the account from the deleted list for good`() = runTest {
        repo.delete(bank)
        repo.purge(bank)
        assertTrue(repo.observeDeletedAccounts().first().isEmpty())
        assertFalse(bank in repo.accounts().map { it.id })
        assertEquals(ArchiveState.PURGED, db.accountDao().get(bank)!!.state)
    }

    @Test fun `deleting a group hides its accounts and lists the group with their count`() = runTest {
        saveIn(savings, "s1"); saveIn(savings, "s2")
        repo.delete("s2")
        repo.deleteGroup(savings)
        assertTrue(repo.accounts().none { it.groupId == savings })
        assertTrue(repo.observeBalances().first().none { it.groupId == savings })
        assertFalse(savings in repo.observeGroups().first().map { it.id })
        val deleted = repo.observeDeletedGroups().first().single()
        assertEquals(savings, deleted.id)
        assertEquals(1, deleted.accountCount) // s1 only; s2 is deleted by itself
        assertTrue(repo.observeDeletedAccounts().first().isEmpty()) // a deleted group's accounts are listed with the group
        repo.restoreGroup(savings)
        assertEquals(listOf("s1"), repo.accounts().filter { it.groupId == savings }.map { it.id })
        assertTrue(repo.observeDeletedGroups().first().isEmpty())
        assertEquals(listOf("s2"), repo.observeDeletedAccounts().first().map { it.id })
    }

    @Test fun `a purged group never comes back to the deleted list`() = runTest {
        repo.deleteGroup(savings)
        repo.purgeGroup(savings)
        assertTrue(repo.observeDeletedGroups().first().isEmpty())
        assertEquals(ArchiveState.PURGED, db.accountDao().getGroup(savings)!!.state)
    }

    @Test fun `a deleted or purged account still has its balance and name for the read-only detail`() = runTest {
        repo.delete(bank)
        assertEquals(null, repo.observeBalances().first().firstOrNull { it.id == bank })
        val deleted = repo.observeAnyBalance(bank, DbTime.MAX).first()!!
        assertEquals(200L, deleted.balance) // -100 expense + 300 transfer in
        repo.purge(bank)
        assertEquals(200L, repo.observeAnyBalance(bank, DbTime.MAX).first()!!.balance)
    }

    @Test fun `updateGroup renames and retypes a group`() = runTest {
        val g = repo.addGroup("Pocket")
        repo.updateGroup(g.id, " Wallet ", AccountGroupType.CARD)
        val saved = repo.getGroup(g.id)!!
        assertEquals("Wallet", saved.name)
        assertEquals(AccountGroupType.CARD, saved.type)
    }

    @Test fun `addGroup puts an Others group after the last one`() = runTest {
        val g = repo.addGroup("  Pocket ")
        assertEquals("Pocket", g.name)
        assertEquals(AccountGroupType.OTHERS, g.type)
        assertEquals(g.id, repo.observeGroups().first().last().id)
        assertEquals(DefaultData.groups.size, g.sortOrder)
    }

    @Test fun `the Transfer-Expense list follows group type and a ticked account stays listed`() = runTest {
        saveIn(savings, "s1")
        repo.setTransferExpense(cash, true) // a CASH account that is ticked
        val listed = repo.observeTransferExpenseAccounts().first().map { it.id }
        assertTrue("s1" in listed)
        assertTrue(cash in listed)
        assertFalse(bank in listed) // ACCOUNTS group, not ticked
        assertFalse(DefaultData.accountId(AccountGroupType.CARD) in listed)
        repo.setTransferExpense(cash, false)
        assertFalse(cash in repo.observeTransferExpenseAccounts().first().map { it.id })
        repo.delete("s1")
        assertFalse("s1" in repo.observeTransferExpenseAccounts().first().map { it.id })
    }

    @Test fun `balances of the remaining accounts ignore a deleted one but entries still count in the range`() = runTest {
        repo.delete(bank)
        assertEquals(-300L, repo.balanceOf(cash))
        assertEquals(2, TxnRepository(db, time).observeAll().first().size)
    }
}
