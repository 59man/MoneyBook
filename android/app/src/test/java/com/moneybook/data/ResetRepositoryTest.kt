package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import com.moneybook.testutil.testPhotos
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResetRepositoryTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC))
    private val settings = FakeSettingsRepository(AppSettings("CZK", monthStartDay = 5))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.accountDao().upsert(db.accountDao().get(cash)!!.copy(initialBalance = 40_000_000))
            db.accountDao().upsert(Fixtures.account("rev", groupId = DefaultData.groupId(AccountGroupType.ACCOUNTS), name = "Revolut", sortOrder = 5))
            db.categoryDao().upsert(Fixtures.category("alk", name = "charita", sortOrder = 20))
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-26T12:00:00.000", cash, 52_500, categoryId = "alk"),
                Fixtures.txn("t2", TxnType.TRANSFER, "2026-09-25T12:00:00.000", cash, 1_000, toAccountId = "rev"),
            ))
        }
    }

    @After fun tearDown() = db.close()

    private fun repo() = ResetRepository(db, settings, Seeder(db, time), time, defaultCurrency = "EUR", photos = testPhotos())

    @Test fun resetContentsRemovesEntriesOnly() = runTest {
        repo().resetContents()
        assertEquals(0, db.txnDao().count())
        assertTrue(db.txnDao().allRows().all { it.deleted && it.dirty })
        assertEquals(listOf("Cash", "Accounts", "Revolut", "Card"), db.accountDao().accounts().map { it.name })
        assertEquals(40_000_000L, db.accountDao().get(cash)!!.initialBalance)
        assertEquals(AppSettings("CZK", monthStartDay = 5), settings.current())
    }

    @Test fun completeResetRestoresTheFreshInstallState() = runTest {
        repo().resetAll()
        assertEquals(0, db.txnDao().count())
        val accounts = db.accountDao().accounts()
        assertEquals(listOf("Cash", "Accounts", "Card"), accounts.map { it.name })
        assertTrue(accounts.all { it.initialBalance == 0L && it.currency == "EUR" })
        assertTrue(db.accountDao().get("rev")!!.deleted)
        assertEquals(DefaultData.expenseCategories.size + DefaultData.incomeCategories.size, db.categoryDao().all().size)
        assertTrue(db.categoryDao().allRows().single { it.id == "alk" }.deleted)
        assertEquals(AppSettings("EUR"), settings.current())
    }

    @Test fun `complete reset removes budgets, contents-only keeps them`() = runTest {
        val budgets = BudgetRepository(db, time)
        budgets.add(null, 30_000, 202610)
        repo().resetContents()
        assertEquals(1, budgets.observe().first().size)
        repo().resetAll()
        assertEquals(0, budgets.observe().first().size)
    }
}
